package com.vibeteaching.parsing.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibeteaching.parsing.ParseModels;
import com.vibeteaching.parsing.config.ParseProperties;
import com.vibeteaching.upload.model.VideoAsset;
import com.vibeteaching.upload.storage.UploadObjectStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;

/** 对象存储与媒体工具 IO 均在调用方数据库事务之外执行。 */
public class MediaPreparation {
    private static final Logger log = LoggerFactory.getLogger(MediaPreparation.class);
    private static final long MAX_UNIT_BYTES = 32L * 1024 * 1024;
    private static final int MAX_PROBE_BYTES = 1024 * 1024;
    private final UploadObjectStore objectStore;
    private final ParseProperties properties;
    private final ObjectMapper objectMapper;

    public MediaPreparation(UploadObjectStore objectStore, ParseProperties properties, ObjectMapper objectMapper) {
        this.objectStore = objectStore;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public record PreparedMedia(long durationMs, boolean hasAudio, List<ParseModels.Input> inputs) {
        public PreparedMedia { inputs = List.copyOf(inputs); }
    }

    public PreparedMedia prepare(VideoAsset asset, String taskId, String attemptToken) {
        validateIdentifier(taskId);
        validateIdentifier(attemptToken);
        if (asset == null || !objectStore.bucket().equals(asset.bucket())) {
            throw failure("MEDIA_BUCKET_MISMATCH", "视频资产不属于当前存储桶", false);
        }
        if (asset.fileSize() <= 0 || asset.fileSize() > properties.maxInputBytes()) {
            throw failure("MEDIA_SIZE_LIMIT", "视频大小超过解析范围", false);
        }
        WorkDirectory work = newWorkDirectory();
        try {
            Path source = work.directory().resolve("source.video");
            download(asset.objectKey(), asset.fileSize(), asset.sha256(), properties.maxInputBytes(), source);
            Probe probe = probe(source);
            long expectedFrames = (probe.durationMs() - 1) / (properties.frameIntervalSeconds() * 1000L) + 1;
            if (expectedFrames > properties.maxFrames()) {
                throw failure("MEDIA_FRAME_LIMIT", "视频超过当前画面采样数量范围，请调整解析参数", false);
            }
            String prefix = "parsing/" + taskId + "/" + attemptToken + "/";
            List<ParseModels.Input> inputs = new ArrayList<>();
            if (probe.hasAudio()) prepareAudio(source, work.directory(), prefix, probe.durationMs(), inputs);
            prepareFrames(source, work.directory(), prefix, probe.durationMs(), inputs);
            if (inputs.isEmpty()) throw failure("MEDIA_NO_INPUTS", "视频未生成可识别的音频或画面", false);
            return new PreparedMedia(probe.durationMs(), probe.hasAudio(), inputs);
        } catch (RecognitionException e) {
            throw e;
        } catch (IOException e) {
            throw failure("MEDIA_PREPARE_IO", "媒体预处理文件操作失败，可稍后重试", true);
        } finally {
            clean(work);
        }
    }

    /** 先核对对象并有界下载；此时尚未调用云端识别服务。 */
    public <T> T withInput(String inputKey, Function<Path, T> action) {
        if (inputKey == null || !inputKey.startsWith("parsing/") || inputKey.contains("..")) {
            throw failure("MEDIA_INPUT_KEY_INVALID", "识别输入对象编号无效", false);
        }
        WorkDirectory work = newWorkDirectory();
        try {
            UploadObjectStore.StoredObject stored = stat(inputKey);
            String suffix = inputKey.endsWith(".wav") ? ".wav" : ".jpg";
            Path local = work.directory().resolve("input" + suffix);
            download(inputKey, stored.size(), stored.sha256(), MAX_UNIT_BYTES, local);
            return action.apply(local);
        } finally {
            clean(work);
        }
    }

    private Probe probe(Path source) throws IOException {
        byte[] output = MediaProcess.run(List.of(properties.ffprobeCommand(), "-v", "error", "-show_entries",
                "format=duration,start_time:stream=codec_type", "-of", "json", source.toString()),
                properties.processTimeout(), MAX_PROBE_BYTES, "FFPROBE");
        JsonNode document;
        try {
            document = objectMapper.readTree(output);
        } catch (IOException e) {
            throw failure("MEDIA_PROBE_INVALID", "媒体信息无法解析", false);
        }
        boolean video = false;
        boolean audio = false;
        if (document != null) {
            for (JsonNode stream : document.path("streams")) {
                video |= "video".equals(stream.path("codec_type").asText());
                audio |= "audio".equals(stream.path("codec_type").asText());
            }
        }
        if (!video) throw failure("MEDIA_NO_VIDEO", "文件不包含可解析的视频轨道", false);
        String start = document.path("format").path("start_time").asText("");
        try {
            if (!start.isBlank() && !start.equals("N/A") && new BigDecimal(start).abs().compareTo(new BigDecimal("0.001")) > 0) {
                throw failure("MEDIA_TIMELINE_UNSUPPORTED", "当前解析暂不支持整体起始时间非零的视频，请先规范化时间轴", false);
            }
        } catch (NumberFormatException e) {
            throw failure("MEDIA_TIMELINE_UNSUPPORTED", "无法确认视频整体起始时间，未提交识别", false);
        }
        long duration;
        try {
            duration = milliseconds(document.path("format").path("duration").asText());
        } catch (RuntimeException e) {
            throw failure("MEDIA_DURATION_UNKNOWN", "无法确认视频时长，未提交识别", false);
        }
        if (duration <= 0 || duration > properties.maxDuration().toMillis()) {
            throw failure("MEDIA_DURATION_LIMIT", "视频时长超过当前解析范围", false);
        }
        return new Probe(duration, audio);
    }

    private void prepareAudio(Path source, Path work, String prefix, long duration,
                              List<ParseModels.Input> inputs) throws IOException {
        Path directory = Files.createDirectory(work.resolve("audio"));
        Path listing = directory.resolve("segments.csv");
        MediaProcess.run(List.of(properties.ffmpegCommand(), "-nostdin", "-v", "error", "-y", "-i", source.toString(),
                "-t", BigDecimal.valueOf(properties.maxDuration().toMillis(), 3).toPlainString(),
                "-map", "0:a:0", "-vn", "-af", "aresample=16000:async=1:first_pts=0,asetnsamples=n=1600:p=0", "-ac", "1", "-ar", "16000",
                "-c:a", "pcm_s16le", "-f", "segment", "-segment_time", Integer.toString(properties.audioSegmentSeconds()),
                "-segment_list", listing.toString(), "-segment_list_type", "csv", "-reset_timestamps", "1",
                directory.resolve("audio_%05d.wav").toString()), properties.processTimeout(), 1024, "FFMPEG_AUDIO");
        if (!Files.isRegularFile(listing) || Files.size(listing) > MAX_PROBE_BYTES) {
            throw failure("MEDIA_AUDIO_INDEX_INVALID", "音频分片索引缺失或过大", false);
        }
        List<String> rows = Files.readAllLines(listing, StandardCharsets.UTF_8);
        long maxSegments = (duration - 1) / (properties.audioSegmentSeconds() * 1000L) + 2;
        if (rows.isEmpty() || rows.size() > maxSegments) {
            throw failure("MEDIA_AUDIO_INDEX_INVALID", "音频分片数量不符合视频时长", false);
        }
        long previousEnd = 0;
        for (int i = 0; i < rows.size(); i++) {
            List<String> fields = csvFields(rows.get(i));
            if (fields.size() != 3) throw failure("MEDIA_AUDIO_INDEX_INVALID", "音频分片索引无效", false);
            String name = Path.of(fields.get(0)).getFileName().toString();
            if (!name.matches("audio_[0-9]{5}\\.wav")) {
                throw failure("MEDIA_AUDIO_INDEX_INVALID", "音频分片文件名无效", false);
            }
            long start;
            long end;
            try {
                start = milliseconds(fields.get(1));
                end = Math.min(duration, milliseconds(fields.get(2)));
            } catch (RuntimeException e) {
                throw failure("MEDIA_AUDIO_INDEX_INVALID", "音频分片时间无效", false);
            }
            if (start < 0 || start < previousEnd - 2 || end <= start) {
                throw failure("MEDIA_AUDIO_INDEX_INVALID", "音频分片时间范围无效", false);
            }
            previousEnd = end;
            String key = prefix + "audio/" + name;
            upload(key, directory.resolve(name), "audio/wav");
            inputs.add(new ParseModels.Input(ParseModels.Kind.ASR, i, key, start, end));
        }
    }

    private void prepareFrames(Path source, Path work, String prefix, long duration,
                               List<ParseModels.Input> inputs) throws IOException {
        Path directory = Files.createDirectory(work.resolve("frames"));
        // 不放大原画面；保留至 2560×1440，避免把中文课件统一压成小图。
        String filter = "fps=1/" + properties.frameIntervalSeconds() + ":start_time=0:round=up:eof_action=pass,"
                + "scale=w='min(2560,iw)':h='min(1440,ih)':force_original_aspect_ratio=decrease:flags=lanczos";
        MediaProcess.run(List.of(properties.ffmpegCommand(), "-nostdin", "-v", "error", "-y", "-i", source.toString(),
                "-map", "0:v:0", "-an", "-vf", filter, "-frames:v", Integer.toString(properties.maxFrames()),
                "-q:v", "2", directory.resolve("frame_%05d.jpg").toString()),
                properties.processTimeout(), 1024, "FFMPEG_FRAMES");
        List<Path> frames;
        try (var paths = Files.list(directory)) {
            frames = paths.filter(path -> path.getFileName().toString().matches("frame_[0-9]{5}\\.jpg"))
                    .sorted().toList();
        }
        if (frames.isEmpty() || frames.size() > properties.maxFrames()) {
            throw failure("MEDIA_FRAMES_INVALID", "视频画面抽取结果无效", false);
        }
        long expectedFrames = (duration - 1) / (properties.frameIntervalSeconds() * 1000L) + 1;
        if (frames.size() != expectedFrames) {
            throw failure("MEDIA_FRAMES_INCOMPLETE", "视频画面时间与媒体时长不一致，未保存不完整解析结果", false);
        }
        for (int i = 0; i < frames.size(); i++) {
            long sampleTime = i * properties.frameIntervalSeconds() * 1000L;
            if (sampleTime >= duration) break;
            String key = prefix + "frames/" + frames.get(i).getFileName();
            upload(key, frames.get(i), "image/jpeg");
            // start=end 表示画面采样时刻，不表示文字的持续区间或语句时间。
            inputs.add(new ParseModels.Input(ParseModels.Kind.OCR, i, key, sampleTime, sampleTime));
        }
    }

    private void upload(String key, Path file, String contentType) {
        try {
            long size = Files.size(file);
            if (size <= 0 || size > MAX_UNIT_BYTES) {
                throw failure("MEDIA_UNIT_SIZE_LIMIT", "识别输入文件超过单项大小范围", false);
            }
            objectStore.put(key, file, contentType, hash(file));
        } catch (RecognitionException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw failure("MEDIA_INPUT_STORE_FAILED", "识别输入保存失败，可稍后重试", true);
        }
    }

    private UploadObjectStore.StoredObject stat(String key) {
        try {
            return objectStore.stat(key).orElseThrow(() -> failure("MEDIA_INPUT_MISSING", "识别输入对象不存在", true));
        } catch (RecognitionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw failure("MEDIA_INPUT_UNAVAILABLE", "识别输入存储暂不可用", true);
        }
    }

    private void download(String key, long expectedSize, String expectedSha, long maxBytes, Path target) {
        UploadObjectStore.StoredObject stored = stat(key);
        if (expectedSize <= 0 || expectedSize > maxBytes || stored.size() != expectedSize) {
            throw failure("MEDIA_INPUT_SIZE_MISMATCH", "媒体输入大小超限或与保存记录不一致", false);
        }
        if (!blank(expectedSha) && !blank(stored.sha256()) && !expectedSha.equalsIgnoreCase(stored.sha256())) {
            throw failure("MEDIA_INPUT_DIGEST_MISMATCH", "媒体输入摘要与保存记录不一致", false);
        }
        MessageDigest digest = sha256();
        try (InputStream input = objectStore.open(key, 0, expectedSize); OutputStream output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            long count = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                count += read;
                if (count > expectedSize || count > maxBytes) {
                    throw failure("MEDIA_INPUT_SIZE_MISMATCH", "媒体输入实际大小超过保存记录", false);
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
            if (count != expectedSize) throw failure("MEDIA_INPUT_TRUNCATED", "媒体输入读取不完整，可稍后重试", true);
            String actualSha = HexFormat.of().formatHex(digest.digest());
            String sha = blank(expectedSha) ? stored.sha256() : expectedSha;
            if (!blank(sha) && !sha.equalsIgnoreCase(actualSha)) {
                throw failure("MEDIA_INPUT_DIGEST_MISMATCH", "媒体输入内容校验失败", false);
            }
        } catch (RecognitionException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw failure("MEDIA_INPUT_DOWNLOAD_FAILED", "媒体输入下载失败，可稍后重试", true);
        }
    }

    private WorkDirectory newWorkDirectory() {
        try {
            Path root = properties.tempDirectory().toAbsolutePath().normalize();
            Files.createDirectories(root);
            root = root.toRealPath();
            Path directory = Files.createTempDirectory(root, "parse-").toRealPath();
            if (!root.equals(directory.getParent())) throw new IOException();
            return new WorkDirectory(root, directory);
        } catch (IOException | RuntimeException e) {
            throw failure("MEDIA_TEMP_UNAVAILABLE", "媒体临时目录不可用", true);
        }
    }

    private void clean(WorkDirectory work) {
        try {
            Path directory = work.directory();
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
            if (Files.isSymbolicLink(directory) || !directory.toRealPath().equals(directory)
                    || !directory.getParent().equals(work.root())) {
                log.warn("parsing_temp_cleanup_refused");
                return;
            }
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.toAbsolutePath().normalize().startsWith(directory)) throw new IOException();
                    Files.deleteIfExists(path);
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("parsing_temp_cleanup_failed");
        }
    }

    private static String hash(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    private static long milliseconds(String seconds) {
        return new BigDecimal(seconds).movePointRight(3).setScale(0, RoundingMode.CEILING).longValueExact();
    }

    private static List<String> csvFields(String row) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < row.length(); i++) {
            char value = row.charAt(i);
            if (value == '"') {
                if (quoted && i + 1 < row.length() && row.charAt(i + 1) == '"') { current.append('"'); i++; }
                else quoted = !quoted;
            } else if (value == ',' && !quoted) {
                fields.add(current.toString()); current.setLength(0);
            } else current.append(value);
        }
        if (quoted) throw failure("MEDIA_AUDIO_INDEX_INVALID", "音频分片索引格式无效", false);
        fields.add(current.toString());
        return fields;
    }

    private static void validateIdentifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,100}")) {
            throw failure("MEDIA_TASK_ID_INVALID", "解析任务编号无效", false);
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static RecognitionException failure(String code, String message, boolean retryable) {
        return new RecognitionException(code, message, retryable, false);
    }
    private record Probe(long durationMs, boolean hasAudio) { }
    private record WorkDirectory(Path root, Path directory) { }
}
