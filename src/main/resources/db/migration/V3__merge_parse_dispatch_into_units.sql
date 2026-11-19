-- 停止旧应用后升级：将当前代数的派发记录并入处理单元，不改动任务与识别结果。
ALTER TABLE parse_unit ADD COLUMN dispatch_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE parse_unit ADD COLUMN next_dispatch_at DATETIME(3) NOT NULL DEFAULT '1970-01-01 00:00:00';
ALTER TABLE parse_unit ADD COLUMN dispatched_at DATETIME(3) NULL;

UPDATE parse_unit SET
    dispatch_attempts = COALESCE((
        SELECT b.attempts FROM parse_outbox b
        WHERE b.task_id = parse_unit.task_id AND b.kind = parse_unit.kind
          AND b.unit_index = parse_unit.unit_index AND b.generation = parse_unit.generation
    ), 0),
    next_dispatch_at = COALESCE((
        SELECT b.next_attempt_at FROM parse_outbox b
        WHERE b.task_id = parse_unit.task_id AND b.kind = parse_unit.kind
          AND b.unit_index = parse_unit.unit_index AND b.generation = parse_unit.generation
    ), next_run_at),
    dispatched_at = (
        SELECT b.sent_at FROM parse_outbox b
        WHERE b.task_id = parse_unit.task_id AND b.kind = parse_unit.kind
          AND b.unit_index = parse_unit.unit_index AND b.generation = parse_unit.generation
    );

CREATE INDEX idx_parse_unit_dispatch ON parse_unit (status, dispatched_at, next_dispatch_at);

-- 历史代数的发件记录不再保留；当前执行代数已保存在 parse_unit，旧消息仍会被拒绝领取。
DROP TABLE parse_outbox;
