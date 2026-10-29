import { createUploadStore, UploadRunner, UploadProtocolError } from './upload-core.js';

const LABELS = { UPLOADING: '待上传 / 可续传', VERIFYING: '后台校验中', COMPLETED: '已定版', FAILED: '处理失败', EXPIRED: '已过期' };
const short = value => value ? `${value.slice(0, 8)}…${value.slice(-4)}` : '—';
const size = value => value >= 1024 * 1024 ? `${(value / 1024 / 1024).toFixed(1)} MiB` : `${Math.round(value / 1024)} KiB`;
function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [name, value] of Object.entries(attrs)) {
    if (name === 'text') node.textContent = value;
    else if (name === 'className') node.className = value;
    else if (name.startsWith('on') && typeof value === 'function') node.addEventListener(name.slice(2).toLowerCase(), value);
    else if (value != null && value !== false) node.setAttribute(name, value === true ? '' : String(value));
  }
  node.append(...children.filter(value => value != null)); return node;
}
function field(label, control, hint) {
  return el('label', { className: 'field' }, el('span', { text: label }), control, hint ? el('small', { className: 'muted', text: hint }) : null);
}
function option(value, text) { return el('option', { value, text }); }
function errorText(error) { return `${error?.message || '操作未完成，请稍后重试。'}${error?.traceId ? `（请求编号：${error.traceId}）` : ''}`; }

