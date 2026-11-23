import { formatSourceTime } from './parse-core.mjs';

const statuses = new Set(['ANSWERED', 'PARTIAL', 'INSUFFICIENT_EVIDENCE']);

export function createQaPanel(openPreview) {
  const ids = ['qa-panel', 'qa-asset', 'qa-close', 'qa-form', 'qa-question', 'qa-submit', 'qa-notice', 'qa-result'];
  const ui = Object.fromEntries(ids.map(id => [id, document.getElementById(id)]));
  let asset;
  let request;

  function notice(message, tone = '') {
    ui['qa-notice'].textContent = message;
    ui['qa-notice'].dataset.tone = tone;
  }

  function clear() {
    request?.abort();
    request = undefined;
    ui['qa-question'].value = '';
    ui['qa-submit'].disabled = false;
    ui['qa-result'].replaceChildren();
    notice('');
  }

  function render(result, selected) {
    if (!statuses.has(result.status) || result.assetId !== selected.assetId || !Array.isArray(result.items)) {
      throw new Error('问答服务返回的结果无法识别。');
    }
    const messages = [];
    if (result.parsePartial) messages.push('视频有部分识别未完成，本次使用已保存的文字。');
    if (result.status !== 'ANSWERED') messages.push('该视频的已识别内容中证据不足。');
    notice(messages.join(' '));
    for (const item of result.items) {
      const section = document.createElement('article');
      section.className = 'qa-item';
      const answer = document.createElement('p');
      answer.className = 'qa-answer';
      answer.textContent = item.text;
      section.append(answer);
      for (const source of item.citations) {
        const quote = document.createElement('blockquote');
        const jump = document.createElement('button');
        const from = formatSourceTime(source.startMs);
        const range = source.kind === 'ASR' ? `${from}–${formatSourceTime(source.endMs)}` : from;
        jump.type = 'button';
        jump.className = 'text-button qa-jump';
        jump.textContent = `${source.kind === 'ASR' ? '语音片段（ASR）' : '画面采样（OCR）'} · ${range} ↗`;
        jump.addEventListener('click', () => openPreview(selected, source.startMs));
        const text = document.createElement('p');
        text.className = 'parse-text';
        text.textContent = source.text;
        quote.append(jump, text);
        section.append(quote);
      }
      ui['qa-result'].append(section);
    }
  }

  ui['qa-close'].addEventListener('click', () => {
    clear();
    asset = undefined;
    ui['qa-panel'].hidden = true;
  });

  ui['qa-form'].addEventListener('submit', async event => {
    event.preventDefault();
    const question = ui['qa-question'].value.trim();
    if (request || !asset || !question) return;
    const selected = asset;
    const pending = new AbortController();
    request = pending;
    const timeout = setTimeout(() => pending.abort(), 120_000);
    ui['qa-submit'].disabled = true;
    ui['qa-result'].replaceChildren();
    notice('正在查找视频证据并生成回答…');
    try {
      const response = await fetch(`/api/videos/${encodeURIComponent(selected.assetId)}/ask`, {
        method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({ question }), signal: pending.signal, credentials: 'same-origin'
      });
      const result = await response.json().catch(() => null);
      if (!response.ok || !result) throw new Error(result?.message || '问答服务暂不可用，请稍后再试。');
      if (request === pending) render(result, selected);
    } catch (error) {
      if (request === pending) notice(pending.signal.aborted ? '问答请求等待超时，请稍后再试。' : error.message, 'error');
    } finally {
      clearTimeout(timeout);
      if (request === pending) {
        request = undefined;
        ui['qa-submit'].disabled = false;
      }
    }
  });

  return {
    open(selected) {
      if (asset?.assetId !== selected.assetId) clear();
      asset = selected;
      ui['qa-asset'].textContent = selected.originalName;
      ui['qa-panel'].hidden = false;
      ui['qa-panel'].scrollIntoView({ block: 'nearest', behavior: 'smooth' });
      ui['qa-question'].focus({ preventScroll: true });
    }
  };
}
