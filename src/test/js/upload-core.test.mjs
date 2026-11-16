import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { FINGERPRINT_ALGORITHM, LOCAL_STORAGE_KEY, UploadError, fingerprintFile, missingParts,
  readResumeRecords, saveResumeRecord, runUploadRounds } from '../../main/resources/static/upload-core.mjs';

// 本文件使用真实 Node File/WebCrypto 与模拟 DOM、fetch；不代表真实浏览器或真实存储联调。
const html = await readFile(new URL('../../main/resources/static/index.html', import.meta.url), 'utf8');
const appUrl = new URL('../../main/resources/static/upload.js', import.meta.url);
const uploadId = '00000000-0000-4000-8000-000000000001';
let pageNumber = 0;

class FakeElement {
  constructor() {
    Object.assign(this, { listeners: {}, hidden: false, disabled: false, dataset: {}, children: [],
      attrs: {}, textContent: '', classList: { toggle() {} }, nextElementSibling: { textContent: '' } });
  }
  addEventListener(type, callback) { this.listeners[type] = callback; }
  replaceChildren(...children) { this.children = children; this.textContent = children.map(child => child.textContent).join(''); }
  append(...children) { this.children.push(...children); }
  setAttribute(key, value) { this.attrs[key] = value; }
  getAttribute(key) { return this.attrs[key]; }
  removeAttribute(key) { delete this.attrs[key]; }
  scrollIntoView() {}
  load() {}
  pause() {}
  get childElementCount() { return this.children.length; }
}

const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const json = (body, status = 200) => new Response(JSON.stringify(body), { status,
  headers: { 'Content-Type': 'application/json' } });
const sessionValue = uploadedChunks => ({ status: 'UPLOADING', uploadedChunks });

