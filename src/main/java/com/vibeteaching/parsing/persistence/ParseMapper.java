package com.vibeteaching.parsing.persistence;

import com.vibeteaching.parsing.ParseModels.Input;
import com.vibeteaching.parsing.ParseModels.Kind;
import com.vibeteaching.parsing.ParseModels.PendingDispatch;
import com.vibeteaching.parsing.ParseModels.Task;
import com.vibeteaching.parsing.ParseModels.TaskStatus;
import com.vibeteaching.parsing.ParseModels.Unit;
import com.vibeteaching.parsing.ParseModels.UnitStatus;
import com.vibeteaching.parsing.ParseModels.WorkMessage;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ParseMapper {
    void insertTask(@Param("taskId") String taskId, @Param("assetId") long assetId,
                    @Param("profile") String profile, @Param("now") Instant now);
    void bindRequest(@Param("requestKey") String requestKey, @Param("taskId") String taskId,
                     @Param("now") Instant now);
    Task findTask(@Param("taskId") String taskId);
    Task findByAsset(@Param("assetId") long assetId, @Param("profile") String profile);
    Task findLatestByAsset(@Param("assetId") long assetId);
    Task findRequest(@Param("requestKey") String requestKey);
    Task lockTask(@Param("taskId") String taskId);
    List<Unit> units(@Param("taskId") String taskId);
    Unit findUnit(@Param("work") WorkMessage work);
    Instant unitUpdatedAt(@Param("work") WorkMessage work);

    List<DispatchRow> pendingDispatches(@Param("now") Instant now, @Param("limit") int limit);
    void dispatchSent(@Param("work") WorkMessage work, @Param("now") Instant now);
    void dispatchFailed(@Param("work") WorkMessage work, @Param("nextAttempt") Instant nextAttempt);
    int claim(@Param("work") WorkMessage work, @Param("token") String token,
              @Param("now") Instant now, @Param("until") Instant until);
    int renew(@Param("work") WorkMessage work, @Param("token") String token, @Param("until") Instant until);
    int finishOwned(@Param("work") WorkMessage work, @Param("token") String token, @Param("status") UnitStatus status,
                    @Param("text") String text, @Param("code") String code, @Param("message") String message,
                    @Param("now") Instant now);

    void updateDuration(@Param("taskId") String taskId, @Param("durationMs") long durationMs);
    void insertUnit(@Param("taskId") String taskId, @Param("input") Input input,
                    @Param("status") UnitStatus status, @Param("now") Instant now);
    void updateUnitError(@Param("work") WorkMessage work, @Param("code") String code, @Param("message") String message);
    void requeue(@Param("work") WorkMessage work, @Param("attempts") int attempts,
                 @Param("next") Instant next, @Param("now") Instant now);
    void republishQueued(@Param("work") WorkMessage work, @Param("now") Instant now);
    List<String> recoverableTaskIds(@Param("now") Instant now, @Param("staleBefore") Instant staleBefore);
    void updateTaskStatus(@Param("taskId") String taskId, @Param("status") TaskStatus status, @Param("now") Instant now);

    record DispatchRow(String taskId, Kind kind, int unitIndex, int generation, int attempts) {
        PendingDispatch toPendingDispatch() {
            return new PendingDispatch(new WorkMessage(taskId, kind, unitIndex, generation), attempts);
        }
    }
}