/** Each factory belongs to one current login; destroy aborts requests before another actor is mounted. */
export function createUploadPanel({ api, actor, notify = () => {}, onVersionReady = () => {} }) {
  let destroyed = false, task = null, chosen = null, selected = null, storageError = null;
  const canUpload = actor.roles?.includes('OPERATOR_USER');
  let store;
  try { store = createUploadStore(window.sessionStorage, actor.id); store.list(); }
  catch (error) { storageError = error; }
  const element = el('section', { className: 'panel upload-panel', 'aria-label': '分片上传' });
  const requirement = el('select', { 'data-testid': 'upload-requirement', 'aria-label': '上传资料项' });
  const execution = el('select', { 'data-testid': 'upload-execution', 'aria-label': '执行批次' });
  const requirementId = el('input', { type: 'text', placeholder: '资料项编号', autocomplete: 'off', 'data-testid': 'upload-requirement-id' });
  const executionId = el('input', { type: 'text', placeholder: '执行批次编号', autocomplete: 'off', 'data-testid': 'upload-execution-id' });
  const contextTitle = el('p', { className: 'muted', text: '从任务详情进入可自动选择资料项和执行批次。' });
  const scoped = el('div', { className: 'grid form-grid' }, field('资料项', requirement), field('执行批次', execution));
  const manual = el('details', { className: 'card' }, el('summary', { text: '按已有资料编号上传' }),
    el('div', { className: 'grid form-grid' }, field('资料项编号', requirementId), field('执行批次编号', executionId)));
  const file = el('input', { type: 'file', accept: '.jpg,.jpeg,.png,.pdf,.mp4', 'data-testid': 'upload-file' });
  const saved = el('select', { 'data-testid': 'upload-saved', 'aria-label': '当前账号的本机续传记录' });
  const fileInfo = el('p', { className: 'muted', text: '支持 JPG、PNG、PDF、MP4。请选择原始文件。' });
  const status = el('p', { role: 'status', 'aria-live': 'polite', 'data-testid': 'upload-state', text: '请选择文件开始。' });
  const session = el('code', { 'data-testid': 'upload-session', text: '尚未创建' });
  const bar = el('progress', { max: 1, value: 0, 'data-testid': 'upload-progress', 'aria-label': '服务器已收分片字节进度' });
  const statistics = el('p', { className: 'muted', 'data-testid': 'upload-statistics', text: '已登记 0 / 0 片' });
  const expires = el('p', { className: 'muted', 'data-testid': 'upload-expires' });
  const fingerprint = el('p', { className: 'muted', 'data-testid': 'upload-fingerprint-progress' });
  const partList = el('div', { className: 'upload-parts', 'data-testid': 'upload-parts' });
  const versions = el('div', { className: 'toolbar' });
  const start = el('button', { type: 'button', className: 'button', text: '开始上传', 'data-testid': 'upload-start' });
  const resume = el('button', { type: 'button', className: 'button', text: '继续上传', 'data-testid': 'upload-resume' });
  const pause = el('button', { type: 'button', className: 'button secondary', text: '暂停', 'data-testid': 'upload-pause' });
  const refresh = el('button', { type: 'button', className: 'button secondary', text: '查询状态', 'data-testid': 'upload-refresh' });
  const another = el('button', { type: 'button', className: 'button secondary', text: '开始另一份资料', 'data-testid': 'upload-new' });
  const forget = el('button', { type: 'button', className: 'button secondary', text: '移除已结束的本机记录', 'data-testid': 'upload-forget' });
  const runner = store ? new UploadRunner({ api, store, onChange: state => render(state) }) : null;

  element.append(
    el('div', { className: 'section-heading' }, el('div', {}, el('p', { className: 'eyebrow', text: '文件与固定版本' }), el('h1', { text: '分片上传' }))),
    el('div', { className: 'card' }, el('h2', { text: '选择上传范围' }), contextTitle, scoped, manual),
    el('div', { className: 'card' }, field('当前账号的续传记录', saved, '当前标签页刷新后保留。重新登录同一账号、选择同一原文件即可继续。'),
      field('原始文件', file), fileInfo,
      el('div', { className: 'toolbar' }, start, resume, pause, refresh, another, forget)),
    el('div', { className: 'card' }, el('h2', { text: '上传进度' }), status, bar, statistics, expires, fingerprint,
      el('p', { className: 'muted' }, '上传会话：', session),
      el('details', {}, el('summary', { text: '分片明细' }), partList), versions),
    el('p', { className: 'muted', text: '文件定版与存证分别处理。上传已受理不等于存证已确认，请在版本详情查看实际结果。' })
  );
  if (!canUpload) element.prepend(el('p', { className: 'empty', text: '当前账号没有资料上传角色。请使用参与任务的运营商操作员账号。' }));
  if (storageError) element.prepend(el('p', { className: 'empty', role: 'alert', text: errorText(storageError) }));

  function selectedRecord() { return chosen ? store?.get(chosen) : null; }
  function renderSaved() {
    if (!store) return;
    const records = store.list();
    saved.replaceChildren(option('', '新建上传会话'));
    for (const record of records) saved.append(option(record.clientRequestId, `${record.filename} · ${LABELS[record.status] || record.status} · ${short(record.uploadId || record.clientRequestId)}`));
    saved.value = chosen || '';
  }
  function fillScope(preferredRequirement, preferredExecution) {
    const record = selectedRecord();
    const requestedRequirement = preferredRequirement || record?.requirementId || requirement.value;
    const requestedExecution = preferredExecution || record?.executionId || execution.value;
    const requirements = task?.requirements || [];
    requirement.replaceChildren(option('', '选择资料项'));
    for (const item of requirements) requirement.append(option(item.id, `${item.name}${item.required ? '（必交）' : ''}`));
    if (requestedRequirement && !requirements.some(item => item.id === requestedRequirement)) requirement.append(option(requestedRequirement, `已保存资料项 ${short(requestedRequirement)}`));
    requirement.value = requestedRequirement || requirements[0]?.id || '';
    const selectedRequirement = requirements.find(item => item.id === requirement.value);
    const subtasks = (task?.subtasks || []).filter(item => !selectedRequirement?.subtaskId || item.id === selectedRequirement.subtaskId);
    execution.replaceChildren(option('', '选择执行批次'));
    for (const item of subtasks) if (item.currentExecutionId) execution.append(option(item.currentExecutionId, `${item.name} · 当前批次 ${short(item.currentExecutionId)}`));
    const known = subtasks.some(item => item.currentExecutionId === requestedExecution);
    if (requestedExecution && !known) execution.append(option(requestedExecution, `已保存批次 ${short(requestedExecution)}`));
    execution.value = requestedExecution || subtasks.find(item => item.currentExecutionId)?.currentExecutionId || '';
    requirementId.value = requirement.value; executionId.value = execution.value;
    contextTitle.textContent = task ? `当前任务：${task.name}` : '从任务详情进入可自动选择范围；续传会话始终沿用原资料项和执行批次。';
    scoped.hidden = !task && !record;
    manual.hidden = Boolean(task || record);
  }
  function render(state = runner?.state || { busy: false, phase: 'idle' }) {
    if (destroyed) return;
    if (state.record) { chosen = state.record.clientRequestId; renderSaved(); }
    const record = state.record || selectedRecord();
    const progress = state.progress;
    const terminal = progress?.processingStatus || record?.status;
    file.disabled = state.busy || !canUpload || !runner;
    requirement.disabled = execution.disabled = requirementId.disabled = executionId.disabled = state.busy || Boolean(chosen);
    saved.disabled = state.busy;
    start.hidden = Boolean(chosen); resume.hidden = !chosen;
    start.disabled = resume.disabled = state.busy || !selected || !canUpload || !runner || ['VERIFYING', 'COMPLETED', 'FAILED', 'EXPIRED'].includes(terminal) || task?.status === 'COMPLETED';
    pause.disabled = !state.busy;
    refresh.disabled = state.busy || !record?.uploadId || !runner;
    another.disabled = state.busy || !canUpload || !runner;
    forget.hidden = !record || !['COMPLETED', 'FAILED', 'EXPIRED'].includes(terminal);
    forget.disabled = state.busy;
    session.textContent = record?.uploadId || (record ? '创建结果待查询（已保留请求编号）' : '尚未创建');
    const total = progress?.size || record?.size || selected?.size || 0;
    bar.max = total || 1; bar.value = progress?.uploadedSize || 0;
    statistics.textContent = progress ? `已登记 ${progress.uploadedParts.length} / ${progress.partCount} 片 · ${size(progress.uploadedSize)} / ${size(progress.size)}` : `已登记 0 片${total ? ` · 文件 ${size(total)}` : ''}`;
    expires.textContent = progress?.expiresAt ? `会话截止：${new Date(progress.expiresAt).toLocaleString('zh-CN', { hour12: false })}` : '';
    fingerprint.textContent = state.phase === 'fingerprint' && selected ? `文件内容检查：${Math.floor(state.fingerprintBytes / selected.size * 100)}%` : '';
    status.textContent = state.error ? errorText(state.error) : state.message || (terminal ? LABELS[terminal] : '请选择文件开始。');
    partList.replaceChildren();
    if (progress) {
      const received = new Set(progress.uploadedParts.map(part => part.partNo));
      for (let number = 1; number <= Math.min(progress.partCount, 120); number += 1) partList.append(el('span', {
        className: received.has(number) ? 'part-chip received' : 'part-chip', text: `第 ${number} 片 · ${received.has(number) ? '已登记' : '待上传'}`
      }));
      if (progress.partCount > 120) partList.append(el('p', { className: 'muted', text: `共 ${progress.partCount} 片，此处显示前 120 片。` }));
    }
    versions.replaceChildren();
    if (progress?.processingStatus === 'COMPLETED' && progress.versionId) {
      const id = progress.versionId;
      const open = el('button', { type: 'button', className: 'button', text: '查看固定版本与存证', 'data-testid': 'upload-open-version' });
      open.addEventListener('click', () => { if (!destroyed) onVersionReady(id); });
      versions.append(open);
    }
  }
  async function perform(action) {
    try { await action(); }
    catch (error) { if (!destroyed && error.name !== 'AbortError') notify(errorText(error), 'error'); }
  }
  function run() {
    if (!runner || !selected) return;
    const record = selectedRecord();
    perform(() => runner.run({ file: selected, requirementId: requirementId.value.trim(), executionId: executionId.value.trim(), record }));
  }
  start.addEventListener('click', run); resume.addEventListener('click', run);
  pause.addEventListener('click', () => { runner?.pause(); });
  refresh.addEventListener('click', () => { const record = selectedRecord(); if (runner && record) perform(() => runner.watch(record)); });
  another.addEventListener('click', () => {
    chosen = null; selected = null; file.value = '';
    runner.state = { busy: false, phase: 'idle', record: null, progress: null, error: null, message: '', fingerprintBytes: 0 };
    renderSaved(); fillScope(); render();
  });
  forget.addEventListener('click', () => {
    try { store.remove(chosen); chosen = null; selected = null; file.value = ''; runner.state = { busy: false, phase: 'idle', record: null, progress: null, error: null }; renderSaved(); fillScope(); render(); }
    catch (error) { notify(errorText(error), 'error'); }
  });
  saved.addEventListener('change', () => {
    chosen = saved.value || null; selected = null; file.value = '';
    const record = selectedRecord();
    if (record && !task?.requirements?.some(item => item.id === record.requirementId)) task = null;
    runner.state = { busy: false, phase: 'idle', record, progress: null, error: null, message: record ? '请重新选择原文件继续上传，或查询后台处理状态。' : '' };
    fillScope(); render();
  });
  file.addEventListener('change', () => { selected = file.files[0] || null; fileInfo.textContent = selected ? `${selected.name} · ${size(selected.size)}` : '支持 JPG、PNG、PDF、MP4。请选择原始文件。'; render(); });
  requirement.addEventListener('change', () => { execution.value = ''; fillScope(requirement.value, ''); });
  execution.addEventListener('change', () => { executionId.value = execution.value; });

  let loadedOnce = false;
  function load() {
    if (destroyed) return;
    try {
      if (!loadedOnce && runner) {
        const pending = store.list().find(record => ['UPLOADING', 'VERIFYING'].includes(record.status));
        if (pending) { chosen = pending.clientRequestId; runner.state.record = pending; runner.state.message = `请重新选择 ${pending.filename} 继续，或查询后台处理状态。`; }
      }
      loadedOnce = true; renderSaved(); fillScope(); render();
    }
    catch (error) { status.textContent = errorText(error); start.disabled = resume.disabled = true; notify(errorText(error), 'error'); }
  }
  function open(context = {}) {
    if (destroyed) return;
    if (runner?.state.busy) { notify('请先暂停当前上传，再切换上传范围。', 'error'); return; }
    task = context.task || null; chosen = null; selected = null; file.value = '';
    if (runner) runner.state = { busy: false, phase: 'idle', record: null, progress: null, error: null, message: '', fingerprintBytes: 0 };
    renderSaved(); fillScope(context.requirementId, context.executionId); render();
  }
  load();
  return { element, load, open, destroy() { destroyed = true; runner?.destroy(); selected = null; file.value = ''; } };
}
