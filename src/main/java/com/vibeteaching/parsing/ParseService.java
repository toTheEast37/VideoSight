package com.vibeteaching.parsing;

import com.vibeteaching.parsing.ParseModels.*;
import com.vibeteaching.parsing.config.ParseProperties;
import com.vibeteaching.parsing.persistence.ParseRepository;
import com.vibeteaching.upload.model.VideoAsset;
import com.vibeteaching.upload.persistence.UploadRepository;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "app.upload.enabled", havingValue = "true")
public class ParseService {
    private final ParseRepository repository;
    private final UploadRepository uploads;
    private final ParseProperties properties;
    private final Clock clock;

    public ParseService(ParseRepository repository, UploadRepository uploads, ParseProperties properties, Clock clock) {
        this.repository = repository;
        this.uploads = uploads;
        this.properties = properties;
        this.clock = clock;
    }

    public TaskView submit(long assetId, String requestKey) {
        ready();
        requireUuid(requestKey);
        VideoAsset asset = asset(assetId);
        if (asset.fileSize() > properties.maxInputBytes()) {
            throw new ParseException(HttpStatus.BAD_REQUEST, "MEDIA_SIZE_LIMIT", "视频超过当前解析大小上限，原视频仍已保存");
        }
        try {
            return view(repository.createOrFind(assetId, profile(), requestKey, clock.instant()));
        } catch (IllegalArgumentException conflict) {
            throw new ParseException(HttpStatus.CONFLICT, "PARSE_REQUEST_CONFLICT", "该请求编号已用于其他视频或解析配置，请重新打开对应视频");
        }
    }

    public TaskView byAsset(long assetId) {
        asset(assetId);
        Task task = repository.findByAsset(assetId, profile()).or(() -> repository.findLatestByAsset(assetId)).orElseThrow(() ->
                new ParseException(HttpStatus.NOT_FOUND, "PARSE_NOT_STARTED", "该视频尚未按当前配置开始解析"));
        return view(task);
    }

    public TaskView byId(String taskId) {
        requireUuid(taskId);
        return view(repository.findTask(taskId).orElseThrow(() ->
                new ParseException(HttpStatus.NOT_FOUND, "PARSE_TASK_NOT_FOUND", "解析任务不存在")));
    }

    public TaskView retry(String taskId, boolean confirmUncertain) {
        ready();
        TaskView current = byId(taskId);
        if (!current.profileVersion().equals(profile())) {
            throw new ParseException(HttpStatus.CONFLICT, "PARSE_PROFILE_CHANGED", "解析配置已变更，请从视频重新发起当前配置的解析");
        }
        if (current.status() == TaskStatus.QUEUED || current.status() == TaskStatus.RUNNING) return current;
        return view(repository.retry(taskId, confirmUncertain, clock.instant()));
    }

    public Settings settings() {
        String problem = configurationProblem();
        return new Settings(properties.enabled(), properties.enabled() && problem == null,
                !properties.enabled() ? "解析服务尚未启用，已保存的识别结果仍可查看" : problem == null
                        ? "可以开始解析。排队和识别期间可关闭页面，稍后回来查看。" : problem,
                properties.maxDuration().toSeconds(), properties.frameIntervalSeconds(), properties.maxFrames(), profile());
    }

