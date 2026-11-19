package com.vibeteaching.parsing.persistence;

import com.vibeteaching.persistence.RepositoryTestSupport;
import com.vibeteaching.parsing.ParseModels.Kind;
import com.vibeteaching.parsing.ParseModels.PendingDispatch;
import com.vibeteaching.parsing.ParseModels.WorkMessage;
import com.vibeteaching.upload.model.UploadSession;
import com.vibeteaching.upload.model.UploadStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class ParseDispatchMigrationTest {
    private static final Instant NOW = Instant.parse("2026-11-19T03:00:00.123Z");

    @Test
    void upgradesAnExistingV2DatabaseWithoutLosingResultsOrChangingWhichGenerationCanBeDispatched() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("2").load().migrate();
        var jdbc = new JdbcTemplate(source);
        var uploads = RepositoryTestSupport.uploads(source);
        var session = uploads.createOrFind(new UploadSession(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "课程.mp4", 10, 10, 1, "a".repeat(64), "video/mp4", UploadStatus.INITIALIZING,
                NOW.plusSeconds(3600), NOW, NOW));
        uploads.updateSession(session.uploadId(), UploadStatus.FINALIZING, session.expiresAt());
        long assetId = uploads.complete(session, "videos", "video/" + session.uploadId(), "b".repeat(64)).assetId();
        String task = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO parse_task VALUES (?, ?, 'v1', 'RUNNING', 20000, ?, ?)",
                task, assetId, utc(NOW), utc(NOW));
        jdbc.update("INSERT INTO parse_request VALUES ('legacy-request', ?, ?)", task, utc(NOW));
        unit(jdbc, task, "OCR", 0, 2, "QUEUED", null);
        unit(jdbc, task, "OCR", 1, 0, "QUEUED", null);
        unit(jdbc, task, "ASR", 0, 0, "SUCCEEDED", "已经付费并保存的语音");
        unit(jdbc, task, "ASR", 1, 4, "UNCERTAIN", null);
        unit(jdbc, task, "OCR", 2, 1, "RUNNING", null);
        unit(jdbc, task, "ASR", 2, 0, "SKIPPED", null);
        outgoing(jdbc, task, "OCR", 0, 1, 99, NOW.plusSeconds(999), NOW);
        outgoing(jdbc, task, "OCR", 0, 2, 3, NOW.plusSeconds(30), null);
        outgoing(jdbc, task, "OCR", 1, 0, 0, NOW, NOW);
        outgoing(jdbc, task, "ASR", 0, 0, 0, NOW, NOW);
        outgoing(jdbc, task, "ASR", 1, 3, 7, NOW.plusSeconds(999), NOW);

        String originalColumns = "task_id, kind, unit_index, generation, status, attempts, input_key, start_ms, end_ms, "
                + "result_text, error_code, error_message, lease_token, lease_until, next_run_at, updated_at";
        var originalUnits = jdbc.queryForList("SELECT " + originalColumns + " FROM parse_unit ORDER BY kind, unit_index");
        var originalTasks = jdbc.queryForList("SELECT * FROM parse_task");
        var originalRequests = jdbc.queryForList("SELECT * FROM parse_request");
        var migration = Flyway.configure().dataSource(source).locations("classpath:db/migration").load();
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT " + originalColumns + " FROM parse_unit ORDER BY kind, unit_index"))
                .isEqualTo(originalUnits);
        assertThat(jdbc.queryForList("SELECT * FROM parse_task")).isEqualTo(originalTasks);
        assertThat(jdbc.queryForList("SELECT * FROM parse_request")).isEqualTo(originalRequests);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'parse_outbox'",
                Integer.class)).isZero();

        var repository = RepositoryTestSupport.parses(source);
        assertThat(repository.pendingDispatches(NOW, 10)).isEmpty();
        assertThat(repository.pendingDispatches(NOW.plusSeconds(30), 10)).containsExactly(
                new PendingDispatch(new WorkMessage(task, Kind.OCR, 0, 2), 3));
        assertThat(jdbc.queryForObject("SELECT next_dispatch_at FROM parse_unit WHERE kind = 'ASR' AND unit_index = 1",
                Timestamp.class).toLocalDateTime()).isEqualTo(utc(NOW));
        assertThat(jdbc.queryForObject("SELECT dispatched_at FROM parse_unit WHERE kind = 'ASR' AND unit_index = 1",
                Timestamp.class)).isNull();
        assertThat(migration.migrate().migrationsExecuted).isZero();
    }

    private void unit(JdbcTemplate jdbc, String task, String kind, int index, int generation, String status, String text) {
        jdbc.update("INSERT INTO parse_unit (task_id, kind, unit_index, generation, status, attempts, input_key, "
                        + "start_ms, end_ms, result_text, lease_token, lease_until, next_run_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 1, ?, 0, 10000, ?, ?, ?, ?, ?)",
                task, kind, index, generation, status, "parsing/legacy/" + kind + index, text,
                status.equals("RUNNING") ? "legacy-worker" : null,
                status.equals("RUNNING") ? utc(NOW.plusSeconds(300)) : null,
                utc(NOW), utc(NOW));
    }

    private void outgoing(JdbcTemplate jdbc, String task, String kind, int index, int generation,
                          int attempts, Instant due, Instant sent) {
        jdbc.update("INSERT INTO parse_outbox (task_id, kind, unit_index, generation, attempts, next_attempt_at, created_at, sent_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", task, kind, index, generation, attempts,
                utc(due), utc(NOW), sent == null ? null : utc(sent));
    }

    // 正式仓储使用 UTC Calendar 写入 DATETIME，旧版夹具同样存储 UTC 墙上时间。
    private static LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
}
