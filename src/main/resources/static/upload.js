import { FINGERPRINT_ALGORITHM, UploadError, formatBytes, bytesForParts, fingerprintFile,
  missingParts, readResumeRecords, saveResumeRecord, runUploadRounds } from './upload-core.mjs';
import { createParsingPanel } from './parsing.js';
import { createQaPanel } from './qa.js';

const parsing = createParsingPanel();
const qa = createQaPanel(openPreview);

const element = id => document.getElementById(id);
const ui = Object.fromEntries(['video-file', 'file-picker', 'settings-summary', 'selected-file', 'file-name',
  'file-meta', 'file-tag', 'progress-section', 'progress-label', 'progress-percent', 'upload-progress',
  'progress-detail', 'upload-notice', 'upload-button', 'pause-button', 'restart-button', 'resume-section',
  'resume-list', 'asset-count', 'refresh-assets', 'library-notice', 'asset-list', 'video-preview',
  'preview-title', 'video-player', 'close-preview'].map(id => [id, element(id)]));

let settings;
let selectedFile;
let fileIdentity;
let record;
let records = [];
let storageReady = false;
let busy = false;
let pauseRequested = false;
let selectionController;
let confirmedParts = new Set();
let lastSession;
let assetLoading = false;
let refreshAfterCurrent = false;
let assetGeneration = 0;
let restartAllowed = false;
let previewSeek;

ui['video-file'].addEventListener('change', () => selectFile(ui['video-file'].files[0]));
ui['upload-button'].addEventListener('click', startUpload);
ui['pause-button'].addEventListener('click', () => {
  pauseRequested = true;
  ui['pause-button'].disabled = true;
  notice('正在暂停。已发出的请求结束后会保留进度，你可以稍后继续。');
});
ui['restart-button'].addEventListener('click', restartUpload);
ui['refresh-assets'].addEventListener('click', refreshAssets);
ui['close-preview'].addEventListener('click', closePreview);
ui['video-player'].addEventListener('error', () => {
  if (!ui['video-player'].getAttribute('src')) return;
  ui['video-player'].nextElementSibling.textContent = '视频暂时无法播放，可能是浏览器不支持编码或文件服务不可用。请尝试下载后用本地播放器打开。';
});

initialize();

async function initialize() {
  try {
    records = readResumeRecords(localStorage);
    storageReady = true;
    renderResumeRecords();
  } catch (error) { notice(error.message, 'error'); }
  await loadSettings();
  if (settings?.enabled) await refreshAssets();
  else {
    ui['refresh-assets'].disabled = true;
    ui['library-notice'].textContent = settings
      ? '视频服务尚未启用，配置完成后可在这里查看已保存的视频。'
      : '暂时无法获取视频服务设置，请检查本地服务后刷新页面。';
  }
}

async function loadSettings() {
  try {
    settings = await request('/api/upload-settings');
    const integerFields = ['chunkSize', 'maxFileSize', 'maxRounds', 'concurrency', 'requestTimeoutMs'];
    if (integerFields.some(field => !Number.isSafeInteger(settings[field]) || settings[field] <= 0)
      || !Number.isSafeInteger(settings.retryDelayMs) || settings.retryDelayMs < 0
      || settings.fingerprintAlgorithm !== FINGERPRINT_ALGORITHM) {
      throw new UploadError('上传设置无法识别，请检查服务配置后刷新页面。');
    }
    ui['settings-summary'].textContent = `单个视频不超过 ${formatBytes(settings.maxFileSize)}`;
    if (!settings.enabled) notice('视频上传尚未启用。请先完成本地存储服务的配置，再刷新页面。', 'error');
    else if (storageReady) notice('选择视频即可开始。若之前上传过一部分，请重新选择同一份原文件。');
  } catch (error) { settings = null; notice(error.message, 'error'); }
  updateControls();
}

