import test from 'node:test';
import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
import { fileFingerprint, contentType, validateProgress, missingParts, createUploadStore, UploadRunner } from '../backend/src/main/resources/static/ui/upload-core.js';

const ACTOR = '10000000-0000-4000-8000-000000000005';
const OTHER = '10000000-0000-4000-8000-000000000006';
const REQUIREMENT = '60000000-0000-4000-8000-000000000001';
const EXECUTION = '50000000-0000-4000-8000-000000000001';
const UPLOAD = '80000000-0000-4000-8000-000000000001';
const VERSION = '90000000-0000-4000-8000-000000000001';
const FILE = () => new File([new Uint8Array(Array.from({ length: 12 }, (_, i) => i + 1))], 'image.png', { type: 'image/png' });
function memory() { const values = new Map(); return { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value), values }; }
async function hash(bytes) { return Buffer.from(await webcrypto.subtle.digest('SHA-256', bytes)).toString('hex'); }
async function record(file, uploadId = UPLOAD) {
  return { clientRequestId: webcrypto.randomUUID(), uploadId, requirementId: REQUIREMENT, executionId: EXECUTION, filename: file.name, contentType: contentType(file), size: file.size, fingerprint: await fileFingerprint(file, { cryptoProvider: webcrypto }), status: 'UPLOADING' };
}
function backend(file) {
  const parts = new Map();
  const calls = []; let complete = false;
  const server = {
    parts, calls, forcedStatus: null, createdLost: false, putLost: false, corruptReceipt: false, createGate: null,
    progress() { const uploadedParts = Array.from(parts.values()).sort((a, b) => a.partNo - b.partNo); const processingStatus = this.forcedStatus || (complete ? 'COMPLETED' : 'UPLOADING'); return { uploadId: UPLOAD, requirementId: REQUIREMENT, executionId: EXECUTION, filename: file.name, contentType: file.type, size: file.size, partSize: 4, partCount: Math.ceil(file.size / 4), processingStatus, uploadedParts, uploadedSize: uploadedParts.reduce((sum, p) => sum + p.size, 0), expiresAt: '2030-01-01T00:00:00Z', versionId: processingStatus === 'COMPLETED' ? VERSION : null }; },
    async request(path, options = {}) {
      calls.push({ path, ...options });
      if (path === '/upload-sessions' && options.method === 'POST') {
        if (server.createGate) await server.createGate.promise;
        if (server.createdLost) { server.createdLost = false; throw Object.assign(new Error('创建响应丢失'), { status: 503 }); }
        return server.progress();
      }
      if (path.endsWith('/complete')) { complete = true; return server.progress(); }
      if (options.method === 'PUT') {
        assert.equal(options.raw, true); assert.equal(options.headers['Content-Type'], 'application/octet-stream');
        const partNo = Number(path.split('/').pop()); const part = { partNo, size: options.body.size, sha256: await hash(await options.body.arrayBuffer()) }; parts.set(partNo, part);
        if (server.putLost) { server.putLost = false; throw Object.assign(new Error('分片返回丢失'), { status: 503 }); }
        return { uploadId: UPLOAD, ...part, sha256: server.corruptReceipt ? '0'.repeat(64) : part.sha256 };
      }
      return server.progress();
    }
  };
  return server;
}
function deferred() { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; }
async function until(predicate) { for (let i = 0; i < 100; i += 1) { if (predicate()) return; await new Promise(resolve => setTimeout(resolve, 1)); } throw new Error('Condition not reached'); }
function runner(server, store, options = {}) { return new UploadRunner({ api: server, store, cryptoProvider: webcrypto, wait: async () => {}, maxPolls: 2, ...options }); }
async function seed(server, file, number) { const bytes = await file.slice((number - 1) * 4, number * 4).arrayBuffer(); server.parts.set(number, { partNo: number, size: bytes.byteLength, sha256: await hash(bytes) }); }

