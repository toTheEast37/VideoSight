package com.vibeteaching.upload.model;

import java.time.Instant;

public record VideoAsset(
        long assetId,
        String uploadId,
        String originalName,
        long fileSize,
        String sha256,
        String bucket,
        String objectKey,
        String contentType,
        Instant createdAt) {
}
