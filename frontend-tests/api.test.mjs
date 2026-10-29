import test from 'node:test';
import assert from 'node:assert/strict';
import { ApiError, createApi } from '../backend/src/main/resources/static/ui/api.js';

const PRIVATE = 'PRIVATE_PROVIDER_PASSWORD_OR_RESPONSE';
const CURRENT_TOKEN = 'CURRENT_IN_MEMORY_BEARER';
function response(data = null, { status = 200, code = 'OK', traceId = 'api-contract-trace', message = 'safe' } = {}) {
  return new Response(JSON.stringify({ code, message, data, traceId }), { status, headers: { 'Content-Type': 'application/json', 'X-Trace-Id': traceId } });
}
function deferred() { let resolve, reject; const promise = new Promise((done, fail) => { resolve = done; reject = fail; }); return { promise, resolve, reject }; }

// File verification's STORED mode is a bodyless POST. A JSON Content-Type here changes the endpoint contract.
test('STORED verification sends no request body or Content-Type and omits ambient cookies', async () => {
  let seen;
  const api = createApi({ token: CURRENT_TOKEN, fetchImpl: async (url, options) => { seen = { url, ...options }; return response({ result: 'MATCH' }); } });
  const value = await api.request('/evidence-versions/version-id/verifications?source=STORED', { method: 'POST' });
  assert.equal(value.result, 'MATCH');
  assert.equal(seen.url, '/api/v1/evidence-versions/version-id/verifications?source=STORED');
  assert.equal(seen.method, 'POST'); assert.equal(seen.body, undefined); assert.equal(seen.headers.has('Content-Type'), false);
  assert.equal(seen.credentials, 'omit'); assert.equal(seen.redirect, 'error'); assert.equal(seen.cache, 'no-store');
  assert.equal(seen.headers.get('Authorization'), `Bearer ${CURRENT_TOKEN}`);
  assert.match(seen.headers.get('X-Trace-Id'), /^[0-9a-f-]{36}$/);
});

test('raw upload keeps Blob bytes intact and declares octet-stream instead of serializing JSON', async () => {
  const bytes = new Blob([new Uint8Array([0, 255, 7, 8])]); let seen;
  const api = createApi({ fetchImpl: async (url, options) => { seen = options; return response({ partNo: 1 }); } });
  await api.request('/upload-sessions/session-id/parts/1', { method: 'PUT', raw: true, body: bytes });
  assert.equal(seen.body, bytes); assert.equal(seen.headers.get('Content-Type'), 'application/octet-stream');
  assert.deepEqual(new Uint8Array(await seen.body.arrayBuffer()), new Uint8Array([0, 255, 7, 8]));
  assert.equal(seen.headers.has('Authorization'), false);
});

test('COPY multipart retains FormData and lets the browser generate its boundary', async () => {
  const form = new FormData(); form.append('file', new Blob(['copy bytes'], { type: 'image/png' }), 'copy.png'); let seen;
  const api = createApi({ token: CURRENT_TOKEN, fetchImpl: async (url, options) => { seen = options; return response({ result: 'MISMATCH' }); } });
  await api.request('/evidence-versions/version-id/verifications?source=COPY', { method: 'POST', body: form });
  assert.equal(seen.body, form); assert.equal(seen.headers.has('Content-Type'), false);
  assert.equal(seen.headers.get('Authorization'), `Bearer ${CURRENT_TOKEN}`);
});

test('Bearer stays bound to its client lifetime and API never reads or writes browser storage', async () => {
  const originals = new Map(); let storageAccesses = 0;
  for (const name of ['localStorage', 'sessionStorage']) {
    originals.set(name, Object.getOwnPropertyDescriptor(globalThis, name));
    Object.defineProperty(globalThis, name, { configurable: true, get() { storageAccesses += 1; throw new Error('Storage must not hold credentials'); } });
  }
  try {
    const tokens = [];
    const fetchImpl = async (url, options) => { tokens.push(options.headers.get('Authorization')); return response({}); };
    const first = createApi({ token: 'FIRST_BEARER', fetchImpl });
    const second = createApi({ token: 'SECOND_BEARER', fetchImpl });
    await first.request('/users/me', { headers: { Authorization: 'CALLER_SUPPLIED_OTHER_BEARER' } });
    await second.request('/users/me');
    assert.deepEqual(tokens, ['Bearer FIRST_BEARER', 'Bearer SECOND_BEARER']); assert.equal(storageAccesses, 0);
    first.dispose(); second.dispose();
  } finally {
    for (const [name, original] of originals) {
      if (original) Object.defineProperty(globalThis, name, original); else delete globalThis[name];
    }
  }
});

test('a current authenticated 401 notifies its client once and never displays provider message text', async () => {
  let notifications = 0;
  const api = createApi({ token: CURRENT_TOKEN, onUnauthorized: () => { notifications += 1; }, fetchImpl: async () => response(null, { status: 401, code: 'UNAUTHORIZED', message: PRIVATE }) });
  await assert.rejects(api.request('/users/me'), error => {
    assert.ok(error instanceof ApiError); assert.equal(error.status, 401); assert.equal(error.code, 'UNAUTHORIZED');
    assert.equal(error.traceId, 'api-contract-trace'); assert.equal(error.message.includes(PRIVATE), false); return true;
  });
  assert.equal(notifications, 1);
});

