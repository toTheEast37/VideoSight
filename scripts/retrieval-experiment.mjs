import { createHash } from 'node:crypto';
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { parseArgs } from 'node:util';

const model = 'qwen3.7-text-embedding-flash';
const rerankModel = 'qwen3.7-text-rerank';
const dimensions = 1024;
const batchSize = 20;
const channelLimit = 20;
const rrfConstant = 60;
const topK = 3;
const rerankCandidateLimit = 10;

function originalText(clip) {
  return [clip.asr, ...clip.ocr.map(frame => frame.text)].filter(Boolean).join('\n');
}

function summaryText(clip) {
  return [clip.summary, clip.keywords.join(' ')].join('\n');
}

const representations = { original: originalText, title: clip => clip.title, summary: summaryText };

function tokens(text) {
  const words = text.normalize('NFKC').toLowerCase().match(/\p{Script=Han}+|[a-z0-9_]+/gu) ?? [];
  return words.flatMap(word => {
    if (!/^\p{Script=Han}/u.test(word)) return [word];
    const characters = Array.from(word);
    if (characters.length < 2) return characters;
    return [word, ...characters, ...characters.slice(1).map((char, index) => characters[index] + char)];
  });
}

function termFrequency(text) {
  const words = tokens(text);
  const counts = new Map();
  for (const word of words) counts.set(word, (counts.get(word) ?? 0) + 1);
  return new Map(Array.from(counts, ([word, count]) => [word, count / words.length]));
}

function keywordRank(clips, question) {
  const scores = new Map();
  for (const word of new Set(tokens(question))) {
    const matches = clips.filter(clip => clip.termFrequency.has(word));
    const idf = Math.log((clips.length + 1) / (matches.length + 1)) + 1;
    for (const clip of matches) {
      scores.set(clip.id, (scores.get(clip.id) ?? 0) + clip.termFrequency.get(word) * idf);
    }
  }
  return sortedScores(scores).slice(0, channelLimit);
}

function sortedScores(scores) {
  return Array.from(scores).sort(([leftId, left], [rightId, right]) => right - left || leftId.localeCompare(rightId));
}

function rrfRank(semantic, keyword) {
  const fused = new Map();
  for (const list of [sortedScores(semantic).slice(0, channelLimit), keyword]) {
    list.forEach(([id], index) => fused.set(id, (fused.get(id) ?? 0) + 1 / (rrfConstant + index + 1)));
  }
  return sortedScores(fused);
}

function coverage(terms, content) {
  if (!terms.length) return 0;
  const normalized = content.normalize('NFKC').toLowerCase();
  const unique = new Set(terms.map(term => term.normalize('NFKC').toLowerCase()));
  return Array.from(unique).filter(term => normalized.includes(term)).length / unique.size;
}

function weightedRank(clips, semantic, query) {
  const scores = clips.map(clip => [
    clip.id,
    0.6 * semantic.get(clip.id)
      + 0.25 * coverage(query.keywords, summaryText(clip) + '\n' + clip.asr)
      + 0.15 * coverage(query.visualKeywords, clip.ocr.map(frame => frame.text).join('\n'))
  ]);
  return sortedScores(scores);
}

function bailianEndpoint(path) {
  const origin = new URL(process.env.VIDEOSIGHT_EMBEDDING_ORIGIN ?? 'https://dashscope.aliyuncs.com');
  const allowedHost = /^(dashscope\.aliyuncs\.com|[a-z0-9-]+\.cn-beijing\.maas\.aliyuncs\.com)$/i;
  if (origin.protocol !== 'https:' || origin.port || !allowedHost.test(origin.hostname)
      || origin.username || origin.password || origin.pathname !== '/' || origin.search || origin.hash) {
    throw new Error('VIDEOSIGHT_EMBEDDING_ORIGIN 需要是百炼北京地域的 HTTPS 服务域名。');
  }
  return new URL(path, origin);
}

function bailianKey() {
  const key = process.env.DASHSCOPE_API_KEY?.trim();
  if (!key) throw new Error('实时运行需要通过本地私密配置向进程提供 DASHSCOPE_API_KEY。');
  return key;
}

