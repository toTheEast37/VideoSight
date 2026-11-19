const taskStatuses = new Set(['QUEUED', 'RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED']);
const unitStatuses = new Set(['QUEUED', 'RUNNING', 'SUCCEEDED', 'SKIPPED', 'FAILED', 'UNCERTAIN']);
const kinds = new Set(['PREPARE', 'ASR', 'OCR']);
export const PARSE_REQUEST_PREFIX = 'vibeteaching.parse-request.v1.';

export class ParseError extends Error {
  constructor(message, details = {}) { super(message); this.name = 'ParseError'; Object.assign(this, details); }
}

export function validateTask(task, assetId) {
  if (!task || typeof task.taskId !== 'string' || !task.taskId || task.assetId !== assetId
    || !taskStatuses.has(task.status) || typeof task.canRetry !== 'boolean' || !Array.isArray(task.units)
    || task.units.some(unit => !kinds.has(unit.kind) || !unitStatuses.has(unit.status)
      || !Number.isSafeInteger(unit.unitIndex) || unit.unitIndex < 0
      || !Number.isFinite(unit.startMs) || !Number.isFinite(unit.endMs)
      || unit.startMs < 0 || unit.endMs < unit.startMs
      || (unit.text != null && typeof unit.text !== 'string')
      || (unit.message != null && typeof unit.message !== 'string'))) {
    throw new ParseError('解析记录不完整，暂时无法确认状态。请刷新状态，勿重复提交。', { code: 'INVALID_RESPONSE' });
  }
  return task;
}

export const shouldPoll = task => task?.status === 'QUEUED' || task?.status === 'RUNNING';

export function retryOptions(task) {
  const available = Boolean(task?.canRetry && !shouldPoll(task));
  const uncertain = available && task.units.some(unit => unit.status === 'UNCERTAIN');
  const failed = available && task.units.some(unit => unit.status === 'FAILED');
  return { available: Boolean(uncertain || failed), confirmUncertain: Boolean(uncertain) };
}

export function describeTask(task) {
  return {
    QUEUED: { label: '等待解析', tone: '', message: '任务已保存，正在等待后台处理。关闭页面后仍可回来查询。' },
    RUNNING: { label: '正在解析', tone: '', message: '后台正在提取文字。下方仅显示已经保存的阶段结果。' },
    SUCCEEDED: { label: '解析完成', tone: 'success', message: '解析完成，以下结果已保存。识别文字仍需对照原视频核对。' },
    PARTIAL: { label: '部分完成', tone: 'error', message: '部分识别未完成，已成功保存的文字仍可查看。请查看各部分的说明。' },
    FAILED: { label: '解析未完成', tone: 'error', message: '解析未完成，请查看失败原因。已保存的内容会保留。' }
  }[task.status];
}

export function formatSourceTime(milliseconds) {
  if (!Number.isFinite(milliseconds) || milliseconds < 0) return '时间未知';
  const seconds = Math.floor(milliseconds / 1000);
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor(seconds % 3600 / 60);
  const tail = String(seconds % 60).padStart(2, '0');
  return hours ? `${hours}:${String(minutes).padStart(2, '0')}:${tail}` : `${minutes}:${tail}`;
}

export function createParseRequest(fetchImpl = globalThis.fetch.bind(globalThis), timeoutMs = 20000) {
  return async (path, { method = 'GET', json } = {}) => {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const response = await fetchImpl(path, { method, headers: { Accept: 'application/json',
        ...(json === undefined ? {} : { 'Content-Type': 'application/json' }) },
        body: json === undefined ? undefined : JSON.stringify(json), signal: controller.signal,
        cache: 'no-store', credentials: 'same-origin' });
      let body;
      try { body = await response.json(); } catch { /* 错误代理可能不返回 JSON。 */ }
      if (!response.ok) throw new ParseError(body?.message || `解析请求失败（${response.status}），请稍后刷新状态。`,
        { status: response.status, code: body?.code || '' });
      if (body === undefined) throw new ParseError('服务返回内容无法识别，请刷新状态确认。', { code: 'INVALID_RESPONSE' });
      return body;
    } catch (error) {
      if (error instanceof ParseError) throw error;
      throw new ParseError(error.name === 'AbortError'
        ? '解析请求等待超时，后台可能仍在处理。' : '暂时无法连接服务，请检查连接后刷新状态。', { code: 'CONNECTION_FAILED' });
    } finally { clearTimeout(timer); }
  };
}

