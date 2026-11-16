package com.vibeteaching.upload.progress;

import com.vibeteaching.upload.UploadException;
import com.vibeteaching.upload.model.UploadSession;
import com.vibeteaching.upload.model.UploadStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class RedisUploadProgressStoreTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RedisUploadProgressStore progress = new RedisUploadProgressStore(redis);
    private final Instant now = Instant.parse("2026-11-15T00:00:00Z");
    private final UploadSession session = new UploadSession("session-1", "request-1", "video.mp4", 8, 4, 2,
            "a".repeat(64), "video/mp4", UploadStatus.UPLOADING, now.plusSeconds(3600), now, now);

    @Test
    void connectionFailureMustNotBecomeAnEmptyProgressSet() {
        doThrow(new RedisConnectionFailureException("连接私密地址失败"))
                .when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));

        assertThatThrownBy(() -> progress.snapshot(session)).isInstanceOfSatisfying(UploadException.class, error -> {
            assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(error.getCode()).isEqualTo("UPLOAD_PROGRESS_UNAVAILABLE");
            assertThat(error.getMessage()).doesNotContain("私密地址");
        });
    }

    @Test
    void missingProgressMustNotBecomeAnEmptyProgressSet() {
        result(List.of("MISSING"));

        assertThatThrownBy(() -> progress.snapshot(session)).isInstanceOfSatisfying(UploadException.class,
                error -> assertThat(error.getCode()).isEqualTo("UPLOAD_PROGRESS_MISSING"));
    }

    @Test
    void inconsistentSetAndHashMustPauseInsteadOfReportingSuccess() {
        result(List.of("CORRUPT"));

        assertThatThrownBy(() -> progress.recordPart(session, 1, "b".repeat(64)))
                .isInstanceOfSatisfying(UploadException.class,
                        error -> assertThat(error.getCode()).isEqualTo("UPLOAD_PROGRESS_CORRUPT"));
    }

    @Test
    void zeroSuccessfulPartsAreValidWhenTheServerConfirmedMetadata() {
        result(List.of("OK"));
        assertThat(progress.snapshot(session)).isEmpty();
    }

    @Test
    void conflictingRetryGetsAConflictResponse() {
        result(List.of("CONFLICT"));

        assertThatThrownBy(() -> progress.recordPart(session, 0, "b".repeat(64)))
                .isInstanceOfSatisfying(UploadException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.getCode()).isEqualTo("PART_CONTENT_CONFLICT");
                });
    }

    @Test
    void malformedSnapshotMustPauseInsteadOfAcceptingInvalidIndexes() {
        result(List.of("OK", "2", "b".repeat(64)));
        assertThatThrownBy(() -> progress.snapshot(session)).isInstanceOfSatisfying(UploadException.class,
                error -> assertThat(error.getCode()).isEqualTo("UPLOAD_PROGRESS_CORRUPT"));
    }

    private void result(List<String> value) {
        doReturn(value).when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }
}