async function bailianPost(endpoint, key, body, operation) {
  const response = await fetch(endpoint, {
    method: 'POST',
    headers: { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    redirect: 'error',
    signal: AbortSignal.timeout(30_000)
  });
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    const code = error.error?.code ?? error.code;
    const safeCode = typeof code === 'string' && /^[a-zA-Z0-9_.-]{1,64}$/.test(code) ? ` (${code})` : '';
    throw new Error(`百炼${operation}接口返回 HTTP ${response.status}${safeCode}。`);
  }
  return response.json();
}

function normalizeVector(vector) {
  if (!Array.isArray(vector) || vector.length !== dimensions || !vector.every(Number.isFinite)) {
    throw new Error('百炼返回的向量维度或数据格式与实验设置不一致。');
  }
  const magnitude = Math.sqrt(vector.reduce((sum, value) => sum + value * value, 0));
  if (!magnitude || !Number.isFinite(magnitude)) throw new Error('无法计算向量相似度。');
  return vector.map(value => value / magnitude);
}

async function obtainEmbeddings(inputs, cachePath, cached) {
  const fingerprint = createHash('sha256').update(JSON.stringify({ model, dimensions, inputs })).digest('hex');
  if (cached) {
    const cache = JSON.parse(await readFile(cachePath, 'utf8'));
    if (cache.fingerprint !== fingerprint || cache.vectors.length !== inputs.length) {
      throw new Error('向量缓存与本次文本或模型不匹配，请重新生成。');
    }
    return cache;
  }

  const key = bailianKey();
  const endpoint = bailianEndpoint('/compatible-mode/v1/embeddings');
  const vectors = [];
  let batches = 0;
  let billedTokens = 0;
  const start = performance.now();
  for (let offset = 0; offset < inputs.length; offset += batchSize) {
    const batch = inputs.slice(offset, offset + batchSize);
    const result = await bailianPost(endpoint, key, { model, input: batch, encoding_format: 'float' }, '向量');
    const rows = result.data?.toSorted((left, right) => left.index - right.index);
    if (rows?.length !== batch.length || rows.some((row, index) => row.index !== index)) {
      throw new Error('百炼返回的向量序号与批次输入不一致。');
    }
    for (const row of rows) {
      normalizeVector(row.embedding);
      vectors.push(row.embedding);
    }
    batches++;
    const totalTokens = result.usage?.total_tokens;
    billedTokens = Number.isInteger(totalTokens) && billedTokens !== null ? billedTokens + totalTokens : null;
  }
  const cache = { fingerprint, model, dimensions, batches, reportedTokens: billedTokens, elapsedMs: Math.round(performance.now() - start), vectors };
  await saveJson(cachePath, cache);
  return cache;
}

async function saveJson(path, data) {
  await mkdir(dirname(path), { recursive: true });
  const temporary = `${path}.${process.pid}.tmp`;
  await writeFile(temporary, JSON.stringify(data, null, 2) + '\n');
  await rename(temporary, path);
}

function dot(left, right) {
  return left.reduce((total, value, index) => total + value * right[index], 0);
}

function semanticScores(clips, vectors, question, text) {
  const queryVector = vectors.get(question);
  return new Map(clips.map(clip => [clip.id, dot(queryVector, vectors.get(text(clip)))]));
}

function evaluate(queries, rankings) {
  const byQuery = queries.map(query => {
    const ranked = rankings(query).map(([id]) => id);
    const expected = new Set(query.expected);
    const first = ranked.findIndex(id => expected.has(id));
    const top3 = ranked.slice(0, topK);
    const recovered = top3.filter(id => expected.has(id)).length;
    return { id: query.id, top3, firstRelevantRank: first < 0 ? null : first + 1,
      recallAt3: expected.size ? recovered / expected.size : null };
  });
  const answerable = byQuery.filter(row => row.recallAt3 !== null);
  const hitsAt3 = answerable.filter(row => row.recallAt3 > 0).length;
  return {
    hitsAt3, count: answerable.length,
    hitAt3: hitsAt3 / answerable.length,
    recallAt3: answerable.reduce((sum, row) => sum + row.recallAt3, 0) / answerable.length,
    mrr: answerable.reduce((sum, row) => sum + (row.firstRelevantRank ? 1 / row.firstRelevantRank : 0), 0) / answerable.length,
    byQuery
  };
}

function latencySummary(samples) {
  const ordered = samples.toSorted((left, right) => left - right);
  const percentile = fraction => Number(ordered[Math.ceil(fraction * ordered.length) - 1].toFixed(3));
  return { p50Ms: percentile(0.5), p95Ms: percentile(0.95) };
}