async function selectFile(file) {
  if (!file || busy || !settings?.enabled || !storageReady) return;
  selectionController?.abort();
  selectionController = new AbortController();
  selectedFile = file;
  fileIdentity = null;
  record = null;
  lastSession = null;
  restartAllowed = false;
  confirmedParts.clear();
  ui['selected-file'].hidden = false;
  ui['file-name'].textContent = file.name;
  ui['file-meta'].textContent = formatBytes(file.size);
  ui['file-tag'].textContent = '正在核对';
  ui['progress-section'].hidden = false;
  progress(0, '正在核对原文件', '只读取文件用于校验，此时尚未发送视频。');
  setBusy(true, false);
  try {
    if (file.size === 0) throw new UploadError('不能上传空文件，请重新选择视频。');
    if (file.size > settings.maxFileSize) throw new UploadError(`文件超过 ${formatBytes(settings.maxFileSize)}，请重新选择。`);
    if (!contentTypeFor(file)) throw new UploadError('当前支持 MP4、WebM 和 MOV 视频，请重新选择。');
    // 老会话使用原来的分片大小，不能因服务设置变化而产生不同的文件身份。
    const candidates = [...records].reverse().filter(item => !item.superseded && item.fileSize === file.size);
    const chunkSizes = [...new Set([...candidates.map(item => item.chunkSize), settings.chunkSize])];
    for (let sizeIndex = 0; sizeIndex < chunkSizes.length; sizeIndex += 1) {
      const chunkSize = chunkSizes[sizeIndex];
      const identity = await fingerprintFile(file, chunkSize, {
        signal: selectionController.signal,
        onProgress: (done, total) => progress(Math.floor(done / total * 100), '正在核对原文件',
          `已核对 ${done} / ${total} 部分${chunkSizes.length > 1 ? ` · 文件匹配 ${sizeIndex + 1} / ${chunkSizes.length}` : ''}`)
      });
      const match = candidates.find(item => item.chunkSize === chunkSize && item.fileFingerprint === identity.fileFingerprint);
      if (match) { record = match; fileIdentity = identity; break; }
      if (chunkSize === settings.chunkSize) fileIdentity = identity;
    }
    if (!fileIdentity) throw new UploadError('未能核对文件，请重新选择。');
    if (record) {
      ui['file-tag'].textContent = '发现续传记录';
      notice('已找到这份视频的上传记录。点击继续后，会先查询已经收到的部分。');
      if (record.uploadId) await inspectSession();
    } else {
      ui['file-tag'].textContent = '待上传';
      notice('文件核对完成，可以开始上传。');
      progress(0, '等待开始', `共 ${fileIdentity.totalChunks} 部分 · 上传中断后可重新选择此文件继续`);
    }
  } catch (error) {
    fileIdentity = null;
    ui['file-tag'].textContent = '无法开始';
    showUploadError(error);
  } finally { setBusy(false); }
}

async function inspectSession() {
  try {
    const session = await fetchSession();
    renderSession(session);
    if (session.status === 'COMPLETED') await handleCompleted(session);
    else if (session.status === 'EXPIRED') {
      restartAllowed = true;
      notice('这次上传已经过期。已保存的视频不会受影响；如需上传此文件，请明确选择重新开始。', 'error');
    } else if (session.status === 'FINALIZING') notice('视频正在完成保存。点击“重试保存”可查询并继续处理，请勿重复创建上传。');
  } catch (error) {
    // 查询失败时保留已匹配的会话，不创建新任务覆盖原记录。
    showUploadError(error);
  }
}

