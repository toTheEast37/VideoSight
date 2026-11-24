package com.vibeteaching.qa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibeteaching.parsing.ParseModels.Kind;
import com.vibeteaching.parsing.ParseModels.TaskStatus;
import com.vibeteaching.parsing.ParseModels.UnitStatus;
import com.vibeteaching.parsing.ParseService;
import com.vibeteaching.parsing.ParseService.TaskView;
import com.vibeteaching.parsing.ParseService.UnitView;
import com.vibeteaching.qa.persistence.VideoVectorMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = {"app.upload.enabled", "app.qa.enabled"}, havingValue = "true")
public class VideoQaService {
    public static final int MAX_QUESTION_LENGTH = 500;
    private static final long WINDOW_MS = 60_000;
    private static final int TOP_WINDOWS = 3;
    private static final int RERANK_CANDIDATES = 10;
    private static final int MAX_ASR_CHARS = 2400;
    private static final int MAX_OCR_CHARS = 800;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern HORIZONTAL_WHITESPACE = Pattern.compile("\\h+");
    private static final Pattern HAN_SPACES = Pattern.compile("(?<=\\p{IsHan}) +(?=\\p{IsHan})");
    private static final Pattern WORDS = Pattern.compile("[a-z0-9][a-z0-9_.-]*|\\p{IsHan}+");
    private static final Pattern VISUAL_HINT = Pattern.compile("画面|屏幕|截图|幻灯片|课件|投影|写着|代码|ppt");
    private static final Set<String> COMMON = Set.of("什么", "哪些", "怎么", "如何", "哪里", "哪个",
            "视频", "讲了", "老师", "请问", "画面", "屏幕", "截图", "出现", "展示", "里面");

    private final ParseService parsing;
    private final VideoVectorMapper storage;
    private final QaModelClient model;
    private final QaProperties properties;
    private final ObjectMapper json;
    private final ReentrantLock[] builds = new ReentrantLock[32];

    public VideoQaService(ParseService parsing, VideoVectorMapper storage, QaModelClient model,
                          QaProperties properties, ObjectMapper json) {
        this.parsing = parsing;
        this.storage = storage;
        this.model = model;
        this.properties = properties;
        this.json = json;
        for (int i = 0; i < builds.length; i++) builds[i] = new ReentrantLock();
    }

    public Answer ask(long assetId, String input) {
        String question = validateQuestion(assetId, input);
        SearchContext context = prepare(assetId);
        if (context.empty()) return result(context.task(), Status.INSUFFICIENT_EVIDENCE, List.of());
        List<Source> sources = retrieve(context, question, false);
        return validateAnswer(context.task(), sources, model.answer(question, sources));
    }

    static String validateQuestion(long assetId, String input) {
        String question = input == null ? "" : input.strip();
        if (assetId <= 0 || question.isBlank() || question.length() > MAX_QUESTION_LENGTH) {
            throw new QaException(HttpStatus.BAD_REQUEST, "INVALID_QA_REQUEST", "请选择视频并输入 1 至 500 字符的问题");
        }
        return question;
    }

    SearchContext prepare(long assetId) {
        if (!properties.ready()) {
            throw new QaException(HttpStatus.SERVICE_UNAVAILABLE, "QA_NOT_CONFIGURED", "视频问答尚未完成百炼配置");
        }
        TaskView task = parsing.byAsset(assetId);
        if (task.status() == TaskStatus.QUEUED || task.status() == TaskStatus.RUNNING) {
            throw new QaException(HttpStatus.CONFLICT, "QA_PARSE_RUNNING", "视频还在解析，请等待识别结果保存后再提问");
        }
        if (task.status() == TaskStatus.FAILED) {
            throw new QaException(HttpStatus.CONFLICT, "QA_PARSE_FAILED", "视频解析失败，请先查看识别状态");
        }

        List<Window> windows = windows(task.units());
        Map<String, float[]> vectors = windows.isEmpty() ? Map.of() : windowVectors(assetId, windows);
        return new SearchContext(task, windows, vectors);
    }

    List<Source> retrieve(SearchContext context, String question, boolean rerank) {
        float[] queryVector = model.embed(List.of(question)).get(0);
        QueryTerms terms = queryTerms(question);
        List<Window> selected = context.windows().stream()
                .map(window -> new ScoredWindow(window, score(window, terms, queryVector, context.vectors().get(window.key()))))
                .sorted(Comparator.comparingDouble(ScoredWindow::score).reversed()
                        .thenComparingLong(item -> item.window().startMs()))
                .limit(rerank ? RERANK_CANDIDATES : TOP_WINDOWS)
                .map(ScoredWindow::window)
                .toList();
        if (!rerank) return selected.stream().flatMap(window -> window.sources().stream()).toList();
        return model.rerank(question, selected.stream().map(Window::text).toList()).stream()
                .limit(TOP_WINDOWS).flatMap(index -> selected.get(index).sources().stream()).toList();
    }

