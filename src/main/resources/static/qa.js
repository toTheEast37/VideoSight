import { formatSourceTime } from './parse-core.mjs';

const statuses = new Set(['ANSWERED', 'PARTIAL', 'INSUFFICIENT_EVIDENCE']);
const supplements = new Set(['NOT_NEEDED', 'COMPLETED', 'FAILED']);

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
    if (result.assetId !== selected.assetId || !Array.isArray(result.subquestions) || !result.subquestions.length
      || result.subquestions.some(item => typeof item.question !== 'string' || !statuses.has(item.status)
        || !supplements.has(item.supplement) || !Array.isArray(item.items))) {
      throw new Error('问答服务返回的结果无法识别。');
    }
    notice(result.parsePartial ? '视频有部分识别未完成，本次使用已保存的文字。' : '');
    for (const subquestion of result.subquestions) {
      const group = document.createElement('section');
      group.className = 'qa-subquestion';
      const heading = document.createElement('h3');
      heading.textContent = subquestion.question;
      group.append(heading);
      if (subquestion.status !== 'ANSWERED' || subquestion.supplement === 'COMPLETED') {
        const state = document.createElement('p');
        state.className = 'qa-subquestion-status';
        if (subquestion.supplement === 'FAILED') {
          state.textContent = '本次补证失败，尚未确认的部分无法回答。';
        } else if (subquestion.status === 'ANSWERED') {
          state.textContent = '补查一次后找到证据。';
        } else {
          state.textContent = subquestion.supplement === 'COMPLETED'
            ? '补查一次后，仍有内容证据不足。' : '该视频的已识别内容中证据不足。';
        }
        group.append(state);
      }
      for (const item of subquestion.items) group.append(renderItem(item, selected));
      ui['qa-result'].append(group);
    }
  }

  function renderItem(item, selected) {
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
    return section;
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
    const timeout = setTimeout(() => pending.abort(), 240_000);
    ui['qa-submit'].disabled = true;
    ui['qa-result'].replaceChildren();
    notice('正在拆解问题、查找并核对视频证据…');
    try {
      const response = await fetch(`/api/videos/${encodeURIComponent(selected.assetId)}/ask/agent`, {
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