    public String configurationProblem() {
        if (blank(properties.asrUrl()) || blank(properties.asrModel()) || blank(properties.asrApiKey())) {
            return "尚未填写语音识别服务配置，请在本机 dev 配置中完成后重启应用";
        }
        try {
            URI uri = URI.create(properties.asrUrl());
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !uri.getPath().endsWith("/services/aigc/multimodal-generation/generation")
                    || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())
                    && ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost())))) {
                return "语音识别接口地址格式不正确，请检查本机配置";
            }
        } catch (IllegalArgumentException invalid) { return "语音识别接口地址格式不正确，请检查本机配置"; }
        if (!properties.asrModel().equals("qwen-audio-3.1-asr-flash")) {
            return "当前适配器使用 qwen-audio-3.1-asr-flash 非流式短音频接口，请检查模型配置";
        }
        if (blank(properties.nameserver())) return "尚未配置后台任务服务，请完成本机 RocketMQ 配置后重启应用";
        if (!executable(properties.ffmpegCommand()) || !executable(properties.ffprobeCommand())) {
            return "尚未找到视频处理工具，请配置 FFmpeg 和 FFprobe 的可执行文件路径";
        }
        return switch (properties.ocrProvider()) {
            case TESSERACT -> executable(properties.ocrCommand()) ? null
                    : "尚未找到画面文字识别工具，请配置 Tesseract 路径和中文语言数据";
            case QWEN -> properties.qwenOcrReady() ? null
                    : "尚未完成百炼画面文字识别配置，请检查接口地址、密钥和 qwen3.5-ocr 模型";
        };
    }

    public String profile() {
        return switch (properties.ocrProvider()) {
            case TESSERACT -> localOcrProfile();
            case QWEN -> profileWithOcr(properties.ocrEndpoint() + "\n" + properties.ocrModel());
        };
    }

    public String localOcrProfile() {
        return profileWithOcr(properties.ocrCommand() + "\nchi_sim+eng");
    }

    private String profileWithOcr(String ocrIdentity) {
        // 只以影响输出的非敏感配置计算版本；密钥轮换不会导致重复付费解析。
        String source = properties.asrUrl() + "\n" + properties.asrModel() + "\n"
                + properties.audioSegmentSeconds() + "\n" + properties.frameIntervalSeconds()
                + "\n" + ocrIdentity + "\nfixed-frame-v1";
        try {
            return properties.profileVersion() + "-" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private void ready() {
        if (!properties.enabled()) throw new ParseException(HttpStatus.SERVICE_UNAVAILABLE, "PARSE_DISABLED", "解析服务尚未启用");
        String problem = configurationProblem();
        if (problem != null) throw new ParseException(HttpStatus.SERVICE_UNAVAILABLE, "PARSE_NOT_CONFIGURED", problem);
    }

    private TaskView view(Task task) {
        List<UnitView> units = repository.units(task.taskId()).stream().map(unit -> new UnitView(
                unit.kind(), unit.unitIndex(), unit.status(), unit.attempts(), unit.startMs(), unit.endMs(),
                unit.text(), unit.errorCode(), unit.message())).toList();
        boolean terminal = task.status() != TaskStatus.RUNNING && task.status() != TaskStatus.QUEUED;
        return new TaskView(task.taskId(), task.assetId(), task.profileVersion(), task.status(), task.durationMs(),
                task.createdAt(), task.updatedAt(), terminal && task.profileVersion().equals(profile()) && units.stream().anyMatch(unit ->
                unit.status() == UnitStatus.FAILED || unit.status() == UnitStatus.UNCERTAIN), units);
    }

    private VideoAsset asset(long assetId) {
        return uploads.findAsset(assetId).orElseThrow(() -> new ParseException(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND", "视频资产不存在"));
    }

    private static boolean blank(String value) { return value == null || value.isBlank() || value.startsWith("<"); }

    private static boolean executable(String value) {
        if (blank(value)) return false;
        try {
            Path command = Path.of(value);
            if (command.isAbsolute() || value.contains("/") || value.contains("\\")) return Files.isRegularFile(command);
            for (String directory : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
                if (directory.isBlank()) continue;
                if (Files.isRegularFile(Path.of(directory, value)) || Files.isRegularFile(Path.of(directory, value + ".exe"))) return true;
            }
        } catch (RuntimeException ignored) { /* 非法路径按配置未就绪处理。 */ }
        return false;
    }

    private static void requireUuid(String value) {
        try {
            if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            throw new ParseException(HttpStatus.BAD_REQUEST, "INVALID_PARSE_IDENTIFIER", "请求或任务编号格式不正确");
        }
    }

    public record Settings(boolean enabled, boolean ready, String message, long maxDurationSeconds,
                           int frameIntervalSeconds, int maxFrames, String profileVersion) { }
    public record UnitView(Kind kind, int unitIndex, UnitStatus status, int attempts, long startMs,
                           long endMs, String text, String errorCode, String message) { }
    public record TaskView(String taskId, long assetId, String profileVersion, TaskStatus status,
                           Long durationMs, Instant createdAt, Instant updatedAt, boolean canRetry, List<UnitView> units) { }
}
