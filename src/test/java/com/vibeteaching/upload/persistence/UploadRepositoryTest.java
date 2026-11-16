package com.vibeteaching.upload.persistence;

import com.vibeteaching.upload.model.UploadSession;
import com.vibeteaching.upload.model.UploadStatus;
import com.vibeteaching.upload.model.VideoAsset;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadRepositoryTest {

    private static final Instant CREATED = Instant.parse("2026-11-15T02:00:00.123Z");
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private UploadRepository repository;

    @BeforeEach
    void createDatabaseUsingProductionMigration() {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        repository = newRepository();
    }

    @Test
    void concurrentInitializationUsesDatabaseUniqueConstraintToReturnOneSession() throws Exception {
        String requestKey = UUID.randomUUID().toString();
        UploadSession first = candidate(requestKey);
        UploadSession second = candidate(requestKey);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<UploadSession> firstResult = workers.submit(() -> {
                start.await();
                return repository.createOrFind(first);
            });
            Future<UploadSession> secondResult = workers.submit(() -> {
                start.await();
                return repository.createOrFind(second);
            });
            start.countDown();

            UploadSession actual = firstResult.get(10, TimeUnit.SECONDS);
            assertThat(secondResult.get(10, TimeUnit.SECONDS)).isEqualTo(actual);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM upload_session", Integer.class)).isEqualTo(1);
            assertThat(newRepository().findSession(actual.uploadId())).contains(actual);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void completedAssetCanBeReadByNewRepositoryAndRepeatedCompletionReturnsSameAsset() {
        UploadSession session = repository.createOrFind(candidate(UUID.randomUUID().toString()));
        repository.updateSession(session.uploadId(), UploadStatus.FINALIZING, session.expiresAt());

        VideoAsset saved = repository.complete(session, "videos", "assets/" + session.uploadId() + ".mp4", "b".repeat(64));

        UploadRepository restarted = newRepository();
        assertThat(restarted.findAsset(saved.assetId())).contains(saved);
        assertThat(restarted.findAssetByUploadId(session.uploadId())).contains(saved);
        assertThat(restarted.findSession(session.uploadId()).orElseThrow().status()).isEqualTo(UploadStatus.COMPLETED);
        assertThat(restarted.listAssets(20)).containsExactly(saved);
        assertThat(restarted.complete(session, "videos", "another-object", "c".repeat(64))).isEqualTo(saved);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM video_asset", Integer.class)).isEqualTo(1);
        assertThat(saved.sha256()).isNotEqualTo(session.fileFingerprint());
    }

    @Test
    void sessionUpdateFailureRollsBackInsertedAssetAndAllowsRetry() {
        UploadSession session = repository.createOrFind(candidate(UUID.randomUUID().toString()));
        repository.updateSession(session.uploadId(), UploadStatus.FINALIZING, session.expiresAt());
        // 让事务第二步被数据库拒绝，验证第一步的资产插入也一起回滚。
        jdbc.execute("ALTER TABLE upload_session ADD CONSTRAINT test_block_complete CHECK (status <> 'COMPLETED')");

        assertThatThrownBy(() -> repository.complete(session, "videos", "assets/final.mp4", "b".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(newRepository().findAssetByUploadId(session.uploadId())).isEmpty();
        assertThat(newRepository().findSession(session.uploadId()).orElseThrow().status()).isEqualTo(UploadStatus.FINALIZING);
        assertThat(newRepository().findCleanupPending(20)).isEmpty();
        jdbc.execute("ALTER TABLE upload_session DROP CONSTRAINT test_block_complete");

        VideoAsset recovered = repository.complete(session, "videos", "assets/final.mp4", "b".repeat(64));
        assertThat(newRepository().findAsset(recovered.assetId())).contains(recovered);
    }

    @Test
    void completedAssetRetainsPersistentCleanupWorkUntilDeletionIsAcknowledged() {
        UploadSession session = repository.createOrFind(candidate(UUID.randomUUID().toString()));
        repository.updateSession(session.uploadId(), UploadStatus.FINALIZING, session.expiresAt());
        VideoAsset asset = repository.complete(session, "videos", "assets/retained.mp4", "b".repeat(64));

        // 模拟进程在资产保存后、临时分片删除前退出，新的仓储仍可找回待清理工作。
        UploadRepository restarted = newRepository();
        assertThat(restarted.findAsset(asset.assetId())).contains(asset);
        assertThat(restarted.findCleanupPending(20)).extracting(UploadSession::uploadId).containsExactly(session.uploadId());

        restarted.markCleaned(session.uploadId());

        assertThat(newRepository().findCleanupPending(20)).isEmpty();
        assertThat(newRepository().findAsset(asset.assetId())).contains(asset);
    }

    @Test
    void expiryCandidatesExcludeCompletedAssetsAndAlreadyExpiredSessions() {
        UploadSession initializing = repository.createOrFind(candidate(UUID.randomUUID().toString()));
        UploadSession uploading = repository.createOrFind(candidate(UUID.randomUUID().toString()));
        UploadSession finalizing = repository.createOrFind(candidate(UUID.randomUUID().toString()));
        UploadSession expired = repository.createOrFind(candidate(UUID.randomUUID().toString()));
        UploadSession completed = repository.createOrFind(candidate(UUID.randomUUID().toString()));
        repository.updateSession(uploading.uploadId(), UploadStatus.UPLOADING, uploading.expiresAt());
        repository.updateSession(finalizing.uploadId(), UploadStatus.FINALIZING, finalizing.expiresAt());
        repository.updateSession(expired.uploadId(), UploadStatus.EXPIRED, expired.expiresAt());
        repository.updateSession(completed.uploadId(), UploadStatus.FINALIZING, completed.expiresAt());
        repository.complete(completed, "videos", "assets/completed.mp4", "b".repeat(64));

        List<UploadSession> candidates = repository.findExpired(CREATED.plusSeconds(3601), 100);

        assertThat(candidates).extracting(UploadSession::uploadId)
                .containsExactlyInAnyOrder(initializing.uploadId(), uploading.uploadId(), finalizing.uploadId());
        assertThat(repository.findAssetByUploadId(completed.uploadId())).isPresent();
    }

    private UploadRepository newRepository() {
        return new UploadRepository(new JdbcTemplate(dataSource), new DataSourceTransactionManager(dataSource));
    }

    private UploadSession candidate(String requestKey) {
        return new UploadSession(UUID.randomUUID().toString(), requestKey, "示例视频.mp4", 10,
                4, 3, "a".repeat(64), "video/mp4", UploadStatus.INITIALIZING,
                CREATED.plusSeconds(3600), CREATED, CREATED);
    }
}