async function rerankCandidates(endpoint, key, query, documents) {
  const result = await bailianPost(endpoint, key, {
    model: rerankModel,
    input: { query, documents },
    parameters: { top_n: documents.length }
  }, '重排');
  const rows = result.output?.results;
  if (!Array.isArray(rows) || rows.length !== documents.length
      || new Set(rows.map(row => row.index)).size !== documents.length
      || rows.some(row => !Number.isInteger(row.index) || row.index < 0 || row.index >= documents.length
        || !Number.isFinite(row.relevance_score) || row.relevance_score < 0 || row.relevance_score > 1)) {
    throw new Error('百炼重排结果未与输入候选一一对应，无法比较。');
  }
  return { rows, totalTokens: result.usage?.total_tokens ?? null };
}

async function measureRerank(endpoint, key, question, candidateIds, clipText) {
  const start = performance.now();
  const documents = candidateIds.map(id => clipText.get(id));
  const { rows, totalTokens } = await rerankCandidates(endpoint, key, question, documents);
  const ranking = sortedScores(rows.map(row => [candidateIds[row.index], row.relevance_score]));
  return { ranking, totalTokens, elapsedMs: performance.now() - start };
}

function sumReportedTokens(samples) {
  return samples.every(row => Number.isInteger(row.totalTokens))
    ? samples.reduce((sum, row) => sum + row.totalTokens, 0)
    : null;
}

async function runRerankExperiment(name, clips, queries, vectors, cache, output, compareFull) {
  const key = bailianKey();
  const endpoint = bailianEndpoint('/api/v1/services/rerank/text-rerank/text-rerank');
  const clipText = new Map(clips.map(clip => [clip.id, originalText(clip)]));
  const allIds = Array.from(clipText.keys());
  const measurements = new Map();
  for (const [index, query] of queries.entries()) {
    const start = performance.now();
    const semantic = semanticScores(clips, vectors, query.question, originalText);
    const before = weightedRank(clips, semantic, query).slice(0, rerankCandidateLimit);
    const shortlist = before.map(([id]) => id);
    const retrievalMs = performance.now() - start;
    const inputs = [['shortlist', shortlist]];
    if (compareFull) {
      inputs.push(['all', allIds]);
      // 轮换两次请求的顺序，减少连接预热和请求先后带来的偏差。
      if (index % 2) inputs.reverse();
    }
    const reranked = new Map();
    for (const [stage, ids] of inputs) {
      reranked.set(stage, await measureRerank(endpoint, key, query.question, ids, clipText));
    }
    const { ranking: after, totalTokens, elapsedMs: addedMs } = reranked.get('shortlist');
    measurements.set(query.id, { before, after, totalTokens, retrievalMs, addedMs,
      pipelineMs: retrievalMs + addedMs, full: reranked.get('all') });
  }

  const timings = Array.from(measurements.values());
  const latency = {
    retrieval: latencySummary(timings.map(row => row.retrievalMs)),
    added: latencySummary(timings.map(row => row.addedMs)),
    pipeline: latencySummary(timings.map(row => row.pipelineMs))
  };
  const before = evaluate(queries, query => measurements.get(query.id).before);
  const after = evaluate(queries, query => measurements.get(query.id).after);
  const candidateCoverage = queries.flatMap(query => {
    if (!query.expected.length) return [];
    const candidates = new Set(measurements.get(query.id).before.map(([id]) => id));
    return [query.expected.filter(id => candidates.has(id)).length / query.expected.length];
  });
  const meanCandidateRecall = candidateCoverage.reduce((sum, value) => sum + value, 0) / candidateCoverage.length;
  const report = {
    name, model: rerankModel, embeddingFingerprint: cache.fingerprint,
    candidateLimit: rerankCandidateLimit, requests: timings.length, reportedTokens: sumReportedTokens(timings), meanCandidateRecall,
    before, after, latency, queries, measurements: Object.fromEntries(measurements)
  };
  const stages = [['加权召回', before, latency.retrieval], ['加权召回＋重排', after, latency.pipeline]];
  if (compareFull) {
    const fullTimings = timings.map(row => row.full);
    const metrics = evaluate(queries, query => measurements.get(query.id).full.ranking);
    const fullLatency = latencySummary(fullTimings.map(row => row.elapsedMs));
    report.fullCorpus = { candidateCount: allIds.length, inputIds: allIds, requests: fullTimings.length,
      reportedTokens: sumReportedTokens(fullTimings), metrics, latency: fullLatency };
    stages.push(['全量 Rerank', metrics, fullLatency]);
  }
  const filename = compareFull ? 'rerank-comparison.json' : 'rerank-results.json';
  await saveJson(resolve(output, filename), report);
  console.log(`${rerankModel} | ${timings.length} 次请求 | 加权排序前 ${rerankCandidateLimit} 个候选 | 候选证据覆盖率 ${(meanCandidateRecall * 100).toFixed(1)}%`);
  if (compareFull) console.log(`全量重排 | ${report.fullCorpus.requests} 次请求 | 每题 ${allIds.length} 个候选`);
  console.table(Object.fromEntries(stages.map(([stage, result, timing]) => [
    stage, { 'Hit@3': `${result.hitsAt3}/${result.count}`, 'Recall@3': result.recallAt3.toFixed(3), ...timing }
  ])));
  console.log(`重排新增耗时 P50 ${latency.added.p50Ms} ms，P95 ${latency.added.p95Ms} ms。`);
}