test('content fingerprint covers middle blocks and reads only bounded slices', async () => {
  const first = new File(['AAAA1111ZZZZ'], 'image.png', { type: 'image/png' });
  const changed = new File(['AAAA2222ZZZZ'], 'image.png', { type: 'image/png' });
  const reads = [];
  const wrapped = { size: first.size, slice(start, end) { reads.push(end - start); return first.slice(start, end); }, arrayBuffer() { throw new Error('Whole-file read must not occur'); } };
  const original = await fileFingerprint(wrapped, { cryptoProvider: webcrypto, blockSize: 4 });
  assert.notEqual(original, await fileFingerprint(changed, { cryptoProvider: webcrypto, blockSize: 4 }));
  assert.deepEqual(reads, [4, 4, 4]);
});

test('fingerprinting can stop before additional file reads', async () => {
  const abort = new AbortController(); let reads = 0;
  const file = FILE(); const wrapped = { size: file.size, slice(a, b) { reads += 1; return file.slice(a, b); } };
  await assert.rejects(fileFingerprint(wrapped, { blockSize: 4, cryptoProvider: webcrypto, signal: abort.signal, onProgress() { abort.abort(); } }), error => error.name === 'AbortError');
  assert.equal(reads, 1);
});

test('part planner uses all received part numbers and rejects inconsistent server statistics', async () => {
  const file = FILE(), server = backend(file); await seed(server, file, 1); await seed(server, file, 3);
  assert.deepEqual(missingParts(server.progress()), [2]);
  assert.throws(() => validateProgress({ ...server.progress(), uploadedSize: 12 }));
  assert.throws(() => validateProgress({ ...server.progress(), uploadedParts: [server.parts.get(1), server.parts.get(1)] }));
});

test('resume metadata is actor-scoped and persists only the allowlisted non-secret fields', async () => {
  const storage = memory(), first = createUploadStore(storage, ACTOR), other = createUploadStore(storage, OTHER);
  first.put({ ...await record(FILE()), accessToken: 'PRIVATE_JWT', password: 'PRIVATE_PASSWORD', bytes: [1, 2] });
  assert.equal(first.list().length, 1); assert.deepEqual(other.list(), []);
  const encoded = storage.getItem(first.key); assert.equal(encoded.includes('PRIVATE_'), false); assert.equal(encoded.includes('accessToken'), false); assert.equal(encoded.includes('bytes'), false);
});

test('a full store refuses to discard uncertain creation identities', async () => {
  const store = createUploadStore(memory(), ACTOR), base = await record(FILE(), null);
  for (let i = 0; i < 20; i += 1) store.put({ ...base, clientRequestId: webcrypto.randomUUID() });
  assert.throws(() => store.put({ ...base, clientRequestId: webcrypto.randomUUID() }), /20/);
  assert.equal(store.list().length, 20);
});

test('known session resumes only its missing part using original server geometry', async () => {
  const file = FILE(), server = backend(file), store = createUploadStore(memory(), ACTOR), saved = store.put(await record(file));
  await seed(server, file, 1); await seed(server, file, 3);
  const result = await runner(server, store).run({ file, record: saved });
  assert.equal(result.versionId, VERSION);
  assert.deepEqual(server.calls.filter(call => call.method === 'PUT').map(call => call.path), [`/upload-sessions/${UPLOAD}/parts/2`]);
  assert.equal(server.calls.some(call => call.path === '/upload-sessions'), false);
  assert.equal(server.calls.filter(call => call.path.endsWith('/complete')).length, 1);
});

test('a changed selected file cannot reuse the saved upload even with identical name and size', async () => {
  const file = FILE(), server = backend(file), store = createUploadStore(memory(), ACTOR), saved = store.put(await record(file));
  const different = new File([new Uint8Array(12)], file.name, { type: file.type });
  await assert.rejects(runner(server, store).run({ file: different, record: saved }), /内容已变化/);
  assert.equal(server.calls.length, 0); assert.equal(store.get(saved.clientRequestId).uploadId, UPLOAD);
});

test('server recorded part content is checked before any missing part is sent', async () => {
  const file = FILE(), server = backend(file), store = createUploadStore(memory(), ACTOR), saved = store.put(await record(file));
  server.parts.set(1, { partNo: 1, size: 4, sha256: '0'.repeat(64) });
  await assert.rejects(runner(server, store).run({ file, record: saved }), /第 1 片/);
  assert.equal(server.calls.filter(call => call.method === 'PUT' || call.method === 'POST').length, 0);
});

