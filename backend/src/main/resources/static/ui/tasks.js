const OWNER = '00000000-0000-4000-8000-000000000001';
const OPERATOR = '00000000-0000-4000-8000-000000000002';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const LABELS = {
  DRAFT: '草稿', ASSIGNED: '已分配', RUNNING: '执行中', WAITING_ACCEPTANCE: '待验收',
  COMPLETED: '已完成', READY: '待开始', CLOSED: '已关闭', PENDING: '待处理',
  APPROVED: '已通过', RETURNED: '已退回', CONFIRMED: '已确认', UNKNOWN: '结果待确认',
  SUBMITTED: '确认中', FAILED: '处理失败', VERIFYING: '文件校验中', UPLOADING: '上传中',
};

function node(tag, attributes = {}, ...children) {
  const element = document.createElement(tag);
  for (const [name, value] of Object.entries(attributes)) {
    if (value === undefined || value === null || value === false) continue;
    if (name === 'text') element.textContent = String(value);
    else if (name === 'className') element.className = value;
    else if (name.startsWith('on') && typeof value === 'function') element.addEventListener(name.slice(2).toLowerCase(), value);
    else if (name in element && !name.startsWith('aria-') && !name.startsWith('data-')) element[name] = value;
    else element.setAttribute(name, value === true ? '' : String(value));
  }
  for (const child of children.flat(Infinity)) {
    if (child === null || child === undefined || child === false) continue;
    element.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return element;
}

function button(text, action, options = {}) {
  return node('button', { type: 'button', className: 'button secondary', text, onClick: action, ...options });
}
function field(label, input, hint = '') {
  return node('label', { className: 'field' }, node('span', { text: label }), input,
    hint ? node('small', { className: 'muted', text: hint }) : null);
}
function status(value) {
  return node('span', { className: 'state-badge', 'data-state': value || 'PENDING', text: LABELS[value] || value || '待处理' });
}
function time(value) {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('zh-CN', { hour12: false });
}
function short(value) { return value ? `${String(value).slice(0, 8)}…` : '—'; }
function ids(value) {
  const result = [...new Set(value.trim().split(/[\s,，;；]+/).filter(Boolean).map(value => value.toLowerCase()))];
  if (result.length > 100 || result.some(value => !UUID.test(value))) throw new Error('请填写有效的用户编号，最多 100 个。');
  return result;
}
function table(headers, rows, empty = '暂无记录') {
  if (!rows.length) return node('p', { className: 'empty', text: empty });
  return node('div', { className: 'table-wrap' }, node('table', { className: 'table' },
    node('thead', {}, node('tr', {}, headers.map(text => node('th', { scope: 'col', text })))),
    node('tbody', {}, rows.map(cells => node('tr', {}, cells.map(cell => node('td', {}, cell)))))));
}

function requirementsEditor(requirements = [{ name: '', required: true }]) {
  const rows = [];
  const list = node('div', { className: 'requirements-editor' });
  function add(item = {}) {
    const name = node('input', { value: item.name || '', required: true, maxLength: 200,
      placeholder: '例如：线路巡检照片', 'data-testid': 'requirement-name' });
    const required = node('input', { type: 'checkbox', checked: item.required !== false });
    const row = { id: item.id, name, required };
    const remove = button('移除', () => { rows.splice(rows.indexOf(row), 1); row.element.remove(); }, {
      disabled: Boolean(item.subtaskId), 'aria-label': '移除此资料要求', className: 'button subtle',
      title: item.subtaskId ? '已绑定子任务的资料要求不能移除' : '移除此资料要求',
    });
    row.element = node('div', { className: 'requirement-editor-row' }, field('资料名称', name),
      node('label', { className: 'checkbox-field' }, required, '必交'), remove);
    rows.push(row); list.append(row.element);
  }
  requirements.forEach(add);
  const element = node('div', {}, list, button('添加资料项', () => add(), { 'data-testid': 'requirement-add' }));
  return { element, values() {
    if (rows.length > 100) throw new Error('一项任务最多配置 100 项资料要求。');
    return rows.map(row => {
      const name = row.name.value.trim();
      if (!name) { row.name.focus(); throw new Error('请填写每一项资料名称。'); }
      return { ...(row.id ? { id: row.id } : {}), name, required: row.required.checked };
    });
  } };
}

/** Same-origin task workspace. The backend remains authoritative for every role, membership and CAS. */
export function createTasksPanel({ api, actor, onSelectTask = () => {}, onOpenVersion = () => {}, onOpenUpload = () => {}, notify = () => {} }) {
  const lifecycle = new AbortController();
  const roles = new Set(actor.roles || []);
  const ownerManager = actor.orgId === OWNER && roles.has('OWNER_TASK_ADMIN');
  const operatorManager = actor.orgId === OPERATOR && roles.has('OPERATOR_TASK_ADMIN');
  const uploader = actor.orgId === OPERATOR && (roles.has('OPERATOR_USER') || operatorManager);
  const pageSize = 12;
  let page = 1, total = 0, listSequence = 0, detailSequence = 0, disposed = false, selected = null;
  let deviceItems = [], devicePage = 0, deviceTotal = 0, devicesPromise = null;
  let currentFormTask = null, busy = false;
  const disclosures = [];
  const dialogs = new Set();
  const message = node('p', { className: 'inline-error', role: 'alert', hidden: true });
  const list = node('div', { className: 'task-list' });
  const pageInfo = node('span', { className: 'muted' });
  const previous = button('上一页', () => { if (page > 1) { page--; load(); } });
  const next = button('下一页', () => { if (page * pageSize < total) { page++; load(); } });
  const listTitle = node('h2', { text: '我的任务' });
  const detailHeading = node('div');
  const detailContent = node('div');
  const actions = node('div', { className: 'task-action-list' });
  const detail = node('section', { className: 'panel task-detail', 'data-testid': 'task-detail', hidden: true }, detailHeading, detailContent, actions);
  const element = node('section', { className: 'tasks-workspace' },
    node('div', { className: 'page-heading' }, node('div', {}, node('p', { className: 'eyebrow', text: '任务工作台' }),
      node('h1', { text: '巡检任务' }), node('p', { className: 'muted', text: '查看执行进度，管理资料和任务交付。' })),
    node('div', { className: 'toolbar' }, button('刷新', () => refresh(), { 'data-testid': 'task-refresh' }),
      ownerManager ? button('新建任务', openCreate, { className: 'button', 'data-testid': 'task-create' }) : null)),
    message, node('section', { className: 'panel' }, node('div', { className: 'section-heading' }, listTitle), list,
      node('div', { className: 'pagination' }, previous, pageInfo, next)), detail);

  const request = (path, options = {}) => api.request(path, { ...options, signal: lifecycle.signal });
  function failure(error, target = message) {
    if (disposed || error?.name === 'AbortError') return;
    const description = error?.message || '操作未完成，请稍后重试。';
    target.textContent = `${description}${error?.traceId ? `（请求编号 ${error.traceId}）` : ''}`;
    target.hidden = false;
    notify(description, 'error');
  }
  function clear(target = message) { target.hidden = true; target.textContent = ''; }
  function updatePager() {
    previous.disabled = page <= 1;
    next.disabled = page * pageSize >= total;
    pageInfo.textContent = `第 ${page} / ${Math.max(1, Math.ceil(total / pageSize))} 页 · 共 ${total} 项可访问任务`;
    listTitle.textContent = `我的任务 · ${total}`;
  }
  async function load() {
    const sequence = ++listSequence;
    clear(); list.setAttribute('aria-busy', 'true');
    if (!list.childElementCount) list.append(node('p', { className: 'empty', text: '正在加载任务…' }));
    try {
      const result = await request(`/tasks?page=${page}&pageSize=${pageSize}`);
      if (disposed || sequence !== listSequence) return;
      total = result.total;
      if (!total) page = 1;
      if (page > 1 && !result.items.length && total > 0) { page = Math.ceil(total / pageSize); return load(); }
      list.replaceChildren(...result.items.map(task => {
        const open = button(task.name, () => openTask(task.id), { className: 'button quiet task-card-title', 'data-testid': 'task-open' });
        return node('article', { className: `card task-card${selected?.id === task.id ? ' is-selected' : ''}`, 'data-testid': 'task-card', 'data-task-id': task.id },
          node('div', { className: 'card-heading' }, open, status(task.status)),
          node('p', { className: 'task-scope', text: task.scope }),
          node('div', { className: 'card-footer' }, node('span', { className: 'muted', text: `更新于 ${time(task.updatedAt)}` }),
            button('查看任务', () => openTask(task.id), { className: 'button subtle' })));
      }));
      if (!result.items.length) list.append(node('p', { className: 'empty', text: '暂无可访问任务。加入任务或获得资料授权后，可在对应工作台查看。' }));
      updatePager();
    } catch (error) {
      if (sequence === listSequence) {
        list.replaceChildren(); total = 0; page = 1; updatePager(); failure(error);
      }
    }
    finally { if (sequence === listSequence) list.removeAttribute('aria-busy'); }
  }
  async function openTask(id, { preserveForms = false, scroll = true } = {}) {
    if (!UUID.test(id || '')) { failure(new Error('任务编号格式不正确。')); return; }
    const sequence = ++detailSequence;
    clear(); detail.setAttribute('aria-busy', 'true');
    try {
      const [task, progress] = await Promise.all([request(`/tasks/${id}`), request(`/tasks/${id}/progress`)]);
      if (disposed || sequence !== detailSequence) return;
      const changed = selected?.id !== id;
      selected = task; detail.hidden = false;
      renderDetail(task, progress);
      if (!preserveForms || changed || currentFormTask !== id) buildActions(task);
      syncActions(); onSelectTask(task);
      for (const card of list.querySelectorAll('[data-task-id]')) card.classList.toggle('is-selected', card.dataset.taskId === id);
      if (scroll) detail.scrollIntoView({ behavior: 'smooth', block: 'start' });
    } catch (error) {
      if (sequence !== detailSequence || disposed) return;
      if (error?.status === 403 || error?.status === 401) {
        selected = null; currentFormTask = null; detail.hidden = true; detailContent.replaceChildren(); actions.replaceChildren();
      }
      failure(error);
    } finally { if (sequence === detailSequence) detail.removeAttribute('aria-busy'); }
  }
  async function refresh() {
    await Promise.all([load(), selected ? openTask(selected.id, { preserveForms: true, scroll: false }) : Promise.resolve()]);
  }
  function renderDetail(task, progress) {
    detailHeading.replaceChildren(node('div', { className: 'section-heading' }, node('div', {},
      node('h2', { text: task.name }), node('p', { className: 'muted mono', text: `任务编号 ${task.id}` })), status(task.status)),
    node('p', { className: 'task-scope', text: task.scope }),
    node('p', { className: 'muted', text: task.completedAt ? `完成于 ${time(task.completedAt)}` : `最近更新 ${time(task.updatedAt)}` }));
    const progressById = new Map((progress.requirements || []).map(item => [item.requirementId, item]));
    const ready = `${progress.acceptanceReadyRequiredCount} / ${progress.requiredCount}`;
    const stats = node('div', { className: 'metric-grid' },
      metric('上报平均进度', `${progress.averageProgress}%`), metric('执行子任务', progress.subtaskCount),
      metric('必交资料就绪', ready), metric('历史版本', progress.historicalVersionCount));
    const flights = node('div', { className: 'progress-grid' }, (progress.subtasks || []).map(subtask => {
      const device = deviceItems.find(item => item.id === subtask.droneId);
      return node('article', { className: 'card progress-card' }, node('div', { className: 'card-heading' },
        node('strong', { text: subtask.name }), status(subtask.executionStatus || 'READY')),
      node('p', { className: 'muted', text: subtask.droneId ? `设备 ${device?.code || short(subtask.droneId)}` : '尚未分配设备' }),
      node('progress', { max: 100, value: subtask.progress, 'aria-label': `${subtask.name}上报进度` }),
      node('div', { className: 'card-footer' }, node('span', { text: `${subtask.progress}%` }),
        button('执行历史', () => openHistory(subtask.subtaskId), { className: 'button subtle' })));
    }));
    if (!flights.childElementCount) flights.append(node('p', { className: 'empty', text: '尚未建立执行子任务。' }));
    const requirements = node('div', { className: 'requirements-list' });
    for (const item of task.requirements || []) {
      const current = progressById.get(item.id);
      const subtask = task.subtasks.find(subtask => subtask.id === item.subtaskId);
      const row = node('article', { className: 'card requirement-row', 'data-testid': 'requirement-row', 'data-requirement-id': item.id },
        node('div', {}, node('strong', { text: item.name }), node('span', { className: 'muted', text: item.required ? ' · 必交' : ' · 选交' }),
          node('p', { className: 'muted', text: subtask ? `所属子任务：${subtask.name}` : '尚未绑定子任务' })),
        node('div', { className: 'toolbar' }, current?.versionId ? button(`查看 V${current.versionNo}`, () => onOpenVersion(current.versionId)) : node('span', { className: 'muted', text: '当前批次暂无版本' }),
          current?.versionId ? status(current.reviewStatus) : null, current?.versionId ? status(current.anchorStatus) : null,
          uploader && ['RUNNING', 'WAITING_ACCEPTANCE'].includes(task.status) && subtask?.currentExecutionId
            ? button('上传资料', () => onOpenUpload(selected, { requirementId: item.id, executionId: subtask.currentExecutionId }), { 'data-testid': 'upload-requirement' }) : null));
      requirements.append(row);
    }
    if (!requirements.childElementCount) requirements.append(node('p', { className: 'empty', text: '尚未配置资料要求。' }));
    const members = table(['成员', '机构', '用户编号'], (task.members || []).map(member => [member.username,
      member.orgId === task.ownerOrgId ? '委托方' : '运营商', node('span', { className: 'mono', text: member.userId })]));
    detailContent.replaceChildren(stats, node('h3', { text: '多机执行进度' }), flights,
      node('h3', { text: '资料与当前版本' }), requirements,
      node('details', { className: 'disclosure' }, node('summary', { text: '任务成员与编号' }), members));
  }
  function metric(label, value) { return node('div', { className: 'metric-card' }, node('span', { className: 'muted', text: label }), node('strong', { text: value })); }
  function showDialog(title, content) {
    const dialog = node('dialog', { className: 'dialog' });
    dialog.append(node('div', { className: 'dialog-body' }, node('div', { className: 'section-heading' },
      node('h2', { text: title }), button('关闭', () => dialog.close(), { className: 'button subtle', 'aria-label': '关闭对话框' })), content));
    dialog.addEventListener('close', () => { dialogs.delete(dialog); dialog.remove(); });
    dialogs.add(dialog); element.append(dialog); dialog.showModal(); return dialog;
  }
  function openCreate() {
    const name = node('input', { required: true, maxLength: 200, placeholder: '例如：园区输电线路季度巡检', 'data-testid': 'task-name' });
    const scope = node('textarea', { required: true, maxLength: 16000, rows: 3, placeholder: '填写巡检区域、范围和交付说明', 'data-testid': 'task-scope' });
    const requirements = requirementsEditor();
    const error = node('p', { className: 'inline-error', role: 'alert', hidden: true });
    const submit = node('button', { type: 'submit', className: 'button', text: '创建任务', 'data-testid': 'task-create-submit' });
    const form = node('form', {}, field('任务名称', name), field('巡检范围', scope),
      node('h3', { text: '交付资料要求' }), requirements.element, error,
      node('div', { className: 'form-actions' }, submit));
    const dialog = showDialog('新建巡检任务', form);
    form.addEventListener('submit', async event => {
      event.preventDefault(); clear(error);
      let body;
      try { body = { name: name.value.trim(), scope: scope.value.trim(), requirements: requirements.values() }; }
      catch (problem) { failure(problem, error); return; }
      submit.disabled = true;
      try {
        const task = await request('/tasks', { method: 'POST', body });
        if (disposed) return;
        dialog.close(); page = 1; await load(); await openTask(task.id); notify('任务已创建。', 'success');
      } catch (problem) { failure(problem, error); }
      finally { submit.disabled = false; }
    });
    name.focus();
  }
  function disclosure(title, fields, submitText, testId, eligible, handler) {
    const error = node('p', { className: 'inline-error', role: 'alert', hidden: true });
    const submit = node('button', { type: 'submit', className: 'button', text: submitText, 'data-testid': testId });
    const form = node('form', {}, fields, error, node('div', { className: 'form-actions' }, submit));
    const section = node('details', { className: 'disclosure' }, node('summary', { text: title }), form);
    disclosures.push({ section, eligible, submit });
    form.addEventListener('submit', event => { event.preventDefault(); if (!busy) mutate(handler, error, submit); });
    actions.append(section); return section;
  }
  function syncActions() {
    for (const item of disclosures) { item.section.hidden = !selected || !item.eligible(selected); item.submit.disabled = busy; }
  }
  async function mutate(handler, error = message, submit = null) {
    if (!selected || busy) return;
    const taskId = selected.id;
    const before = JSON.stringify([selected.lockVersion, selected.status, selected.subtasks.map(item => [item.id, item.lockVersion])]);
    clear(error); busy = true; syncActions(); if (submit) submit.disabled = true;
    try {
      await handler();
      if (disposed) return;
      notify('操作已完成。', 'success');
      await load();
      if (selected?.id === taskId) await openTask(taskId, { preserveForms: false, scroll: false });
    } catch (problem) {
      if (problem?.status === 409 && selected?.id === taskId) {
        await openTask(taskId, { preserveForms: true, scroll: false });
        const after = selected ? JSON.stringify([selected.lockVersion, selected.status, selected.subtasks.map(item => [item.id, item.lockVersion])]) : null;
        failure({ ...problem, message: before !== after
          ? '任务状态已更新，已加载最新状态并保留表单内容。请核对后再次提交。'
          : '当前任务尚不满足此操作条件。请核对资料、设备、复核和存证状态后重试，表单内容已保留。' }, error);
      } else failure(problem, error);
    } finally { busy = false; syncActions(); if (submit?.isConnected) submit.disabled = false; }
  }
  function buildActions(task) {
    actions.replaceChildren(); disclosures.length = 0; currentFormTask = task.id;
    const path = () => `/tasks/${selected.id}`;
    const currentLock = () => ({ expectedLockVersion: selected.lockVersion });
    if (ownerManager) {
      const adminId = node('input', { required: true, placeholder: '运营商任务管理员的用户编号', 'data-testid': 'assign-admin-id' });
      disclosure('分配运营商', field('运营商管理员编号', adminId, '由该账号登录后，在个人信息中复制用户编号。'), '确认分配', 'assign-submit', task => task.status === 'DRAFT', () => {
        const id = adminId.value.trim().toLowerCase(); if (!UUID.test(id)) throw new Error('请填写有效的管理员用户编号。');
        return request(`${path()}/assign`, { method: 'POST', body: { operatorOrgId: selected.operatorOrgId, operatorAdminId: id, ...currentLock() } });
      });
      const editor = requirementsEditor(task.requirements);
      disclosure('维护资料要求', editor.element, '保存资料要求', 'requirements-submit', task => ['DRAFT', 'ASSIGNED'].includes(task.status), () =>
        request(`${path()}/requirements`, { method: 'PUT', body: { requirements: editor.values(), ...currentLock() } }));
    }
    if (ownerManager || operatorManager) {
      const ownMembers = (task.members || []).filter(member => member.orgId === actor.orgId);
      const checks = ownMembers.map(member => ({ id: member.userId, input: node('input', { type: 'checkbox', checked: true,
        disabled: member.userId === task.createdBy, 'data-member-id': member.userId }), name: member.username }));
      const extra = node('textarea', { rows: 2, placeholder: '多个用户编号用换行或逗号分隔', 'data-testid': 'members-extra' });
      const fields = node('div', {}, node('p', { className: 'muted', text: '仅维护本机构成员。任务创建者及另一机构成员保留；用户编号可从该账号的个人信息复制。' }),
        node('div', { className: 'checkbox-grid' }, checks.map(item => node('label', { className: 'checkbox-field' }, item.input, item.name))), field('新增成员编号', extra));
      disclosure('维护本机构成员', fields, '保存成员', 'members-submit', () => true, () => {
        const userIds = [...new Set([...checks.filter(item => item.input.checked).map(item => item.id), ...ids(extra.value)])];
        if (userIds.length > 100) throw new Error('每次最多指定 100 位成员。');
        return request(`${path()}/members`, { method: 'PUT', body: { userIds, ...currentLock() } });
      });
    }
    if (operatorManager) {
      const name = node('input', { required: true, maxLength: 200, placeholder: '例如：北区线路巡检', 'data-testid': 'subtask-name' });
      const options = task.requirements.filter(item => !item.subtaskId).map(item => ({ id: item.id, input: node('input', {
        type: 'checkbox', 'data-requirement-id': item.id }), name: item.name }));
      disclosure('建立执行子任务', node('div', {}, field('子任务名称', name), node('p', { className: 'muted', text: '选择尚未绑定的资料要求。每个子任务至少需要一项必交资料。' }),
        node('div', { className: 'checkbox-grid' }, options.map(item => node('label', { className: 'checkbox-field' }, item.input, item.name)))),
      '建立子任务', 'subtask-submit', task => task.status === 'ASSIGNED' && options.length > 0, () => {
        const requirementIds = options.filter(item => item.input.checked).map(item => item.id);
        if (!requirementIds.length) throw new Error('请至少选择一项资料要求。');
        return request(`${path()}/subtasks`, { method: 'POST', body: { name: name.value.trim(), requirementIds, ...currentLock() } });
      });
      const subtask = node('select', { required: true, 'data-testid': 'execution-subtask' });
      subtask.append(node('option', { value: '', text: '请选择子任务' }), ...task.subtasks.map(item => node('option', { value: item.id, text: item.name })));
      const drone = node('select', { required: true, 'data-testid': 'execution-drone' }, node('option', { value: '', text: '载入设备后选择' }));
      const reason = node('textarea', { rows: 2, maxLength: 1000, 'data-testid': 'execution-reason', placeholder: '运行中换机时填写调整原因' });
      const deviceHint = node('p', { className: 'muted', text: '仅列出当前已启用设备；设备启停由设备管理页维护。' });
      const moreDevices = button('载入设备', async () => {
        moreDevices.disabled = true;
        try { await loadDevices(); fillDevices(); }
        catch (error) { failure(error); }
        finally { moreDevices.disabled = false; }
      }, { 'data-testid': 'execution-load-devices' });
      function fillDevices() {
        const previous = drone.value;
        const selectedSubtask = selected?.subtasks.find(item => item.id === subtask.value);
        const choices = deviceItems.filter(item => item.enabled && item.id !== selectedSubtask?.execution?.droneId);
        drone.replaceChildren(node('option', { value: '', text: '请选择启用设备' }), ...choices.map(item => node('option', {
          value: item.id, text: `${item.code} · ${item.online ? '在线' : '离线'}` })));
        if (choices.some(item => item.id === previous)) drone.value = previous;
        deviceHint.textContent = `已载入 ${deviceItems.length} / ${deviceTotal} 台设备，仅显示已启用且可用于本次分配的设备。`;
        moreDevices.textContent = deviceItems.length < deviceTotal ? '载入更多设备' : '刷新设备';
      }
      subtask.addEventListener('change', fillDevices);
      const executionSection = disclosure('分配设备或换机', node('div', {}, field('执行子任务', subtask), field('巡检设备', drone), moreDevices, deviceHint,
        field('调整原因', reason, '首次分配无需填写；运行中更换设备时必填。')), '保存设备分配', 'execution-submit',
      task => ['ASSIGNED', 'RUNNING', 'WAITING_ACCEPTANCE'].includes(task.status) && task.subtasks.length > 0, () => {
        const current = selected.subtasks.find(item => item.id === subtask.value);
        if (!current || !drone.value) throw new Error('请选择子任务和设备。');
        const replacement = Boolean(current.currentExecutionId);
        if (replacement && !reason.value.trim()) throw new Error('运行中换机请填写调整原因。');
        return request(`/subtasks/${current.id}/executions`, { method: 'POST', body: {
          droneId: drone.value, expectedLockVersion: current.lockVersion, ...(reason.value.trim() ? { reason: reason.value.trim() } : {}),
        } });
      });
      executionSection.addEventListener('toggle', async () => {
        if (!executionSection.open) return;
        if (deviceItems.length) { fillDevices(); return; }
        try { await loadDevices(); if (executionSection.isConnected) fillDevices(); } catch (error) { failure(error); }
      });
      if (deviceItems.length) fillDevices();
      actionButton('开始巡检', 'task-start', task => task.status === 'ASSIGNED', () => request(`${path()}/start`, { method: 'POST', body: currentLock() }));
      actionButton('提交验收', 'task-submit-acceptance', task => task.status === 'RUNNING', () => request(`${path()}/submit-acceptance`, { method: 'POST', body: currentLock() }));
    }
    if (ownerManager) actionButton('确认任务完成', 'task-complete', task => task.status === 'WAITING_ACCEPTANCE', () => request(`${path()}/complete`, { method: 'POST', body: currentLock() }));
  }
  function actionButton(text, testId, eligible, handler) {
    const submit = button(text, () => mutate(handler), { className: 'button', 'data-testid': testId });
    const section = node('div', { className: 'form-actions' }, submit);
    disclosures.push({ section, eligible, submit }); actions.append(section);
  }
  async function loadDevices() {
    if (devicesPromise) return devicesPromise;
    const nextPage = deviceItems.length < deviceTotal || devicePage === 0 ? devicePage + 1 : 1;
    devicesPromise = request(`/drones?page=${nextPage}&pageSize=100`).then(result => {
      if (nextPage === 1) deviceItems = [];
      deviceItems = [...new Map([...deviceItems, ...result.items].map(item => [item.id, item])).values()];
      devicePage = nextPage; deviceTotal = result.total;
    }).finally(() => { devicesPromise = null; });
    return devicesPromise;
  }
  async function openHistory(id) {
    const container = node('div', {}, node('p', { className: 'empty', text: '正在加载执行历史…' }));
    const dialog = showDialog('执行历史', container);
    let historyPage = 1;
    async function fetchPage() {
      try {
        const result = await request(`/subtasks/${id}/executions?page=${historyPage}&pageSize=20`);
        if (!dialog.isConnected || disposed) return;
        const rows = result.items.map(item => [item.current ? '当前批次' : '历史批次', node('span', { className: 'mono', text: item.id }),
          node('span', { className: 'mono', text: item.droneId }), status(item.status), `${item.progress}%`, time(item.startedAt), item.adjustmentReason || '—']);
        container.replaceChildren(table(['批次', '执行编号', '设备编号', '状态', '进度', '开始时间', '调整原因'], rows),
          node('div', { className: 'pagination' }, button('上一页', () => { historyPage--; fetchPage(); }, { disabled: historyPage <= 1 }),
            node('span', { className: 'muted', text: `第 ${historyPage} 页 · 共 ${result.total} 批次` }),
            button('下一页', () => { historyPage++; fetchPage(); }, { disabled: historyPage * 20 >= result.total })));
      } catch (error) { if (dialog.isConnected) { container.replaceChildren(); failure(error, container); } }
    }
    await fetchPage();
  }
  function destroy() {
    disposed = true; lifecycle.abort(); selected = null;
    for (const dialog of dialogs) dialog.close();
    dialogs.clear(); element.replaceChildren();
  }
  return { element, load, openTask, refresh, destroy };
}
