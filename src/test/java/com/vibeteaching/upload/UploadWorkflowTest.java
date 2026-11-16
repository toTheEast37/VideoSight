package com.vibeteaching.upload;

import com.vibeteaching.upload.config.UploadProperties;
import com.vibeteaching.upload.model.UploadSession;
import com.vibeteaching.upload.model.UploadStatus;
import com.vibeteaching.upload.model.VideoAsset;
import com.vibeteaching.upload.persistence.UploadRepository;
import com.vibeteaching.upload.progress.UploadProgressStore;
import com.vibeteaching.upload.storage.UploadObjectStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 使用小字节样例验证持久化及故障窗口，不代表真实视频或大文件验收。 */
class UploadWorkflowTest {
    private static final byte[] FILE = {1, 2, 3, 4, 5, 6};
    private static final int CHUNK_SIZE = 4;

    @TempDir Path directory;
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private UploadService service;
    private UploadProperties properties;
    private final MemoryProgress progress = new MemoryProgress();
    private final MemoryObjects objects = new MemoryObjects();
    private final MutableClock clock = new MutableClock();

    @BeforeEach
    void initializeRealDatabaseAndControllableExternalStores() {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        properties = new UploadProperties(true, CHUNK_SIZE, 1024, 3, 2, 1000, 0,
                Duration.ofHours(1), directory);
        service = newService();
    }

    @Test
    void freshServiceResumesTheSameSessionAndKeepsPreviouslySuccessfulParts() {
        UploadService.InitializeRequest request = request();
        var initial = service.initialize(request);
        uploadPart(service, initial.uploadId(), 0);

        UploadService restarted = newService();
        var resumed = restarted.initialize(request);
        assertThat(resumed.uploadId()).isEqualTo(initial.uploadId());
        assertThat(restarted.status(initial.uploadId()).uploadedChunks()).containsExactly(0);
        assertThat(progress.initializations).isEqualTo(1);

        uploadPart(restarted, resumed.uploadId(), 1);
        VideoAsset asset = restarted.complete(resumed.uploadId());
        assertThat(objects.bytes.get(asset.objectKey())).containsExactly(FILE);
        assertThat(objects.writes.get(partKey(initial.uploadId(), 0))).isEqualTo(1);
        assertThat(newRepository().findAssetByUploadId(initial.uploadId())).contains(asset);
    }

