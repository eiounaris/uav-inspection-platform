const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const HASH = /^[0-9a-f]{64}$/;
const TYPES = { jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', pdf: 'application/pdf', mp4: 'video/mp4' };
const STATUSES = new Set(['UPLOADING', 'VERIFYING', 'COMPLETED', 'FAILED', 'EXPIRED']);
const ENCODER = new TextEncoder();

export class UploadProtocolError extends Error {
  constructor(message) { super(message); this.name = 'UploadProtocolError'; }
}

export function aborted(signal) {
  if (signal?.aborted) throw new DOMException('上传已暂停', 'AbortError');
}

export function contentType(file) {
  const extension = String(file?.name || '').split('.').pop().toLowerCase();
  const type = TYPES[extension];
  if (!type || !file?.name || file.name.length > 255 || /[\\/\u0000-\u001f\u007f]/.test(file.name)) {
    throw new UploadProtocolError('请选择 JPG、PNG、PDF 或 MP4 文件，文件名不能包含路径或控制字符。');
  }
  if (!Number.isSafeInteger(file.size) || file.size <= 0) throw new UploadProtocolError('请选择非空文件。');
  if (file.type && file.type !== type && file.type !== 'application/octet-stream') {
    throw new UploadProtocolError('文件扩展名与文件类型不一致，请检查原文件。');
  }
  return type;
}

async function digest(bytes, cryptoProvider = globalThis.crypto) {
  if (!cryptoProvider?.subtle) throw new UploadProtocolError('当前浏览器无法计算文件指纹，请使用 localhost 或 HTTPS 打开平台。');
  const value = new Uint8Array(await cryptoProvider.subtle.digest('SHA-256', bytes));
  return { bytes: value, hex: Array.from(value, byte => byte.toString(16).padStart(2, '0')).join('') };
}

/** A domain-separated hash of every fixed-size block digest, not the file's SHA-256. */
export async function fileFingerprint(file, { signal, cryptoProvider = globalThis.crypto, blockSize = 4 * 1024 * 1024, onProgress = () => {} } = {}) {
  if (!Number.isSafeInteger(blockSize) || blockSize < 1 || !Number.isSafeInteger(file?.size) || file.size <= 0) {
    throw new UploadProtocolError('文件指纹参数不正确。');
  }
  const count = Math.ceil(file.size / blockSize);
  const header = ENCODER.encode(`inspection-upload-fingerprint-v1\n${file.size}\n${blockSize}\n${count}\n`);
  const composed = new Uint8Array(header.length + count * 32);
  composed.set(header);
  for (let index = 0; index < count; index += 1) {
    aborted(signal);
    const bytes = await file.slice(index * blockSize, Math.min(file.size, (index + 1) * blockSize)).arrayBuffer();
    aborted(signal);
    const hashed = await digest(bytes, cryptoProvider);
    aborted(signal);
    composed.set(hashed.bytes, header.length + index * 32);
    onProgress(Math.min(file.size, (index + 1) * blockSize), file.size);
  }
  const result = await digest(composed, cryptoProvider);
  aborted(signal);
  return result.hex;
}

export function validateProgress(progress, record) {
  if (!progress || !UUID.test(progress.uploadId || '') || !STATUSES.has(progress.processingStatus)
      || !Number.isSafeInteger(progress.size) || progress.size <= 0
      || !Number.isSafeInteger(progress.partSize) || progress.partSize <= 0
      || !Number.isSafeInteger(progress.partCount) || progress.partCount < 1
      || progress.partCount !== Math.ceil(progress.size / progress.partSize)
      || !Array.isArray(progress.uploadedParts)) throw new UploadProtocolError('服务器返回的上传进度不完整，请重新查询。');
  if (record && (progress.requirementId !== record.requirementId || progress.executionId !== record.executionId
      || progress.filename !== record.filename || progress.contentType !== record.contentType || progress.size !== record.size
      || record.uploadId && record.uploadId !== progress.uploadId)) {
    throw new UploadProtocolError('服务器会话与当前文件或任务范围不一致，已停止续传。');
  }
  const received = new Set();
  let total = 0;
  for (const part of progress.uploadedParts) {
    if (!Number.isSafeInteger(part.partNo) || part.partNo < 1 || part.partNo > progress.partCount || received.has(part.partNo)
        || part.size !== Math.min(progress.partSize, progress.size - (part.partNo - 1) * progress.partSize) || !HASH.test(part.sha256 || '')) {
      throw new UploadProtocolError('服务器返回的已收分片不一致，已停止续传。');
    }
    received.add(part.partNo); total += part.size;
  }
  if (progress.uploadedSize !== total || progress.processingStatus === 'COMPLETED' && !UUID.test(progress.versionId || '')) {
    throw new UploadProtocolError('服务器返回的上传统计不一致，请重新查询。');
  }
  return progress;
}

export function missingParts(progress) {
  validateProgress(progress);
  const received = new Set(progress.uploadedParts.map(part => part.partNo));
  return Array.from({ length: progress.partCount }, (_, index) => index + 1).filter(number => !received.has(number));
}

function cleanRecord(record) {
  if (!record || !UUID.test(record.clientRequestId || '') || !UUID.test(record.requirementId || '') || !UUID.test(record.executionId || '')
      || !HASH.test(record.fingerprint || '') || !Number.isSafeInteger(record.size) || record.size <= 0
      || typeof record.filename !== 'string' || !record.filename || record.filename.length > 255
      || !Object.values(TYPES).includes(record.contentType)
      || record.uploadId != null && !UUID.test(record.uploadId)
      || record.versionId != null && !UUID.test(record.versionId)) throw new UploadProtocolError('本机续传记录不完整，不能安全复用。');
  return {
    clientRequestId: record.clientRequestId, uploadId: record.uploadId || null,
    requirementId: record.requirementId, executionId: record.executionId,
    filename: record.filename, contentType: record.contentType, size: record.size, fingerprint: record.fingerprint,
    status: STATUSES.has(record.status) ? record.status : 'UPLOADING', versionId: record.versionId || null,
    updatedAt: typeof record.updatedAt === 'string' ? record.updatedAt : new Date().toISOString()
  };
}

export function createUploadStore(storage, actorId) {
  if (!UUID.test(actorId || '')) throw new UploadProtocolError('当前账号信息不完整，请重新登录。');
  const key = `inspection.upload.v1:${actorId}`;
  function list() {
    let raw;
    try { raw = storage.getItem(key); } catch { throw new UploadProtocolError('浏览器暂存不可用，请允许本站会话存储后再上传。'); }
    if (raw == null) return [];
    try {
      const data = JSON.parse(raw);
      if (data.version !== 1 || data.actorId !== actorId || !Array.isArray(data.sessions) || data.sessions.length > 20) throw new Error();
      return data.sessions.map(cleanRecord);
    } catch { throw new UploadProtocolError('本机续传记录无法读取，请保留页面并联系维护人员检查，避免重复创建会话。'); }
  }
  function save(records) {
    try { storage.setItem(key, JSON.stringify({ version: 1, actorId, sessions: records.map(cleanRecord) })); }
    catch (error) { if (error instanceof UploadProtocolError) throw error; throw new UploadProtocolError('续传信息保存失败，已停止新建上传；请检查浏览器存储空间。'); }
  }
  return {
    key, list,
    get(id) { return list().find(record => record.clientRequestId === id) || null; },
    put(value) {
      const record = cleanRecord(value); const records = list();
      const index = records.findIndex(item => item.clientRequestId === record.clientRequestId);
      if (index < 0) {
        if (records.length >= 20) throw new UploadProtocolError('本机已有 20 份续传记录，请先移除已结束的记录。');
        records.unshift(record);
      } else records[index] = record;
      save(records); return record;
    },
    remove(id) { save(list().filter(record => record.clientRequestId !== id)); }
  };
}

export function pauseDelay(milliseconds, signal) {
  aborted(signal);
  return new Promise((resolve, reject) => {
    const done = () => { signal?.removeEventListener('abort', cancel); resolve(); };
    const timer = setTimeout(done, milliseconds);
    const cancel = () => { clearTimeout(timer); signal?.removeEventListener('abort', cancel); reject(new DOMException('上传已暂停', 'AbortError')); };
    signal?.addEventListener('abort', cancel, { once: true });
  });
}

/** Explicit upload protocol; every uncertain request retains its durable client request identity. */
export class UploadRunner {
  constructor({ api, store, onChange = () => {}, cryptoProvider = globalThis.crypto, wait = pauseDelay, maxPolls = 30 }) {
    this.api = api; this.store = store; this.onChange = onChange; this.crypto = cryptoProvider; this.wait = wait; this.maxPolls = maxPolls;
    this.sequence = 0; this.controller = null; this.destroyed = false;
    this.state = { busy: false, phase: 'idle', record: null, progress: null, error: null, fingerprintBytes: 0, message: '' };
  }
  _emit(patch) {
    this.state = { ...this.state, ...patch };
    if (!this.destroyed) this.onChange(this.state);
  }
  _check(operation) { aborted(operation.signal); if (this.destroyed || this.sequence !== operation.sequence) throw new DOMException('上传已暂停', 'AbortError'); }
  _begin() {
    if (this.destroyed) throw new UploadProtocolError('上传面板已关闭。');
    if (this.state.busy) throw new UploadProtocolError('上一项操作尚未结束，请稍候。');
    this.controller = new AbortController();
    const operation = { signal: this.controller.signal, sequence: ++this.sequence };
    this._emit({ busy: true, error: null, message: '' }); return operation;
  }
  pause() { this.controller?.abort(); }
  destroy() { this.destroyed = true; this.pause(); }
  _remember(record, progress) {
    const saved = this.store.put({ ...record, uploadId: progress.uploadId, status: progress.processingStatus, versionId: progress.versionId, updatedAt: new Date().toISOString() });
    this._emit({ record: saved, progress }); return saved;
  }
  async _query(record, operation) {
    const progress = await this.api.request(`/upload-sessions/${record.uploadId}`, { signal: operation.signal });
    this._check(operation); validateProgress(progress, record); return this._remember(record, progress);
  }
  async _finish(operation, action) {
    try { return await action(); }
    catch (error) {
      if (operation.signal.aborted || error.name === 'AbortError') this._emit({ phase: 'paused', error: null, message: '已暂停；再次继续时会先查询服务器，再补传缺片。' });
      else this._emit({ phase: 'error', error, message: error.message || '操作未完成，可保留会话稍后继续。' });
      throw error;
    } finally { if (this.sequence === operation.sequence) { this.controller = null; this._emit({ busy: false }); } }
  }
  async watch(record) {
    const operation = this._begin();
    return this._finish(operation, async () => {
      record = cleanRecord(record);
      if (!record.uploadId) throw new UploadProtocolError('请重新选择原文件并继续，以找回未返回结果的上传会话。');
      await this._query(record, operation);
      return this._poll(operation);
    });
  }
  async run({ file, requirementId, executionId, record = null }) {
    const operation = this._begin();
    return this._finish(operation, async () => {
      const type = contentType(file);
      if (record) {
        record = cleanRecord(record);
        if (record.filename !== file.name || record.contentType !== type || record.size !== file.size) {
          throw new UploadProtocolError('所选文件与原会话的名称、类型或大小不同，请重新选择原文件。');
        }
        requirementId = record.requirementId; executionId = record.executionId;
      }
      if (!UUID.test(requirementId || '') || !UUID.test(executionId || '')) throw new UploadProtocolError('请先选择资料项和执行批次。');
      this._emit({ phase: 'fingerprint', record, progress: null, fingerprintBytes: 0, message: '正在检查完整文件内容…' });
      const fingerprint = await fileFingerprint(file, { signal: operation.signal, cryptoProvider: this.crypto, onProgress: value => this._emit({ fingerprintBytes: value }) });
      this._check(operation);
      if (record && fingerprint !== record.fingerprint) throw new UploadProtocolError('所选文件内容已变化，不能复用原上传会话。');
      if (!record) {
        record = this.store.put({ clientRequestId: this.crypto.randomUUID(), uploadId: null, requirementId, executionId, filename: file.name, contentType: type, size: file.size, fingerprint });
        this._emit({ record });
      }
      let progress;
      this._emit({ phase: 'connecting', message: record.uploadId ? '正在查询服务器已收分片…' : '正在创建上传会话…' });
      if (record.uploadId) progress = await this.api.request(`/upload-sessions/${record.uploadId}`, { signal: operation.signal });
      else progress = await this.api.request('/upload-sessions', { method: 'POST', signal: operation.signal, body: {
        clientRequestId: record.clientRequestId, requirementId, executionId, filename: record.filename, contentType: type, size: file.size
      } });
      this._check(operation); validateProgress(progress, record); record = this._remember(record, progress);
      if (progress.processingStatus !== 'UPLOADING') return this._poll(operation);
      this._emit({ phase: 'checking', message: '正在核对服务器已有分片…' });
      for (const part of progress.uploadedParts) {
        const start = (part.partNo - 1) * progress.partSize;
        const hashed = await digest(await file.slice(start, start + part.size).arrayBuffer(), this.crypto);
        this._check(operation);
        if (hashed.hex !== part.sha256) throw new UploadProtocolError(`第 ${part.partNo} 片与原文件不一致，已停止续传。`);
      }
      for (const number of missingParts(progress)) {
        this._check(operation);
        if (progress.uploadedParts.some(part => part.partNo === number)) continue;
        const start = (number - 1) * progress.partSize;
        const chunk = file.slice(start, Math.min(file.size, start + progress.partSize));
        const hashed = await digest(await chunk.arrayBuffer(), this.crypto); this._check(operation);
        this._emit({ phase: 'uploading', message: `正在上传第 ${number} / ${progress.partCount} 片…` });
        const result = await this.api.request(`/upload-sessions/${record.uploadId}/parts/${number}`, {
          method: 'PUT', body: chunk, raw: true, headers: { 'Content-Type': 'application/octet-stream' }, signal: operation.signal
        });
        this._check(operation);
        if (result.uploadId !== record.uploadId || result.partNo !== number || result.size !== chunk.size || result.sha256 !== hashed.hex) {
          throw new UploadProtocolError('分片回执与本地文件不一致，请查询会话后再继续。');
        }
        record = await this._query(record, operation); progress = this.state.progress;
        if (progress.processingStatus !== 'UPLOADING') return this._poll(operation);
      }
      this._check(operation);
      this._emit({ phase: 'completing', message: '分片已齐，正在提交后台定版…' });
      progress = await this.api.request(`/upload-sessions/${record.uploadId}/complete`, { method: 'POST', signal: operation.signal });
      this._check(operation); validateProgress(progress, record); this._remember(record, progress);
      return this._poll(operation);
    });
  }
  async _poll(operation) {
    for (let turn = 0; ; turn += 1) {
      this._check(operation); const progress = this.state.progress;
      if (progress.processingStatus === 'COMPLETED') { this._emit({ phase: 'completed', message: '文件已定版。存证结果请在版本详情查看。' }); return progress; }
      if (progress.processingStatus === 'FAILED' || progress.processingStatus === 'EXPIRED') {
        this._emit({ phase: 'terminal', message: progress.processingStatus === 'EXPIRED' ? '会话已过期，不能继续写入。请显式开始新的上传。' : '后台处理失败。请查看任务及资料要求后显式开始新的上传。' }); return progress;
      }
      if (progress.processingStatus === 'UPLOADING') { this._emit({ phase: 'paused', message: '会话仍可上传，请重新选择原文件并继续。' }); return progress; }
      if (turn >= this.maxPolls) { this._emit({ phase: 'waiting', message: '后台仍在处理，稍后可查询状态；无需重新上传。' }); return progress; }
      this._emit({ phase: 'waiting', message: '后台正在校验并定版，正在查询状态…' });
      await this.wait(2000, operation.signal); this._check(operation);
      await this._query(this.state.record, operation);
    }
  }
}
