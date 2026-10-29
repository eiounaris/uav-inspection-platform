import { el, field, button, badge, format, empty, pageBar, dialog, bindSubmit, errorText } from './common.js';

const localTime = value => {
  const date = new Date(value);
  return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
};
const utcTime = value => {
  const date = new Date(value);
  if (!Number.isFinite(date.getTime()) || date.getTime() <= Date.now()) throw new Error('请选择晚于当前时间的有效期限');
  return date.toISOString();
};

export function createSharingPanel({ api, actor, notify, onOpenVersion, getSelectedTask = () => null }) {
  const canApprove = actor.roles.includes('OWNER_DATA_APPROVER');
  const state = { page: 1, pageSize: 10, total: 0, mode: 'mine' };
  let rows = [], destroyed = false, request = null, generation = 0;
  const opened = new Set();
  const content = el('div', { className: 'sharing-list' });
  const taskInput = el('input', { type: 'text', placeholder: '输入所属任务编号', testId: 'grant-task', autocomplete: 'off' });
  const taskControls = el('div', { className: 'toolbar', hidden: true },
    field('所属任务', taskInput, '只展示你在该任务中有审批权限的申请'),
    button('使用当前任务', () => {
      const selected = getSelectedTask();
      if (!selected?.id) { notify('请先在任务工作台打开一个任务', 'error'); return; }
      taskInput.value = selected.id; state.page = 1; load();
    }, { className: 'button secondary' }),
    button('查询申请', () => { state.page = 1; load(); }, { testId: 'grant-load' }));
  const mineButton = button('我的申请', () => setMode('mine'), { className: 'button secondary', 'aria-pressed': 'true', testId: 'grant-mine' });
  const taskButton = canApprove ? button('任务审批', () => setMode('task'), { className: 'button secondary', 'aria-pressed': 'false', testId: 'grant-task-mode' }) : null;
  const element = el('section', { className: 'panel', 'aria-label': '共享授权' },
    el('div', { className: 'section-heading' },
      el('div', {}, el('p', { className: 'eyebrow' }, '跨机构协作'), el('h1', {}, '共享授权'), el('p', { className: 'muted' }, '每项授权只适用于指定文件版本，申请与审批均保留记录。')),
      button('申请共享', () => openApply(), { testId: 'grant-new' })),
    el('div', { className: 'card' }, el('div', { className: 'toolbar' }, mineButton, taskButton,
      button('刷新', () => load(), { className: 'button quiet', testId: 'grant-refresh' })), taskControls), content);

  function track(node) { opened.add(node); node.addEventListener('close', () => opened.delete(node), { once: true }); return node; }
  function setMode(mode) {
    state.mode = mode; state.page = 1;
    mineButton.setAttribute('aria-pressed', String(mode === 'mine'));
    taskButton?.setAttribute('aria-pressed', String(mode === 'task'));
    taskControls.hidden = mode !== 'task';
    if (mode === 'task' && !taskInput.value) taskInput.value = getSelectedTask()?.id || '';
    load();
  }
  function path() {
    const query = new URLSearchParams({ mine: String(state.mode === 'mine'), page: String(state.page), pageSize: String(state.pageSize) });
    if (state.mode === 'task') query.set('taskId', taskInput.value.trim());
    return `/access-grants?${query}`;
  }
  async function load() {
    if (destroyed) return;
    request?.abort(); request = new AbortController(); const selected = ++generation;
    if (state.mode === 'task' && !taskInput.value.trim()) { rows = []; state.total = 0; content.replaceChildren(empty('请选择所属任务', '任务审批按任务分别查询，不展示其他任务的申请。')); return; }
    content.replaceChildren(el('div', { className: 'card muted', role: 'status' }, '正在获取当前权限范围内的申请…'));
    try {
      const data = await api.request(path(), { signal: request.signal });
      if (destroyed || selected !== generation) return;
      rows = data.items; state.total = data.total;
      render();
    } catch (error) {
      if (destroyed || selected !== generation || error?.name === 'AbortError') return;
      content.replaceChildren(el('div', { className: 'card', role: 'alert' }, el('p', {}, errorText(error)), button('重试', () => load(), { className: 'button secondary' })));
      notify(errorText(error), 'error');
    }
  }
  function render() {
    const body = el('tbody');
    for (const row of rows) {
      const actions = el('div', { className: 'toolbar' });
      if (row.status === 'APPROVED') actions.append(button('查看版本', () => onOpenVersion(row.versionId), { className: 'button secondary', testId: 'grant-open-version' }));
      if (state.mode === 'task' && canApprove) {
        if (row.status === 'PENDING') actions.append(
          button('批准', () => decision(row, 'APPROVED'), { testId: 'grant-approve' }),
          button('拒绝', () => decision(row, 'REJECTED'), { className: 'button secondary', testId: 'grant-reject' }));
        if (row.status === 'APPROVED') actions.append(button('撤销', () => decision(row, 'REVOKED'), { className: 'button danger', testId: 'grant-revoke' }));
      }
      const note = row.revokeReason || row.decisionReason;
      body.append(el('tr', { dataset: { grantId: row.id }, testId: 'grant-row' },
        el('td', {}, el('code', { title: row.versionId }, format.shortId(row.versionId)), el('div', { className: 'muted' }, `申请人 ${format.shortId(row.applicantId)}`)),
        el('td', {}, badge(row.status)),
        el('td', {}, el('span', {}, row.reason), note ? el('div', { className: 'muted' }, `处理说明：${note}`) : null),
        el('td', {}, format.time(row.validUntil || row.requestedValidUntil), el('div', { className: 'muted' }, row.validUntil ? '授权有效期' : '申请有效期')),
        el('td', {}, actions)));
    }
    const table = el('div', { className: 'table-wrap' }, el('table', { className: 'table' },
      el('thead', {}, el('tr', {}, ...['指定版本', '当前状态', '申请用途', '截止时间', '操作'].map(text => el('th', { scope: 'col' }, text)))), body));
    content.replaceChildren(el('div', { className: 'card' }, rows.length ? table : empty('暂无共享申请', state.mode === 'mine' ? '已提交的申请将在这里展示，批准后可查看指定版本。' : '该任务当前没有可展示的申请。'),
      pageBar(state, page => { state.page = page; load(); })));
  }
  function openApply(versionId = '') {
    if (destroyed) return;
    const version = el('input', { type: 'text', value: versionId, required: true, pattern: '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', testId: 'grant-version', autocomplete: 'off' });
    const reason = el('textarea', { required: true, rows: 3, maxlength: 1000, testId: 'grant-reason', placeholder: '说明资料用途和申请原因' });
    const until = el('input', { type: 'datetime-local', required: true, value: localTime(Date.now() + 86400000), testId: 'grant-until' });
    const error = el('p', { className: 'form-error', role: 'alert', hidden: true });
    const form = el('form', { className: 'form-stack' }, field('资料版本编号', version, '授权只覆盖此固定版本，不开放所属任务及其他版本。'), field('申请用途', reason), field('申请截止时间', until), error,
      el('div', { className: 'toolbar' }, el('button', { type: 'submit', className: 'button', testId: 'grant-apply' }, '提交申请')));
    const modal = track(dialog('申请指定版本共享', form));
    let previousPayload = '', clientRequestId = '';
    bindSubmit(form, async () => {
      error.hidden = true;
      const body = { versionId: version.value.trim(), reason: reason.value.trim(), requestedValidUntil: utcTime(until.value) };
      if (!body.reason) throw new Error('请填写申请用途');
      const signature = JSON.stringify(body);
      if (signature !== previousPayload) { previousPayload = signature; clientRequestId = crypto.randomUUID(); }
      try {
        await api.request('/access-grants', { method: 'POST', body: { ...body, clientRequestId } });
        if (destroyed) return;
        modal.close(); notify('共享申请已提交', 'success'); setMode('mine');
      } catch (failure) { error.textContent = errorText(failure); error.hidden = false; throw failure; }
    }, notify);
  }
  function decision(row, action) {
    if (destroyed) return;
    let current = row, busy = false;
    const title = action === 'APPROVED' ? '批准共享' : action === 'REJECTED' ? '拒绝共享申请' : '撤销共享授权';
    const until = action === 'APPROVED' ? el('input', { type: 'datetime-local', required: true, value: localTime(row.requestedValidUntil), max: localTime(row.requestedValidUntil), testId: 'grant-decision-until' }) : null;
    const reason = el('textarea', { rows: 3, maxlength: 1000, required: action !== 'APPROVED', testId: 'grant-decision-reason', placeholder: action === 'APPROVED' ? '可选：补充批准说明' : '请填写处理原因' });
    const stateLine = el('div', { className: 'toolbar' }, el('span', { className: 'muted' }, '当前状态'), badge(row.status));
    const error = el('p', { className: 'form-error', role: 'alert', hidden: true });
    const submit = el('button', { type: 'submit', className: action === 'REVOKED' ? 'button danger' : 'button', testId: 'grant-confirm' }, title);
    const form = el('form', { className: 'form-stack' }, el('p', {}, `指定版本 ${row.versionId}`), stateLine,
      until ? field('授权截止时间', until, `不得超过申请期限 ${format.time(row.requestedValidUntil)}`) : null,
      field(action === 'APPROVED' ? '批准说明' : '处理原因', reason), error, el('div', { className: 'toolbar' }, submit));
    const modal = track(dialog(title, form));
    const allowed = () => current && (action === 'REVOKED' ? current.status === 'APPROVED' : current.status === 'PENDING');
    form.addEventListener('submit', async event => {
      event.preventDefault(); if (busy || !allowed() || !form.reportValidity()) return;
      busy = true; submit.disabled = true; error.hidden = true;
      try {
        const text = reason.value.trim();
        if (action !== 'APPROVED' && !text) throw new Error('请填写处理原因');
        const body = action === 'REVOKED' ? { expectedLockVersion: current.lockVersion, reason: text } : { expectedLockVersion: current.lockVersion, decision: action, ...(text ? { reason: text } : {}), ...(until ? { validUntil: utcTime(until.value) } : {}) };
        await api.request(`/access-grants/${current.id}/${action === 'REVOKED' ? 'revoke' : 'decision'}`, { method: 'POST', body });
        if (destroyed) return;
        modal.close(); notify(action === 'REVOKED' ? '共享授权已撤销' : action === 'APPROVED' ? '共享授权已批准' : '共享申请已拒绝', 'success'); await load();
      } catch (failure) {
        if (destroyed || failure?.name === 'AbortError') return;
        if (failure.status === 409) {
          await load(); if (destroyed) return;
          current = rows.find(item => item.id === row.id) || null;
          stateLine.replaceChildren(el('span', { className: 'muted' }, '最新状态'), current ? badge(current.status) : el('span', {}, '当前页面已无此申请'));
          error.textContent = `${errorText(failure)}。填写内容已保留，请核对最新状态后再操作。`;
        } else error.textContent = errorText(failure);
        error.hidden = false; notify(errorText(failure), 'error');
      } finally { busy = false; submit.disabled = !allowed(); }
    });
  }
  function destroy() { destroyed = true; generation++; request?.abort(); for (const modal of opened) modal.close(); opened.clear(); }
  return { element, load, openApply, destroy };
}
