package com.vibeteaching.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibeteaching.VibeTeachingApplication;
import com.vibeteaching.parsing.ParseException;
import com.vibeteaching.parsing.ParseModels.Input;
import com.vibeteaching.parsing.ParseModels.Kind;
import com.vibeteaching.parsing.ParseModels.PendingDispatch;
import com.vibeteaching.parsing.ParseModels.TaskStatus;
import com.vibeteaching.parsing.ParseModels.UnitStatus;
import com.vibeteaching.parsing.ParseModels.WorkMessage;
import com.vibeteaching.parsing.persistence.ParseRepository;
import com.vibeteaching.upload.model.UploadSession;
import com.vibeteaching.upload.model.UploadStatus;
import com.vibeteaching.upload.model.VideoAsset;
import com.vibeteaching.upload.persistence.UploadRepository;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 显式开启时在本机 MySQL 独立临时库中验证 Mapper 的写入与重读。 */
@EnabledIfSystemProperty(named = "mysql.mapper.integration", matches = "true")
class MySqlMapperIntegrationTest {
    private static final Pattern LOCAL_MYSQL = Pattern.compile("^(jdbc:mysql://(?:127\\.0\\.0\\.1|localhost)(?::[0-9]+)?/)[^?]+(\\?.*)?$");
    private static final Instant START = Instant.parse("2026-11-19T03:12:13.123Z");
    private static final Duration LEASE = Duration.ofSeconds(30);

    @Test
    void writesAndReopensUploadsAndParsingThroughMyBatisInAnIsolatedDatabase() throws Exception {
        Path configPath = Path.of(System.getProperty("mysql.mapper.config", "config/application-dev.yaml")).toAbsolutePath();
        var properties = new YamlPropertySourceLoader().load("mapper-dev",
                new FileSystemResource(configPath));
        DataSourceProperties config = new Binder(ConfigurationPropertySources.from(properties))
                .bind("spring.datasource", Bindable.of(DataSourceProperties.class)).get();
        var url = LOCAL_MYSQL.matcher(config.getUrl());
        if (!url.matches()) throw new IllegalStateException("真实仓储检查要求本机 MySQL 的 dev URL");
        String schema = "videosight_mapper_" + UUID.randomUUID().toString().replace("-", "");
        String options = url.group(2) == null ? "" : url.group(2);
        var admin = new JdbcTemplate(new DriverManagerDataSource(config.getUrl(), config.getUsername(), config.getPassword()));
        admin.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4");
        try {
            String testUrl = url.group(1) + schema + options;
            var source = new DriverManagerDataSource(testUrl, config.getUsername(), config.getPassword());
            var jdbc = new JdbcTemplate(source);
            Flyway flyway = Flyway.configure().dataSource(source).locations("classpath:db/migration").load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(3);

            UploadRepository uploads = RepositoryTestSupport.uploads(source);
            VideoAsset asset = uploadAndTestRollback(uploads, jdbc);
            ParseRepository parses = RepositoryTestSupport.parses(source);
            String taskId = parseAndTestRecovery(parses, asset.assetId());
            assertThat(jdbc.queryForObject("SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s.%f') "
                    + "FROM parse_task WHERE task_id = ?", String.class, taskId)).isEqualTo("2026-11-19 03:12:13.123000");

            var reopened = new DriverManagerDataSource(testUrl, config.getUsername(), config.getPassword());
            assertThat(Flyway.configure().dataSource(reopened).locations("classpath:db/migration").load()
                    .migrate().migrationsExecuted).isZero();
            assertThat(RepositoryTestSupport.uploads(reopened).findAsset(asset.assetId())).contains(asset);
            ParseRepository reread = RepositoryTestSupport.parses(reopened);
            assertThat(reread.findTask(taskId)).hasValueSatisfying(task -> {
                assertThat(task.status()).isEqualTo(TaskStatus.SUCCEEDED);
                assertThat(task.createdAt()).isEqualTo(START);
            });
            assertThat(reread.units(taskId)).extracting(unit -> unit.text())
                    .containsExactlyInAnyOrder(null, "保存的画面", "确认重试后的语音");
            verifyHistoryThroughHttp(configPath, testUrl, asset.assetId(), taskId);
        } finally {
            admin.execute("DROP DATABASE `" + schema + "`");
        }
    }

    private VideoAsset uploadAndTestRollback(UploadRepository uploads, JdbcTemplate jdbc) {
        String uploadId = UUID.randomUUID().toString();
        UploadSession candidate = new UploadSession(uploadId, UUID.randomUUID().toString(), "课程.mp4",
                10, 10, 1, "a".repeat(64), "video/mp4", UploadStatus.INITIALIZING,
                START.plusSeconds(3600), START, START);
        assertThat(uploads.createOrFind(candidate)).isEqualTo(candidate);
        assertThat(uploads.createOrFind(candidate)).isEqualTo(candidate);
        uploads.updateSession(uploadId, UploadStatus.FINALIZING, candidate.expiresAt());
        jdbc.execute("ALTER TABLE upload_session ADD CONSTRAINT test_block_complete CHECK (status <> 'COMPLETED')");
        assertThatThrownBy(() -> uploads.complete(candidate, "videos", "video/saved.mp4", "b".repeat(64)))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("test_block_complete");
        assertThat(uploads.findAssetByUploadId(uploadId)).isEmpty();
        assertThat(uploads.findSession(uploadId)).hasValueSatisfying(session ->
                assertThat(session.status()).isEqualTo(UploadStatus.FINALIZING));
        jdbc.execute("ALTER TABLE upload_session DROP CHECK test_block_complete");
        VideoAsset asset = uploads.complete(candidate, "videos", "video/saved.mp4", "b".repeat(64));
        assertThat(asset.assetId()).isPositive();
        assertThat(uploads.complete(candidate, "videos", "video/saved.mp4", "b".repeat(64))).isEqualTo(asset);
        assertThat(uploads.findCleanupPending(5)).extracting(UploadSession::uploadId).containsExactly(uploadId);
        uploads.markCleaned(uploadId);
        return asset;
    }