async function startUpload() {
  if (busy || !selectedFile || !fileIdentity || !settings?.enabled || !storageReady) return;
  if (lastSession?.status === 'EXPIRED' || record?.status === 'EXPIRED') return;
  pauseRequested = false;
  restartAllowed = false;
  setBusy(true, true);
  try {
    if (!record) {
      record = { requestKey: crypto.randomUUID(), originalName: selectedFile.name, fileSize: selectedFile.size,
        contentType: contentTypeFor(selectedFile), fileFingerprint: fileIdentity.fileFingerprint,
        fingerprintAlgorithm: FINGERPRINT_ALGORITHM, chunkSize: fileIdentity.chunkSize,
        status: 'UPLOADING', updatedAt: new Date().toISOString() };
    }
    persistRecord(); // 每次继续均先确认可落盘，首次请求尤其必须先保存 requestKey。
    if (!record.uploadId || record.needsInitialization) {
      notice('正在创建或找回本次上传…');
      const session = await request('/api/uploads', { method: 'POST', json: {
        requestKey: record.requestKey, originalName: record.originalName, fileSize: record.fileSize,
        contentType: record.contentType, fileFingerprint: record.fileFingerprint
      }});
      validateSession(session);
      record = { ...record, uploadId: session.uploadId, status: session.status, needsInitialization: false, updatedAt: new Date().toISOString() };
      persistRecord(); // 保存失败即暂停，后续仍可用原 requestKey 取回同一个会话。
      renderSession(session);
    }
    const outcome = await runUploadRounds({ totalChunks: fileIdentity.totalChunks, maxRounds: settings.maxRounds,
      concurrency: settings.concurrency, retryDelayMs: settings.retryDelayMs,
      fetchSession, shouldStop: () => pauseRequested,
      onSession: renderSession,
      onRound: (round, missing) => {
        ui['file-tag'].textContent = '上传中';
        notice(`第 ${round} 轮：正在上传 ${missing} 个尚未收到的部分。`);
      },
      uploadPart: async index => {
        const start = index * fileIdentity.chunkSize;
        await request(`/api/uploads/${encodeURIComponent(record.uploadId)}/parts/${index}`, { method: 'PUT',
          headers: { 'Content-Type': 'application/octet-stream', 'X-Chunk-SHA256': fileIdentity.chunkHashes[index] },
          body: selectedFile.slice(start, Math.min(start + fileIdentity.chunkSize, selectedFile.size)) });
      },
      onPart: index => { confirmedParts.add(index); renderPartProgress(); }
    });
    if (outcome.paused) { ui['file-tag'].textContent = '已暂停'; notice('上传已暂停，已收到的部分会保留。点击继续上传即可补齐。'); }
    else if (outcome.completed) await handleCompleted(outcome.session);
    else if (outcome.ready || outcome.completing) {
      if (pauseRequested) { notice('已暂停。所有部分已收到，稍后点击继续即可完成保存。'); return; }
      ui['file-tag'].textContent = '正在保存';
      progress(100, '正在完成保存', '所有部分已收到，正在确认视频完整性与保存结果。');
      notice('正在完成保存，请保持页面打开。此时还未报告上传成功。');
      const asset = await request(`/api/uploads/${encodeURIComponent(record.uploadId)}/complete`, { method: 'POST' });
      validateAsset(asset);
      if (asset.uploadId !== record.uploadId) throw new UploadError('保存结果与当前上传不一致，已暂停显示成功。');
      await handleCompleted({ ...lastSession, status: 'COMPLETED', assetId: asset.assetId });
    } else if (outcome.exhausted) {
      ui['file-tag'].textContent = '等待继续';
      notice(`本次自动补传已结束，仍有 ${outcome.missing} 部分未确认收到。已保留进度，请检查连接后点击继续上传。`, 'error');
    }
  } catch (error) { showUploadError(error); }
  finally { setBusy(false); }
}

function validateSession(session) {
  if (!session || typeof session.uploadId !== 'string' || !['UPLOADING', 'FINALIZING', 'COMPLETED', 'EXPIRED'].includes(session.status)
    || session.requestKey !== record.requestKey || session.fileFingerprint !== fileIdentity.fileFingerprint
    || session.fileSize !== selectedFile.size || session.chunkSize !== fileIdentity.chunkSize
    || session.totalChunks !== fileIdentity.totalChunks || (record.uploadId && session.uploadId !== record.uploadId)) {
    throw new UploadError('服务端上传记录与所选文件不一致，已暂停上传。请保留原文件和续传记录。', { status: 409, code: 'FILE_MISMATCH' });
  }
  missingParts(session.totalChunks, session.uploadedChunks);
  return session;
}