// 浏览器只保存提交意图，不保存或重建识别进度；任务和文字始终从服务端读取。
export function createParseCoordinator({ request, storage, uuid = () => crypto.randomUUID() }) {
  const inFlight = new Map();
  const taskPath = taskId => `/api/parse-tasks/${encodeURIComponent(taskId)}`;
  const assetPath = assetId => `/api/videos/${encodeURIComponent(assetId)}/parse`;
  const inspect = async assetId => {
    try { return validateTask(await request(assetPath(assetId)), assetId); }
    catch (error) {
      if (error.status === 404 && error.code === 'PARSE_NOT_STARTED') return null;
      throw error;
    }
  };
  const inspectTask = async task => validateTask(await request(taskPath(task.taskId)), task.assetId);
  function exclusive(assetId, action) {
    if (inFlight.has(assetId)) return inFlight.get(assetId);
    const operation = Promise.resolve().then(action).finally(() => inFlight.delete(assetId));
    inFlight.set(assetId, operation);
    return operation;
  }
  function requestKey(assetId) {
    try {
      const key = `${PARSE_REQUEST_PREFIX}${assetId}`;
      const existing = storage.getItem(key);
      if (existing != null && !/^[\da-f]{8}-[\da-f]{4}-[\da-f]{4}-[\da-f]{4}-[\da-f]{12}$/i.test(existing)) {
        throw new Error('无效的请求键');
      }
      const value = existing || uuid();
      storage.setItem(key, value); // 必须在 POST 前确认定位信息确实可持久保存。
      return value;
    } catch {
      throw new ParseError('未能保存本次解析的请求标识，已暂停提交。请检查浏览器存储；已有结果仍可查看。', { code: 'LOCAL_STORAGE_FAILED' });
    }
  }
  const start = assetId => exclusive(assetId, async () => {
    let existing;
    try { existing = await inspect(assetId); } // 每次提交前核对，包括上一次响应丢失后的重试。
    catch (error) { throw new ParseError(error.message, { code: error.code, unconfirmed: true }); }
    if (existing) return existing;
    const key = requestKey(assetId);
    try { return validateTask(await request(assetPath(assetId), { method: 'POST', json: { requestKey: key } }), assetId); }
    catch (error) {
      // 提交失败不代表服务端没有受理；先找回数据库记录，不用新键重建任务。
      try {
        const recovered = await inspect(assetId);
        if (recovered) return recovered;
      } catch {
        throw new ParseError('提交结果暂时无法确认，核对状态也未成功。请先刷新状态，勿重复发起解析。',
          { code: 'SUBMISSION_UNCONFIRMED', unconfirmed: true });
      }
      throw new ParseError(`${error.message} 尚未查到解析记录；再次提交前会重新核对，并复用原请求标识。`, { code: error.code });
    }
  });
  const retry = (task, confirmUncertain = false) => exclusive(task.assetId, async () => {
    let current;
    try { current = await inspectTask(task); }
    catch (error) { throw new ParseError(error.message, { code: error.code, unconfirmed: true }); }
    const options = retryOptions(current);
    if (!options.available) return current;
    if (options.confirmUncertain && !confirmUncertain) {
      throw new ParseError('存在未确认受理状态的识别请求。重试可能重复计费，请先阅读说明并明确选择。');
    }
    try { return validateTask(await request(`${taskPath(task.taskId)}/retry`, { method: 'POST', json: { confirmUncertain } }), task.assetId); }
    catch (error) {
      let recovered;
      try { recovered = await inspectTask(task); } catch { /* 保留最后读取结果，不把查询失败当作空任务。 */ }
      throw new ParseError('重试请求结果未确认。已尝试重新读取状态，请核对当前状态后再操作。',
        { code: 'RETRY_UNCONFIRMED', unconfirmed: !recovered, task: recovered });
    }
  });
  return { inspect, inspectTask, start, retry };
}