function createHarness() {
  const values = new Map();
  const state = {
    settings: { enabled: true, chunkSize: 4, maxFileSize: 100, maxRounds: 3, concurrency: 2,
      requestTimeoutMs: 1000, retryDelayMs: 0, fingerprintAlgorithm: FINGERPRINT_ALGORITHM },
    values, session: null, assets: [], initializations: 0, completions: 0, partWrites: [], inFlight: 0,
    failPart: () => false, failQuery: false, failComplete: false, loseInitializeResponse: false,
    initializationLost: false, initializing: false, queryCalls: 0, videoListRequests: 0, events: []
  };
  state.storage = { getItem: key => values.get(key), setItem: (key, value) => values.set(key, value) };
  state.fetch = async (path, options = {}) => {
    if (path === '/api/upload-settings') return json(state.settings);
    if (path === '/api/videos') { state.videoListRequests += 1; return json(state.assets); }
    if (path === '/api/uploads' && options.method === 'POST') {
      const input = JSON.parse(options.body);
      const persisted = JSON.parse(values.get(LOCAL_STORAGE_KEY));
      assert.ok(persisted.some(row => row.requestKey === input.requestKey), '初始化之前必须已保存请求键');
      state.initializations += 1;
      if (!state.session) state.session = { ...input, uploadId, chunkSize: state.settings.chunkSize,
        totalChunks: Math.ceil(input.fileSize / state.settings.chunkSize), status: 'UPLOADING',
        expiresAt: '2030-11-16T00:00:00Z', uploadedChunks: [], assetId: null };
      else assert.equal(input.requestKey, state.session.requestKey, '重试初始化必须复用请求键');
      state.initializing = false;
      if (state.loseInitializeResponse && !state.initializationLost) {
        state.initializationLost = true;
        throw new TypeError('模拟响应丢失');
      }
      await delay(1);
      return json(state.session);
    }
    if (path === `/api/uploads/${uploadId}`) {
      state.queryCalls += 1;
      state.events.push('query');
      assert.equal(state.inFlight, 0, '下一轮查询之前所有在途分片请求必须已经结束');
      if (state.failQuery) return json({ code: 'STATE_UNAVAILABLE', message: '模拟进度查询失败' }, 503);
      if (state.initializing) return json({ code: 'INITIALIZING', message: '上传初始化尚未完成，请重试本次初始化' }, 409);
      if (state.session.status === 'EXPIRED') return json({ code: 'SESSION_EXPIRED', message: '上传已过期' }, 410);
      return json(state.session);
    }
    if (path.startsWith(`/api/uploads/${uploadId}/parts/`)) {
      const index = Number(path.split('/').at(-1));
      state.partWrites.push(index);
      state.inFlight += 1;
      state.events.push(`begin-${index}`);
      await delay(index === 1 ? 2 : 4);
      state.inFlight -= 1;
      state.events.push(`end-${index}`);
      const failure = state.failPart(index);
      if (failure) return json({ code: failure.code || 'STORAGE_UNAVAILABLE', message: '模拟分片失败' }, failure.status || 503);
      assert.equal(options.headers['Content-Type'], 'application/octet-stream');
      const bytes = await options.body.arrayBuffer();
      const digest = Buffer.from(await crypto.subtle.digest('SHA-256', bytes)).toString('hex');
      assert.equal(options.headers['X-Chunk-SHA256'], digest, '请求摘要必须对应该片字节');
      if (!state.session.uploadedChunks.includes(index)) state.session.uploadedChunks.push(index);
      return new Response(null, { status: 204 });
    }
    if (path === `/api/uploads/${uploadId}/complete`) {
      state.completions += 1;
      assert.equal(state.session.uploadedChunks.length, state.session.totalChunks);
      state.session.status = 'FINALIZING';
      if (state.failComplete) return json({ code: 'DATABASE_UNAVAILABLE', message: '模拟保存失败' }, 503);
      state.session.status = 'COMPLETED';
      state.session.assetId = 1;
      const asset = { assetId: 1, uploadId, originalName: state.session.originalName,
        fileSize: state.session.fileSize, createdAt: '2026-11-15T00:00:00Z' };
      state.assets = [asset];
      return json(asset);
    }
    throw new Error(`未预期的模拟请求：${options.method || 'GET'} ${path}`);
  };
  state.newPage = async () => {
    const elements = new Map([...html.matchAll(/id="([^"]+)"/g)].map(match => [match[1], new FakeElement()]));
    globalThis.document = { getElementById: id => elements.get(id), createElement: () => new FakeElement(),
      createTextNode: textContent => ({ textContent }) };
    globalThis.localStorage = state.storage;
    globalThis.fetch = state.fetch;
    await import(`${appUrl.href}?nodeSimulation=${++pageNumber}`);
    await delay(5);
    return {
      elements,
      choose: async file => { elements.get('video-file').files = [file]; await elements.get('video-file').listeners.change(); },
      click: name => elements.get(name).listeners.click(),
      notice: () => elements.get('upload-notice')
    };
  };
  return state;
}

test('纯逻辑：逐片摘要绑定内容，且同一内容可以重新匹配', async () => {
  const file = new File(['abcdefghijkl'], 'clip.mp4', { type: 'video/mp4' });
  const same = await fingerprintFile(file, 4);
  assert.deepEqual(same, await fingerprintFile(file, 4));
  assert.equal(same.chunkHashes.length, 3);
  assert.notEqual(same.fileFingerprint, (await fingerprintFile(new File(['abcXefghijkl'], 'clip.mp4'), 4)).fileFingerprint);
});

test('纯逻辑：缺片计算拒绝非法或缺失的服务端进度', () => {
  assert.deepEqual(missingParts(4, [0, 3, 0]), [1, 2]);
  assert.throws(() => missingParts(4, [4]), /无效/);
  assert.throws(() => missingParts(4, undefined), /不完整/);
});

test('纯逻辑：上传定位信息更新同一条记录，存储失败必须报错', async () => {
  const harness = createHarness();
  const identity = await fingerprintFile(new File(['abcd'], 'clip.mp4'), 4);
  const record = { requestKey: crypto.randomUUID(), originalName: 'clip.mp4', fileSize: 4, ...identity };
  saveResumeRecord(harness.storage, record);
  saveResumeRecord(harness.storage, { ...record, uploadId });
  assert.equal(readResumeRecords(harness.storage).length, 1);
  assert.equal(readResumeRecords(harness.storage)[0].uploadId, uploadId);
  assert.throws(() => saveResumeRecord({ getItem: () => null, setItem: () => { throw Error('空间不足'); } }, record), /未能保存/);
});

test('纯逻辑：查询失败不能当作空集合继续上传', async () => {
  let writes = 0;
  await assert.rejects(runUploadRounds({ totalChunks: 1, maxRounds: 3, concurrency: 1,
    fetchSession: async () => { throw new UploadError('模拟查询失败'); }, uploadPart: async () => { writes += 1; }
  }), /查询失败/);
  assert.equal(writes, 0);
});

test('纯逻辑：409 内容冲突和 410 过期不能自动重试', async () => {
  let attempts = 0;
  await assert.rejects(runUploadRounds({ totalChunks: 1, maxRounds: 3, concurrency: 1,
    fetchSession: async () => sessionValue([]),
    uploadPart: async () => { attempts += 1; throw new UploadError('内容冲突', { status: 409 }); }
  }), /内容冲突/);
  assert.equal(attempts, 1);
  await assert.rejects(runUploadRounds({ totalChunks: 1, maxRounds: 3, concurrency: 1,
    fetchSession: async () => ({ status: 'EXPIRED', uploadedChunks: [] }),
    uploadPart: async () => { attempts += 1; }
  }), /过期/);
  assert.equal(attempts, 1);
});

test('模拟页面与接口：连续点击、按轮补传、完成后刷新读取资产', async () => {
  const harness = createHarness();
  let failed = false;
  harness.failPart = index => index === 1 && !failed ? (failed = true, { status: 503 }) : false;
  const page = await harness.newPage();
  await page.choose(new File(['abcdefghijkl'], 'clip.mp4', { type: 'video/mp4' }));
  await Promise.all([page.click('upload-button'), page.click('upload-button')]);
  assert.equal(harness.initializations, 1);
  assert.deepEqual(harness.partWrites, [0, 1, 2, 1]);
  assert.equal(harness.completions, 1);
  assert.equal(page.notice().dataset.tone, 'success');
  assert.equal(page.elements.get('asset-list').childElementCount, 1);
  assert.equal(JSON.parse(harness.values.get(LOCAL_STORAGE_KEY))[0].uploadId, uploadId);
});

test('模拟页面与接口：上传服务未启用时不请求资产接口，列表刷新保持禁用', async () => {
  const harness = createHarness();
  harness.settings.enabled = false;
  const page = await harness.newPage();
  assert.equal(harness.videoListRequests, 0);
  assert.equal(page.elements.get('video-file').disabled, true);
  assert.equal(page.elements.get('refresh-assets').disabled, true);
  assert.match(page.elements.get('library-notice').textContent, /尚未启用/);
  await page.click('refresh-assets');
  assert.equal(harness.videoListRequests, 0);
});

test('模拟页面与接口：刷新重选原文件，默认分片大小变更后仍复用旧会话', async () => {
  const harness = createHarness();
  harness.settings.maxRounds = 1;
  harness.failPart = index => index === 1 && { status: 503 };
  const file = new File(['abcdefghijkl'], 'clip.mp4', { type: 'video/mp4' });
  let page = await harness.newPage();
  await page.choose(file);
  await page.click('upload-button');
  assert.equal(page.notice().dataset.tone, 'error');
  assert.equal(harness.completions, 0);
  assert.deepEqual(harness.session.uploadedChunks, [0, 2]);
  harness.settings.chunkSize = 8;
  harness.failPart = () => false;
  harness.partWrites = [];
  page = await harness.newPage();
  await page.choose(file);
  await page.click('upload-button');
  assert.equal(harness.initializations, 1);
  assert.deepEqual(harness.partWrites, [1]);
  assert.equal(page.notice().dataset.tone, 'success');
});

test('模拟页面与接口：初始化响应丢失后复用原请求键找回会话', async () => {
  const harness = createHarness();
  harness.loseInitializeResponse = true;
  const page = await harness.newPage();
  await page.choose(new File(['abcdefghijkl'], 'clip.mp4', { type: 'video/mp4' }));
  await page.click('upload-button');
  assert.equal(page.notice().dataset.tone, 'error');
  assert.equal(harness.partWrites.length, 0);
  assert.equal(JSON.parse(harness.values.get(LOCAL_STORAGE_KEY))[0].uploadId, undefined);
  await page.click('upload-button');
  assert.equal(harness.initializations, 2);
  assert.equal(harness.assets.length, 1);
});

test('模拟页面与接口：进度查询失败暂停，完成保存失败不报成功且允许重试', async () => {
  const harness = createHarness();
  harness.failQuery = true;
  const page = await harness.newPage();
  await page.choose(new File(['abcdefghijkl'], 'clip.mp4', { type: 'video/mp4' }));
  await page.click('upload-button');
  assert.equal(harness.partWrites.length, 0);
  assert.equal(page.notice().dataset.tone, 'error');
  harness.failQuery = false;
  harness.failComplete = true;
  await page.click('upload-button');
  assert.equal(page.notice().dataset.tone, 'error');
  assert.equal(harness.assets.length, 0);
  const previousWrites = harness.partWrites.length;
  harness.failComplete = false;
  await page.click('upload-button');
  assert.equal(harness.partWrites.length, previousWrites);
  assert.equal(harness.completions, 2);
  assert.equal(harness.assets.length, 1);
  assert.equal(page.notice().dataset.tone, 'success');
});

test('模拟页面与接口：浏览器存储失败不发送初始化，恢复后才允许继续', async () => {
  const harness = createHarness();
  const normalSet = harness.storage.setItem;
  harness.storage.setItem = () => { throw new Error('空间不足'); };
  const page = await harness.newPage();
  await page.choose(new File(['abcdefghijkl'], 'clip.mp4', { type: 'video/mp4' }));
  await page.click('upload-button');
  await page.click('upload-button');
  assert.equal(harness.initializations, 0);
  assert.equal(page.notice().dataset.tone, 'error');
  harness.storage.setItem = normalSet;
  await page.click('upload-button');
  assert.equal(harness.initializations, 1);
  assert.equal(harness.assets.length, 1);
});

test('模拟页面与接口：过期会话刷新后不自动创建新上传', async () => {
  const harness = createHarness();
  harness.failQuery = true;
  const file = new File(['abcdefghijkl'], 'clip.mp4', { type: 'video/mp4' });
  let page = await harness.newPage();
  await page.choose(file);
  await page.click('upload-button');
  harness.failQuery = false;
  harness.session.status = 'EXPIRED';
  page = await harness.newPage();
  await page.choose(file);
  await page.click('upload-button');
  assert.equal(harness.initializations, 1);
  assert.equal(harness.partWrites.length, 0);
  assert.equal(page.elements.get('restart-button').hidden, false);
});

test('模拟页面与接口：INITIALIZING 由用户继续后重试原初始化，不冒充内容冲突重试', async () => {
  const harness = createHarness();
  harness.failQuery = true;
  const file = new File(['abcdefghijkl'], 'clip.mp4', { type: 'video/mp4' });
  let page = await harness.newPage();
  await page.choose(file);
  await page.click('upload-button');
  harness.failQuery = false;
  harness.initializing = true;
  page = await harness.newPage();
  await page.choose(file);
  assert.equal(page.notice().dataset.tone, 'error');
  assert.equal(harness.initializations, 1);
  await page.click('upload-button');
  assert.equal(harness.initializations, 2);
  assert.equal(harness.assets.length, 1);
});
