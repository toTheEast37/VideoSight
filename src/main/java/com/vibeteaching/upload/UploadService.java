package com.vibeteaching.upload;

import com.vibeteaching.upload.config.UploadProperties;
import com.vibeteaching.upload.model.UploadSession;
import com.vibeteaching.upload.model.UploadStatus;
import com.vibeteaching.upload.model.VideoAsset;
import com.vibeteaching.upload.persistence.UploadRepository;
import com.vibeteaching.upload.progress.UploadProgressStore;
import com.vibeteaching.upload.storage.UploadObjectStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@DependsOn("uploadFlyway")
@ConditionalOnProperty(name = "app.upload.enabled", havingValue = "true")
public class UploadService {
    private static final Logger log = LoggerFactory.getLogger(UploadService.class);
    public static final String FINGERPRINT_ALGORITHM = "SHA256_CHUNK_LIST_V1";
    private static final Set<String> TYPES = Set.of("video/mp4", "video/webm", "video/quicktime");
    private final UploadRepository repository;
    private final UploadProgressStore progress;
    private final UploadObjectStore objects;
    private final UploadProperties properties;
    private final Clock clock;
    // 本地单实例边界：固定条带锁同时协调分片、完成和清理，避免无限增长的锁表。
    private final ReentrantLock[] locks = new ReentrantLock[64];

    public UploadService(UploadRepository repository, UploadProgressStore progress, UploadObjectStore objects,
                         UploadProperties properties, Clock clock) {
        this.repository = repository;
        this.progress = progress;
        this.objects = objects;
        this.properties = properties;
        this.clock = clock;
        for (int i = 0; i < locks.length; i++) locks[i] = new ReentrantLock();
    }

    public SessionView initialize(InitializeRequest request) {
        requireUuid(request.requestKey());
        requireDigest(request.fileFingerprint());
        String name = request.originalName();
        if (name == null || name.isBlank() || name.length() > 255 || name.chars().anyMatch(c -> c < 32)
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            throw invalid("文件名无效");
        }
        if (request.fileSize() <= 0 || request.fileSize() > properties.maxFileSize()) throw invalid("文件大小超出允许范围");
        if (request.contentType() == null || !TYPES.contains(request.contentType())) throw invalid("开发版支持 MP4、WebM 和 MOV 视频");
        Instant now = clock.instant();
        int count = Math.toIntExact((request.fileSize() - 1) / properties.chunkSize() + 1);
        UploadSession candidate = new UploadSession(UUID.randomUUID().toString(), request.requestKey(), name,
                request.fileSize(), properties.chunkSize(), count, request.fileFingerprint(), request.contentType(),
                UploadStatus.INITIALIZING, now.plus(properties.sessionTtl()), now, now);
        UploadSession existing = repository.createOrFind(candidate);
        ReentrantLock lock = lock(existing.uploadId());
        lock.lock();
        try {
            UploadSession session = session(existing.uploadId());
            if (!session.originalName().equals(name) || session.fileSize() != request.fileSize()
                    || !session.fileFingerprint().equals(request.fileFingerprint())
                    || !session.contentType().equals(request.contentType())) {
                throw conflict("FILE_MISMATCH", "该请求编号已绑定其他文件，请重新选择原文件");
            }
            if (repository.findAssetByUploadId(session.uploadId()).isPresent()) return view(session);
            requireNotExpired(session);
            if (session.status() == UploadStatus.INITIALIZING) {
                objects.ensureBucket();
                progress.initialize(session);
                repository.updateSession(session.uploadId(), UploadStatus.UPLOADING, session.expiresAt());
                session = session(session.uploadId());
            }
            return view(session);
        } finally { lock.unlock(); }
    }

    public SessionView status(String uploadId) {
        requireUuid(uploadId);
        ReentrantLock lock = lock(uploadId);
        lock.lock();
        try { return view(session(uploadId)); } finally { lock.unlock(); }
    }

    public void uploadPart(String uploadId, int index, String expectedDigest, long declaredLength, InputStream input) {
        requireUuid(uploadId);
        requireDigest(expectedDigest);
        ReentrantLock lock = lock(uploadId);
        lock.lock();
        Path temp = null;
        try {
            UploadSession session = session(uploadId);
            if (index < 0 || index >= session.totalChunks()) throw invalid("分片序号不合法");
            long size = partSize(session, index);
            if (declaredLength >= 0 && declaredLength != size) throw invalid("分片大小与会话不符");
            requireNotExpired(session);
            if (session.status() != UploadStatus.UPLOADING) throw conflict("UPLOAD_STATE_CONFLICT", "当前会话不能继续写入分片，请查询上传结果");
            Map<Integer, String> current = progress.snapshot(session);
            if (current.containsKey(index) && !current.get(index).equals(expectedDigest)) {
                throw conflict("CHUNK_CONFLICT", "同一分片序号的内容不一致");
            }
            temp = temporaryFile();
            MessageDigest digest = sha256();
            long actual = copyBounded(input, temp, size, digest);
            if (actual != size || !hex(digest.digest()).equals(expectedDigest)) throw invalid("分片长度或校验摘要不匹配");
            String key = partKey(uploadId, index);
            var previous = objects.stat(key);
            if (previous.isPresent() && (previous.get().size() != size || !expectedDigest.equals(previous.get().sha256()))) {
                throw conflict("CHUNK_CONFLICT", "已存分片与本次内容不一致，请重新选择原文件");
            }
            if (previous.isEmpty()) objects.put(key, temp, "application/octet-stream", expectedDigest);
            Instant expiry = clock.instant().plus(properties.sessionTtl());
            repository.updateSession(uploadId, UploadStatus.UPLOADING, expiry);
            progress.recordPart(session(uploadId), index, expectedDigest);
        } catch (IOException exception) {
            throw unavailable("LOCAL_IO_UNAVAILABLE", "临时文件读写失败，分片未确认成功，请稍后重试");
        } finally {
            deleteLocal(temp);
            lock.unlock();
        }
    }