async function fetchSession() {
  return validateSession(await request(`/api/uploads/${encodeURIComponent(record.uploadId)}`));
}

function renderSession(session) {
  lastSession = session;
  confirmedParts = new Set(session.uploadedChunks);
  if (record && record.status !== session.status) {
    record = { ...record, status: session.status, updatedAt: new Date().toISOString() };
    persistRecord();
  }
  renderPartProgress();
  if (session.status === 'EXPIRED') restartAllowed = true;
}

function renderPartProgress() {
  const bytes = bytesForParts([...confirmedParts], selectedFile.size, fileIdentity.chunkSize);
  progress(Math.floor(bytes / selectedFile.size * 100), '已确认收到的视频部分',
    `${confirmedParts.size} / ${fileIdentity.totalChunks} 部分 · ${formatBytes(bytes)} / ${formatBytes(selectedFile.size)}${lastSession?.expiresAt ? ` · 续传有效至 ${formatDate(lastSession.expiresAt)}` : ''}`);
}

async function handleCompleted(session) {
  if (!Number.isSafeInteger(session.assetId) || session.assetId <= 0) throw new UploadError('服务端未返回可读取的视频编号，暂时无法确认保存结果。');
  lastSession = session;
  record = { ...record, status: 'COMPLETED', assetId: session.assetId, updatedAt: new Date().toISOString() };
  persistRecord();
  ui['file-tag'].textContent = '已保存';
  progress(100, '视频已保存', '可以从视频资料库重新打开或下载。');
  notice('视频已上传并保存。刷新页面后仍可从资料库打开。', 'success');
  await refreshAssets();
}

function restartUpload() {
  if (busy || !restartAllowed || !selectedFile || !fileIdentity) return;
  try {
    if (record) { record = { ...record, superseded: true }; persistRecord(); }
    record = null;
    lastSession = null;
    confirmedParts.clear();
    restartAllowed = false;
    // 新请求必须使用当前设置的分片大小；如已变更，重新核对文件。
    if (fileIdentity.chunkSize !== settings.chunkSize) { selectFile(selectedFile); return; }
    progress(0, '等待重新开始', '这会建立一次新的上传，旧会话不会被当作新的上传继续。');
    ui['file-tag'].textContent = '待上传';
    notice('已准备重新开始。点击开始上传后创建新的上传会话。');
    updateControls();
  } catch (error) { showUploadError(error); }
}

function persistRecord() {
  records = saveResumeRecord(localStorage, record);
  renderResumeRecords();
}

function renderResumeRecords() {
  const pending = [...records].reverse().filter(item => !item.superseded && item.status !== 'COMPLETED');
  ui['resume-section'].hidden = pending.length === 0;
  ui['resume-list'].replaceChildren(...pending.map(item => {
    const entry = document.createElement('li');
    const title = document.createElement('strong');
    title.textContent = item.originalName;
    const detail = document.createElement('span');
    detail.textContent = `${formatBytes(item.fileSize)} · ${item.status === 'EXPIRED' ? '会话已过期' : '等待重新选择原文件'}${item.updatedAt ? ` · ${formatDate(item.updatedAt)}` : ''}`;
    entry.append(title, detail);
    return entry;
  }));
}

function showUploadError(error) {
  const message = error instanceof Error ? error.message : '发生了未知错误，请检查连接后重试。';
  if (error?.status === 410 || error?.code === 'SESSION_EXPIRED') {
    restartAllowed = true;
    if (record) {
      record = { ...record, status: 'EXPIRED' };
      try { persistRecord(); } catch { /* 保留原始错误，避免存储错误覆盖过期提示。 */ }
    }
  }
  if (error?.code === 'INITIALIZING' && record) {
    record = { ...record, needsInitialization: true };
    try { persistRecord(); } catch { /* 下次继续仍先确认本地存储可写。 */ }
  }
  ui['file-tag'].textContent = restartAllowed ? '已过期' : '已暂停';
  notice(message, 'error');
  updateControls();
}

