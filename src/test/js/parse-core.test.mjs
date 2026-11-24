import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createParseCoordinator, createParseRequest, ParseError, PARSE_REQUEST_PREFIX,
  retryOptions } from '../../main/resources/static/parse-core.mjs';
import { createParsingPanel } from '../../main/resources/static/parsing.js';

// 可控 HTTP / DOM 替身；不代表真实识别、真实浏览器或重启持久化验收。
const html = await readFile(new URL('../../main/resources/static/index.html', import.meta.url), 'utf8');
const asset = { assetId: 7, originalName: '中文课程.mp4' };
const taskId = '00000000-0000-4000-8000-000000000007';
const unit = (kind, status, text = '') => ({ kind, unitIndex: 0, status, attempts: 1,
  startMs: kind === 'PREPARE' ? 0 : 60000, endMs: kind === 'ASR' ? 120000 : kind === 'PREPARE' ? 0 : 60000,
  text, message: status === 'UNCERTAIN' ? '请求受理状态未确认' : status === 'FAILED' ? '识别服务暂时不可用' : '' });
const taskValue = (status = 'QUEUED', units = [unit('PREPARE', 'QUEUED')], canRetry = false) => ({
  taskId, assetId: asset.assetId, profileVersion: 'v1', status, durationMs: 120000, canRetry, units,
  createdAt: '2026-11-17T01:00:00Z', updatedAt: '2026-11-17T01:01:00Z' });
const missing = () => new ParseError('尚未开始解析', { status: 404, code: 'PARSE_NOT_STARTED' });
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

function storageValue() {
  const values = new Map();
  return { values, getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value) };
}

test('响应丢失：先持久保存请求键，连续点击只提交一次，GET 找回已受理任务', async () => {
  const storage = storageValue();
  let saved, posts = 0;
  const calls = [];
  const request = async (path, options = {}) => {
    calls.push(options.method || 'GET');
    if (options.method === 'POST') {
      posts += 1;
      assert.equal(storage.getItem(`${PARSE_REQUEST_PREFIX}${asset.assetId}`), options.json.requestKey);
      saved = taskValue();
      throw new ParseError('模拟响应丢失');
    }
    if (!saved) throw missing();
    return saved;
  };
  const service = createParseCoordinator({ request, storage });
  const [first, second] = await Promise.all([service.start(asset.assetId), service.start(asset.assetId)]);
  assert.equal(first.taskId, taskId);
  assert.equal(second.taskId, taskId);
  assert.equal(posts, 1);
  assert.deepEqual(calls, ['GET', 'POST', 'GET']);
  // 新页面没有任务内存和请求键，也必须能从后端找回，而非再次创建。
  const freshPage = createParseCoordinator({ request, storage: storageValue() });
  assert.equal((await freshPage.start(asset.assetId)).taskId, taskId);
  assert.equal(posts, 1);
});

test('响应丢失且核对失败：保留原请求键，下一次先查询，查询确认未创建才可复用原键提交', async () => {
  const storage = storageValue();
  let queries = 0, posts = 0;
  const keys = [];
  const request = async (path, options = {}) => {
    if (options.method === 'POST') {
      posts += 1;
      keys.push(options.json.requestKey);
      if (posts === 1) throw new ParseError('连接中断');
      return taskValue();
    }
    queries += 1;
    if (queries === 2) throw new ParseError('模拟状态查询失败', { status: 503 });
    throw missing();
  };
  await assert.rejects(createParseCoordinator({ request, storage }).start(asset.assetId), error => {
    assert.equal(error.unconfirmed, true);
    return true;
  });
  assert.equal(posts, 1);
  await createParseCoordinator({ request, storage }).start(asset.assetId);
  assert.equal(queries, 3);
  assert.equal(posts, 2);
  assert.equal(keys[0], keys[1]);
});

