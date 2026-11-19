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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Calendar;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.TimeZone;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SqlTypeValue;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 所有任务推进只持有短数据库事务；供应商、FFmpeg 和消息发送在调用方执行。 */
@Repository
@ConditionalOnProperty(name = "app.upload.enabled", havingValue = "true")
@DependsOn("uploadFlyway")
public class ParseRepository {
    private static final String TASK_COLUMNS = "task_id, asset_id, profile_version, status, duration_ms, created_at, updated_at";
    private static final String UNIT_COLUMNS = "task_id, kind, unit_index, generation, status, attempts, input_key, "
            + "start_ms, end_ms, result_text, error_code, error_message, lease_token, lease_until, next_run_at";
    private static final RowMapper<Task> TASK_MAPPER = (rs, row) -> new Task(rs.getString("task_id"),
            rs.getLong("asset_id"), rs.getString("profile_version"), TaskStatus.valueOf(rs.getString("status")),
            rs.getObject("duration_ms") == null ? null : rs.getLong("duration_ms"),
            instant(rs, "created_at"), instant(rs, "updated_at"));
    private static final RowMapper<Unit> UNIT_MAPPER = (rs, row) -> new Unit(rs.getString("task_id"),
            Kind.valueOf(rs.getString("kind")), rs.getInt("unit_index"), rs.getInt("generation"),
            UnitStatus.valueOf(rs.getString("status")), rs.getInt("attempts"), rs.getString("input_key"),
            rs.getLong("start_ms"), rs.getLong("end_ms"), rs.getString("result_text"), rs.getString("error_code"),
            rs.getString("error_message"), rs.getString("lease_token"), instant(rs, "lease_until"),
            instant(rs, "next_run_at"));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public ParseRepository(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
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
                        jdbc.update("INSERT INTO parse_task (" + TASK_COLUMNS + ") VALUES (?, ?, ?, 'QUEUED', NULL, ?, ?)",
                                id, assetId, profile, timestamp(now), timestamp(now));
                        insertUnit(id, new Input(Kind.PREPARE, 0, null, 0, 0), UnitStatus.QUEUED, now);
                        task = findTask(id).orElseThrow();
                    }
                    jdbc.update("INSERT INTO parse_request (request_key, task_id, created_at) VALUES (?, ?, ?)",
                            requestKey, task.taskId(), timestamp(now));
                    return task;
                }));
            } catch (DuplicateKeyException conflict) {
                if (attempt == 3) throw conflict;
            }
        }
        throw new IllegalStateException("解析任务初始化失败");
    }

    public Optional<Task> findTask(String taskId) {
        return jdbc.query("SELECT " + TASK_COLUMNS + " FROM parse_task WHERE task_id = ?", TASK_MAPPER, taskId)
                .stream().findFirst();
    }

    public Optional<Task> findByAsset(long assetId, String profile) {
        return jdbc.query("SELECT " + TASK_COLUMNS + " FROM parse_task WHERE asset_id = ? AND profile_version = ?",
                TASK_MAPPER, assetId, profile).stream().findFirst();
    }

    public Optional<Task> findLatestByAsset(long assetId) {
        return jdbc.query("SELECT " + TASK_COLUMNS + " FROM parse_task WHERE asset_id = ? "
                + "ORDER BY created_at DESC, task_id DESC LIMIT 1", TASK_MAPPER, assetId).stream().findFirst();
    }

    public List<Unit> units(String taskId) {
        return jdbc.query("SELECT " + UNIT_COLUMNS + " FROM parse_unit WHERE task_id = ? ORDER BY kind, unit_index",
                UNIT_MAPPER, taskId);
    }

    public List<PendingDispatch> pendingDispatches(Instant now, int limit) {
        if (limit < 1) throw new IllegalArgumentException("查询条数必须大于零");
        return jdbc.query("SELECT task_id, kind, unit_index, generation, dispatch_attempts FROM parse_unit "
                        + "WHERE status = 'QUEUED' AND dispatched_at IS NULL AND next_dispatch_at <= ? "
                        + "AND next_run_at <= ? ORDER BY next_dispatch_at, task_id, kind, unit_index LIMIT ?",
                (rs, row) -> new PendingDispatch(new WorkMessage(rs.getString("task_id"),
                        Kind.valueOf(rs.getString("kind")), rs.getInt("unit_index"), rs.getInt("generation")),
                        rs.getInt("dispatch_attempts")), timestamp(now), timestamp(now), limit);
    }

    public void dispatchSent(WorkMessage message, Instant now) {
        jdbc.update("UPDATE parse_unit SET dispatched_at = ? WHERE task_id = ? AND kind = ? AND unit_index = ? "
                        + "AND generation = ? AND status = 'QUEUED' AND dispatched_at IS NULL",
                timestamp(now), message.taskId(), message.kind().name(), message.unitIndex(), message.generation());
    }

    public void dispatchFailed(WorkMessage message, Instant nextAttempt) {
        jdbc.update("UPDATE parse_unit SET dispatch_attempts = dispatch_attempts + 1, next_dispatch_at = ? "
                        + "WHERE task_id = ? AND kind = ? AND unit_index = ? AND generation = ? "
                        + "AND status = 'QUEUED' AND dispatched_at IS NULL", timestamp(nextAttempt),
                message.taskId(), message.kind().name(), message.unitIndex(), message.generation());
    }

    public Optional<Unit> claim(WorkMessage message, String token, Instant now, Duration lease) {
        requireText(token, 128, "执行令牌");
        if (lease.isNegative() || lease.isZero()) throw new IllegalArgumentException("执行租期必须大于零");
        return Objects.requireNonNull(transaction.execute(status -> {
            if (lockTask(message.taskId()).isEmpty()) return Optional.empty();
            int changed = jdbc.update("UPDATE parse_unit SET status = 'RUNNING', attempts = attempts + 1, "
                            + "lease_token = ?, lease_until = ?, updated_at = ? WHERE task_id = ? AND kind = ? "
                            + "AND unit_index = ? AND generation = ? AND status = 'QUEUED' AND next_run_at <= ?",
                    token, timestamp(now.plus(lease)), timestamp(now), message.taskId(), message.kind().name(),
                    message.unitIndex(), message.generation(), timestamp(now));
            if (changed == 0) return Optional.empty();
            refreshTask(message.taskId(), now);
            return findUnit(message);
        }));
    }

    public boolean renew(WorkMessage message, String token, Instant until) {
        // 与 recover 使用同一个任务行锁，避免恢复线程读到旧租期后覆盖刚刚成功的心跳。
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (lockTask(message.taskId()).isEmpty()) return false;
            return jdbc.update("UPDATE parse_unit SET lease_until = ? WHERE task_id = ? AND kind = ? AND unit_index = ? "
                            + "AND generation = ? AND status = 'RUNNING' AND lease_token = ?",
                    timestamp(until), message.taskId(), message.kind().name(), message.unitIndex(), message.generation(), token) == 1;
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
            jdbc.update("UPDATE parse_task SET duration_ms = ? WHERE task_id = ?", durationMs, message.taskId());
            for (Input input : inputs) {
                insertUnit(message.taskId(), input, UnitStatus.QUEUED, now);
            }
            if (!hasAudio) insertUnit(message.taskId(), new Input(Kind.ASR, 0, null, 0, durationMs), UnitStatus.SKIPPED, now);
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
                jdbc.update("UPDATE parse_unit SET error_code = ?, error_message = ? WHERE task_id = ? AND kind = ? AND unit_index = ?",
                        truncate(code, 64), truncate(errorMessage, 1000), unit.taskId(), unit.kind().name(), unit.unitIndex());
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
            if (!confirmUncertain && currentUnits.stream().anyMatch(unit -> unit.status() == UnitStatus.UNCERTAIN)) {
                throw new ParseException(HttpStatus.CONFLICT, "ASR_RESUBMIT_CONFIRMATION",
                        "语音识别可能已被受理，确认可能重复计费后才能重试");
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

    public int recover(Instant now, Duration queuedTimeout) {
        if (queuedTimeout.isNegative() || queuedTimeout.isZero()) throw new IllegalArgumentException("排队恢复期限必须大于零");
        Instant staleBefore = now.minus(queuedTimeout);
        List<String> taskIds = jdbc.query("SELECT DISTINCT task_id FROM parse_unit WHERE "
                        + "(status = 'RUNNING' AND lease_until <= ?) OR "
                        + "(status = 'QUEUED' AND next_run_at <= ? AND updated_at <= ?) LIMIT 100",
                (rs, row) -> rs.getString(1), timestamp(now), timestamp(now), timestamp(staleBefore));
        int recovered = 0;
        for (String taskId : taskIds) {
            Integer count = transaction.execute(status -> {
                if (lockTask(taskId).isEmpty()) return 0;
                int changed = 0;
                for (Unit unit : units(taskId)) {
                    if (unit.status() == UnitStatus.RUNNING && unit.leaseUntil() != null && !unit.leaseUntil().isAfter(now)) {
                        if (unit.kind() == Kind.ASR) {
                            // 请求可能已被供应商受理。没有确认结果时禁止自动再次付费提交。
                            finishOwned(work(unit), unit.leaseToken(), UnitStatus.UNCERTAIN, null,
                                    "ASR_RESULT_UNCERTAIN", "执行中断，语音识别可能已受理；请确认后手动重试", now);
                        } else {
                            requeue(unit, now, now, false);
                        }
                        changed++;
                    } else if (unit.status() == UnitStatus.QUEUED && !unit.nextRunAt().isAfter(now)) {
                        Instant updatedAt = jdbc.queryForObject("SELECT updated_at FROM parse_unit WHERE task_id = ? AND kind = ? AND unit_index = ?",
                                (rs, row) -> instant(rs, "updated_at"), unit.taskId(), unit.kind().name(), unit.unitIndex());
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
        return jdbc.query("SELECT " + TASK_COLUMNS + " FROM parse_task WHERE task_id = "
                + "(SELECT task_id FROM parse_request WHERE request_key = ?)", TASK_MAPPER, requestKey).stream().findFirst();
    }

    private void verifyBinding(Task task, long assetId, String profile) {
        if (task.assetId() != assetId || !task.profileVersion().equals(profile)) {
            throw new IllegalArgumentException("同一请求键不能用于其他视频或解析配置");
        }
    }

    private Optional<Task> lockTask(String taskId) {
        return jdbc.query("SELECT " + TASK_COLUMNS + " FROM parse_task WHERE task_id = ? FOR UPDATE", TASK_MAPPER, taskId)
                .stream().findFirst();
    }

    private Optional<Unit> findUnit(WorkMessage message) {
        return jdbc.query("SELECT " + UNIT_COLUMNS + " FROM parse_unit WHERE task_id = ? AND kind = ? AND unit_index = ? AND generation = ?",
                UNIT_MAPPER, message.taskId(), message.kind().name(), message.unitIndex(), message.generation()).stream().findFirst();
    }

    private boolean owned(WorkMessage message, String token) {
        return findUnit(message).filter(unit -> unit.status() == UnitStatus.RUNNING && Objects.equals(unit.leaseToken(), token)).isPresent();
    }

    private void finishOwned(WorkMessage message, String token, UnitStatus unitStatus, String text,
                             String code, String errorMessage, Instant now) {
        int changed = jdbc.update("UPDATE parse_unit SET status = ?, result_text = ?, error_code = ?, error_message = ?, "
                        + "lease_token = NULL, lease_until = NULL, updated_at = ? WHERE task_id = ? AND kind = ? "
                        + "AND unit_index = ? AND generation = ? AND status = 'RUNNING' AND lease_token = ?",
                unitStatus.name(), text, code, errorMessage, timestamp(now), message.taskId(), message.kind().name(),
                message.unitIndex(), message.generation(), token);
        if (changed != 1) throw new IllegalStateException("解析执行权已改变，不能保存旧结果");
    }

    private void insertUnit(String taskId, Input input, UnitStatus status, Instant now) {
        jdbc.update("INSERT INTO parse_unit (task_id, kind, unit_index, generation, status, attempts, input_key, "
                        + "start_ms, end_ms, next_run_at, updated_at, next_dispatch_at) VALUES (?, ?, ?, 0, ?, 0, ?, ?, ?, ?, ?, ?)",
                taskId, input.kind().name(), input.unitIndex(), status.name(), input.inputKey(), input.startMs(), input.endMs(),
                timestamp(now), timestamp(now), timestamp(now));
    }

    private void requeue(Unit unit, Instant next, Instant now, boolean resetAttempts) {
        jdbc.update("UPDATE parse_unit SET status = 'QUEUED', generation = generation + 1, attempts = ?, "
                        + "lease_token = NULL, lease_until = NULL, error_code = NULL, error_message = NULL, next_run_at = ?, "
                        + "updated_at = ?, dispatch_attempts = 0, dispatched_at = NULL, next_dispatch_at = ? "
                        + "WHERE task_id = ? AND kind = ? AND unit_index = ? AND generation = ?",
                resetAttempts ? 0 : unit.attempts(), timestamp(next), timestamp(now), timestamp(next), unit.taskId(), unit.kind().name(),
                unit.unitIndex(), unit.generation());
    }

    private void republishQueued(Unit unit, Instant now) {
        // 排队久不等于旧消息失效。保留代数，原消息和补发消息任意一个先到都能领取，
        // 避免长队列中的旧消息不断被废弃、替代消息不断排到队尾而饥饿。
        jdbc.update("UPDATE parse_unit SET updated_at = ?, dispatched_at = NULL, next_dispatch_at = ? "
                        + "WHERE task_id = ? AND kind = ? AND unit_index = ? AND generation = ? AND status = 'QUEUED'",
                timestamp(now), timestamp(now), unit.taskId(), unit.kind().name(), unit.unitIndex(), unit.generation());
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
        jdbc.update("UPDATE parse_task SET status = ?, updated_at = ? WHERE task_id = ?", taskStatus.name(), timestamp(now), taskId);
    }

    private static WorkMessage work(Unit unit) { return new WorkMessage(unit.taskId(), unit.kind(), unit.unitIndex(), unit.generation()); }
    private static String truncate(String value, int length) { return value == null || value.length() <= length ? value : value.substring(0, length); }
    private static void requireText(String value, int max, String label) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException(label + "不能为空或超长");
    }
    private static SqlTypeValue timestamp(Instant value) {
        return (statement, index, sqlType, typeName) -> statement.setTimestamp(index,
                value == null ? null : Timestamp.from(value), Calendar.getInstance(TimeZone.getTimeZone("UTC")));
    }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column, Calendar.getInstance(TimeZone.getTimeZone("UTC")));
        return value == null ? null : value.toInstant();
    }
}