function setBusy(value, showPause = false) {
  busy = value;
  ui['pause-button'].hidden = !value || !showPause;
  ui['pause-button'].disabled = false;
  ui['file-picker'].classList.toggle('is-disabled', value);
  updateControls();
}

function updateControls() {
  ui['video-file'].disabled = busy || !settings?.enabled || !storageReady;
  const completed = record?.status === 'COMPLETED';
  ui['upload-button'].disabled = busy || !fileIdentity || !settings?.enabled || !storageReady || completed || restartAllowed;
  ui['upload-button'].replaceChildren(document.createTextNode(busy ? '正在处理…' : completed ? '已保存' : lastSession?.status === 'FINALIZING' ? '重试保存' : record ? '继续上传' : '开始上传'));
  ui['restart-button'].hidden = !restartAllowed || busy || !fileIdentity;
}

function notice(message, tone = '') { ui['upload-notice'].textContent = message; ui['upload-notice'].dataset.tone = tone; }
function progress(value, label, detail) {
  const clamped = Math.max(0, Math.min(100, value));
  ui['progress-section'].hidden = false;
  ui['upload-progress'].value = clamped;
  ui['progress-percent'].textContent = `${clamped}%`;
  ui['progress-label'].textContent = label;
  ui['progress-detail'].textContent = detail;
}

async function request(path, { method = 'GET', json, headers = {}, body } = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), settings?.requestTimeoutMs || 60000);
  try {
    const response = await fetch(path, { method, headers: { Accept: 'application/json', ...headers,
      ...(json === undefined ? {} : { 'Content-Type': 'application/json' }) },
      body: json === undefined ? body : JSON.stringify(json), signal: controller.signal,
      cache: 'no-store', credentials: 'same-origin' });
    if (!response.ok) {
      let errorBody;
      try { errorBody = await response.json(); } catch { /* 容忍代理或服务错误返回非JSON。 */ }
      throw new UploadError(errorBody?.message || `请求失败（${response.status}），请稍后重试。`, {
        status: response.status, code: errorBody?.code || '', retryable: response.status >= 500 && errorBody?.code !== 'UPLOAD_DISABLED'
      });
    }
    if (response.status === 204) return undefined;
    try { return await response.json(); }
    catch { throw new UploadError('服务端返回内容无法识别，请暂停后重试。'); }
  } catch (error) {
    if (error instanceof UploadError) throw error;
    if (error.name === 'AbortError') throw new UploadError('请求等待超时。服务端可能仍在保存，继续时会先查询实际进度。', { retryable: true });
    throw new UploadError('暂时无法连接本地服务。已保留续传信息，请检查服务与连接后重试。', { retryable: true });
  } finally { clearTimeout(timer); }
}

async function refreshAssets() {
  if (!settings?.enabled) return;
  if (assetLoading) { refreshAfterCurrent = true; return; }
  assetLoading = true;
  const generation = ++assetGeneration;
  ui['refresh-assets'].disabled = true;
  ui['library-notice'].hidden = false;
  ui['library-notice'].dataset.tone = '';
  ui['library-notice'].textContent = '正在读取视频列表…';
  try {
    const assets = await request('/api/videos');
    if (!Array.isArray(assets)) throw new UploadError('视频列表格式无法识别，请稍后刷新。');
    assets.forEach(validateAsset);
    if (generation !== assetGeneration) return;
    ui['asset-count'].textContent = assets.length ? `(${assets.length})` : '';
    ui['asset-list'].replaceChildren(...assets.map(renderAsset));
    ui['library-notice'].hidden = assets.length > 0;
    if (!assets.length) {
      const symbol = document.createElement('span');
      symbol.className = 'empty-illustration';
      symbol.setAttribute('aria-hidden', 'true');
      symbol.textContent = '▱';
      ui['library-notice'].replaceChildren(symbol, document.createTextNode('还没有已保存的视频，先上传第一份吧。'));
    }
  } catch (error) {
    ui['library-notice'].textContent = `${error.message} ${ui['asset-list'].childElementCount ? '下方仍是上次读取的列表。' : ''}`;
    ui['library-notice'].dataset.tone = 'error';
  } finally {
    assetLoading = false;
    ui['refresh-assets'].disabled = false;
    if (refreshAfterCurrent) { refreshAfterCurrent = false; refreshAssets(); }
  }
}