test('disposed old client ignores late 401 even when fetch ignores abort and a new client exists', async () => {
  const pending = deferred(); let oldNotifications = 0, newNotifications = 0, oldSignal;
  const old = createApi({ token: 'OLD_BEARER', onUnauthorized: () => { oldNotifications += 1; }, fetchImpl: async (url, options) => { oldSignal = options.signal; return pending.promise; } });
  const request = old.request('/users/me'); old.dispose();
  const fresh = createApi({ token: 'NEW_BEARER', onUnauthorized: () => { newNotifications += 1; }, fetchImpl: async () => response({ username: 'new-session' }) });
  assert.equal((await fresh.request('/users/me')).username, 'new-session');
  pending.resolve(response(null, { status: 401, code: 'UNAUTHORIZED' }));
  await assert.rejects(request, error => error.name === 'AbortError');
  assert.equal(oldSignal.aborted, true); assert.equal(oldNotifications, 0); assert.equal(newNotifications, 0);
});

test('disposing while body decoding prevents an admitted old success from leaking into the new view', async () => {
  const body = deferred(), parsingStarted = deferred();
  const api = createApi({ token: CURRENT_TOKEN, fetchImpl: async () => ({ status: 200, ok: true, headers: new Headers(), async json() { parsingStarted.resolve(); return body.promise; } }) });
  const request = api.request('/tasks'); await parsingStarted.promise; api.dispose();
  body.resolve({ code: 'OK', data: { taskId: 'OLD_PRIVATE_TASK' }, traceId: 'old-trace' });
  await assert.rejects(request, error => error.name === 'AbortError');
});

test('body parsing rejected after disposal remains cancellation instead of an internal protocol error', async () => {
  const body = deferred(), parsingStarted = deferred(); let notifications = 0;
  const api = createApi({ token: CURRENT_TOKEN, onUnauthorized: () => { notifications += 1; }, fetchImpl: async () => ({
    status: 401, ok: false, headers: new Headers({ 'X-Trace-Id': 'old-body-trace' }),
    async json() { parsingStarted.resolve(); return body.promise; }
  }) });
  const request = api.request('/users/me'); await parsingStarted.promise; api.dispose(); body.reject(new SyntaxError(PRIVATE));
  await assert.rejects(request, error => error.name === 'AbortError' && !(error instanceof ApiError));
  assert.equal(notifications, 0);
});

test('a caller abort cancels only that request and leaves the current client usable', async () => {
  const body = deferred(), abort = new AbortController(); let requests = 0, firstSignal;
  const api = createApi({ token: CURRENT_TOKEN, fetchImpl: async (url, options) => {
    requests += 1;
    if (requests === 1) { firstSignal = options.signal; return body.promise; }
    return response({ usable: true });
  } });
  const request = api.request('/tasks', { signal: abort.signal }); abort.abort(); body.resolve(response({ mustNotReturn: true }));
  await assert.rejects(request, error => error.name === 'AbortError'); assert.equal(firstSignal.aborted, true);
  assert.deepEqual(await api.request('/users/me'), { usable: true });
});

test('an already disposed client never starts another network request', async () => {
  let requests = 0;
  const api = createApi({ fetchImpl: async () => { requests += 1; return response({}); } }); api.dispose();
  await assert.rejects(api.request('/users/me'), error => error.name === 'AbortError'); assert.equal(requests, 0);
});

test('network failure is a safe dependency error with no fake HTTP status or provider exception', async () => {
  const api = createApi({ fetchImpl: async () => { throw new TypeError(PRIVATE); } });
  await assert.rejects(api.request('/tasks'), error => {
    assert.ok(error instanceof ApiError); assert.equal(error.status, 0); assert.equal(error.code, 'DEPENDENCY_UNAVAILABLE');
    assert.equal(error.message.includes(PRIVATE), false); assert.equal(error.cause, undefined); return true;
  });
});

test('non-JSON gateway response produces a safe error and retains only request trace metadata', async () => {
  const api = createApi({ fetchImpl: async () => ({ status: 502, ok: false, headers: new Headers({ 'X-Trace-Id': 'gateway-trace' }), async json() { throw new SyntaxError(PRIVATE); } }) });
  await assert.rejects(api.request('/tasks'), error => {
    assert.ok(error instanceof ApiError); assert.equal(error.status, 502); assert.equal(error.code, 'INTERNAL_ERROR');
    assert.equal(error.traceId, 'gateway-trace'); assert.equal(error.message.includes(PRIVATE), false); return true;
  });
});

test('malformed envelopes cannot be returned as successful application data', async () => {
  for (const envelope of [null, { data: {} }, { code: ['OK'], data: {} }, { code: 'OK', message: PRIVATE }]) {
    const api = createApi({ fetchImpl: async () => responseEnvelope(envelope) });
    await assert.rejects(api.request('/tasks'), error => error instanceof ApiError && error.code === 'INTERNAL_ERROR' && !error.message.includes(PRIVATE));
  }
});
function responseEnvelope(envelope) { return new Response(JSON.stringify(envelope), { status: 200, headers: { 'Content-Type': 'application/json' } }); }

test('unknown response error code and arbitrary backend message never become visible error prose', async () => {
  const api = createApi({ fetchImpl: async () => response(null, { status: 500, code: 'UNRECOGNIZED_BACKEND_CODE', message: PRIVATE }) });
  await assert.rejects(api.request('/tasks'), error => error instanceof ApiError && error.message === '请求未能完成，请稍后重试' && !error.message.includes(PRIVATE));
});

test('API-relative path contract prevents sending the current Bearer to an external URL', async () => {
  let calls = 0;
  const api = createApi({ token: CURRENT_TOKEN, fetchImpl: async () => { calls += 1; return response({}); } });
  for (const path of ['https://other.example/collect', '//other.example/collect', 'users/me']) await assert.rejects(api.request(path), TypeError);
  assert.equal(calls, 0);
});