    private List<Window> windows(List<UnitView> units) {
        Map<Long, List<Source>> groups = new TreeMap<>();
        for (UnitView unit : units) {
            if (unit.status() != UnitStatus.SUCCEEDED || unit.kind() == Kind.PREPARE
                    || unit.text() == null || unit.text().isBlank()) continue;
            String text = truncate(unit.text().strip(), unit.kind() == Kind.ASR ? MAX_ASR_CHARS : MAX_OCR_CHARS);
            Source source = new Source(unit.kind() + "-" + unit.unitIndex(), unit.kind(),
                    unit.startMs(), unit.endMs(), text);
            groups.computeIfAbsent(unit.startMs() / WINDOW_MS * WINDOW_MS, key -> new ArrayList<>()).add(source);
        }
        List<Window> windows = new ArrayList<>();
        groups.forEach((start, sources) -> {
            sources.sort(Comparator.comparingLong(Source::startMs).thenComparing(Source::id));
            Set<String> seenOcr = new LinkedHashSet<>();
            List<Source> distinct = sources.stream()
                    .filter(source -> {
                        if (source.kind() == Kind.ASR) return true;
                        String cleaned = cleanOcrForSearch(source.text());
                        return !cleaned.isEmpty() && seenOcr.add(cleaned);
                    })
                    .toList();
            String asr = join(distinct, Kind.ASR);
            String ocr = String.join("\n", seenOcr);
            String text = (asr + "\n" + ocr).strip();
            if (!text.isEmpty()) {
                windows.add(new Window(start, contentKey(text), text, normalize(asr), normalize(ocr), distinct));
            }
        });
        return windows;
    }

    private static String cleanOcrForSearch(String text) {
        return text.replace("\uFEFF", "").replace("\u200B", "").lines()
                .map(line -> HORIZONTAL_WHITESPACE.matcher(line).replaceAll(" ").strip())
                .map(line -> HAN_SPACES.matcher(line).replaceAll(""))
                .filter(line -> !line.isEmpty())
                .collect(Collectors.joining("\n"));
    }

    private static String truncate(String text, int limit) {
        if (text.length() <= limit) return text;
        return text.substring(0, Character.isHighSurrogate(text.charAt(limit - 1)) ? limit - 1 : limit);
    }

    private static String join(List<Source> sources, Kind kind) {
        return sources.stream().filter(source -> source.kind() == kind).map(Source::text)
                .collect(Collectors.joining("\n"));
    }

    private String contentKey(String text) {
        try {
            String input = properties.endpoint() + "\n" + properties.embeddingModel() + "\n" + text;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("缺少片段摘要算法", impossible);
        }
    }

    private Map<String, float[]> windowVectors(long assetId, List<Window> windows) {
        Map<String, String> inputs = new LinkedHashMap<>();
        windows.forEach(window -> inputs.put(window.key(), window.text()));
        ReentrantLock lock = builds[Math.floorMod(Long.hashCode(assetId), builds.length)];
        lock.lock();
        try {
            Map<String, float[]> vectors = new HashMap<>();
            for (var row : storage.find(assetId, List.copyOf(inputs.keySet()))) {
                float[] cached = readVector(row.embeddingJson());
                if (cached != null) vectors.put(row.contentKey(), cached);
            }
            List<String> missing = inputs.keySet().stream().filter(key -> !vectors.containsKey(key)).toList();
            for (int offset = 0; offset < missing.size(); offset += QaModelClient.BATCH_SIZE) {
                List<String> batch = missing.subList(offset, Math.min(missing.size(), offset + QaModelClient.BATCH_SIZE));
                List<float[]> embedded = model.embed(batch.stream().map(inputs::get).toList());
                for (int i = 0; i < batch.size(); i++) {
                    String key = batch.get(i);
                    float[] vector = embedded.get(i);
                    storage.save(assetId, key, encodeVector(vector));
                    vectors.put(key, vector);
                }
            }
            return vectors;
        } finally {
            lock.unlock();
        }
    }

    private float[] readVector(String encoded) {
        try {
            float[] vector = json.readValue(encoded, float[].class);
            QaModelClient.validateVector(vector);
            return vector;
        } catch (JsonProcessingException | QaException invalid) {
            return null;
        }
    }

