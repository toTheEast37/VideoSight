package com.vibeteaching.upload.model;

import java.time.Instant;

public record UploadSession(
        String uploadId,
        String requestKey,
        String originalName,
        long fileSize,
        int chunkSize,
        int totalChunks,
        String fileFingerprint,
        String contentType,
        UploadStatus status,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt) {
}
