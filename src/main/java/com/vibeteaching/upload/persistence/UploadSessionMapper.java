package com.vibeteaching.upload.persistence;

import com.vibeteaching.upload.model.UploadSession;
import com.vibeteaching.upload.model.UploadStatus;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UploadSessionMapper {
    void insert(UploadSession candidate);
    UploadSession find(@Param("uploadId") String uploadId);
    UploadSession findByRequestKey(@Param("requestKey") String requestKey);
    UploadSession lock(@Param("uploadId") String uploadId);
    int updateStatus(@Param("uploadId") String uploadId, @Param("status") UploadStatus status,
                     @Param("expiresAt") Instant expiresAt, @Param("now") Instant now);
    void markCleanupPending(@Param("uploadId") String uploadId);
    void markCleaned(@Param("uploadId") String uploadId);
    List<UploadSession> findExpired(@Param("now") Instant now, @Param("limit") int limit);
    List<UploadSession> findCleanupPending(@Param("limit") int limit);
}
