export function el(tag, attributes = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attributes || {})) {
    if (value === undefined || value === null || value === false) continue;
    if (key === 'className' || key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else if (key === 'testId') node.dataset.testid = value;
    else if (key === 'dataset') Object.assign(node.dataset, value);
    else if (key.startsWith('on') && typeof value === 'function') node.addEventListener(key.slice(2).toLowerCase(), value);
    else if (key === 'value') node.value = value;
    else if (key === 'checked' || key === 'disabled' || key === 'hidden' || key === 'selected' || key === 'required') node[key] = Boolean(value);
    else if (key !== 'style' && !key.toLowerCase().startsWith('on')) node.setAttribute(key, value === true ? '' : String(value));
  }
  for (const child of children.flat(Infinity)) {
    if (child === undefined || child === null || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}
export const dom = { el };
const labels = {
  DRAFT: '草稿', ASSIGNED: '已分配', READY: '待执行', RUNNING: '执行中', WAITING_ACCEPTANCE: '待验收', COMPLETED: '已完成', CLOSED: '已关闭',
  UPLOADING: '上传中', VERIFYING: '校验定版中', EXPIRED: '已过期', FAILED: '处理失败', PENDING: '待处理', SUBMITTED: '等待确认',
  UNKNOWN: '结果待确认', CONFIRMED: '已确认', APPROVED: '已通过', RETURNED: '已退回', REJECTED: '已拒绝', REVOKED: '已撤销',
  MATCH: '核验一致', MISMATCH: '核验不一致', ANCHOR_UNCONFIRMED: '尚未确认存证', FILE_READ_FAILED: '文件读取失败', CHAIN_QUERY_FAILED: '存证查询失败',
  ONLINE: '在线', OFFLINE: '离线', ENABLED: '已启用', DISABLED: '已停用', STORED: '存储文件', COPY: '上传副本', SUCCESS: '成功', VALID: '有效', INVALID: '无效',
};
export const roleLabels = { ORG_ADMIN: '机构管理员', OWNER_TASK_ADMIN: '委托方任务管理', OWNER_REVIEWER: '资料复核', OWNER_DATA_APPROVER: '共享审批', OPERATOR_TASK_ADMIN: '运营方任务管理', OPERATOR_USER: '巡检操作员' };
export const format = {
  status: value => labels[value] || value || '—',
  time: value => value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—',
  size: value => { if (!Number.isFinite(Number(value))) return '—'; const n = Number(value); return n < 1024 ? `${n} B` : n < 1048576 ? `${(n / 1024).toFixed(1)} KB` : `${(n / 1048576).toFixed(1)} MB`; },
  shortId: value => value ? `${value.slice(0, 8)}…${value.slice(-4)}` : '—',
};
export function badge(value) { return el('span', { className: `state-badge state-${String(value || 'PENDING').toLowerCase()}` }, format.status(value)); }
export function field(label, control, hint) { return el('label', { className: 'field' }, el('span', { className: 'field-label' }, label), control, hint ? el('small', { className: 'muted' }, hint) : null); }
export function button(text, handler, options = {}) { return el('button', { type: 'button', className: 'button', ...options, onclick: handler }, text); }
export function empty(text = '暂无记录', hint = '有新的业务记录后会显示在这里。') { return el('div', { className: 'empty' }, el('span', { className: 'empty-symbol', 'aria-hidden': 'true' }, '○'), el('strong', {}, text), el('p', {}, hint)); }
export function pageBar(state, callback) {
  const totalPages = Math.max(1, Math.ceil(state.total / state.pageSize));
  return el('div', { className: 'pagination' }, el('span', { className: 'muted' }, `共 ${state.total} 条 · 第 ${state.page} / ${totalPages} 页`), el('div', { className: 'toolbar' }, button('上一页', () => callback(state.page - 1), { className: 'button secondary', disabled: state.page <= 1 }), button('下一页', () => callback(state.page + 1), { className: 'button secondary', disabled: state.page >= totalPages })));
}
export function errorText(error) { return `${error?.message || '操作未能完成，请重试'}${error?.traceId ? `（诊断编号：${error.traceId}）` : ''}`; }
export function dialog(title, ...content) {
  const node = el('dialog', { className: 'dialog', 'aria-label': title });
  node.append(el('div', { className: 'dialog-heading' }, el('h2', {}, title), button('关闭', () => node.close(), { className: 'button quiet', 'aria-label': `关闭${title}` })), el('div', { className: 'dialog-body' }, ...content));
  node.addEventListener('close', () => { node.querySelectorAll('input[type=password]').forEach(input => input.value = ''); node.remove(); }, { once: true });
  document.body.append(node); node.showModal(); return node;
}
export function bindSubmit(form, action, notify) {
  let running = false;
  form.addEventListener('submit', async event => {
    event.preventDefault(); if (running || !form.reportValidity()) return;
    running = true; const submitters = [...form.querySelectorAll('[type=submit]')]; submitters.forEach(node => node.disabled = true);
    try { await action(); } catch (error) { if (error?.name !== 'AbortError') notify(errorText(error), 'error'); }
    finally { running = false; submitters.forEach(node => node.disabled = false); }
  });
}
export const hasRole = (actor, ...roles) => roles.some(role => actor.roles.includes(role));
export const organizationName = id => id === '00000000-0000-4000-8000-000000000001' ? '委托机构' : '运营机构';
