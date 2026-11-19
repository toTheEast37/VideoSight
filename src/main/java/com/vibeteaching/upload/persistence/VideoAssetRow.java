package com.vibeteaching.upload.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.vibeteaching.upload.model.UploadSession;
import com.vibeteaching.upload.model.VideoAsset;
import java.time.Instant;
import java.util.Objects;

/** 可写的持久层行模型，供 MyBatis 回填数据库生成的资产编号。 */
@TableName("video_asset")
public class VideoAssetRow {
    @TableId(type = IdType.AUTO)
    private Long assetId;
    private String uploadId;
    private String originalName;
    private long fileSize;
    private String sha256;
    private String bucket;
    private String objectKey;
    private String contentType;
    private Instant createdAt;

    public VideoAssetRow() {
    }

    VideoAssetRow(UploadSession session, String bucket, String objectKey, String sha256, Instant createdAt) {
        this.uploadId = session.uploadId();
        this.originalName = session.originalName();
        this.fileSize = session.fileSize();
        this.sha256 = sha256;
        this.bucket = bucket;
        this.objectKey = objectKey;
        this.contentType = session.contentType();
        this.createdAt = createdAt;
    }

    VideoAsset toAsset() {
        return new VideoAsset(Objects.requireNonNull(assetId, "数据库未返回资产编号"), uploadId,
                originalName, fileSize, sha256, bucket, objectKey, contentType, createdAt);
    }
}
