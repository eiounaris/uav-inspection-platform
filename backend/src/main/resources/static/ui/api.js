const messages = { UNAUTHORIZED: '登录已失效，请重新登录', FORBIDDEN: '当前账号无权访问此资料或执行此操作', INVALID_ARGUMENT: '请检查填写内容后重试', VERSION_CONFLICT: '内容已发生变化，请刷新后确认再重试', IDEMPOTENCY_CONFLICT: '此请求编号已用于其他内容', PART_CONTENT_CONFLICT: '已接收分片与当前文件不一致，请重新选择原文件', UPLOAD_SESSION_EXPIRED: '上传会话已过期', UPLOAD_NOT_WRITABLE: '此上传当前不能继续写入', UPLOAD_PARTS_INCOMPLETE: '分片尚未完整接收', TASK_COMPLETED: '任务已完成，不能新增或修改巡检成果', FILE_TOO_LARGE: '文件超过服务允许的大小', UNSUPPORTED_MEDIA_TYPE: '文件类型不受支持', DEPENDENCY_UNAVAILABLE: '依赖服务暂不可用，已保留当前操作信息，请稍后重试', RESOURCE_NOT_FOUND: '记录不存在', INTERNAL_ERROR: '服务暂时无法完成操作' };
export class ApiError extends Error {
  constructor(status, code, traceId) { super(messages[code] || '请求未能完成，请稍后重试'); this.name = 'ApiError'; this.status = status; this.code = code; this.traceId = traceId; }
}
export function createApi({ token, onUnauthorized = () => {}, fetchImpl = globalThis.fetch } = {}) {
  const lifetime = new AbortController();
  return {
    dispose() { lifetime.abort(); },
    async request(path, options = {}) {
      if (!path.startsWith('/') || path.startsWith('//') || lifetime.signal.aborted) throw lifetime.signal.aborted ? new DOMException('Request cancelled', 'AbortError') : new TypeError('Expected an API-relative path');
      const headers = new Headers(options.headers || {}); headers.set('Accept', 'application/json'); headers.set('X-Trace-Id', crypto.randomUUID());
      if (token) headers.set('Authorization', `Bearer ${token}`);
      let body;
      if (options.body !== undefined && options.body !== null) {
        if (options.raw || options.body instanceof FormData) { body = options.body; if (options.raw && !headers.has('Content-Type') && !(body instanceof FormData)) headers.set('Content-Type', 'application/octet-stream'); }
        else { body = JSON.stringify(options.body); headers.set('Content-Type', 'application/json'); }
      }
      const signal = options.signal ? AbortSignal.any([lifetime.signal, options.signal]) : lifetime.signal;
      let response;
      try { response = await fetchImpl(`/api/v1${path}`, { method: options.method || 'GET', headers, body, signal, credentials: 'omit', cache: 'no-store', redirect: 'error' }); }
      catch (error) { if (error?.name === 'AbortError' || signal.aborted) throw new DOMException('Request cancelled', 'AbortError'); throw new ApiError(0, 'DEPENDENCY_UNAVAILABLE'); }
      if (signal.aborted) throw new DOMException('Request cancelled', 'AbortError');
      let envelope;
      try { envelope = await response.json(); } catch (error) {
        if (signal.aborted || error?.name === 'AbortError') throw new DOMException('Request cancelled', 'AbortError');
        throw new ApiError(response.status, 'INTERNAL_ERROR', response.headers.get('X-Trace-Id'));
      }
      if (signal.aborted) throw new DOMException('Request cancelled', 'AbortError');
      if (!envelope || typeof envelope.code !== 'string' || !Object.hasOwn(envelope, 'data')) throw new ApiError(response.status, 'INTERNAL_ERROR');
      if (!response.ok || envelope.code !== 'OK') { if (response.status === 401) onUnauthorized(); throw new ApiError(response.status, envelope.code, envelope.traceId); }
      return envelope.data;
    },
  };
}