function validateAsset(asset) {
  if (!asset || !Number.isSafeInteger(asset.assetId) || asset.assetId <= 0 || typeof asset.uploadId !== 'string'
    || typeof asset.originalName !== 'string' || !Number.isSafeInteger(asset.fileSize) || asset.fileSize <= 0) {
    throw new UploadError('服务端视频记录不完整，无法确认读取结果。');
  }
}

function renderAsset(asset) {
  const card = document.createElement('article');
  card.className = 'asset-card';
  const thumbnail = document.createElement('div');
  thumbnail.className = 'asset-thumbnail';
  thumbnail.setAttribute('aria-hidden', 'true');
  thumbnail.textContent = '▶';
  const info = document.createElement('div');
  info.className = 'asset-info';
  const title = document.createElement('h3');
  title.className = 'asset-title';
  title.textContent = asset.originalName;
  const metadata = document.createElement('p');
  metadata.className = 'asset-meta';
  metadata.textContent = `${formatBytes(asset.fileSize)} · ${formatDate(asset.createdAt)}`;
  const actions = document.createElement('div');
  actions.className = 'asset-actions';
  const play = document.createElement('button');
  play.type = 'button';
  play.className = 'text-button';
  play.textContent = '播放';
  play.addEventListener('click', () => openPreview(asset));
  const download = document.createElement('a');
  download.href = `/api/videos/${asset.assetId}/content?download=true`;
  download.textContent = '下载';
  const parse = document.createElement('button');
  parse.type = 'button';
  parse.className = 'text-button';
  parse.textContent = '解析 / 查看文字';
  parse.addEventListener('click', () => parsing.open(asset));
  const ask = document.createElement('button');
  ask.type = 'button';
  ask.className = 'text-button';
  ask.textContent = '提问';
  ask.addEventListener('click', () => qa.open(asset));
  actions.append(play, download, parse, ask);
  info.append(title, metadata, actions);
  card.append(thumbnail, info);
  return card;
}

function openPreview(asset, startMs) {
  const player = ui['video-player'];
  const path = `/api/videos/${asset.assetId}/content`;
  const reload = startMs === undefined || player.getAttribute('src') !== path;
  previewSeek?.abort();
  ui['video-preview'].hidden = false;
  ui['preview-title'].textContent = asset.originalName;
  if (startMs !== undefined) {
    const seek = () => { player.currentTime = startMs / 1000; };
    if (!reload && player.readyState >= 1) seek();
    else {
      previewSeek = new AbortController();
      player.addEventListener('loadedmetadata', seek, { once: true, signal: previewSeek.signal });
    }
  }
  if (reload) {
    player.src = path;
    player.nextElementSibling.textContent = '浏览器若不支持该视频的编码，可下载后用本地播放器打开。';
    player.load();
  }
  ui['video-preview'].scrollIntoView({ block: 'nearest', behavior: 'smooth' });
}

function closePreview() {
  previewSeek?.abort();
  ui['video-player'].pause();
  ui['video-player'].removeAttribute('src');
  ui['video-player'].load();
  ui['video-preview'].hidden = true;
}

function formatDate(value) {
  if (!value) return '时间未知';
  const date = new Date(value);
  return Number.isNaN(date.valueOf()) ? '时间未知' : date.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false });
}

function contentTypeFor(file) {
  if (['video/mp4', 'video/webm', 'video/quicktime'].includes(file.type)) return file.type;
  const extension = file.name.split('.').pop().toLowerCase();
  return { mp4: 'video/mp4', webm: 'video/webm', mov: 'video/quicktime' }[extension] || '';
}