async function main() {
  const { values } = parseArgs({ options: {
    fixture: { type: 'string', default: 'scripts/fixtures/retrieval-course.json' },
    output: { type: 'string', default: 'work/retrieval-experiment' },
    cached: { type: 'boolean', default: false },
    rerank: { type: 'boolean', default: false },
    'rerank-all': { type: 'boolean', default: false }
  } });
  const { name, clips, queries } = JSON.parse(await readFile(values.fixture, 'utf8'));
  const clipIds = new Set(clips.map(clip => clip.id));
  if (clipIds.size !== clips.length || new Set(queries.map(query => query.id)).size !== queries.length
      || !queries.some(query => query.expected.length)
      || queries.some(query => query.expected.some(id => !clipIds.has(id)))) {
    throw new Error('样例须有唯一的片段与问题 ID，且至少有一题标注已存在的证据片段。');
  }
  const inputs = Array.from(new Set([
    ...Object.values(representations).flatMap(text => clips.map(text)), ...queries.map(query => query.question)
  ]));
  if (inputs.some(text => !text.trim())) throw new Error('向量输入含空文本。');
  const rerank = values.rerank || values['rerank-all'];
  const cache = await obtainEmbeddings(inputs, resolve(values.output, 'vectors.json'), values.cached || rerank);
  const vectors = new Map(inputs.map((text, index) => [text, normalizeVector(cache.vectors[index])]));
  if (rerank) {
    await runRerankExperiment(name, clips, queries, vectors, cache, values.output, values['rerank-all']);
    return;
  }
  const indexed = clips.map(clip => ({ ...clip, termFrequency: termFrequency(originalText(clip)) }));
  const keywords = new Map(queries.map(query => [query.id, keywordRank(indexed, query.question)]));
  const semantic = new Map(Object.entries(representations).map(([mode, text]) => [
    mode, new Map(queries.map(query => [
      query.id, semanticScores(clips, vectors, query.question, text)
    ]))
  ]));
  const rrf = mode => evaluate(queries, query => rrfRank(semantic.get(mode).get(query.id), keywords.get(query.id)));
  const baseline = rrf('original');
  const results = {
    '排序/RRF': baseline,
    '排序/DOVideo 加权': evaluate(queries, query => weightedRank(clips, semantic.get('original').get(query.id), query)),
    '向量/小标题': rrf('title'),
    '向量/摘要加关键词': rrf('summary'),
    '向量/原文': baseline
  };
  const { vectors: _, ...embedding } = cache;
  await saveJson(resolve(values.output, 'results.json'), { name, clips: clips.length, queries, embedding, results });
  console.log(`${name} | ${clips.length} 个片段，${queries.length} 个问题 | ${model} ${dimensions} 维 | ${values.cached ? '本地缓存回放' : `百炼 ${embedding.batches} 次请求`}`);
  console.table(Object.fromEntries(Object.entries(results).map(([label, value]) => [label, {
    'Hit@3': `${value.hitsAt3}/${value.count}`,
    'Recall@3': value.recallAt3.toFixed(3), MRR: value.mrr.toFixed(3)
  }])));
}

main().catch(error => {
  console.error(error.message);
  process.exitCode = 1;
});
