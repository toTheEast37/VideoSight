package com.vibeteaching.parsing;

import java.time.Instant;

/** 解析任务与持久检查点；消息只携带定位与代数，不携带媒体内容。 */
public final class ParseModels {
    private ParseModels() { }

    public enum Kind { PREPARE, ASR, OCR }
    public enum UnitStatus { QUEUED, RUNNING, SUCCEEDED, SKIPPED, FAILED, UNCERTAIN }
    public enum TaskStatus { QUEUED, RUNNING, SUCCEEDED, PARTIAL, FAILED }

    public record Task(String taskId, long assetId, String profileVersion, TaskStatus status,
                       Long durationMs, Instant createdAt, Instant updatedAt) { }
    public record Unit(String taskId, Kind kind, int unitIndex, int generation, UnitStatus status,
                       int attempts, String inputKey, long startMs, long endMs, String text,
                       String errorCode, String message, String leaseToken, Instant leaseUntil,
                       Instant nextRunAt) { }
    public record WorkMessage(String taskId, Kind kind, int unitIndex, int generation) { }
    public record Input(Kind kind, int unitIndex, String inputKey, long startMs, long endMs) { }
    public record PendingDispatch(WorkMessage message, int attempts) { }
}