    private void verifyHistoryThroughHttp(Path configPath, String testUrl, long assetId, String taskId) throws Exception {
        try (var application = new SpringApplicationBuilder(VibeTeachingApplication.class).run(
                "--spring.config.location=classpath:/application.yaml," + configPath.toUri(),
                "--spring.profiles.active=dev", "--spring.datasource.url=" + testUrl,
                "--app.upload.enabled=true", "--app.parse.enabled=false", "--server.address=127.0.0.1", "--server.port=0")) {
            int port = ((WebServerApplicationContext) application).getWebServer().getPort();
            RestClient client = RestClient.create("http://127.0.0.1:" + port);
            ObjectMapper json = application.getBean(ObjectMapper.class);
            String asset = client.get().uri("/api/videos/{assetId}", assetId).retrieve().body(String.class);
            assertThat(json.readTree(asset).path("assetId").asLong()).isEqualTo(assetId);
            String history = client.get().uri("/api/videos/{assetId}/parse", assetId).retrieve().body(String.class);
            var task = json.readTree(history);
            assertThat(task.path("taskId").asText()).isEqualTo(taskId);
            assertThat(task.path("status").asText()).isEqualTo("SUCCEEDED");
            assertThat(task.path("units").findValuesAsText("text")).contains("保存的画面", "确认重试后的语音");
        }
    }

    private String parseAndTestRecovery(ParseRepository parses, long assetId) throws Exception {
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        String taskId;
        try {
            var first = pool.submit(() -> {
                start.await();
                return parses.createOrFind(assetId, "mapper-test", UUID.randomUUID().toString(), START).taskId();
            });
            var second = pool.submit(() -> {
                start.await();
                return parses.createOrFind(assetId, "mapper-test", UUID.randomUUID().toString(), START).taskId();
            });
            start.countDown();
            taskId = first.get(15, TimeUnit.SECONDS);
            assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(taskId);
        } finally {
            pool.shutdownNow();
        }

        WorkMessage prepare = pending(parses, Kind.PREPARE, START);
        assertThat(parses.claim(prepare, "prepare-old", START, LEASE)).isPresent();
        assertThat(parses.claim(prepare, "prepare-duplicate", START, LEASE)).isEmpty();
        Instant next = START.plusSeconds(1);
        assertThat(parses.fail(prepare, "prepare-old", "TEST_RETRY", "重试", true, false, 3, next, START)).isTrue();
        parses.dispatchSent(prepare, next);
        WorkMessage retry = pending(parses, Kind.PREPARE, next);
        assertThat(retry.generation()).isEqualTo(prepare.generation() + 1);
        assertThat(parses.claim(prepare, "prepare-late", next, LEASE)).isEmpty();
        assertThat(parses.claim(retry, "prepare-new", next, LEASE)).isPresent();

        Input audio = new Input(Kind.ASR, 0, "parsing/audio.wav", 0, 1000);
        Input frame = new Input(Kind.OCR, 0, "parsing/frame.jpg", 0, 0);
        assertThatThrownBy(() -> parses.completePreparation(retry, "prepare-new", 1000, true,
                List.of(audio, frame, frame), next)).isInstanceOf(DuplicateKeyException.class);
        assertThat(parses.units(taskId)).singleElement().satisfies(unit -> {
            assertThat(unit.status()).isEqualTo(UnitStatus.RUNNING);
            assertThat(unit.leaseToken()).isEqualTo("prepare-new");
        });
        assertThat(parses.completePreparation(retry, "prepare-new", 1000, true, List.of(audio, frame), next)).isTrue();

        WorkMessage ocr = pending(parses, Kind.OCR, next);
        assertThat(parses.claim(ocr, "ocr", next, LEASE)).isPresent();
        assertThat(parses.complete(ocr, "ocr", "保存的画面", next)).isTrue();
        assertThat(parses.complete(ocr, "stale-ocr", "过期写入", next)).isFalse();
        WorkMessage asr = pending(parses, Kind.ASR, next);
        assertThat(parses.claim(asr, "asr", next, LEASE)).isPresent();
        Instant expired = next.plus(LEASE).plusSeconds(1);
        assertThat(parses.recover(expired, Duration.ofMinutes(5))).isEqualTo(1);
        assertThat(parses.units(taskId)).filteredOn(unit -> unit.kind() == Kind.ASR).singleElement()
                .satisfies(unit -> assertThat(unit.status()).isEqualTo(UnitStatus.UNCERTAIN));
        assertThatThrownBy(() -> parses.retry(taskId, false, expired)).isInstanceOfSatisfying(ParseException.class,
                error -> assertThat(error.code()).isEqualTo("ASR_RESUBMIT_CONFIRMATION"));
        parses.retry(taskId, true, expired);
        WorkMessage confirmed = pending(parses, Kind.ASR, expired);
        assertThat(parses.claim(confirmed, "confirmed", expired, LEASE)).isPresent();
        assertThat(parses.complete(confirmed, "confirmed", "确认重试后的语音", expired)).isTrue();
        return taskId;
    }

    private WorkMessage pending(ParseRepository parses, Kind kind, Instant now) {
        return parses.pendingDispatches(now, 20).stream().map(PendingDispatch::message)
                .filter(message -> message.kind() == kind).findFirst().orElseThrow();
    }
}
