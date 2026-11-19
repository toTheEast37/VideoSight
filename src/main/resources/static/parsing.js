import { createParseRequest, createParseCoordinator, describeTask, retryOptions, shouldPoll, formatSourceTime } from './parse-core.mjs';

const unitLabels = { QUEUED: '等待处理', RUNNING: '处理中', SUCCEEDED: '已保存', SKIPPED: '已跳过', FAILED: '未完成', UNCERTAIN: '受理状态待确认' };

export function createParsingPanel({ document: doc = globalThis.document, storage,
  request = createParseRequest(), pollIntervalMs = 2000 } = {}) {
  const ids = ['parse-panel', 'parse-title', 'parse-settings', 'parse-notice', 'parse-status', 'parse-updated',
    'parse-start', 'parse-refresh', 'parse-close', 'parse-retry', 'parse-uncertain-retry', 'parse-uncertain-help',
    'parse-prepare', 'parse-asr', 'parse-ocr', 'parse-results'];
  const ui = Object.fromEntries(ids.map(id => [id, doc.getElementById(id)]));
  const service = createParseCoordinator({ request, storage: storage || {
    getItem: key => globalThis.localStorage.getItem(key),
    setItem: (key, value) => globalThis.localStorage.setItem(key, value)
  } });
  let asset, task, settings, known = false, busy = false, generation = 0, timer;

  ui['parse-close'].addEventListener('click', () => {
    generation += 1;
    clearTimeout(timer);
    busy = false;
    ui['parse-panel'].hidden = true;
  });
  ui['parse-refresh'].addEventListener('click', refresh);
  ui['parse-start'].addEventListener('click', () => mutate(() => service.start(asset.assetId)));
  ui['parse-retry'].addEventListener('click', () => mutate(() => service.retry(task, false)));
  ui['parse-uncertain-retry'].addEventListener('click', () => mutate(() => service.retry(task, true)));

  function notice(message, tone = '') {
    ui['parse-notice'].textContent = message;
    ui['parse-notice'].dataset.tone = tone;
  }
  function controls() {
    const ready = settings?.enabled && settings?.ready;
    const retry = retryOptions(task);
    ui['parse-start'].hidden = Boolean(task);
    ui['parse-start'].disabled = busy || !known || !ready;
    ui['parse-refresh'].disabled = busy;
    ui['parse-retry'].hidden = !retry.available || retry.confirmUncertain;
    ui['parse-retry'].disabled = busy || !ready || !known;
    ui['parse-uncertain-retry'].hidden = !retry.available || !retry.confirmUncertain;
    ui['parse-uncertain-retry'].disabled = busy || !ready || !known;
    ui['parse-uncertain-help'].hidden = !retry.confirmUncertain;
  }
  function settingsText() {
    if (!settings) return;
    ui['parse-settings'].textContent = !settings.enabled || !settings.ready
      ? settings.message || '解析暂时不可用，请配置完成后刷新状态。'
      : `可解析 ${formatSourceTime(settings.maxDurationSeconds * 1000)} 以内的视频；画面每 ${settings.frameIntervalSeconds} 秒抽样一次，最多 ${settings.maxFrames} 帧。`;
    ui['parse-settings'].dataset.tone = settings.enabled && settings.ready ? '' : 'error';
  }
  function render() {
    controls();
    ui['parse-results'].hidden = !task;
    if (!task) {
      ui['parse-status'].textContent = known ? '尚未解析' : '状态待确认';
      ui['parse-updated'].textContent = '';
      return;
    }
    const description = describeTask(task);
    ui['parse-status'].textContent = description.label;
    ui['parse-status'].dataset.tone = description.tone;
    notice(description.message, description.tone);
    const time = new Date(task.updatedAt);
    ui['parse-updated'].textContent = Number.isNaN(time.valueOf()) ? '' : `最近保存：${time.toLocaleString('zh-CN', { hour12: false })}`;
    renderUnits('PREPARE', ui['parse-prepare']);
    renderUnits('ASR', ui['parse-asr']);
    renderUnits('OCR', ui['parse-ocr']);
  }
  function renderUnits(kind, container) {
    const units = task.units.filter(unit => unit.kind === kind).sort((a, b) => a.unitIndex - b.unitIndex);
    if (!units.length) {
      const empty = doc.createElement('p');
      empty.className = 'parse-empty';
      empty.textContent = '尚未建立识别片段，等待视频检查完成。';
      container.replaceChildren(empty);
      return;
    }
    container.replaceChildren(...units.map(unit => {
      const item = doc.createElement('article');
      item.className = 'parse-unit';
      const header = doc.createElement('p');
      header.className = 'parse-unit-heading';
      const source = kind === 'PREPARE' ? '视频检查' : kind === 'OCR'
        ? `截图 ${formatSourceTime(unit.startMs)}`
        : `音频 ${formatSourceTime(unit.startMs)}—${formatSourceTime(unit.endMs)}`;
      header.textContent = `${source} · ${unitLabels[unit.status]}`;
      header.dataset.tone = ['FAILED', 'UNCERTAIN'].includes(unit.status) ? 'error' : '';
      item.append(header);
      if (unit.message) {
        const message = doc.createElement('p');
        message.className = 'parse-unit-message';
        message.textContent = unit.message;
        item.append(message);
      }
      if (unit.text) {
        const text = doc.createElement('pre');
        text.className = 'parse-text';
        text.textContent = unit.text;
        item.append(text);
      } else if (unit.status === 'SUCCEEDED' && kind !== 'PREPARE') {
        const empty = doc.createElement('p');
        empty.className = 'parse-empty';
        empty.textContent = kind === 'ASR' ? '本段未识别到语音文字。' : '该截图未识别到文字。';
        item.append(empty);
      }
      return item;
    }));
  }
  function schedule() {
    clearTimeout(timer);
    if (!task || !shouldPoll(task) || ui['parse-panel'].hidden) return;
    const token = generation;
    timer = setTimeout(async () => {
      if (token !== generation || busy) return;
      try {
        const current = await service.inspectTask(task);
        if (token !== generation) return;
        task = current;
        known = true;
        render();
        schedule();
      } catch (error) {
        if (token !== generation) return;
        known = false;
        controls();
        notice(`${error.message} 下方保留最近读取的结果；请点击“刷新状态”继续核对。`, 'error');
      }
    }, pollIntervalMs);
  }
  async function refresh() {
    if (busy || !asset) return;
    clearTimeout(timer);
    const token = ++generation; // 使先前已经发出、尚未返回的轮询失效。
    busy = true;
    controls();
    notice('正在读取后台保存的解析状态…');
    const [config, state] = await Promise.allSettled([request('/api/parse-settings'), service.inspect(asset.assetId)]);
    if (token !== generation) return;
    if (config.status === 'fulfilled' && typeof config.value?.enabled === 'boolean' && typeof config.value?.ready === 'boolean'
      && (!config.value.ready || ['maxDurationSeconds', 'frameIntervalSeconds', 'maxFrames']
        .every(field => Number.isFinite(config.value[field]) && config.value[field] > 0))) {
      settings = config.value;
      settingsText();
    } else {
      settings = null;
      ui['parse-settings'].textContent = config.reason?.message || '解析设置无法读取，请刷新状态。';
      ui['parse-settings'].dataset.tone = 'error';
    }
    if (state.status === 'fulfilled') {
      task = state.value;
      known = true;
      render();
      if (!task) notice(settings?.enabled && settings?.ready
        ? '点击“开始解析文字”后，后台将提取语音和抽样画面中的文字。' : '解析尚未开始。请先处理上方显示的不可用原因。');
    } else {
      known = false;
      render();
      notice(`${state.reason.message}${task ? ' 下方保留最近读取的结果。' : ' 未确认状态前不会重新创建任务。'}`, 'error');
    }
    busy = false;
    controls();
    if (known) schedule();
  }
  async function mutate(action) {
    if (busy || !known || !settings?.enabled || !settings?.ready || !asset) return;
    clearTimeout(timer);
    const token = ++generation;
    busy = true;
    controls();
    notice('正在核对并提交，请稍候…');
    try {
      const current = await action();
      if (token !== generation) return;
      task = current;
      known = true;
      render();
    } catch (error) {
      if (token !== generation) return;
      if (error.task) task = error.task;
      known = !error.unconfirmed;
      render();
      notice(error.message, 'error');
    } finally {
      if (token === generation) { busy = false; controls(); if (known) schedule(); }
    }
  }
  async function open(selected) {
    if (busy) return;
    generation += 1;
    clearTimeout(timer);
    asset = selected;
    task = null;
    known = false;
    ui['parse-panel'].hidden = false;
    ui['parse-title'].textContent = selected.originalName;
    ui['parse-settings'].textContent = '正在获取解析设置…';
    render();
    ui['parse-panel'].scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    await refresh();
  }
  return { open };
}
