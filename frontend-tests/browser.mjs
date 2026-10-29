import { readFile, writeFile, rename, chmod, mkdir } from 'node:fs/promises';
import { randomBytes, randomUUID, createHash } from 'node:crypto';
import { spawn } from 'node:child_process';
import { resolve, relative, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { crc32 } from 'node:zlib';

const ROOT = process.cwd();
const directory = resolve(process.env.UI_RUN_DIRECTORY || '.local/invalid-ui-run');
const reportPath = join(directory, 'browser.json');
const checkpointPath = join(directory, 'fixture.json');
const OWNER = '00000000-0000-4000-8000-000000000001';
const OPERATOR = '00000000-0000-4000-8000-000000000002';
const secretValues = new Set();
const runId = randomUUID();
const accounts = Object.fromEntries([
  ['owner', OWNER, ['OWNER_TASK_ADMIN']],
  ['admin', OPERATOR, ['OPERATOR_TASK_ADMIN', 'OPERATOR_USER']],
  ['uploader', OPERATOR, ['OPERATOR_USER']],
  ['reviewer', OWNER, ['OWNER_REVIEWER']],
  ['approver', OWNER, ['OWNER_DATA_APPROVER']],
  ['recipient', OPERATOR, ['OPERATOR_USER']],
  ['outsider', OPERATOR, ['OPERATOR_USER']],
].map(([name, orgId, roles]) => {
  const password = randomBytes(32).toString('base64url'); secretValues.add(password);
  return [name, { id: randomUUID(), username: `ui-${runId.slice(0, 12)}-${name}`, password, orgId, roles }];
}));
const report = { formatVersion: 1, passed: false, runId, startedAt: new Date().toISOString(), checks: [], requests: [] };
let browser, context, page, currentToken, stage = 'startup';
let taskId, firstVersionId, secondVersionId;
let releaseBlocked = () => {};
let responseLeak = false, signedDownloadHttp200 = false;
const pageErrors = [];
const parts = [];
const responseInspections = new Set();
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const safeName = value => /^[A-Za-z0-9_.-]{1,160}$/.test(value || '') ? value : 'Error';

// A valid ancillary chunk keeps the original image decodable and gives the
// multi-part fixture real, CRC-checked bytes without trailing garbage.
function paddedPng(original, targetSize, character = 'p') {
  const text = Buffer.alloc(targetSize - original.length - 12, character);
  Buffer.from('probe\0', 'latin1').copy(text);
  const chunk = Buffer.alloc(text.length + 12);
  chunk.writeUInt32BE(text.length, 0); chunk.write('tEXt', 4, 'ascii'); text.copy(chunk, 8);
  chunk.writeUInt32BE(crc32(chunk.subarray(4, -4)), chunk.length - 4);
  return Buffer.concat([original.subarray(0, -12), chunk, original.subarray(-12)]);
}

class CheckFailure extends Error { constructor(name) { super(name); this.name = 'CheckFailure'; this.check = name; } }
function check(name, condition) {
  report.checks.push({ check: name, passed: Boolean(condition) });
  if (!condition) throw new CheckFailure(name);
}
async function privateJson(path, value) {
  const text = JSON.stringify(value, null, 2) + '\n';
  if ([...secretValues].some(secret => secret && text.includes(secret)) || /[?&]X-Amz-(?:Signature|Credential)=/i.test(text)) throw new CheckFailure('private_artifact_contains_secret');
  const temporary = `${path}.${randomUUID()}.tmp`;
  await writeFile(temporary, text, { mode: 0o600 }); await rename(temporary, path); await chmod(path, 0o600);
}
async function save() {
  report.stage = stage; report.caseCount = report.requests.length; report.checkCount = report.checks.length;
  report.pageErrorTypes = pageErrors;
  await privateJson(reportPath, report);
}
async function step(name, operation) { stage = name; await save(); return operation(); }
async function fixture(phase, input) {
  const java = process.env.UI_JAVA, classpath = process.env.UI_CLASSPATH;
  if (!java || !classpath) throw new CheckFailure('fixture_java_environment_missing');
  const output = await new Promise((done, fail) => {
    const child = spawn(java, ['-Duser.timezone=Asia/Shanghai', '-cp', classpath,
      'com.uav.inspection.ui.probe.UiBrowserFixture', phase, checkpointPath], {
      cwd: ROOT, env: process.env, stdio: ['pipe', 'pipe', 'pipe'],
    });
    let stdout = '', received = 0;
    const timeout = setTimeout(() => { child.kill('SIGKILL'); fail(new CheckFailure('fixture_process_timed_out')); }, 120000);
    child.stdout.on('data', bytes => { received += bytes.length; if (received > 4194304) child.kill('SIGKILL'); else stdout += bytes.toString('utf8'); });
    // Do not persist provider diagnostics or any process stdin.
    child.stderr.on('data', () => {});
    child.on('error', () => { clearTimeout(timeout); fail(new CheckFailure('fixture_process_start_failed')); });
    child.on('close', code => {
      clearTimeout(timeout);
      try {
        const value = JSON.parse(stdout.trim());
        if (code !== 0 || value.passed !== true) fail(new CheckFailure(`fixture_${phase}_${safeName(value.failedCheck || 'failed')}`));
        else done(value);
      } catch { fail(new CheckFailure(`fixture_${phase}_safe_json_missing`)); }
    });
    child.stdin.end(input ? JSON.stringify(input) : '');
  });
  return output;
}
const testId = id => page.getByTestId(id);
async function apiAction(method, path, action, expected = 200) {
  const response = page.waitForResponse(value => {
    const url = new URL(value.url());
    return url.pathname === `/api/v1${path}` && value.request().method() === method;
  }, { timeout: 90000 });
  await action();
  const value = await response;
  check(`${safeName(stage)}_http_${expected}`, value.status() === expected);
  const body = await value.json();
  check(`${safeName(stage)}_api_envelope`, typeof body.code === 'string' && Object.hasOwn(body, 'data') && typeof body.traceId === 'string');
  if (expected >= 200 && expected < 300) check(`${safeName(stage)}_api_success`, body.code === 'OK');
  return body.data;
}
async function login(name) {
  const account = accounts[name];
  if (await testId('logout').isVisible().catch(() => false)) {
    await apiAction('POST', '/auth/logout', () => testId('logout').click());
  }
  await testId('login-username').waitFor();
  await testId('login-username').fill(account.username); await testId('login-password').fill(account.password);
  const result = await apiAction('POST', '/auth/login', () => testId('login-submit').click());
  currentToken = result.accessToken; secretValues.add(currentToken);
  await testId('logout').waitFor();
}
async function openTask(manager = false) {
  await testId('nav-tasks').click();
  await page.locator(`[data-testid="task-card"][data-task-id="${taskId}"] [data-testid="task-open"]`).click();
  await taskReady(manager);
}
async function taskReady(manager = true) {
  await page.waitForFunction(({ id, manager }) => {
    const node = document.querySelector('[data-testid="task-detail"]');
    const button = node?.querySelector('[data-testid="members-submit"]');
    return node && !node.hidden && node.textContent.includes(id) && node.getAttribute('aria-busy') !== 'true'
      && (!manager || (button && !button.disabled));
  }, { id: taskId, manager }, { timeout: 30000 });
}
async function disclosure(text) {
  const summary = page.locator('[data-testid="task-detail"] summary').filter({ hasText: text });
  if (!await summary.evaluate(node => node.parentElement.open)) await summary.click();
}
async function openVersion(id) {
  await testId('nav-evidence').click(); await testId('version-id').fill(id);
  await apiAction('GET', `/evidence-versions/${id}`, () => testId('version-open').click());
  await page.waitForFunction(value => {
    const detail = document.querySelector('[data-testid="version-detail"]');
    return detail && !detail.hidden && detail.dataset.versionId === value && Boolean(detail.querySelector('[data-testid="version-filename"]'));
  }, id, { timeout: 30000 });
}
async function waitConfirmed() {
  await page.waitForFunction(() => document.querySelector('[data-testid="anchor-status"]')?.textContent.includes('已确认'), null, { timeout: 150000 });
}
async function completedUpload() {
  await testId('upload-open-version').waitFor({ timeout: 150000 });
  const uploadId = await testId('upload-session').textContent();
  await testId('upload-open-version').click(); await waitConfirmed();
  const versionId = await testId('version-detail').getAttribute('data-version-id');
  check('completed_upload_has_real_version_id', /^[0-9a-f-]{36}$/.test(versionId || ''));
  return { uploadId, versionId };
}
async function storageHasNoSecrets() {
  const values = await page.evaluate(() => [...Object.entries(localStorage), ...Object.entries(sessionStorage)]);
  const encoded = JSON.stringify(values);
  check(`${safeName(stage)}_browser_storage_has_no_credentials`, ![...secretValues].some(value => value && encoded.includes(value))
    && !/eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}/.test(encoded)
    && !values.some(([key]) => /password|authorization|accessToken|deviceToken/i.test(key)));
}
async function forbidden(path, name) {
  const result = await context.request.get(`${process.env.UI_BASE_URL}/api/v1${path}`, {
    headers: { Authorization: `Bearer ${currentToken}`, 'X-Trace-Id': `ui-negative-${randomUUID()}` },
  });
  const data = await result.json();
  report.requests.push({ method: 'GET', path: `/api/v1${path}`, status: result.status(), scope: 'READ_ONLY_NEGATIVE_API_CHECK' });
  check(name, result.status() === 403 && data.code === 'FORBIDDEN');
}