test('lost creation response retains the client request ID and retries that identity', async () => {
  const file = FILE(), server = backend(file), store = createUploadStore(memory(), ACTOR); server.createdLost = true;
  const first = runner(server, store); await assert.rejects(first.run({ file, requirementId: REQUIREMENT, executionId: EXECUTION }));
  const saved = store.list()[0]; assert.equal(saved.uploadId, null);
  await runner(server, store).run({ file, record: saved });
  const creates = server.calls.filter(call => call.path === '/upload-sessions');
  assert.equal(creates.length, 2); assert.equal(creates[0].body.clientRequestId, creates[1].body.clientRequestId); assert.equal(store.list().length, 1);
});

test('a committed part with lost response is discovered by GET instead of reuploaded', async () => {
  const file = FILE(), server = backend(file), store = createUploadStore(memory(), ACTOR), saved = store.put(await record(file)); server.putLost = true;
  await assert.rejects(runner(server, store).run({ file, record: saved }));
  await runner(server, store).run({ file, record: store.get(saved.clientRequestId) });
  const numbers = server.calls.filter(call => call.method === 'PUT').map(call => Number(call.path.split('/').pop()));
  assert.deepEqual(numbers, [1, 2, 3]);
});

test('pause fences an ignored-abort late create response before PUT or complete', async () => {
  const file = FILE(), server = backend(file), store = createUploadStore(memory(), ACTOR); server.createGate = deferred();
  const work = runner(server, store); const pending = work.run({ file, requirementId: REQUIREMENT, executionId: EXECUTION });
  await until(() => server.calls.length === 1); work.pause(); server.createGate.resolve();
  await assert.rejects(pending, error => error.name === 'AbortError');
  assert.equal(server.calls.length, 1); assert.equal(store.list().length, 1); assert.equal(store.list()[0].uploadId, null); assert.equal(work.state.phase, 'paused');
});

test('one runner cannot start a second mutation while the first request is unsettled', async () => {
  const file = FILE(), server = backend(file), store = createUploadStore(memory(), ACTOR); server.createGate = deferred();
  const work = runner(server, store), pending = work.run({ file, requirementId: REQUIREMENT, executionId: EXECUTION });
  await until(() => server.calls.length === 1);
  await assert.rejects(work.run({ file, requirementId: REQUIREMENT, executionId: EXECUTION }), /上一项操作/);
  work.pause(); server.createGate.resolve(); await assert.rejects(pending); assert.equal(server.calls.length, 1);
});

test('incorrect part receipt prevents the completion request', async () => {
  const file = FILE(), server = backend(file), store = createUploadStore(memory(), ACTOR), saved = store.put(await record(file)); server.corruptReceipt = true;
  await assert.rejects(runner(server, store).run({ file, record: saved }), /分片回执/);
  assert.equal(server.calls.filter(call => call.path.endsWith('/complete')).length, 0);
});

test('read-only status watch handles background verification without selecting file bytes', async () => {
  const file = FILE(), server = backend(file), store = createUploadStore(memory(), ACTOR), saved = store.put(await record(file)); server.forcedStatus = 'VERIFYING';
  const work = runner(server, store); const progress = await work.watch(saved);
  assert.equal(progress.processingStatus, 'VERIFYING'); assert.equal(work.state.phase, 'waiting'); assert.equal(work.state.busy, false);
  assert.equal(server.calls.filter(call => call.method).length, 0); assert.equal(server.calls.length, 3);
});

test('completed status can be opened after reload without creating another upload', async () => {
  const file = FILE(), server = backend(file), store = createUploadStore(memory(), ACTOR), saved = store.put(await record(file)); server.forcedStatus = 'COMPLETED';
  const work = runner(server, store), result = await work.watch(saved);
  assert.equal(result.versionId, VERSION); assert.equal(work.state.phase, 'completed'); assert.equal(server.calls.length, 1);
});

test('invalid file names and conflicting MIME are rejected before any request', () => {
  assert.throws(() => contentType(new File(['x'], '../image.png', { type: 'image/png' })));
  assert.throws(() => contentType(new File(['x'], 'image.png', { type: 'image/jpeg' })));
  assert.equal(contentType(new File(['x'], 'report.pdf')), 'application/pdf');
});