test('新识别配置可从已完成的旧结果发起解析，连续点击和丢失响应仍只创建一次', async () => {
  const storage = storageValue();
  storage.setItem(`${PARSE_REQUEST_PREFIX}${asset.assetId}`, taskId);
  let current = taskValue('SUCCEEDED', [unit('OCR', 'SUCCEEDED', '旧画面文字')]);
  let posts = 0;
  const service = createParseCoordinator({ storage, request: async (path, options = {}) => {
    if (options.method !== 'POST') return current;
    posts += 1;
    assert.notEqual(options.json.requestKey, taskId);
    assert.equal(storage.getItem(`${PARSE_REQUEST_PREFIX}${asset.assetId}.v2`), options.json.requestKey);
    current = { ...taskValue(), taskId: 'new-task', profileVersion: 'v2' };
    throw new ParseError('模拟新解析的响应丢失');
  } });
  const result = await Promise.all([service.start(asset.assetId, 'v2'), service.start(asset.assetId, 'v2')]);
  assert.deepEqual(result.map(task => task.taskId), ['new-task', 'new-task']);
  assert.equal((await service.start(asset.assetId, 'v2')).taskId, 'new-task');
  assert.equal(posts, 1);
});

test('状态查询失败、资产不存在和浏览器存储失败均不能变成新解析', async () => {
  let posts = 0;
  for (const error of [new ParseError('查询失败', { status: 503 }), new ParseError('资产不存在', { status: 404, code: 'ASSET_NOT_FOUND' })]) {
    const service = createParseCoordinator({ storage: storageValue(), request: async (path, options = {}) => {
      if (options.method === 'POST') posts += 1;
      throw error;
    } });
    await assert.rejects(service.start(asset.assetId), failure => failure.unconfirmed);
  }
  const service = createParseCoordinator({ storage: { getItem: () => null, setItem: () => { throw Error('存储不可用'); } },
    request: async (path, options = {}) => { if (options.method === 'POST') posts += 1; throw missing(); } });
  await assert.rejects(service.start(asset.assetId), /未能保存/);
  assert.equal(posts, 0);
});

test('重试受服务端状态控制：活动任务不能重试，受理不确定必须明确接受重复计费可能', async () => {
  assert.equal(retryOptions(taskValue('RUNNING', [unit('ASR', 'FAILED')], true)).available, false);
  const uncertain = taskValue('PARTIAL', [unit('ASR', 'UNCERTAIN')], true);
  let posts = 0;
  const service = createParseCoordinator({ storage: storageValue(), request: async (path, options = {}) => {
    if (options.method === 'POST') { posts += 1; assert.equal(options.json.confirmUncertain, true); return taskValue(); }
    return uncertain;
  } });
  await assert.rejects(service.retry(uncertain, false), /重复计费/);
  assert.equal(posts, 0);
  await service.retry(uncertain, true);
  assert.equal(posts, 1);
});

class FakeElement {
  constructor() { Object.assign(this, { listeners: {}, hidden: false, disabled: false, dataset: {}, children: [], textContent: '' }); }
  addEventListener(name, callback) { this.listeners[name] = callback; }
  replaceChildren(...children) { this.children = children; }
  append(...children) { this.children.push(...children); }
  scrollIntoView() { }
  set innerHTML(value) { throw Error('识别结果不得作为 HTML 插入'); }
}

