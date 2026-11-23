package com.vibeteaching.parsing;

import com.vibeteaching.persistence.RepositoryTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibeteaching.parsing.ParseModels.Input;
import com.vibeteaching.parsing.ParseModels.Kind;
import com.vibeteaching.parsing.ParseModels.PendingDispatch;
import com.vibeteaching.parsing.ParseModels.TaskStatus;
import com.vibeteaching.parsing.ParseModels.UnitStatus;
import com.vibeteaching.parsing.ParseModels.WorkMessage;
import com.vibeteaching.parsing.config.ParseProperties;
import com.vibeteaching.parsing.media.AsrClient;
import com.vibeteaching.parsing.media.MediaPreparation;
import com.vibeteaching.parsing.media.OcrClient;
import com.vibeteaching.parsing.media.RecognitionException;
import com.vibeteaching.parsing.mq.ParseDispatchScheduler;
import com.vibeteaching.parsing.persistence.ParseMapper;
import com.vibeteaching.parsing.persistence.ParseRepository;
import com.vibeteaching.upload.model.UploadSession;
import com.vibeteaching.upload.model.UploadStatus;
import com.vibeteaching.upload.model.VideoAsset;
import com.vibeteaching.upload.persistence.UploadRepository;
import com.vibeteaching.upload.storage.UploadObjectStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实迁移、仓储、工作者和派发器协作；媒体/模型替身只控制外部调用与故障。 */
class ParseWorkflowTest {
    private static final Instant START = Instant.parse("2026-11-17T08:00:00.123Z");
    @TempDir Path directory;
    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;
    private UploadRepository uploads;
    private ParseRepository repository;
    private ParseProperties properties;
    private ParseService service;
    private FixturePreparation preparation;
    private MutableClock clock;
    private long assetId;
    private final List<ParseWorker> workers = new ArrayList<>();

    @BeforeEach
    void createDurableWorkflow() throws Exception {
        Files.writeString(directory.resolve("fixture-tool.exe"), "fixture executable path; never launched");
        properties = properties(Map.of());
        clock = new MutableClock(START);
        source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
        uploads = RepositoryTestSupport.uploads(source);
        repository = repository();
        service = new ParseService(repository, uploads, properties, clock);
        preparation = new FixturePreparation(properties);
        assetId = asset();
    }

    @AfterEach
    void stopHeartbeatThreads() { workers.forEach(ParseWorker::close); }