    private String encodeVector(float[] vector) {
        try {
            return json.writeValueAsString(vector);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("无法编码片段向量", failure);
        }
    }

    private static QueryTerms queryTerms(String question) {
        String normalized = normalize(question);
        Set<String> ordinary = new LinkedHashSet<>();
        Set<String> technical = new LinkedHashSet<>();
        var matcher = WORDS.matcher(normalized);
        while (matcher.find()) {
            String word = matcher.group().replaceAll("[._-]+$", "");
            if (word.length() < 2) continue;
            if (!Character.isIdeographic(word.codePointAt(0))) {
                ordinary.add(word);
                technical.add(word);
                continue;
            }
            int[] characters = word.codePoints().toArray();
            for (int i = 0; i < characters.length - 1; i++) {
                String term = new String(characters, i, 2);
                if (!COMMON.contains(term)) ordinary.add(term);
            }
        }
        Set<String> visual = technical.isEmpty() || VISUAL_HINT.matcher(normalized).find() ? ordinary : technical;
        return new QueryTerms(ordinary, visual);
    }

    private static String normalize(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return WHITESPACE.matcher(normalized).replaceAll(" ").strip();
    }

    private static double score(Window window, QueryTerms terms, float[] query, float[] document) {
        return 0.60 * cosine(query, document) + 0.25 * coverage(terms.ordinary(), window.asr())
                + 0.15 * coverage(terms.visual(), window.ocr());
    }

    private static double coverage(Set<String> terms, String text) {
        if (terms.isEmpty()) return 0;
        return terms.stream().filter(text::contains).count() / (double) terms.size();
    }

    private static double cosine(float[] left, float[] right) {
        double dot = 0, leftSquared = 0, rightSquared = 0;
        for (int i = 0; i < left.length; i++) {
            dot += (double) left[i] * right[i];
            leftSquared += (double) left[i] * left[i];
            rightSquared += (double) right[i] * right[i];
        }
        return dot / Math.sqrt(leftSquared * rightSquared);
    }

    static Answer validateAnswer(TaskView task, List<Source> candidates, JsonNode response) {
        if (response == null || !response.isObject()) throw QaException.invalidModelOutput();
        Status status;
        try {
            status = Status.valueOf(response.path("status").asText());
        } catch (IllegalArgumentException invalid) {
            throw QaException.invalidModelOutput();
        }
        JsonNode items = response.path("items");
        if (!items.isArray() || items.size() > 6 || items.isEmpty() != (status == Status.INSUFFICIENT_EVIDENCE)) {
            throw QaException.invalidModelOutput();
        }
        Map<String, Source> byId = candidates.stream().collect(Collectors.toMap(Source::id, source -> source));
        List<Item> validated = new ArrayList<>();
        for (JsonNode item : items) {
            String statement = text(item.path("text"), 1000);
            JsonNode references = item.path("citations");
            if (!references.isArray() || references.isEmpty() || references.size() > 3) throw QaException.invalidModelOutput();
            List<Source> citations = new ArrayList<>();
            for (JsonNode reference : references) {
                Source source = byId.get(reference.path("id").asText());
                String quote = text(reference.path("quote"), 400);
                if (source == null || !source.text().contains(quote)) throw QaException.invalidModelOutput();
                citations.add(new Source(source.id(), source.kind(), source.startMs(), source.endMs(), quote));
            }
            validated.add(new Item(statement, List.copyOf(citations)));
        }
        return result(task, status, List.copyOf(validated));
    }

    private static String text(JsonNode node, int limit) {
        if (!node.isTextual() || node.textValue().isBlank() || node.textValue().length() > limit) {
            throw QaException.invalidModelOutput();
        }
        return node.textValue().strip();
    }

    private static Answer result(TaskView task, Status status, List<Item> items) {
        return new Answer(task.assetId(), task.taskId(), task.status() == TaskStatus.PARTIAL, status, items);
    }

    enum Status { ANSWERED, PARTIAL, INSUFFICIENT_EVIDENCE }
    public record Source(String id, Kind kind, long startMs, long endMs, String text) { }
    public record Item(String text, List<Source> citations) { }
    public record Answer(long assetId, String parseTaskId, boolean parsePartial, Status status, List<Item> items) { }
    record SearchContext(TaskView task, List<Window> windows, Map<String, float[]> vectors) {
        boolean empty() { return windows.isEmpty(); }
    }
    private record Window(long startMs, String key, String text, String asr, String ocr, List<Source> sources) { }
    private record ScoredWindow(Window window, double score) { }
    private record QueryTerms(Set<String> ordinary, Set<String> visual) { }
}
