export const FINGERPRINT_ALGORITHM = 'SHA256_CHUNK_LIST_V1';
export const LOCAL_STORAGE_KEY = 'vibeteaching.uploads.v1';

export class UploadError extends Error {
  constructor(message, { status = 0, code = '', retryable = false } = {}) {
    super(message);
    this.name = 'UploadError';
    this.status = status;
    this.code = code;
    this.retryable = retryable;
  }
}

export function formatBytes(bytes) {
  if (!Number.isFinite(bytes) || bytes < 0) return '未知大小';
  if (bytes < 1024) return `${bytes} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let value = bytes / 1024;
  let index = 0;
  while (value >= 1024 && index < units.length - 1) { value /= 1024; index += 1; }
  return `${value >= 10 ? value.toFixed(1) : value.toFixed(2)} ${units[index]}`;
}

export function bytesForParts(parts, fileSize, chunkSize) {
  return parts.reduce((sum, index) => sum + Math.min(chunkSize, fileSize - index * chunkSize), 0);
}

export function missingParts(totalChunks, successfulParts) {
  if (!Number.isSafeInteger(totalChunks) || totalChunks <= 0 || !Array.isArray(successfulParts)) {
    throw new UploadError('服务端返回的上传进度不完整，已暂停上传。');
  }
  const received = new Set(successfulParts);
  for (const part of received) {
    if (!Number.isInteger(part) || part < 0 || part >= totalChunks) {
      throw new UploadError('服务端返回了无效的上传进度，请暂停后重试。');
    }
  }
  return Array.from({ length: totalChunks }, (_, index) => index).filter(index => !received.has(index));
}

export async function fingerprintFile(file, chunkSize, { onProgress = () => {}, signal } = {}) {
  if (!globalThis.crypto?.subtle) throw new UploadError('当前浏览器无法校验文件，请使用最新版浏览器，并从 localhost 或 127.0.0.1 打开页面。');
  if (!Number.isSafeInteger(chunkSize) || chunkSize <= 0) throw new UploadError('上传设置无效，请刷新页面后重试。');
  const totalChunks = Math.ceil(file.size / chunkSize);
  const chunkHashes = [];
  for (let index = 0; index < totalChunks; index += 1) {
    signal?.throwIfAborted();
    const bytes = await file.slice(index * chunkSize, Math.min((index + 1) * chunkSize, file.size)).arrayBuffer();
    signal?.throwIfAborted();
    const digest = await crypto.subtle.digest('SHA-256', bytes);
    chunkHashes.push(toHex(digest));
    onProgress(index + 1, totalChunks);
  }
  signal?.throwIfAborted();
  const encoded = new TextEncoder().encode(chunkHashes.join(''));
  const fileFingerprint = toHex(await crypto.subtle.digest('SHA-256', encoded));
  return { fileFingerprint, chunkHashes, totalChunks, chunkSize, fingerprintAlgorithm: FINGERPRINT_ALGORITHM };
}

function toHex(buffer) {
  return Array.from(new Uint8Array(buffer), byte => byte.toString(16).padStart(2, '0')).join('');
}

export function readResumeRecords(storage) {
  let raw;
  try { raw = storage.getItem(LOCAL_STORAGE_KEY); }
  catch { throw new UploadError('浏览器未允许保存续传信息，请允许此站点使用本地存储后重试。'); }
  if (!raw) return [];
  let records;
  try { records = JSON.parse(raw); }
  catch { throw new UploadError('本浏览器的续传记录无法读取。请保留当前页面，并先检查浏览器的站点存储。'); }
  if (!Array.isArray(records) || records.some(record => !isResumeRecord(record))) {
    throw new UploadError('本浏览器的续传记录格式异常，已暂停创建新的上传。');
  }
  return records;
}

function isResumeRecord(record) {
  return record && typeof record.requestKey === 'string' && typeof record.fileFingerprint === 'string'
    && record.fingerprintAlgorithm === FINGERPRINT_ALGORITHM && Number.isSafeInteger(record.chunkSize)
    && record.chunkSize > 0 && typeof record.originalName === 'string' && Number.isSafeInteger(record.fileSize)
    && record.fileSize > 0 && (!record.uploadId || typeof record.uploadId === 'string');
}

export function saveResumeRecord(storage, record) {
  const records = readResumeRecords(storage);
  const index = records.findIndex(item => item.requestKey === record.requestKey);
  if (index < 0) records.push(record); else records[index] = record;
  try { storage.setItem(LOCAL_STORAGE_KEY, JSON.stringify(records)); }
  catch { throw new UploadError('续传信息未能保存到浏览器，已暂停上传。请检查站点存储空间及隐私设置。'); }
  return records;
}

export async function settleWithConcurrency(items, concurrency, callback, shouldStop = () => false) {
  let next = 0;
  const results = new Array(items.length);
  const workers = Array.from({ length: Math.min(Math.max(1, concurrency), items.length) }, async () => {
    while (next < items.length && !shouldStop()) {
      const slot = next++;
      try { results[slot] = { status: 'fulfilled', value: await callback(items[slot]) }; }
      catch (reason) { results[slot] = { status: 'rejected', reason }; }
    }
  });
  await Promise.all(workers);
  return results.filter(Boolean);
}

/** 每一轮都先查询；所有分片请求结束后才允许进入下一轮。 */
export async function runUploadRounds({ totalChunks, maxRounds, concurrency, retryDelayMs, fetchSession,
  uploadPart, onSession = () => {}, onRound = () => {}, onPart = () => {}, shouldStop = () => false,
  wait = ms => new Promise(resolve => setTimeout(resolve, ms)) }) {
  let lastSession;
  let stopError;
  for (let round = 1; round <= maxRounds; round += 1) {
    if (shouldStop()) return { paused: true, session: lastSession };
    lastSession = await fetchSession(); // 查询失败绝不能按零进度继续。
    onSession(lastSession);
    if (lastSession.status === 'COMPLETED') return { completed: true, session: lastSession };
    if (lastSession.status === 'EXPIRED') throw new UploadError('这次上传已经过期，不能继续补传。请确认后重新开始。', { status: 410 });
    if (lastSession.status === 'FINALIZING') return { completing: true, session: lastSession };
    const missing = missingParts(totalChunks, lastSession.uploadedChunks);
    if (missing.length === 0) return { ready: true, session: lastSession };
    onRound(round, missing.length);
    const results = await settleWithConcurrency(missing, concurrency, async index => {
      try {
        const result = await uploadPart(index);
        onPart(index);
        return result;
      } catch (error) {
        if (!error?.retryable && !stopError) stopError = error;
        throw error;
      }
    }, () => shouldStop() || Boolean(stopError));
    for (const result of results) {
      if (result.status === 'rejected' && !result.reason?.retryable && !stopError) stopError = result.reason;
    }
    // 即使一片发生不可重试错误，也等待本轮已发出的请求全部结束。
    if (stopError) throw stopError;
    if (shouldStop()) return { paused: true, session: lastSession };
    if (round < maxRounds) await wait(retryDelayMs);
  }
  // 最后一轮也须以服务端记录确认，不能用请求结束数量判断完成。
  lastSession = await fetchSession();
  onSession(lastSession);
  if (lastSession.status === 'COMPLETED') return { completed: true, session: lastSession };
  if (lastSession.status === 'EXPIRED') throw new UploadError('这次上传已经过期，不能继续补传。请确认后重新开始。', { status: 410 });
  if (lastSession.status === 'FINALIZING') return { completing: true, session: lastSession };
  const missing = missingParts(totalChunks, lastSession.uploadedChunks);
  return missing.length === 0 ? { ready: true, session: lastSession } : { exhausted: true, session: lastSession, missing: missing.length };
}
