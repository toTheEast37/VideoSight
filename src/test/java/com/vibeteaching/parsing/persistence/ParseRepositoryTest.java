package com.vibeteaching.parsing.persistence;

import com.vibeteaching.persistence.RepositoryTestSupport;
import com.vibeteaching.parsing.ParseException;
import com.vibeteaching.parsing.ParseModels.Input;
import com.vibeteaching.parsing.ParseModels.Kind;
import com.vibeteaching.parsing.ParseModels.PendingDispatch;
import com.vibeteaching.parsing.ParseModels.Task;
import com.vibeteaching.parsing.ParseModels.TaskStatus;
import com.vibeteaching.parsing.ParseModels.Unit;
import com.vibeteaching.parsing.ParseModels.UnitStatus;
import com.vibeteaching.parsing.ParseModels.WorkMessage;
import com.vibeteaching.upload.model.UploadSession;
import com.vibeteaching.upload.model.UploadStatus;
import com.vibeteaching.upload.persistence.UploadRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParseRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-11-17T03:00:00.123Z");
    private static final Duration LEASE = Duration.ofSeconds(30);
    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;
    private ParseRepository repository;
    private long assetId;

    @BeforeEach
    void useProductionMigrations() {
        source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000", "sa", "");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
        repository = restarted();
        assetId = seedAsset();
    }

    @Test
    void concurrentDifferentRequestKeysBindOneDurableTaskAndOneInitialMessage() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Task> first = workers.submit(() -> {
                start.await();
                return repository.createOrFind(assetId, "v1", "request-a", NOW);
            });
            Future<Task> second = workers.submit(() -> {
                start.await();
                return restarted().createOrFind(assetId, "v1", "request-b", NOW);
            });
            start.countDown();
            Task task = first.get(10, TimeUnit.SECONDS);
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(task);
            assertThat(restarted().findTask(task.taskId())).contains(task);
            assertThat(count("parse_task")).isEqualTo(1);
            assertThat(count("parse_request")).isEqualTo(2);
            assertThat(count("parse_unit")).isEqualTo(1);
            assertThat(restarted().pendingDispatches(NOW, 10)).hasSize(1);
            assertThat(repository.createOrFind(assetId, "v1", "request-b", NOW.plusSeconds(1))).isEqualTo(task);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void requestKeyCannotSilentlyMoveToAnotherAssetOrProfile() {
        Task original = repository.createOrFind(assetId, "v1", "same-request", NOW);
        long otherAsset = seedAsset();
        assertThatThrownBy(() -> repository.createOrFind(otherAsset, "v1", "same-request", NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("同一请求键");
        assertThatThrownBy(() -> repository.createOrFind(assetId, "v2", "same-request", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(count("parse_task")).isEqualTo(1);
        assertThat(repository.findTask(original.taskId())).contains(original);
    }

    @Test
    void latestAssetHistoryCanBeDiscoveredAfterTheConfiguredProfileHasChanged() {
        Task first = repository.createOrFind(assetId, "old-profile", "old-request", NOW);
        Task latest = repository.createOrFind(assetId, "newer-profile", "newer-request", NOW.plusSeconds(1));
        assertThat(restarted().findByAsset(assetId, "not-yet-used-profile")).isEmpty();
        assertThat(restarted().findLatestByAsset(assetId)).contains(latest);
        assertThat(restarted().findTask(first.taskId())).contains(first);
        assertThat(restarted().findLatestByAsset(seedAsset())).isEmpty();
    }

    @Test
    void unitFailureRollsBackAcceptanceAndAllowsTheSameRequestToRetry() {
        jdbc.execute("ALTER TABLE parse_unit ADD CONSTRAINT test_reject_prepare CHECK (kind <> 'PREPARE')");
        assertThatThrownBy(() -> repository.createOrFind(assetId, "v1", "retry-acceptance", NOW))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("parse_task")).isZero();
        assertThat(count("parse_request")).isZero();
        assertThat(count("parse_unit")).isZero();
        jdbc.execute("ALTER TABLE parse_unit DROP CONSTRAINT test_reject_prepare");
        Task recovered = repository.createOrFind(assetId, "v1", "retry-acceptance", NOW);
        assertThat(restarted().findTask(recovered.taskId())).isPresent();
        assertThat(restarted().pendingDispatches(NOW, 10)).hasSize(1);
    }

    @Test
    void preparationAndAllChildMessagesCommitTogetherOrRemainRetryable() {
        Task task = task();
        WorkMessage prepare = message(task, Kind.PREPARE);
        repository.claim(prepare, "prepare-worker", NOW, LEASE).orElseThrow();
        jdbc.execute("ALTER TABLE parse_unit ADD CONSTRAINT test_reject_ocr CHECK (kind <> 'OCR')");
        assertThatThrownBy(() -> repository.completePreparation(prepare, "prepare-worker", 10_000, true, inputs(), NOW))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(restarted().units(task.taskId())).hasSize(1).allMatch(unit -> unit.status() == UnitStatus.RUNNING);
        assertThat(restarted().findTask(task.taskId()).orElseThrow().durationMs()).isNull();
        assertThat(count("parse_unit")).isEqualTo(1);

        jdbc.execute("ALTER TABLE parse_unit DROP CONSTRAINT test_reject_ocr");
        assertThat(repository.completePreparation(prepare, "prepare-worker", 10_000, true, inputs(), NOW)).isTrue();
        assertThat(restarted().units(task.taskId())).hasSize(3);
        assertThat(restarted().pendingDispatches(NOW, 10)).hasSize(2);
        assertThat(repository.completePreparation(prepare, "prepare-worker", 10_000, true, inputs(), NOW)).isFalse();
        assertThat(count("parse_unit")).isEqualTo(3);
    }

    @Test
    void leaseRecoveryRejectsOldDeliveryAndOldWorkerWrites() {
        Task task = prepared();
        WorkMessage ocr = message(task, Kind.OCR);
        assertThat(repository.claim(ocr, "old-worker", NOW, LEASE)).isPresent();
        assertThat(repository.claim(ocr, "duplicate-worker", NOW, LEASE)).isEmpty();

        Instant resumedAt = NOW.plusSeconds(31);
        assertThat(repository.recover(resumedAt, Duration.ofHours(1))).isEqualTo(1);
        Unit recovered = unit(task, Kind.OCR);
        assertThat(recovered.generation()).isEqualTo(1);
        assertThat(repository.complete(ocr, "old-worker", "迟到结果", resumedAt)).isFalse();
        assertThat(repository.renew(ocr, "old-worker", resumedAt.plusSeconds(30))).isFalse();
        assertThat(repository.claim(ocr, "another-old-worker", resumedAt, LEASE)).isEmpty();
        WorkMessage current = work(recovered);
        assertThat(repository.claim(current, "new-worker", resumedAt, LEASE)).isPresent();
        assertThat(repository.complete(current, "wrong-token", "错误结果", resumedAt)).isFalse();
        assertThat(repository.complete(current, "new-worker", "已保存画面文字", resumedAt)).isTrue();
        assertThat(restarted().units(task.taskId())).filteredOn(unit -> unit.kind() == Kind.OCR)
                .extracting(Unit::text).containsExactly("已保存画面文字");
    }

    @Test
    void interruptedAsrWaitsForManualRetryAndKeepsSuccessfulOcrAcrossRestart() {
        Task task = prepared();
        WorkMessage asr = message(task, Kind.ASR);
        WorkMessage ocr = message(task, Kind.OCR);
        repository.claim(asr, "uncertain-asr", NOW, LEASE).orElseThrow();
        repository.claim(ocr, "ocr-worker", NOW, LEASE).orElseThrow();
        repository.complete(ocr, "ocr-worker", "课件内容", NOW);

        Instant later = NOW.plusSeconds(31);
        assertThat(repository.recover(later, Duration.ofHours(1))).isEqualTo(1);
        assertThat(unit(task, Kind.ASR).status()).isEqualTo(UnitStatus.UNCERTAIN);
        assertThat(restarted().findTask(task.taskId()).orElseThrow().status()).isEqualTo(TaskStatus.PARTIAL);
        assertThat(repository.recover(later.plusSeconds(31), Duration.ofHours(1))).isZero();
        assertThat(repository.complete(asr, "uncertain-asr", "迟到语音", later)).isFalse();

        Task retried = restarted().retry(task.taskId(), true, later);
        assertThat(retried.status()).isEqualTo(TaskStatus.RUNNING);
        assertThat(unit(task, Kind.OCR).text()).isEqualTo("课件内容");
        assertThat(unit(task, Kind.OCR).generation()).isZero();
        assertThat(unit(task, Kind.ASR).generation()).isEqualTo(1);
        WorkMessage retry = work(unit(task, Kind.ASR));
        repository.claim(retry, "confirmed-retry", later, LEASE).orElseThrow();
        repository.complete(retry, "confirmed-retry", "语音内容", later);
        assertThat(restarted().findTask(task.taskId()).orElseThrow().status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(restarted().units(task.taskId())).filteredOn(unit -> unit.kind() != Kind.PREPARE)
                .extracting(Unit::text).containsExactlyInAnyOrder("语音内容", "课件内容");
    }

    @Test
    void boundedRetriesHaveDurableDueTimeAndManualRetryOnlyResetsFailedUnit() {
        Task task = prepared();
        WorkMessage ocr = message(task, Kind.OCR);
        repository.claim(ocr, "attempt-one", NOW, LEASE).orElseThrow();
        Instant due = NOW.plusSeconds(60);
        repository.fail(ocr, "attempt-one", "OCR_UNAVAILABLE", "临时错误", true, false, 2, due, NOW);
        WorkMessage second = work(unit(task, Kind.OCR));
        assertThat(repository.claim(second, "too-early", NOW.plusSeconds(59), LEASE)).isEmpty();
        assertThat(repository.pendingDispatches(NOW, 100)).noneMatch(pending -> pending.message().equals(second));
        assertThat(restarted().pendingDispatches(due, 100)).anyMatch(pending -> pending.message().equals(second));
        repository.claim(second, "attempt-two", due, LEASE).orElseThrow();
        repository.fail(second, "attempt-two", "OCR_UNAVAILABLE", "仍不可用", true, false, 2, due, due);
        assertThat(unit(task, Kind.OCR).status()).isEqualTo(UnitStatus.FAILED);
        assertThat(unit(task, Kind.OCR).attempts()).isEqualTo(2);
        assertThat(repository.fail(second, "attempt-two", "LATE", "迟到错误", true, false, 2, due, due)).isFalse();
        repository.retry(task.taskId(), false, due);
        assertThat(unit(task, Kind.OCR).attempts()).isZero();
        assertThat(unit(task, Kind.ASR).generation()).isZero();
    }

    @Test
    void retryRechecksTheLatestUncertainStateInsideItsTransactionBeforeChangingAnyUnit() {
        Task task = prepared();
        WorkMessage initialAsr = message(task, Kind.ASR);
        WorkMessage initialOcr = message(task, Kind.OCR);
        repository.claim(initialAsr, "first-asr", NOW, LEASE).orElseThrow();
        repository.claim(initialOcr, "first-ocr", NOW, LEASE).orElseThrow();
        repository.fail(initialAsr, "first-asr", "ASR_UNAVAILABLE", "尚未提交", false, false, 1, NOW, NOW);
        repository.fail(initialOcr, "first-ocr", "OCR_UNAVAILABLE", "画面不可用", false, false, 1, NOW, NOW);
        // 请求 A 在此看到了普通 FAILED，尚未进入仓储的重试事务。
        assertThat(repository.findTask(task.taskId()).orElseThrow().status()).isEqualTo(TaskStatus.FAILED);

        // 请求 B 先完成一轮重试，使 ASR 的最新状态变为 UNCERTAIN。
        repository.retry(task.taskId(), true, NOW);
        WorkMessage repeatedAsr = message(task, Kind.ASR);
        WorkMessage repeatedOcr = message(task, Kind.OCR);
        repository.claim(repeatedAsr, "second-asr", NOW, LEASE).orElseThrow();
        repository.claim(repeatedOcr, "second-ocr", NOW, LEASE).orElseThrow();
        repository.fail(repeatedAsr, "second-asr", "ASR_RESULT_UNCERTAIN", "受理结果待确认", false, true, 1, NOW, NOW);
        repository.fail(repeatedOcr, "second-ocr", "OCR_UNAVAILABLE", "仍不可用", false, false, 1, NOW, NOW);
        List<Unit> beforeRetry = restarted().units(task.taskId());
        int unitCount = count("parse_unit");

        // 请求 A 的旧检查不能授权重提：仓储应拒绝，连普通 FAILED OCR 也不能部分重排。
        assertThatThrownBy(() -> restarted().retry(task.taskId(), false, NOW))
                .isInstanceOfSatisfying(ParseException.class, error ->
                        assertThat(error.code()).isEqualTo("ASR_RESUBMIT_CONFIRMATION"));
        assertThat(restarted().units(task.taskId())).isEqualTo(beforeRetry);
        assertThat(count("parse_unit")).isEqualTo(unitCount);
        assertThat(restarted().findTask(task.taskId()).orElseThrow().status()).isEqualTo(TaskStatus.FAILED);
    }

    @Test
    void lostQueuedMessageIsRepublishedWithoutInvalidatingWorkAlreadyWaitingInTheBroker() {
        Task task = task();
        PendingDispatch original = repository.pendingDispatches(NOW, 1).get(0);
        repository.dispatchSent(original.message(), NOW);
        assertThat(restarted().pendingDispatches(NOW, 10)).isEmpty();
        assertThat(repository.recover(NOW.plusSeconds(59), Duration.ofMinutes(1))).isZero();
        Instant later = NOW.plusSeconds(61);
        assertThat(repository.recover(later, Duration.ofMinutes(1))).isEqualTo(1);
        PendingDispatch replacement = restarted().pendingDispatches(later, 10).get(0);
        assertThat(replacement.message()).isEqualTo(original.message());
        // 再经历一轮长排队，最初位于队首的消息仍然有效，不会反复被替代消息挤到队尾。
        Instant muchLater = later.plusSeconds(61);
        assertThat(repository.recover(muchLater, Duration.ofMinutes(1))).isEqualTo(1);
        assertThat(count("parse_unit")).isEqualTo(1);
        assertThat(repository.claim(original.message(), "original-arrives-first", muchLater, LEASE)).isPresent();
        assertThat(repository.claim(replacement.message(), "duplicate-arrives-later", muchLater, LEASE)).isEmpty();
        assertThat(repository.pendingDispatches(muchLater, 10)).isEmpty();
    }

    @Test
    void preparationSuccessDoesNotTurnAllFailedRecognitionUnitsIntoPartialSuccess() {
        Task task = prepared();
        WorkMessage asr = message(task, Kind.ASR);
        WorkMessage ocr = message(task, Kind.OCR);
        repository.claim(asr, "asr", NOW, LEASE).orElseThrow();
        repository.claim(ocr, "ocr", NOW, LEASE).orElseThrow();
        repository.fail(asr, "asr", "ASR_REJECTED", "格式不支持", false, false, 2, NOW, NOW);
        repository.fail(ocr, "ocr", "OCR_UNAVAILABLE", "不可用", false, false, 2, NOW, NOW);
        assertThat(restarted().findTask(task.taskId()).orElseThrow().status()).isEqualTo(TaskStatus.FAILED);
    }

    @Test
    void successfulPreparationRetryClearsPreviousFailureAndLeavesNoObsoleteDispatchWork() {
        Task task = task();
        WorkMessage first = message(task, Kind.PREPARE);
        repository.claim(first, "first", NOW, LEASE).orElseThrow();
        repository.fail(first, "first", "STORAGE_UNAVAILABLE", "暂不可用", true, false, 2, NOW, NOW);
        WorkMessage second = message(task, Kind.PREPARE);
        assertThat(repository.pendingDispatches(NOW, 10)).extracting(PendingDispatch::message).containsExactly(second);
        repository.claim(second, "second", NOW, LEASE).orElseThrow();
        repository.completePreparation(second, "second", 10_000, false, List.of(), NOW);
        Unit prepared = unit(task, Kind.PREPARE);
        assertThat(prepared.errorCode()).isNull();
        assertThat(prepared.message()).isNull();
        assertThat(repository.findTask(task.taskId()).orElseThrow().status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(restarted().pendingDispatches(NOW, 10)).isEmpty();
    }

    @Test
    void lostPublishReceiptAndRepeatedCallbacksCannotRestartAnAlreadyRunningOrCompletedUnit() {
        Task task = task();
        PendingDispatch outgoing = repository.pendingDispatches(NOW, 1).get(0);
        // Broker 已收到消息，但发送回执或本地 dispatched_at 保存失败。另一轮发送可能再次见到同一消息。
        repository.dispatchFailed(outgoing.message(), NOW);
        PendingDispatch resent = restarted().pendingDispatches(NOW, 1).get(0);
        assertThat(resent.message()).isEqualTo(outgoing.message());
        repository.claim(resent.message(), "winner", NOW, LEASE).orElseThrow();
        assertThat(repository.claim(outgoing.message(), "duplicate", NOW, LEASE)).isEmpty();
        assertThat(repository.pendingDispatches(NOW, 10)).isEmpty();
        repository.completePreparation(resent.message(), "winner", 10_000, false, List.of(), NOW);
        repository.dispatchSent(outgoing.message(), NOW);
        repository.dispatchSent(outgoing.message(), NOW.plusSeconds(1));
        repository.dispatchFailed(outgoing.message(), NOW.plusSeconds(2));
        assertThat(restarted().pendingDispatches(NOW.plusSeconds(3), 10)).isEmpty();
        assertThat(repository.claim(outgoing.message(), "late-duplicate", NOW.plusSeconds(3), LEASE)).isEmpty();
        assertThat(restarted().findTask(task.taskId()).orElseThrow().status()).isEqualTo(TaskStatus.SUCCEEDED);
    }

    @Test
    void latePublishCallbacksCannotMarkOrPostponeTheNextGeneration() {
        Task task = task();
        WorkMessage original = message(task, Kind.PREPARE);
        repository.claim(original, "first", NOW, LEASE).orElseThrow();
        Instant due = NOW.plusSeconds(60);
        repository.fail(original, "first", "STORAGE_UNAVAILABLE", "稍后重试", true, false, 3, due, NOW);
        WorkMessage current = message(task, Kind.PREPARE);

        repository.dispatchSent(original, NOW);
        repository.dispatchFailed(original, due.plusSeconds(3600));
        assertThat(restarted().pendingDispatches(due.minusSeconds(1), 10)).isEmpty();
        assertThat(restarted().pendingDispatches(due, 10)).containsExactly(new PendingDispatch(current, 0));

        repository.dispatchFailed(current, due.plusSeconds(10));
        assertThat(restarted().pendingDispatches(due, 10)).isEmpty();
        assertThat(restarted().pendingDispatches(due.plusSeconds(10), 10))
                .containsExactly(new PendingDispatch(current, 1));
        assertThat(unit(task, Kind.PREPARE).attempts()).isEqualTo(1);
        repository.dispatchSent(current, due.plusSeconds(10));
        repository.dispatchFailed(current, due.plusSeconds(3600));
        assertThat(restarted().pendingDispatches(due.plusSeconds(3600), 10)).isEmpty();
    }

    @Test
    void silentVideoAndEmptyOcrTextStillProduceSavedSuccessfulResult() {
        Task task = task();
        WorkMessage prepare = message(task, Kind.PREPARE);
        repository.claim(prepare, "prepare", NOW, LEASE).orElseThrow();
        repository.completePreparation(prepare, "prepare", 10_000, false,
                List.of(new Input(Kind.OCR, 0, "parse/frame.png", 0, 0)), NOW);
        assertThat(unit(task, Kind.ASR).status()).isEqualTo(UnitStatus.SKIPPED);
        WorkMessage ocr = message(task, Kind.OCR);
        repository.claim(ocr, "ocr", NOW, LEASE).orElseThrow();
        repository.complete(ocr, "ocr", "", NOW);
        assertThat(restarted().findTask(task.taskId()).orElseThrow().status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(unit(task, Kind.OCR).text()).isEmpty();
    }

    private Task task() { return repository.createOrFind(assetId, "v1", UUID.randomUUID().toString(), NOW); }
    private Task prepared() {
        Task task = task();
        WorkMessage prepare = message(task, Kind.PREPARE);
        repository.claim(prepare, "prepare", NOW, LEASE).orElseThrow();
        repository.completePreparation(prepare, "prepare", 10_000, true, inputs(), NOW);
        return task;
    }
    private List<Input> inputs() {
        return List.of(new Input(Kind.ASR, 0, "parse/audio.wav", 0, 10_000),
                new Input(Kind.OCR, 0, "parse/frame.png", 0, 0));
    }
    private WorkMessage message(Task task, Kind kind) { return work(unit(task, kind)); }
    private Unit unit(Task task, Kind kind) {
        return restarted().units(task.taskId()).stream().filter(unit -> unit.kind() == kind).findFirst().orElseThrow();
    }
    private WorkMessage work(Unit unit) { return new WorkMessage(unit.taskId(), unit.kind(), unit.unitIndex(), unit.generation()); }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private ParseRepository restarted() { return RepositoryTestSupport.parses(source); }
    private long seedAsset() {
        UploadRepository uploads = RepositoryTestSupport.uploads(source);
        UploadSession session = uploads.createOrFind(new UploadSession(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "课程.mp4", 10, 10, 1, "a".repeat(64), "video/mp4", UploadStatus.INITIALIZING, NOW.plusSeconds(3600), NOW, NOW));
        uploads.updateSession(session.uploadId(), UploadStatus.FINALIZING, session.expiresAt());
        return uploads.complete(session, "videos", "video/" + session.uploadId(), "b".repeat(64)).assetId();
    }
}