    @Test
    void duplicateInitializationAndCompletionReturnOnePersistentAsset() {
        UploadService.InitializeRequest request = request();
        var first = service.initialize(request);
        assertThat(service.initialize(request).uploadId()).isEqualTo(first.uploadId());
        uploadAll(first.uploadId());

        VideoAsset asset = service.complete(first.uploadId());
        assertThat(service.complete(first.uploadId())).isEqualTo(asset);
        assertThat(newService().complete(first.uploadId())).isEqualTo(asset);
        assertThat(newService().initialize(request).assetId()).isEqualTo(asset.assetId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM upload_session", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM video_asset", Integer.class)).isEqualTo(1);
        assertThat(objects.writes.get(finalKey(first.uploadId()))).isEqualTo(1);
    }

    @Test
    void objectWriteFailureDoesNotRecordSuccessfulPart() {
        var session = service.initialize(request());
        objects.failNextPut = true;

        assertThatThrownBy(() -> uploadPart(service, session.uploadId(), 0))
                .isInstanceOfSatisfying(UploadException.class,
                        error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(service.status(session.uploadId()).uploadedChunks()).isEmpty();
        assertThat(objects.bytes).doesNotContainKey(partKey(session.uploadId(), 0));
        assertThat(newRepository().findAssetByUploadId(session.uploadId())).isEmpty();
    }

    @Test
    void objectSuccessFollowedByRedisFailureCanBeRetriedWithoutRewritingTheObject() {
        var session = service.initialize(request());
        progress.failNextRecord = true;

        assertThatThrownBy(() -> uploadPart(service, session.uploadId(), 0)).isInstanceOf(UploadException.class);
        assertThat(service.status(session.uploadId()).uploadedChunks()).isEmpty();
        assertThat(objects.bytes).containsKey(partKey(session.uploadId(), 0));

        uploadPart(newService(), session.uploadId(), 0);

        assertThat(service.status(session.uploadId()).uploadedChunks()).containsExactly(0);
        assertThat(objects.writes.get(partKey(session.uploadId(), 0))).isEqualTo(1);
    }

    @Test
    void finalObjectSuccessFollowedBySqlFailureDoesNotReportSuccessAndRetryReusesObject() {
        var session = service.initialize(request());
        uploadAll(session.uploadId());
        jdbc.execute("ALTER TABLE upload_session ADD CONSTRAINT test_block_complete CHECK (status <> 'COMPLETED')");

        assertThatThrownBy(() -> service.complete(session.uploadId())).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(objects.bytes).containsKey(finalKey(session.uploadId()));
        assertThat(newRepository().findAssetByUploadId(session.uploadId())).isEmpty();
        assertThat(newRepository().findSession(session.uploadId()).orElseThrow().status()).isEqualTo(UploadStatus.FINALIZING);
        assertThat(service.status(session.uploadId()).assetId()).isNull();
        jdbc.execute("ALTER TABLE upload_session DROP CONSTRAINT test_block_complete");

        VideoAsset recovered = newService().complete(session.uploadId());

        assertThat(objects.writes.get(recovered.objectKey())).isEqualTo(1);
        assertThat(newRepository().findAssetByUploadId(session.uploadId())).contains(recovered);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM video_asset", Integer.class)).isEqualTo(1);
    }

    @Test
    void completedAssetRemainsDiscoverableWithoutRedisAfterTheUploadDeadline() {
        var session = service.initialize(request());
        uploadAll(session.uploadId());
        VideoAsset asset = service.complete(session.uploadId());
        progress.sessions.clear();
        clock.current = clock.current.plus(Duration.ofDays(2));

        UploadService restarted = newService();
        assertThat(restarted.status(session.uploadId()).status()).isEqualTo(UploadStatus.COMPLETED);
        assertThat(restarted.status(session.uploadId()).assetId()).isEqualTo(asset.assetId());
        assertThat(restarted.complete(session.uploadId())).isEqualTo(asset);
        assertThat(restarted.asset(asset.assetId())).isEqualTo(asset);
        assertThat(restarted.assets()).containsExactly(asset);
        assertThat(objects.bytes.get(asset.objectKey())).containsExactly(FILE);
    }

    @Test
    void wrongPartDigestIsRejectedBeforeWritingAnObjectOrRecordingProgress() {
        var session = service.initialize(request());
        byte[] content = part(0);

        assertThatThrownBy(() -> service.uploadPart(session.uploadId(), 0, "f".repeat(64), content.length,
                new ByteArrayInputStream(content))).isInstanceOfSatisfying(UploadException.class,
                error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        assertThat(objects.bytes).isEmpty();
        assertThat(service.status(session.uploadId()).uploadedChunks()).isEmpty();
    }

    @Test
    void wrongWholeFileFingerprintPreventsCompletionEvenWhenAllPartsWereUploaded() {
        var request = request();
        var session = service.initialize(new UploadService.InitializeRequest(request.requestKey(), request.originalName(),
                request.fileSize(), request.contentType(), "f".repeat(64)));
        uploadAll(session.uploadId());

        assertThatThrownBy(() -> service.complete(session.uploadId())).isInstanceOfSatisfying(UploadException.class,
                error -> assertThat(error.getCode()).isEqualTo("FILE_MISMATCH"));

        assertThat(newRepository().findAssetByUploadId(session.uploadId())).isEmpty();
        assertThat(objects.bytes).doesNotContainKey(finalKey(session.uploadId()));
    }

    @Test
    void changedStoredPartIsDetectedDuringMergeAndNeverBecomesASuccessfulAsset() {
        var session = service.initialize(request());
        uploadAll(session.uploadId());
        objects.bytes.put(partKey(session.uploadId(), 0), new byte[]{9, 2, 3, 4});

        assertThatThrownBy(() -> service.complete(session.uploadId())).isInstanceOfSatisfying(UploadException.class,
                error -> assertThat(error.getCode()).isEqualTo("FILE_MISMATCH"));

        assertThat(newRepository().findAssetByUploadId(session.uploadId())).isEmpty();
        assertThat(objects.bytes).doesNotContainKey(finalKey(session.uploadId()));
    }

    @Test
    void reusingInitializationKeyForAnotherFileIsRejectedWithoutCreatingAnotherSession() {
        var request = request();
        service.initialize(request);
        var changed = new UploadService.InitializeRequest(request.requestKey(), "另一个文件.mp4", FILE.length,
                "video/mp4", request.fileFingerprint());

        assertThatThrownBy(() -> service.initialize(changed)).isInstanceOfSatisfying(UploadException.class,
                error -> assertThat(error.getCode()).isEqualTo("FILE_MISMATCH"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM upload_session", Integer.class)).isEqualTo(1);
    }

    @Test
    void initializationFailureRetriesTheSamePersistentSessionInsteadOfCreatingAnother() {
        var request = request();
        progress.failNextInitialize = true;
        assertThatThrownBy(() -> service.initialize(request)).isInstanceOf(UploadException.class);
        String uploadId = jdbc.queryForObject("SELECT upload_id FROM upload_session", String.class);
        assertThat(newRepository().findSession(uploadId).orElseThrow().status()).isEqualTo(UploadStatus.INITIALIZING);

        var recovered = newService().initialize(request);

        assertThat(recovered.uploadId()).isEqualTo(uploadId);
        assertThat(recovered.status()).isEqualTo(UploadStatus.UPLOADING);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM upload_session", Integer.class)).isEqualTo(1);
    }

    @Test
    void lostProgressForAnActiveUploadIsNotReinitializedAsZeroSuccessfulParts() {
        var request = request();
        var session = service.initialize(request);
        uploadPart(service, session.uploadId(), 0);
        progress.sessions.clear();

        assertThatThrownBy(() -> newService().initialize(request)).isInstanceOfSatisfying(UploadException.class,
                error -> assertThat(error.getCode()).isEqualTo("UPLOAD_PROGRESS_MISSING"));

        assertThat(progress.initializations).isEqualTo(1);
        assertThat(objects.bytes).containsKey(partKey(session.uploadId(), 0));
        assertThat(newRepository().findSession(session.uploadId()).orElseThrow().status()).isEqualTo(UploadStatus.UPLOADING);
    }

    private UploadRepository newRepository() {
        return new UploadRepository(new JdbcTemplate(dataSource), new DataSourceTransactionManager(dataSource));
    }

    private UploadService newService() { return new UploadService(newRepository(), progress, objects, properties, clock); }

    private static UploadService.InitializeRequest request() {
        String hashes = hash(part(0)) + hash(part(1));
        return new UploadService.InitializeRequest(UUID.randomUUID().toString(), "字节样例.mp4", FILE.length,
                "video/mp4", hash(hashes.getBytes(StandardCharsets.UTF_8)));
    }

    private void uploadAll(String uploadId) {
        uploadPart(service, uploadId, 0);
        uploadPart(service, uploadId, 1);
    }

    private static void uploadPart(UploadService target, String uploadId, int index) {
        byte[] content = part(index);
        target.uploadPart(uploadId, index, hash(content), content.length, new ByteArrayInputStream(content));
    }

    private static byte[] part(int index) {
        return Arrays.copyOfRange(FILE, index * CHUNK_SIZE, Math.min(FILE.length, (index + 1) * CHUNK_SIZE));
    }

    private static String hash(byte[] content) { return UploadService.hex(UploadService.sha256().digest(content)); }
    private static String partKey(String uploadId, int index) { return "uploads/" + uploadId + "/parts/" + index; }
    private static String finalKey(String uploadId) { return "videos/" + uploadId + "/original"; }

    private static class MutableClock extends Clock {
        private Instant current = Instant.parse("2026-11-15T02:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }

    private static class MemoryProgress implements UploadProgressStore {
        private final Map<String, Map<Integer, String>> sessions = new HashMap<>();
        private boolean failNextRecord;
        private boolean failNextInitialize;
        private int initializations;

        @Override public void initialize(UploadSession session) {
            initializations++;
            if (failNextInitialize) {
                failNextInitialize = false;
                throw new UploadException(HttpStatus.SERVICE_UNAVAILABLE, "UPLOAD_PROGRESS_UNAVAILABLE", "测试初始化失败");
            }
            sessions.putIfAbsent(session.uploadId(), new HashMap<>());
        }
        @Override public Map<Integer, String> snapshot(UploadSession session) {
            Map<Integer, String> value = sessions.get(session.uploadId());
            if (value == null) throw new UploadException(HttpStatus.SERVICE_UNAVAILABLE, "UPLOAD_PROGRESS_MISSING", "测试进度不存在");
            return Map.copyOf(value);
        }
        @Override public void recordPart(UploadSession session, int index, String sha256) {
            if (failNextRecord) {
                failNextRecord = false;
                throw new UploadException(HttpStatus.SERVICE_UNAVAILABLE, "UPLOAD_PROGRESS_UNAVAILABLE", "测试进度写入失败");
            }
            sessions.get(session.uploadId()).put(index, sha256);
        }
        @Override public void renew(UploadSession session) { snapshot(session); }
        @Override public void delete(String uploadId) { sessions.remove(uploadId); }
    }

    private static class MemoryObjects implements UploadObjectStore {
        private final Map<String, byte[]> bytes = new HashMap<>();
        private final Map<String, String> hashes = new HashMap<>();
        private final Map<String, Integer> writes = new HashMap<>();
        private boolean failNextPut;

        @Override public void ensureBucket() { }
        @Override public Optional<StoredObject> stat(String key) {
            return bytes.containsKey(key) ? Optional.of(new StoredObject(bytes.get(key).length, hashes.get(key))) : Optional.empty();
        }
        @Override public void put(String key, Path file, String contentType, String sha256) {
            if (failNextPut) {
                failNextPut = false;
                throw new UploadException(HttpStatus.SERVICE_UNAVAILABLE, "OBJECT_STORAGE_UNAVAILABLE", "测试对象写入失败");
            }
            try { bytes.put(key, Files.readAllBytes(file)); }
            catch (IOException error) { throw new IllegalStateException(error); }
            hashes.put(key, sha256);
            writes.merge(key, 1, Integer::sum);
        }
        @Override public InputStream open(String key, long offset, long length) {
            byte[] content = bytes.get(key);
            if (content == null) throw new UploadException(HttpStatus.SERVICE_UNAVAILABLE, "OBJECT_STORAGE_UNAVAILABLE", "测试对象不存在");
            return new ByteArrayInputStream(Arrays.copyOfRange(content, Math.toIntExact(offset),
                    Math.toIntExact(Math.min(content.length, offset + length))));
        }
        @Override public void delete(String key) { bytes.remove(key); hashes.remove(key); }
        @Override public String bucket() { return "test-videos"; }
    }
}