    public VideoAsset complete(String uploadId) {
        requireUuid(uploadId);
        ReentrantLock lock = lock(uploadId);
        lock.lock();
        Path merged = null;
        try {
            var existing = repository.findAssetByUploadId(uploadId);
            if (existing.isPresent()) {
                cleanupCompleted(session(uploadId));
                return existing.get();
            }
            UploadSession session = session(uploadId);
            requireNotExpired(session);
            if (session.status() != UploadStatus.UPLOADING && session.status() != UploadStatus.FINALIZING) {
                throw conflict("UPLOAD_STATE_CONFLICT", "上传会话尚未准备好");
            }
            Map<Integer, String> parts = progress.snapshot(session);
            if (parts.size() != session.totalChunks()) throw conflict("MISSING_PARTS", "仍有分片未确认成功，请继续补传");
            MessageDigest fingerprint = sha256();
            for (int i = 0; i < session.totalChunks(); i++) {
                String value = parts.get(i);
                if (value == null) throw conflict("MISSING_PARTS", "分片尚未齐全");
                fingerprint.update(value.getBytes(StandardCharsets.UTF_8));
            }
            if (!hex(fingerprint.digest()).equals(session.fileFingerprint())) {
                throw conflict("FILE_MISMATCH", "分片内容与原文件指纹不一致，不能完成上传");
            }
            repository.updateSession(uploadId, UploadStatus.FINALIZING, clock.instant().plus(properties.sessionTtl()));
            session = session(uploadId);
            progress.renew(session);
            merged = temporaryFile();
            MessageDigest fullDigest = sha256();
            try (OutputStream output = Files.newOutputStream(merged)) {
                for (int i = 0; i < session.totalChunks(); i++) {
                    long expectedSize = partSize(session, i);
                    MessageDigest partDigest = sha256();
                    long copied = 0;
                    try (InputStream input = objects.open(partKey(uploadId, i), 0, expectedSize)) {
                        byte[] buffer = new byte[65536];
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            copied += read;
                            if (copied > expectedSize) throw conflict("FILE_MISMATCH", "分片数据大小异常");
                            output.write(buffer, 0, read);
                            fullDigest.update(buffer, 0, read);
                            partDigest.update(buffer, 0, read);
                        }
                    }
                    if (copied != expectedSize || !hex(partDigest.digest()).equals(parts.get(i))) {
                        throw conflict("FILE_MISMATCH", "存储中的分片校验失败，不能报告上传成功");
                    }
                }
            }
            String sha = hex(fullDigest.digest());
            String finalKey = "videos/" + uploadId + "/original";
            var stored = objects.stat(finalKey);
            if (stored.isPresent() && (stored.get().size() != session.fileSize() || !sha.equals(stored.get().sha256()))) {
                throw conflict("FILE_MISMATCH", "最终对象已存在但内容不一致");
            }
            if (stored.isEmpty()) objects.put(finalKey, merged, session.contentType(), sha);
            VideoAsset asset = repository.complete(session, objects.bucket(), finalKey, sha);
            cleanupCompleted(session);
            return asset;
        } catch (IOException exception) {
            throw unavailable("LOCAL_IO_UNAVAILABLE", "合并读取失败，尚未完成保存，可稍后重试");
        } finally {
            deleteLocal(merged);
            lock.unlock();
        }
    }

    public List<VideoAsset> assets() { return repository.listAssets(100); }

    public VideoAsset asset(long id) {
        return repository.findAsset(id).orElseThrow(() -> new UploadException(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND", "视频资产不存在"));
    }

    private SessionView view(UploadSession session) {
        var asset = repository.findAssetByUploadId(session.uploadId());
        if (asset.isPresent()) return new SessionView(session.uploadId(), session.requestKey(), session.originalName(),
                session.fileSize(), session.chunkSize(), session.totalChunks(), session.fileFingerprint(),
                UploadStatus.COMPLETED, session.expiresAt(), List.of(), asset.get().assetId());
        requireNotExpired(session);
        if (session.status() == UploadStatus.INITIALIZING) throw conflict("INITIALIZING", "上传初始化尚未完成，请重试本次初始化");
        return new SessionView(session.uploadId(), session.requestKey(), session.originalName(), session.fileSize(),
                session.chunkSize(), session.totalChunks(), session.fileFingerprint(), session.status(), session.expiresAt(),
                progress.snapshot(session).keySet().stream().sorted().toList(), null);
    }

    private void requireNotExpired(UploadSession session) {
        if (session.status() == UploadStatus.EXPIRED || !session.expiresAt().isAfter(clock.instant())) {
            throw new UploadException(HttpStatus.GONE, "SESSION_EXPIRED", "上传会话已过期，请明确选择重新开始");
        }
    }

    @Scheduled(fixedDelayString = "${app.upload.cleanup-delay-ms:600000}")
    public void cleanupExpired() {
        try {
            for (UploadSession completed : repository.findCleanupPending(20)) {
                ReentrantLock lock = lock(completed.uploadId());
                if (!lock.tryLock()) continue;
                try { cleanupCompleted(completed); } finally { lock.unlock(); }
            }
            for (UploadSession candidate : repository.findExpired(clock.instant(), 20)) {
                ReentrantLock lock = lock(candidate.uploadId());
                if (!lock.tryLock()) continue;
                try {
                    UploadSession current = session(candidate.uploadId());
                    if (repository.findAssetByUploadId(current.uploadId()).isPresent()
                            || current.expiresAt().isAfter(clock.instant())) continue;
                    for (int i = 0; i < current.totalChunks(); i++) objects.delete(partKey(current.uploadId(), i));
                    objects.delete("videos/" + current.uploadId() + "/original");
                    progress.delete(current.uploadId());
                    repository.updateSession(current.uploadId(), UploadStatus.EXPIRED, current.expiresAt());
                } catch (RuntimeException exception) {
                    log.warn("过期上传清理未完成，将在后续轮次重试，会话 {}", candidate.uploadId());
                } finally { lock.unlock(); }
            }
        } catch (RuntimeException exception) { log.warn("上传过期清理暂不可用，将在后续轮次重试"); }
    }

    private void cleanupCompleted(UploadSession session) {
        try {
            for (int i = 0; i < session.totalChunks(); i++) objects.delete(partKey(session.uploadId(), i));
            progress.delete(session.uploadId());
            repository.markCleaned(session.uploadId());
        } catch (RuntimeException exception) {
            // 清理失败不能改变已保存成功；完成回执由 MySQL 资产行提供。
            log.warn("视频已保存，临时分片清理未完成，会话 {}", session.uploadId());
        }
    }

    private UploadSession session(String id) {
        return repository.findSession(id).orElseThrow(() -> new UploadException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "上传会话不存在"));
    }

    private Path temporaryFile() throws IOException {
        Files.createDirectories(properties.tempDirectory());
        return Files.createTempFile(properties.tempDirectory(), "upload-", ".tmp");
    }

    private static long copyBounded(InputStream input, Path file, long maximum, MessageDigest digest) throws IOException {
        long copied = 0;
        try (OutputStream output = Files.newOutputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) {
                copied += count;
                if (copied > maximum) throw invalid("分片实际大小超出约定");
                output.write(buffer, 0, count);
                digest.update(buffer, 0, count);
            }
        }
        return copied;
    }

    private static void deleteLocal(Path file) {
        if (file != null) try { Files.deleteIfExists(file); } catch (IOException exception) { log.warn("临时文件删除失败，将保留于忽略目录供排查"); }
    }

    private ReentrantLock lock(String id) { return locks[(id.hashCode() & Integer.MAX_VALUE) % locks.length]; }
    private static String partKey(String id, int index) { return "uploads/" + id + "/parts/" + index; }
    private static long partSize(UploadSession session, int index) { return Math.min(session.chunkSize(), session.fileSize() - (long) index * session.chunkSize()); }
    public static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    public static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
    private static void requireUuid(String value) {
        try { if (value == null || !UUID.fromString(value).toString().equals(value)) throw invalid("请求编号格式无效"); }
        catch (IllegalArgumentException exception) { throw invalid("请求编号格式无效"); }
    }
    private static void requireDigest(String value) { if (value == null || !value.matches("[0-9a-f]{64}")) throw invalid("文件校验摘要格式无效"); }
    private static UploadException invalid(String message) { return new UploadException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message); }
    private static UploadException conflict(String code, String message) { return new UploadException(HttpStatus.CONFLICT, code, message); }
    private static UploadException unavailable(String code, String message) { return new UploadException(HttpStatus.SERVICE_UNAVAILABLE, code, message); }

    public record InitializeRequest(String requestKey, String originalName, long fileSize, String contentType, String fileFingerprint) { }
    public record SessionView(String uploadId, String requestKey, String originalName, long fileSize, int chunkSize,
                              int totalChunks, String fileFingerprint, UploadStatus status, Instant expiresAt,
                              List<Integer> uploadedChunks, Long assetId) { }
}
