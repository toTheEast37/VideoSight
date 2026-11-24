package com.vibeteaching.parsing.persistence;

import com.vibeteaching.parsing.ParseException;
import com.vibeteaching.parsing.ParseModels.Input;
import com.vibeteaching.parsing.ParseModels.Kind;
import com.vibeteaching.parsing.ParseModels.PendingDispatch;
import com.vibeteaching.parsing.ParseModels.Task;
import com.vibeteaching.parsing.ParseModels.TaskStatus;
import com.vibeteaching.parsing.ParseModels.Unit;
import com.vibeteaching.parsing.ParseModels.UnitStatus;
import com.vibeteaching.parsing.ParseModels.WorkMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 所有任务推进只持有短数据库事务；供应商、FFmpeg 和消息发送在调用方执行。 */
@Repository
@ConditionalOnProperty(name = "app.upload.enabled", havingValue = "true")
@DependsOn("uploadFlyway")
public class ParseRepository {
    private final ParseMapper mapper;
    private final TransactionTemplate transaction;

    public ParseRepository(ParseMapper mapper, PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public Task createOrFind(long assetId, String profile, String requestKey, Instant now) {
        requireText(profile, 128, "解析配置版本");
        requireText(requestKey, 128, "请求键");
        // 两个唯一键可能分别与并发请求冲突。失败事务回滚后再重试绑定，不能在失败事务中继续查。
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                return Objects.requireNonNull(transaction.execute(status -> {
                    Optional<Task> requested = findRequest(requestKey);
                    if (requested.isPresent()) {
                        verifyBinding(requested.get(), assetId, profile);
                        return requested.get();
                    }
                    Task task = findByAsset(assetId, profile).orElse(null);
                    if (task == null) {
                        String id = UUID.randomUUID().toString();
                        mapper.insertTask(id, assetId, profile, now);
                        mapper.insertUnit(id, new Input(Kind.PREPARE, 0, null, 0, 0), UnitStatus.QUEUED, now);
                        task = findTask(id).orElseThrow();
                    }
                    mapper.bindRequest(requestKey, task.taskId(), now);
                    return task;
                }));
            } catch (DuplicateKeyException conflict) {
                if (attempt == 3) throw conflict;
            }
        }
        throw new IllegalStateException("解析任务初始化失败");
    }

    public Optional<Task> findTask(String taskId) {
        return Optional.ofNullable(mapper.findTask(taskId));
    }

    public Optional<Task> findByAsset(long assetId, String profile) {
        return Optional.ofNullable(mapper.findByAsset(assetId, profile));
    }

    public Optional<Task> findLatestByAsset(long assetId) {
        return Optional.ofNullable(mapper.findLatestByAsset(assetId));
    }

    public List<Unit> units(String taskId) {
        return mapper.units(taskId);
    }

    public List<PendingDispatch> pendingDispatches(Instant now, int limit) {
        if (limit < 1) throw new IllegalArgumentException("查询条数必须大于零");
        return mapper.pendingDispatches(now, limit).stream().map(ParseMapper.DispatchRow::toPendingDispatch).toList();
    }

    public void dispatchSent(WorkMessage message, Instant now) {
        mapper.dispatchSent(message, now);
    }

    public void dispatchFailed(WorkMessage message, Instant nextAttempt) {
        mapper.dispatchFailed(message, nextAttempt);
    }

    public Optional<Unit> claim(WorkMessage message, String token, Instant now, Duration lease) {
        requireText(token, 128, "执行令牌");
        if (lease.isNegative() || lease.isZero()) throw new IllegalArgumentException("执行租期必须大于零");
        return Objects.requireNonNull(transaction.execute(status -> {
            if (lockTask(message.taskId()).isEmpty()) return Optional.empty();
            int changed = mapper.claim(message, token, now, now.plus(lease));
            if (changed == 0) return Optional.empty();
            refreshTask(message.taskId(), now);
            return findUnit(message);
        }));
    }

    public boolean renew(WorkMessage message, String token, Instant until) {
        // 与 recover 使用同一个任务行锁，避免恢复线程读到旧租期后覆盖刚刚成功的心跳。
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (lockTask(message.taskId()).isEmpty()) return false;
            return mapper.renew(message, token, until) == 1;
        }));
    }

    public boolean completePreparation(WorkMessage message, String token, long durationMs, boolean hasAudio,
                                       List<Input> inputs, Instant now) {
        if (message.kind() != Kind.PREPARE || durationMs < 0) throw new IllegalArgumentException("预处理参数不合法");
        for (Input input : inputs) {
            if (input.kind() == Kind.PREPARE || input.unitIndex() < 0 || input.startMs() < 0
                    || input.endMs() < input.startMs() || input.endMs() > durationMs
                    || input.inputKey() == null || input.inputKey().isBlank()
                    || (!hasAudio && input.kind() == Kind.ASR)) {
                throw new IllegalArgumentException("识别分片参数不合法");
            }
        }
        if (hasAudio && inputs.stream().noneMatch(input -> input.kind() == Kind.ASR)) {
            throw new IllegalArgumentException("存在音轨时必须保存 ASR 分片");
        }
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (lockTask(message.taskId()).isEmpty() || !owned(message, token)) return false;
            finishOwned(message, token, UnitStatus.SUCCEEDED, null, null, null, now);
            mapper.updateDuration(message.taskId(), durationMs);
            for (Input input : inputs) {
                mapper.insertUnit(message.taskId(), input, UnitStatus.QUEUED, now);
            }
            if (!hasAudio) {
                mapper.insertUnit(message.taskId(), new Input(Kind.ASR, 0, null, 0, durationMs), UnitStatus.SKIPPED, now);
            }
            refreshTask(message.taskId(), now);
            return true;
        }));
    }

    public boolean complete(WorkMessage message, String token, String text, Instant now) {
        if (message.kind() == Kind.PREPARE) throw new IllegalArgumentException("预处理必须同时保存分片");
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (lockTask(message.taskId()).isEmpty() || !owned(message, token)) return false;
            finishOwned(message, token, UnitStatus.SUCCEEDED, text == null ? "" : text, null, null, now);
            refreshTask(message.taskId(), now);
            return true;
        }));
    }

    public boolean fail(WorkMessage message, String token, String code, String errorMessage, boolean retryable,
                        boolean uncertain, int maxAttempts, Instant next, Instant now) {
        if (maxAttempts < 1) throw new IllegalArgumentException("重试次数必须大于零");
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (lockTask(message.taskId()).isEmpty() || !owned(message, token)) return false;
            Unit unit = findUnit(message).orElseThrow();
            if (!uncertain && retryable && unit.attempts() < maxAttempts) {
                requeue(unit, next, now, false);
                mapper.updateUnitError(message, truncate(code, 64), truncate(errorMessage, 1000));
            } else {
                finishOwned(message, token, uncertain ? UnitStatus.UNCERTAIN : UnitStatus.FAILED,
                        null, truncate(code, 64), truncate(errorMessage, 1000), now);
            }
            refreshTask(message.taskId(), now);
            return true;
        }));
    }

    public Task retry(String taskId, boolean confirmUncertain, Instant now) {
        return Objects.requireNonNull(transaction.execute(status -> {
            lockTask(taskId).orElseThrow(() -> new NoSuchElementException("解析任务不存在"));
            List<Unit> currentUnits = units(taskId);
            // 服务层读取状态后，另一请求可能已经重试并得到“不确定”结果。
            // 必须在本次重排所持的任务行锁内重新核对，不能让旧请求绕过重复计费确认。
            if (!confirmUncertain) {
                Unit uncertain = currentUnits.stream().filter(unit -> unit.status() == UnitStatus.UNCERTAIN)
                        .findFirst().orElse(null);
                if (uncertain != null) {
                    throw new ParseException(HttpStatus.CONFLICT, uncertain.kind().name() + "_RESUBMIT_CONFIRMATION",
                            "云端识别可能已被受理，确认可能重复计费后才能重试");
                }
            }
            for (Unit unit : currentUnits) {
                if (unit.status() == UnitStatus.FAILED || unit.status() == UnitStatus.UNCERTAIN) {
                    requeue(unit, now, now, true);
                }
            }
            refreshTask(taskId, now);
            return findTask(taskId).orElseThrow();
        }));
    }

    public int recover(Instant now, Duration queuedTimeout, String localOcrProfile) {
        if (queuedTimeout.isNegative() || queuedTimeout.isZero()) throw new IllegalArgumentException("排队恢复期限必须大于零");
        Instant staleBefore = now.minus(queuedTimeout);
        List<String> taskIds = mapper.recoverableTaskIds(now, staleBefore);
        int recovered = 0;
        for (String taskId : taskIds) {
            Integer count = transaction.execute(status -> {
                Task task = lockTask(taskId).orElse(null);
                if (task == null) return 0;
                boolean localOcr = task.profileVersion().equals(localOcrProfile);
                int changed = 0;
                for (Unit unit : units(taskId)) {
                    if (unit.status() == UnitStatus.RUNNING && unit.leaseUntil() != null && !unit.leaseUntil().isAfter(now)) {
                        if (unit.kind() == Kind.ASR || unit.kind() == Kind.OCR && !localOcr) {
                            // 请求可能已被供应商受理。没有确认结果时禁止自动再次付费提交。
                            finishOwned(work(unit), unit.leaseToken(), UnitStatus.UNCERTAIN, null,
                                    unit.kind().name() + "_RESULT_UNCERTAIN",
                                    "执行中断，云端识别可能已受理；请确认后手动重试", now);
                        } else {
                            requeue(unit, now, now, false);
                        }
                        changed++;
                    } else if (unit.status() == UnitStatus.QUEUED && !unit.nextRunAt().isAfter(now)) {
                        Instant updatedAt = mapper.unitUpdatedAt(work(unit));
                        if (updatedAt != null && !updatedAt.isAfter(staleBefore)) {
                            republishQueued(unit, now);
                            changed++;
                        }
                    }
                }
                if (changed > 0) refreshTask(taskId, now);
                return changed;
            });
            recovered += count == null ? 0 : count;
        }
        return recovered;
    }

    private Optional<Task> findRequest(String requestKey) {
        return Optional.ofNullable(mapper.findRequest(requestKey));
    }

    private void verifyBinding(Task task, long assetId, String profile) {
        if (task.assetId() != assetId || !task.profileVersion().equals(profile)) {
            throw new IllegalArgumentException("同一请求键不能用于其他视频或解析配置");
        }
    }

    private Optional<Task> lockTask(String taskId) {
        return Optional.ofNullable(mapper.lockTask(taskId));
    }

    private Optional<Unit> findUnit(WorkMessage message) {
        return Optional.ofNullable(mapper.findUnit(message));
    }

    private boolean owned(WorkMessage message, String token) {
        return findUnit(message).filter(unit -> unit.status() == UnitStatus.RUNNING && Objects.equals(unit.leaseToken(), token)).isPresent();
    }

    private void finishOwned(WorkMessage message, String token, UnitStatus unitStatus, String text,
                             String code, String errorMessage, Instant now) {
        int changed = mapper.finishOwned(message, token, unitStatus, text, code, errorMessage, now);
        if (changed != 1) throw new IllegalStateException("解析执行权已改变，不能保存旧结果");
    }

    private void requeue(Unit unit, Instant next, Instant now, boolean resetAttempts) {
        mapper.requeue(work(unit), resetAttempts ? 0 : unit.attempts(), next, now);
    }

    private void republishQueued(Unit unit, Instant now) {
        // 排队久不等于旧消息失效。保留代数，原消息和补发消息任意一个先到都能领取，
        // 避免长队列中的旧消息不断被废弃、替代消息不断排到队尾而饥饿。
        mapper.republishQueued(work(unit), now);
    }

    private void refreshTask(String taskId, Instant now) {
        List<Unit> units = units(taskId);
        boolean running = units.stream().anyMatch(unit -> unit.status() == UnitStatus.RUNNING);
        boolean queued = units.stream().anyMatch(unit -> unit.status() == UnitStatus.QUEUED);
        boolean prepared = units.stream().anyMatch(unit -> unit.kind() == Kind.PREPARE && unit.status() == UnitStatus.SUCCEEDED);
        boolean failed = units.stream().anyMatch(unit -> unit.status() == UnitStatus.FAILED || unit.status() == UnitStatus.UNCERTAIN);
        boolean recognized = units.stream().anyMatch(unit -> unit.kind() != Kind.PREPARE && unit.status() == UnitStatus.SUCCEEDED);
        TaskStatus taskStatus = running || (queued && prepared) ? TaskStatus.RUNNING
                : queued ? TaskStatus.QUEUED : failed ? (recognized ? TaskStatus.PARTIAL : TaskStatus.FAILED) : TaskStatus.SUCCEEDED;
        mapper.updateTaskStatus(taskId, taskStatus, now);
    }

    private static WorkMessage work(Unit unit) { return new WorkMessage(unit.taskId(), unit.kind(), unit.unitIndex(), unit.generation()); }
    private static String truncate(String value, int length) { return value == null || value.length() <= length ? value : value.substring(0, length); }
    private static void requireText(String value, int max, String label) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException(label + "不能为空或超长");
    }
}