function pageHarness(initialTask = null) {
  const elements = new Map([...html.matchAll(/id="([^"]+)"/g)].map(match => [match[1], new FakeElement()]));
  const doc = { getElementById: id => elements.get(id), createElement: () => new FakeElement() };
  const state = { task: initialTask, posts: [], pollError: false, pollComplete: false, failCreate: false,
    settings: { enabled: true, ready: true, message: '', maxDurationSeconds: 600, frameIntervalSeconds: 5, maxFrames: 120 } };
  const request = async (path, options = {}) => {
    if (path === '/api/parse-settings') return state.settings;
    if (options.method === 'POST') {
      state.posts.push({ path, ...options });
      if (state.failCreate) throw new ParseError('保存失败', { status: 503 });
      state.task = taskValue();
      return state.task;
    }
    if (path === `/api/parse-tasks/${taskId}`) {
      if (state.pollError) throw new ParseError('状态读取失败', { status: 503 });
      if (state.pollComplete) state.task = taskValue('SUCCEEDED', [unit('ASR', 'SUCCEEDED', '服务端已保存的语音'), unit('OCR', 'SUCCEEDED', '服务端已保存的画面')]);
    }
    if (!state.task) throw missing();
    return state.task;
  };
  const panel = createParsingPanel({ document: doc, storage: storageValue(), request, pollIntervalMs: 5 });
  return { ...state, state, panel, elements, click: id => elements.get(id).listeners.click(),
    close: () => elements.get('parse-close').listeners.click() };
}

function allText(element) { return [element.textContent, ...element.children.map(allText)].join('\n'); }

test('页面部分失败显示已保存文字与来源时间，受理不确定按钮明确确认后才重试，轮询服务器完成状态', async () => {
  const injection = '<img src=x onerror="alert(1)">';
  const page = pageHarness(taskValue('PARTIAL', [unit('ASR', 'SUCCEEDED', injection), unit('OCR', 'UNCERTAIN')], true));
  try {
    await page.panel.open(asset);
    assert.equal(page.elements.get('parse-status').textContent, '部分完成');
    assert.equal(page.elements.get('parse-notice').dataset.tone, 'error');
    assert.equal(page.elements.get('parse-retry').hidden, true);
    assert.equal(page.elements.get('parse-uncertain-retry').hidden, false);
    assert.equal(page.elements.get('parse-uncertain-help').hidden, false);
    assert.match(allText(page.elements.get('parse-asr')), /音频 1:00—2:00/);
    assert.ok(allText(page.elements.get('parse-asr')).includes(injection));
    assert.match(allText(page.elements.get('parse-ocr')), /截图 1:00/);
    await page.click('parse-uncertain-retry');
    assert.deepEqual(page.state.posts[0].json, { confirmUncertain: true });
    assert.equal(page.elements.get('parse-status').textContent, '等待解析');
    assert.notEqual(page.elements.get('parse-notice').dataset.tone, 'success');
    page.state.pollComplete = true;
    await delay(30);
    assert.equal(page.elements.get('parse-status').textContent, '解析完成');
    assert.match(allText(page.elements.get('parse-asr')), /服务端已保存的语音/);
    assert.equal(page.elements.get('parse-notice').dataset.tone, 'success');
  } finally { page.close(); }
});

test('轮询失败保留最近读取内容，明确刷新后才继续读取，不显示完成', async () => {
  const page = pageHarness(taskValue('RUNNING', [unit('ASR', 'SUCCEEDED', '已保存的片段'), unit('OCR', 'RUNNING')]));
  try {
    page.state.pollError = true;
    await page.panel.open(asset);
    await delay(25);
    assert.equal(page.elements.get('parse-notice').dataset.tone, 'error');
    assert.match(page.elements.get('parse-notice').textContent, /保留最近读取/);
    assert.match(allText(page.elements.get('parse-asr')), /已保存的片段/);
    assert.equal(page.elements.get('parse-start').hidden, true);
    assert.equal(page.state.posts.length, 0);
    page.state.pollError = false;
    page.state.task = taskValue('SUCCEEDED', [unit('ASR', 'SUCCEEDED', '已保存的片段')]);
    await page.click('parse-refresh');
    assert.equal(page.elements.get('parse-status').textContent, '解析完成');
  } finally { page.close(); }
});

test('解析未就绪仍可读历史；保存失败不能显示解析成功或清空请求键后重建', async () => {
  const historical = pageHarness(taskValue('SUCCEEDED', [unit('ASR', 'SUCCEEDED', '历史识别内容')]));
  try {
    historical.state.settings = { enabled: false, ready: false, message: '文字解析尚未启用' };
    await historical.panel.open(asset);
    assert.match(historical.elements.get('parse-settings').textContent, /尚未启用/);
    assert.match(allText(historical.elements.get('parse-asr')), /历史识别内容/);
    assert.equal(historical.state.posts.length, 0);
  } finally { historical.close(); }
  const failed = pageHarness();
  try {
    failed.state.failCreate = true;
    await failed.panel.open(asset);
    await failed.click('parse-start');
    assert.equal(failed.elements.get('parse-results').hidden, true);
    assert.equal(failed.elements.get('parse-notice').dataset.tone, 'error');
    assert.match(failed.elements.get('parse-notice').textContent, /保存失败/);
    await failed.click('parse-start');
    assert.equal(failed.state.posts.length, 2);
    assert.equal(failed.state.posts[0].json.requestKey, failed.state.posts[1].json.requestKey);
  } finally { failed.close(); }
});

test('HTTP 非 JSON 错误和连接失败保留可理解错误，不冒充空解析结果', async () => {
  const request = createParseRequest(async () => new Response('unavailable', { status: 503 }));
  await assert.rejects(request('/api/videos/7/parse'), error => error.status === 503);
  await assert.rejects(createParseRequest(async () => { throw TypeError('网络错误'); })('/api/videos/7/parse'), /无法连接/);
});
