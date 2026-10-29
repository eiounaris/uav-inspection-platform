import { el, field, button, badge, format, empty, pageBar, dialog, bindSubmit, errorText } from './common.js';

const OPERATOR = '00000000-0000-4000-8000-000000000002';

/** Device administration keeps one-time device credentials in the open dialog only. */
export function createDevicesPanel({ api, actor, notify = () => {} }) {
  const lifetime = new AbortController();
  const canManage = actor.orgId === OPERATOR && actor.roles.includes('OPERATOR_TASK_ADMIN');
  let page = 1, sequence = 0, disposed = false;
  const openCredentials = new Set();
  const error = el('p', { className: 'inline-error', role: 'alert', hidden: true });
  const body = el('div');
  const pagination = el('div');
  const code = el('input', { required: true, maxlength: 64, pattern: '[A-Za-z0-9][A-Za-z0-9_\\-]{0,63}',
    autocomplete: 'off', placeholder: '例如：UAV-NORTH-01', testId: 'device-code' });
  const submit = el('button', { type: 'submit', className: 'button', testId: 'device-create' }, '登记设备');
  const createForm = el('form', { className: 'toolbar device-create-form' },
    field('设备编号', code, '支持字母、数字、下划线和短横线，最长 64 字符。'), submit);
  const element = el('section', { className: 'devices-workspace' },
    el('div', { className: 'page-heading' }, el('div', {}, el('p', { className: 'eyebrow' }, '执行设备'),
      el('h1', {}, '设备管理'), el('p', { className: 'muted' }, '登记巡检设备，查看在线状态并控制使用权限。')),
    button('刷新设备', () => load(), { className: 'button secondary', testId: 'device-refresh' })),
    error, canManage ? el('section', { className: 'panel' }, el('h2', {}, '登记新设备'), createForm) : null,
    el('section', { className: 'panel' }, el('h2', {}, '设备列表'), body, pagination));
  const request = (path, options = {}) => api.request(path, { ...options, signal: lifetime.signal });

  function showError(problem) {
    if (disposed || problem?.name === 'AbortError') return;
    error.textContent = errorText(problem); error.hidden = false;
    notify(errorText(problem), 'error');
  }
  function clearError() { error.hidden = true; error.textContent = ''; }

  bindSubmit(createForm, async () => {
    clearError();
    try {
      const deviceCode = code.value.trim();
      if (!/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(deviceCode)) throw new Error('请填写有效的设备编号。');
      const created = await request('/drones', { method: 'POST', body: { code: deviceCode } });
      if (disposed) { created.deviceToken = ''; return; }
      showCredential(created);
      code.value = ''; page = 1; await load(); notify('设备已登记，请保存本次设备凭据。', 'success');
    } catch (problem) { showError(problem); }
  }, notify);

  function showCredential(created) {
    const input = el('input', { type: 'password', readonly: true, value: created.deviceToken,
      autocomplete: 'off', spellcheck: 'false', testId: 'device-token', 'aria-label': '一次性设备凭据' });
    // The response object is not retained as a second copy of the credential.
    created.deviceToken = '';
    const reveal = button('显示凭据', () => {
      input.type = input.type === 'password' ? 'text' : 'password';
      reveal.textContent = input.type === 'password' ? '显示凭据' : '隐藏凭据';
    }, { className: 'button secondary', testId: 'device-token-reveal' });
    const copy = button('复制凭据', async () => {
      if (!input.value) return;
      try {
        if (!navigator.clipboard?.writeText) throw new Error('clipboard_unavailable');
        await navigator.clipboard.writeText(input.value);
        if (!disposed && input.isConnected) notify('设备凭据已复制。', 'success');
      } catch { if (!disposed) notify('无法直接复制，请显示凭据后手动复制并妥善保存。', 'error'); }
    }, { className: 'button secondary', testId: 'device-token-copy' });
    const modal = dialog('设备凭据', el('p', {}, `设备 ${created.device.code} 已登记。`),
      el('p', { className: 'muted' }, '此凭据仅在登记时提供，请保存后用于设备连接。关闭后不会在页面保留。'),
      field('一次性设备凭据', input), el('div', { className: 'form-actions' }, reveal, copy));
    modal.dataset.testid = 'device-credentials-dialog';
    openCredentials.add(modal);
    modal.addEventListener('close', () => {
      input.value = ''; input.type = 'password'; openCredentials.delete(modal);
    }, { once: true });
  }

  async function setEnabled(device, trigger) {
    if (trigger.disabled) return;
    clearError(); trigger.disabled = true;
    try {
      await request(`/drones/${device.id}/enabled`, { method: 'PATCH', body: {
        enabled: !device.enabled, expectedLockVersion: device.lockVersion,
      } });
      if (disposed) return;
      notify(device.enabled ? '设备已停用。' : '设备已启用。', 'success'); await load();
    } catch (problem) {
      if (problem?.status === 409) {
        await load();
        if (!disposed) showError({ ...problem, message: '设备状态已更新，请核对最新状态后重新操作。' });
      } else showError(problem);
    } finally { if (!disposed && trigger.isConnected) trigger.disabled = false; }
  }

  async function load() {
    if (disposed) return;
    if (!canManage) {
      body.replaceChildren(empty('当前账号无设备管理权限', '请使用运营方任务管理员账号登记或维护设备。'));
      pagination.replaceChildren(); return;
    }
    const current = ++sequence;
    clearError(); body.setAttribute('aria-busy', 'true');
    if (!body.childElementCount) body.replaceChildren(empty('正在加载设备', '正在读取设备状态。'));
    try {
      const result = await request(`/drones?page=${page}&pageSize=12`);
      if (disposed || current !== sequence) return;
      if (!result.total && page !== 1) { page = 1; return load(); }
      if (page > 1 && !result.items.length) { page--; return load(); }
      const rows = result.items.map(device => {
        let toggle;
        toggle = button(device.enabled ? '停用' : '启用', () => setEnabled(device, toggle), {
          className: 'button secondary', testId: 'device-toggle', 'aria-label': `${device.enabled ? '停用' : '启用'}设备 ${device.code}`,
        });
        return el('tr', { testId: 'device-row', dataset: { deviceId: device.id } },
          el('td', {}, el('strong', {}, device.code), el('small', { className: 'muted mono' }, device.id)),
          el('td', {}, badge(device.enabled ? 'ENABLED' : 'DISABLED')),
          el('td', {}, badge(device.online ? 'ONLINE' : 'OFFLINE')),
          el('td', {}, format.time(device.lastSeenAt)), el('td', {}, toggle));
      });
      body.replaceChildren(rows.length ? el('div', { className: 'table-wrap' }, el('table', { className: 'table' },
        el('thead', {}, el('tr', {}, ['设备', '使用状态', '连接状态', '最后上报', '操作'].map(text => el('th', { scope: 'col' }, text)))),
        el('tbody', {}, rows))) : empty('暂无设备', '登记第一台设备后，设备状态会显示在这里。'));
      pagination.replaceChildren(pageBar({ page, pageSize: 12, total: result.total }, value => { page = value; load(); }));
    } catch (problem) {
      if (current === sequence && !disposed) {
        body.replaceChildren(empty('设备未能加载', '请检查连接并稍后刷新。')); pagination.replaceChildren(); showError(problem);
      }
    } finally { if (current === sequence) body.removeAttribute('aria-busy'); }
  }

  function destroy() {
    disposed = true; lifetime.abort();
    for (const modal of openCredentials) { modal.querySelectorAll('input').forEach(input => input.value = ''); modal.close(); }
    openCredentials.clear(); code.value = ''; element.replaceChildren();
  }
  return { element, load, destroy };
}