    @Test
    void duplicateMqDeliveriesDoNotRepeatPreparationOrPaidRecognition() {
        AtomicInteger asrCalls = new AtomicInteger();
        AtomicInteger ocrCalls = new AtomicInteger();
        ParseWorker worker = worker(repository, path -> {
            asrCalls.incrementAndGet(); return "课程语音";
        }, path -> { ocrCalls.incrementAndGet(); return "课件文字"; });
        String taskId = service.submit(assetId, UUID.randomUUID().toString()).taskId();
        WorkMessage prepare = pending(Kind.PREPARE);
        worker.consume(prepare);
        worker.consume(prepare);
        List<PendingDispatch> recognition = repository.pendingDispatches(clock.instant(), 20);
        for (PendingDispatch event : recognition) {
            worker.consume(event.message());
            worker.consume(event.message());
        }
        assertThat(preparation.calls.get()).isEqualTo(1);
        assertThat(asrCalls.get()).isEqualTo(1);
        assertThat(ocrCalls.get()).isEqualTo(1);
        assertThat(service.byId(taskId).status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(service.byId(taskId).units()).filteredOn(unit -> unit.kind() != Kind.PREPARE)
                .extracting(ParseService.UnitView::text).containsExactlyInAnyOrder("课程语音", "课件文字");
    }

    @Test
    void retryingPartialTaskKeepsSavedAsrAndOnlyCallsTheFailedOcrAgain() {
        AtomicInteger asrCalls = new AtomicInteger();
        AtomicInteger ocrCalls = new AtomicInteger();
        ParseWorker worker = worker(repository, path -> {
            asrCalls.incrementAndGet(); return "已保存语音";
        }, path -> {
            if (ocrCalls.incrementAndGet() == 1) throw new RecognitionException("OCR_UNAVAILABLE", "画面识别失败", false, false);
            return "重试后的画面文字";
        });
        String taskId = service.submit(assetId, UUID.randomUUID().toString()).taskId();
        worker.consume(pending(Kind.PREPARE));
        worker.consume(pending(Kind.ASR));
        worker.consume(pending(Kind.OCR));
        assertThat(service.byId(taskId).status()).isEqualTo(TaskStatus.PARTIAL);

        service.retry(taskId, false);
        assertThat(repository.pendingDispatches(clock.instant(), 20)).extracting(event -> event.message().kind()).containsExactly(Kind.OCR);
        worker.consume(pending(Kind.OCR));
        ParseService restarted = new ParseService(repository(), uploads, properties, clock);
        assertThat(restarted.byId(taskId).status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(restarted.byId(taskId).units()).filteredOn(unit -> unit.kind() == Kind.ASR)
                .extracting(ParseService.UnitView::text).containsExactly("已保存语音");
        assertThat(preparation.calls.get()).isEqualTo(1);
        assertThat(asrCalls.get()).isEqualTo(1);
        assertThat(ocrCalls.get()).isEqualTo(2);
    }

    @Test
    void externalSuccessFollowedByDatabaseFailureNeverReportsSavedSuccessOrAutomaticallyChargesAgain() {
        AtomicInteger asrCalls = new AtomicInteger();
        ParseWorker worker = worker(repository, path -> {
            asrCalls.incrementAndGet(); return "供应商已成功识别";
        }, path -> "课件");
        String taskId = service.submit(assetId, UUID.randomUUID().toString()).taskId();
        worker.consume(pending(Kind.PREPARE));
        WorkMessage asr = pending(Kind.ASR);
        jdbc.execute("ALTER TABLE parse_unit ADD CONSTRAINT test_reject_saved_asr CHECK (kind <> 'ASR' OR status <> 'SUCCEEDED')");
        assertThatThrownBy(() -> worker.consume(asr)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(service.byId(taskId).status()).isEqualTo(TaskStatus.RUNNING);
        assertThat(service.byId(taskId).units()).filteredOn(unit -> unit.kind() == Kind.ASR)
                .allMatch(unit -> unit.status() == UnitStatus.RUNNING && unit.text() == null);
        jdbc.execute("ALTER TABLE parse_unit DROP CONSTRAINT test_reject_saved_asr");
        worker.consume(asr);
        assertThat(asrCalls.get()).isEqualTo(1);

        clock.advance(properties.leaseDuration().plusSeconds(1));
        repository.recover(clock.instant(), properties.requeueAfter());
        worker.consume(pending(Kind.OCR));
        ParseService.TaskView recovered = new ParseService(repository(), uploads, properties, clock).byId(taskId);
        assertThat(recovered.status()).isEqualTo(TaskStatus.PARTIAL);
        assertThat(recovered.units()).filteredOn(unit -> unit.kind() == Kind.ASR)
                .allMatch(unit -> unit.status() == UnitStatus.UNCERTAIN && unit.text() == null);
        assertThatThrownBy(() -> service.retry(taskId, false)).isInstanceOf(ParseException.class)
                .hasMessageContaining("重复计费");
        assertThat(asrCalls.get()).isEqualTo(1);
    }

    @Test
    void restartWithParsingDisabledAndChangedProfileStillReadsSavedHistoryWithoutModelCalls() {
        AtomicInteger calls = new AtomicInteger();
        ParseWorker worker = worker(repository, path -> { calls.incrementAndGet(); return "历史语音"; },
                path -> { calls.incrementAndGet(); return "历史画面"; });
        String taskId = service.submit(assetId, UUID.randomUUID().toString()).taskId();
        worker.consume(pending(Kind.PREPARE));
        worker.consume(pending(Kind.ASR));
        worker.consume(pending(Kind.OCR));
        ParseProperties changed = properties(Map.of("app.parse.enabled", "false", "app.parse.profile-version", "v2",
                "app.parse.asr-url", "", "app.parse.asr-api-key", ""));
        ParseService restarted = new ParseService(repository(), RepositoryTestSupport.uploads(source), changed, clock);
        assertThat(restarted.settings().ready()).isFalse();
        assertThat(restarted.byId(taskId).status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(restarted.byAsset(assetId).taskId()).isEqualTo(taskId);
        assertThat(restarted.byAsset(assetId).units()).extracting(ParseService.UnitView::text)
                .contains("历史语音", "历史画面");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void relayMayRepeatASuccessfulSendAfterReceiptPersistenceFailsWithoutRepeatingRecognition() {
        AtomicInteger receiptFailures = new AtomicInteger();
        ParseRepository unreliableReceipt = new ParseRepository(RepositoryTestSupport.mapperSession(source).getMapper(ParseMapper.class),
                new DataSourceTransactionManager(source)) {
            @Override public void dispatchSent(WorkMessage message, Instant now) {
                if (receiptFailures.getAndIncrement() == 0) throw new TransientDataAccessResourceException("可控回执保存失败");
                super.dispatchSent(message, now);
            }
        };
        AtomicInteger asrCalls = new AtomicInteger();
        AtomicInteger ocrCalls = new AtomicInteger();
        ParseWorker worker = worker(unreliableReceipt, path -> { asrCalls.incrementAndGet(); return "语音"; },
                path -> { ocrCalls.incrementAndGet(); return "画面"; });
        ArrayDeque<WorkMessage> broker = new ArrayDeque<>();
        ParseDispatchScheduler relay = new ParseDispatchScheduler(unreliableReceipt, broker::addLast, properties, clock);
        String taskId = service.submit(assetId, UUID.randomUUID().toString()).taskId();
        relay.dispatch();
        assertThat(broker).hasSize(1);
        clock.advance(properties.retryDelay());
        relay.dispatch();
        assertThat(broker).hasSize(2);
        assertThat(broker.peekFirst()).isEqualTo(broker.peekLast());
        while (!broker.isEmpty()) worker.consume(broker.removeFirst());
        relay.dispatch();
        while (!broker.isEmpty()) worker.consume(broker.removeFirst());
        assertThat(service.byId(taskId).status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(preparation.calls.get()).isEqualTo(1);
        assertThat(asrCalls.get()).isEqualTo(1);
        assertThat(ocrCalls.get()).isEqualTo(1);
        assertThat(repository().pendingDispatches(clock.instant(), 100)).isEmpty();
    }

    private WorkMessage pending(Kind kind) {
        return repository.pendingDispatches(clock.instant(), 100).stream().map(PendingDispatch::message)
                .filter(message -> message.kind() == kind).findFirst().orElseThrow();
    }

    private ParseWorker worker(ParseRepository backing, AsrClient asr, OcrClient ocr) {
        ParseWorker worker = new ParseWorker(backing, uploads, preparation, asr, ocr, properties, service, clock);
        workers.add(worker);
        return worker;
    }

    private ParseRepository repository() { return RepositoryTestSupport.parses(source); }

    private ParseProperties properties(Map<String, String> changes) {
        Map<String, String> values = new HashMap<>();
        values.put("app.parse.enabled", "true");
        values.put("app.parse.ffmpeg-command", directory.resolve("fixture-tool.exe").toString());
        values.put("app.parse.ffprobe-command", directory.resolve("fixture-tool.exe").toString());
        values.put("app.parse.ocr-command", directory.resolve("fixture-tool.exe").toString());
        values.put("app.parse.asr-url", "https://example.invalid/api/v1/services/aigc/multimodal-generation/generation");
        values.put("app.parse.asr-model", "qwen-audio-3.1-asr-flash");
        values.put("app.parse.asr-api-key", "test-fixture-value");
        values.put("app.parse.nameserver", "127.0.0.1:9876");
        values.put("app.parse.lease-duration", "30s");
        values.putAll(changes);
        return new Binder(new MapConfigurationPropertySource(values)).bind("app.parse", Bindable.of(ParseProperties.class)).get();
    }

    private long asset() {
        UploadSession session = uploads.createOrFind(new UploadSession(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "课程.mp4", 10, 10, 1, "a".repeat(64), "video/mp4", UploadStatus.INITIALIZING, START.plusSeconds(3600), START, START));
        uploads.updateSession(session.uploadId(), UploadStatus.FINALIZING, session.expiresAt());
        return uploads.complete(session, "videos", "video/" + session.uploadId(), "b".repeat(64)).assetId();
    }

    private static class FixturePreparation extends MediaPreparation {
        private final AtomicInteger calls = new AtomicInteger();
        FixturePreparation(ParseProperties properties) { super(new UnusedStorage(), properties, new ObjectMapper()); }
        @Override public PreparedMedia prepare(VideoAsset asset, String taskId, String token) {
            calls.incrementAndGet();
            return new PreparedMedia(1000, true, List.of(new Input(Kind.ASR, 0, "parsing/fixture/audio.wav", 0, 1000),
                    new Input(Kind.OCR, 0, "parsing/fixture/frame.jpg", 0, 0)));
        }
        @Override public <T> T withInput(String key, Function<Path, T> action) { return action.apply(Path.of(key)); }
    }

    /** 媒体边界已由 FixturePreparation 控制；若意外落入真实存储调用应立即失败。 */
    private static class UnusedStorage implements UploadObjectStore {
        @Override public void ensureBucket() { throw new AssertionError("工作流替身不应访问对象存储"); }
        @Override public Optional<StoredObject> stat(String key) { throw new AssertionError("工作流替身不应访问对象存储"); }
        @Override public void put(String key, Path path, String contentType, String sha256) { throw new AssertionError("工作流替身不应访问对象存储"); }
        @Override public InputStream open(String key, long offset, long length) { throw new AssertionError("工作流替身不应访问对象存储"); }
        @Override public void delete(String key) { throw new AssertionError("工作流替身不应访问对象存储"); }
        @Override public String bucket() { throw new AssertionError("工作流替身不应访问对象存储"); }
    }

    private static class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