async function main() {
  check('private_run_and_base_environment', relative(resolve(ROOT, '.local'), directory) && !relative(resolve(ROOT, '.local'), directory).startsWith('..') && Boolean(process.env.UI_BASE_URL));
  await mkdir(directory, { recursive: true, mode: 0o700 }); await chmod(directory, 0o700);
  await step('seed_isolated_accounts', async () => {
    const result = await fixture('create', { accounts: Object.values(accounts) });
    check('seven_fixture_accounts_seeded', result.accountCount === 7);
    report.fixtureAccounts = Object.fromEntries(Object.entries(accounts).map(([name, value]) => [name, value.id]));
  });
  const { chromium } = await import(pathToFileURL(resolve(ROOT, '.local/browser-tools/node_modules/playwright/index.mjs')).href);
  browser = await chromium.launch({ headless: true, args: ['--no-sandbox', '--disable-dev-shm-usage'] });
  context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', acceptDownloads: true });
  page = await context.newPage(); page.setDefaultTimeout(30000);
  context.on('page', value => value.on('pageerror', error => pageErrors.push(safeName(error.name))));
  page.on('pageerror', error => pageErrors.push(safeName(error.name)));
  context.on('response', response => {
    const url = new URL(response.url());
    if (url.searchParams.has('X-Amz-Signature') && response.status() === 200) signedDownloadHttp200 = true;
    if (url.origin !== new URL(process.env.UI_BASE_URL).origin || !url.pathname.startsWith('/api/v1/')) return;
    const method = response.request().method();
    report.requests.push({ method, path: url.pathname, status: response.status() });
    const inspect = (async () => {
      try {
        const data = await response.json();
        const credential = url.pathname === '/api/v1/auth/login' || (method === 'POST' && url.pathname === '/api/v1/drones');
        if (credential) { const secret = data.data?.accessToken || data.data?.deviceToken; if (secret) secretValues.add(secret); }
        const text = JSON.stringify(data);
        if (/"(?:objectKey|object_key|transactionBytes|commitBytes)"\s*:/.test(text) || (!credential && [...secretValues].some(value => value && text.includes(value)))) responseLeak = true;
      } catch { /* Aborted HTTP reads are expected at the controlled upload interruption. */ }
    })();
    responseInspections.add(inspect); inspect.finally(() => responseInspections.delete(inspect));
  });
  page.on('request', request => {
    const url = new URL(request.url()), match = /^\/api\/v1\/upload-sessions\/([0-9a-f-]{36})\/parts\/(\d+)$/.exec(url.pathname);
    if (request.method() === 'PUT' && match) parts.push({ uploadId: match[1], partNo: Number(match[2]), stage });
  });
  await page.goto(process.env.UI_BASE_URL, { waitUntil: 'domcontentloaded' });
  const suffix = runId.slice(0, 8), taskName = `园区线路巡检 · ${suffix}`;
  const firstName = `北区线路照片 ${suffix}`, secondName = `南区线路照片 ${suffix}`;
  let firstRequirement, secondRequirement, firstSubtask, secondSubtask, firstDevice, secondDevice;
  await step('owner_create_and_assign', async () => {
    await login('owner'); await testId('task-create').click();
    await testId('task-name').fill(taskName); await testId('task-scope').fill('园区北区与南区线路巡检，分别提交原始现场照片。');
    await testId('requirement-name').nth(0).fill(firstName); await testId('requirement-add').click(); await testId('requirement-name').nth(1).fill(secondName);
    let task = await apiAction('POST', '/tasks', () => testId('task-create-submit').click());
    taskId = task.id; report.taskId = taskId;
    firstRequirement = task.requirements.find(value => value.name === firstName).id; secondRequirement = task.requirements.find(value => value.name === secondName).id;
    await taskReady(); await disclosure('分配运营商'); await testId('assign-admin-id').fill(accounts.admin.id);
    await apiAction('POST', `/tasks/${taskId}/assign`, () => testId('assign-submit').click()); await taskReady();
    await disclosure('维护本机构成员'); await testId('members-extra').fill(`${accounts.reviewer.id}\n${accounts.approver.id}`);
    task = await apiAction('PUT', `/tasks/${taskId}/members`, () => testId('members-submit').click()); await taskReady();
    check('owner_members_include_reviewer_and_approver', [accounts.reviewer.id, accounts.approver.id].every(id => task.members.some(value => value.userId === id)));
  });
  await step('admin_register_two_devices', async () => {
    await login('admin'); await testId('nav-devices').click();
    const devices = [];
    for (let number = 1; number <= 2; number++) {
      await testId('device-code').fill(`UI-${suffix}-${number}`);
      const result = await apiAction('POST', '/drones', () => testId('device-create').click()); devices.push(result.device.id); secretValues.add(result.deviceToken);
      await testId('device-credentials-dialog').waitFor();
      const tokenNode = await testId('device-token').elementHandle();
      check('new_device_credential_is_initially_masked', await testId('device-token').getAttribute('type') === 'password');
      await testId('device-token-reveal').click(); await page.getByRole('button', { name: '关闭设备凭据', exact: true }).click();
      await testId('device-credentials-dialog').waitFor({ state: 'detached' });
      check('closed_device_dialog_clears_revealed_credential', await tokenNode.evaluate(value => value.value) === ''); await tokenNode.dispose();
    }
    [firstDevice, secondDevice] = devices; report.deviceIds = devices; await storageHasNoSecrets();
  });
  await step('admin_configure_two_executions_and_start', async () => {
    await openTask(true); await disclosure('维护本机构成员'); await testId('members-extra').fill(accounts.uploader.id);
    await apiAction('PUT', `/tasks/${taskId}/members`, () => testId('members-submit').click()); await taskReady();
    const subs = [];
    for (const [name, requirement] of [['北区执行', firstRequirement], ['南区执行', secondRequirement]]) {
      await disclosure('建立执行子任务'); await testId('subtask-name').fill(name);
      await page.locator(`input[type=checkbox][data-requirement-id="${requirement}"]`).check();
      const task = await apiAction('POST', `/tasks/${taskId}/subtasks`, () => testId('subtask-submit').click()); await taskReady();
      subs.push(task.subtasks.find(value => value.name === name).id);
    }
    [firstSubtask, secondSubtask] = subs;
    for (const [subtask, device] of [[firstSubtask, firstDevice], [secondSubtask, secondDevice]]) {
      await disclosure('分配设备或换机'); await testId('execution-subtask').selectOption(subtask);
      await page.waitForFunction(value => [...document.querySelector('[data-testid="execution-drone"]').options].some(option => option.value === value), device);
      await testId('execution-drone').selectOption(device);
      await apiAction('POST', `/subtasks/${subtask}/executions`, () => testId('execution-submit').click()); await taskReady();
    }
    const task = await apiAction('POST', `/tasks/${taskId}/start`, () => testId('task-start').click()); await taskReady();
    check('task_starts_with_two_running_executions', task.status === 'RUNNING' && task.subtasks.length === 2 && task.subtasks.every(value => value.execution.status === 'RUNNING'));
  });
  const original = await readFile(join(ROOT, 'examples/files/inspection-sample.png'));
  const large = paddedPng(original, 17 * 1024 * 1024);
  const changed = paddedPng(original, 17 * 1024 * 1024, 'q');
  const largeFile = { name: `north-${suffix}.png`, mimeType: 'image/png', buffer: large };
  const smallFile = { name: `south-${suffix}.png`, mimeType: 'image/png', buffer: original };
  let originalUpload, originalPart, originalRequestId;
  await step('upload_pause_after_real_first_part', async () => {
    await login('uploader'); await openTask();
    await page.locator(`[data-testid="requirement-row"][data-requirement-id="${firstRequirement}"] [data-testid="upload-requirement"]`).click();
    let reached, release, handled;
    const intercepted = new Promise(done => { reached = done; });
    const released = new Promise(done => { release = done; });
    const finished = new Promise(done => { handled = done; });
    releaseBlocked = release;
    const handler = async route => {
      originalUpload = /^\/api\/v1\/upload-sessions\/([^/]+)\//.exec(new URL(route.request().url()).pathname)[1];
      reached(); await released;
      try { await route.abort('failed'); } catch { /* Pause already cancelled this request. */ } finally { handled(); }
    };
    await page.route('**/api/v1/upload-sessions/*/parts/2', handler);
    try {
      await testId('upload-file').setInputFiles(largeFile); await testId('upload-start').click();
      await Promise.race([intercepted, new Promise((_, fail) => setTimeout(() => fail(new CheckFailure('second_part_not_reached')), 60000))]);
      const data = await fixture('inspect');
      const rows = data.parts.filter(value => value.session_id === originalUpload);
      const upload = data.uploads.find(value => value.id === originalUpload);
      check('first_large_part_really_committed_before_pause', rows.length === 1 && rows[0].part_no === 1 && rows[0].size === 8388608 && upload.part_count === 3);
      originalPart = rows[0]; originalRequestId = upload.client_request_id;
      await testId('upload-pause').click(); release(); await finished;
      await page.waitForFunction(() => document.querySelector('[data-testid="upload-state"]')?.textContent.includes('已暂停'));
    } finally { release(); await page.unroute('**/api/v1/upload-sessions/*/parts/2', handler); releaseBlocked = () => {}; }
    report.resumedUploadId = originalUpload; await storageHasNoSecrets();
  });
  await step('refresh_relogin_and_resume_only_missing_parts', async () => {
    const firstCount = parts.length;
    await page.reload({ waitUntil: 'domcontentloaded' }); await testId('login-username').waitFor();
    check('refresh_requires_login_without_persisted_jwt', await testId('login-submit').isVisible()); await storageHasNoSecrets();
    await login('uploader'); await testId('nav-upload').click();
    check('same_upload_session_restored_after_reload', await testId('upload-session').textContent() === originalUpload);
    await testId('upload-file').setInputFiles(largeFile); await testId('upload-resume').click();
    const value = await completedUpload(); firstVersionId = value.versionId;
    const resumed = parts.slice(firstCount).filter(value => value.uploadId === originalUpload).map(value => value.partNo);
    check('resume_puts_only_parts_two_and_three', value.uploadId === originalUpload && resumed.length === 2 && resumed[0] === 2 && resumed[1] === 3);
    const data = await fixture('inspect');
    const current = data.parts.find(value => value.session_id === originalUpload && value.part_no === 1);
    check('resume_preserves_first_part_bytes_and_registration_time', current.sha256 === originalPart.sha256 && current.created_at === originalPart.created_at);
    check('resume_keeps_original_client_request_identity', data.uploads.find(value => value.id === originalUpload).client_request_id === originalRequestId && data.uploadCount === 1);
    report.browserResumeVerified = true;
  });
  await step('upload_second_required_material', async () => {
    await openTask(); await page.locator(`[data-testid="requirement-row"][data-requirement-id="${secondRequirement}"] [data-testid="upload-requirement"]`).click();
    await testId('upload-file').setInputFiles(smallFile); await testId('upload-start').click();
    const value = await completedUpload(); secondVersionId = value.versionId;
    check('two_distinct_fixed_versions_confirmed', Boolean(firstVersionId) && Boolean(secondVersionId) && firstVersionId !== secondVersionId);
    report.versionIds = [firstVersionId, secondVersionId];
  });
  await step('owner_reviewer_approves_both_versions', async () => {
    await login('reviewer');
    for (const id of [firstVersionId, secondVersionId]) {
      await openVersion(id); await testId('review-decision').selectOption('APPROVED');
      await testId('review-reason').fill('现场资料齐全，复核通过。');
      const reviewed = await apiAction('POST', `/evidence-versions/${id}/review`, () => testId('review-submit').click());
      check('review_conclusion_from_real_ui', reviewed.reviewStatus === 'APPROVED');
      await testId('review-submit').waitFor({ state: 'detached' });
    }
  });
  let grantId;
  await step('recipient_applies_for_precise_version', async () => {
    await login('recipient'); await testId('nav-sharing').click(); await testId('grant-new').click();
    await testId('grant-version').fill(firstVersionId); await testId('grant-reason').fill('用于核对北区巡检现场资料。');
    const grant = await apiAction('POST', '/access-grants', () => testId('grant-apply').click()); grantId = grant.id; report.grantId = grantId;
  });
  async function grantTaskMode() {
    await testId('nav-sharing').click(); await testId('grant-task-mode').click(); await testId('grant-task').fill(taskId); await testId('grant-load').click();
    await page.locator(`[data-testid="grant-row"][data-grant-id="${grantId}"]`).waitFor();
  }
  await step('task_approver_grants_exact_version', async () => {
    await login('approver'); await grantTaskMode();
    await page.locator(`[data-grant-id="${grantId}"] [data-testid="grant-approve"]`).click();
    const grant = await apiAction('POST', `/access-grants/${grantId}/decision`, () => testId('grant-confirm').click());
    check('ui_approval_targets_only_requested_version', grant.status === 'APPROVED' && grant.versionId === firstVersionId);
    await testId('grant-confirm').waitFor({ state: 'detached' });
  });
  await step('recipient_scope_and_actual_file_verification', async () => {
    await login('recipient'); await testId('nav-evidence').click();
    await page.locator(`[data-testid="version-row"][data-version-id="${firstVersionId}"]`).waitFor();
    check('shared_version_list_does_not_include_second', await testId('version-row').count() === 1);
    await testId('version-id').fill(secondVersionId);
    await apiAction('GET', `/evidence-versions/${secondVersionId}`, () => testId('version-open').click(), 403); await testId('version-error').waitFor();
    check('forbidden_version_shows_no_previous_detail', await testId('version-filename').count() === 0);
    await forbidden(`/tasks/${taskId}`, 'precise_grant_does_not_open_parent_task');
    await openVersion(firstVersionId);
    let result = await apiAction('POST', `/evidence-versions/${firstVersionId}/verifications`, () => testId('verification-stored').click());
    check('stored_file_verifies_match', result.result === 'MATCH');
    await testId('verification-copy-file').setInputFiles(largeFile);
    result = await apiAction('POST', `/evidence-versions/${firstVersionId}/verifications`, () => testId('verification-copy').click());
    check('unchanged_copy_verifies_match', result.result === 'MATCH');
    await testId('verification-copy-file').setInputFiles({ ...largeFile, name: `changed-${suffix}.png`, buffer: changed });
    result = await apiAction('POST', `/evidence-versions/${firstVersionId}/verifications`, () => testId('verification-copy').click());
    check('changed_valid_image_copy_verifies_mismatch', result.result === 'MISMATCH');
    await testId('version-download').click();
    const deadline = Date.now() + 30000;
    while (!signedDownloadHttp200 && Date.now() < deadline) await new Promise(done => setTimeout(done, 100));
    check('actual_signed_object_navigation_returns_http_200', signedDownloadHttp200);
    report.preciseSharingVerified = true; report.copyAndStoredVerificationVerified = true; report.signedDownloadHttp200 = true;
    await storageHasNoSecrets();
  });
  await step('task_submit_and_formal_completion', async () => {
    await login('admin'); await openTask(true);
    let task = await apiAction('POST', `/tasks/${taskId}/submit-acceptance`, () => testId('task-submit-acceptance').click());
    check('ui_submit_reaches_waiting_acceptance', task.status === 'WAITING_ACCEPTANCE'); await taskReady();
    await login('owner'); await openTask(true);
    task = await apiAction('POST', `/tasks/${taskId}/complete`, () => testId('task-complete').click());
    check('ui_completion_is_real_terminal_task', task.status === 'COMPLETED' && Boolean(task.completedAt)); await taskReady();
    report.formalTaskCompletionVerified = true;
  });
  await step('completed_task_share_revocation_and_outsider_denial', async () => {
    await login('approver'); await grantTaskMode(); await page.locator(`[data-grant-id="${grantId}"] [data-testid="grant-revoke"]`).click();
    await testId('grant-decision-reason').fill('资料用途结束，撤销共享。');
    const grant = await apiAction('POST', `/access-grants/${grantId}/revoke`, () => testId('grant-confirm').click()); check('completed_task_grant_revokes_normally', grant.status === 'REVOKED');
    await testId('grant-confirm').waitFor({ state: 'detached' });
    await login('recipient'); await testId('nav-evidence').click();
    await page.getByText('暂无可见资料', { exact: true }).waitFor(); check('revoked_recipient_has_empty_version_list', await testId('version-row').count() === 0);
    await testId('version-id').fill(firstVersionId); await apiAction('GET', `/evidence-versions/${firstVersionId}`, () => testId('version-open').click(), 403); await testId('version-error').waitFor();
    await login('outsider'); await testId('nav-tasks').click(); await page.getByText('暂无可访问任务。加入任务或获得资料授权后，可在对应工作台查看。', { exact: true }).waitFor();
    check('outsider_has_empty_task_list', await testId('task-card').count() === 0);
    await testId('nav-evidence').click(); await page.getByText('暂无可见资料', { exact: true }).waitFor(); check('outsider_has_empty_version_list', await testId('version-row').count() === 0);
    await forbidden(`/tasks/${taskId}`, 'outsider_direct_task_is_forbidden'); await forbidden(`/evidence-versions/${firstVersionId}`, 'outsider_direct_version_is_forbidden');
    report.revocationAndOutsiderVerified = true; await storageHasNoSecrets();
  });
  await step('responsive_screenshots_without_credentials', async () => {
    await login('owner'); await openTask(true);
    check('screenshots_have_no_open_credential_dialog', await page.locator('dialog[open]').count() === 0 && await testId('login-password').count() === 0 && await testId('device-token').count() === 0);
    await page.waitForFunction(() => !document.querySelector('.toast'));
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({ path: join(directory, 'desktop.png'), fullPage: true }); await chmod(join(directory, 'desktop.png'), 0o600);
    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({ path: join(directory, 'mobile.png'), fullPage: true }); await chmod(join(directory, 'mobile.png'), 0o600);
    check('mobile_document_has_no_horizontal_overflow', await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1));
    await page.setViewportSize({ width: 1440, height: 1000 }); report.responsiveLayoutVerified = true; await storageHasNoSecrets();
  });
  await step('real_database_object_and_two_institution_inspection', async () => {
    const inspection = await fixture('inspect');
    check('real_fixture_has_two_confirmed_reviewed_versions', inspection.versionCount === 2 && inspection.versions.every(value => value.anchor_status === 'CONFIRMED' && value.review_status === 'APPROVED' && value.bothInstitutionsAgree && value.attemptCount === 1));
    check('real_original_large_and_small_objects_unchanged', inspection.versions.some(value => value.id === firstVersionId && value.sha256 === sha256(large)) && inspection.versions.some(value => value.id === secondVersionId && value.sha256 === sha256(original)));
    check('real_task_is_completed_and_grant_revoked', inspection.tasks.length === 1 && inspection.tasks[0].status === 'COMPLETED' && inspection.grants.length === 1 && inspection.grants[0].status === 'REVOKED');
    check('real_review_and_verification_rows_match_ui_outcomes', inspection.reviews.length === 2 && inspection.verifications.length === 3 && inspection.verifications.map(value => value.result).join(',') === 'MATCH,MATCH,MISMATCH');
    check('no_duplicate_upload_or_fixed_version_after_resume', inspection.uploadCount === 2 && inspection.parts.length === 4 && inspection.uploads.every(value => value.status === 'COMPLETED'));
    await privateJson(join(directory, 'browser-inspection.json'), inspection);
    report.inspectionReport = relative(ROOT, join(directory, 'browser-inspection.json')); report.realLedgerRegistrations = 2; report.realResultsInspected = true;
  });
  await Promise.allSettled([...responseInspections]);
  check('business_responses_exclude_internal_secrets_and_object_keys', !responseLeak);
  check('browser_has_no_uncaught_application_errors', pageErrors.length === 0);
  report.passed = true; report.completedAt = new Date().toISOString(); stage = 'complete'; await save();
}

try {
  await main();
  process.stdout.write(JSON.stringify({ passed: true, report: relative(ROOT, reportPath), realLedgerRegistrations: 2 }) + '\n');
} catch (error) {
  report.passed = false; report.failureType = safeName(error?.name); report.failedCheck = error instanceof CheckFailure ? safeName(error.check) : safeName(stage);
  report.completedAt = new Date().toISOString();
  try { await save(); } catch { /* Never substitute a raw exception or secret-bearing artifact. */ }
  process.stdout.write(JSON.stringify({ passed: false, failedCheck: report.failedCheck, failureType: report.failureType }) + '\n');
  process.exitCode = 1;
} finally {
  releaseBlocked();
  if (browser) await browser.close().catch(() => {});
  currentToken = null;
  for (const account of Object.values(accounts)) account.password = '';
  secretValues.clear();
}
