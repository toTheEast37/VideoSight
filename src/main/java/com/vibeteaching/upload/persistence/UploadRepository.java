package com.vibeteaching.upload.persistence;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.vibeteaching.upload.model.UploadSession;
import com.vibeteaching.upload.model.UploadStatus;
import com.vibeteaching.upload.model.VideoAsset;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
@ConditionalOnProperty(name = "app.upload.enabled", havingValue = "true")
@DependsOn("uploadFlyway")
public class UploadRepository {
    private final UploadSessionMapper sessions;
    private final VideoAssetMapper assets;
    private final TransactionTemplate transaction;

    public UploadRepository(UploadSessionMapper sessions, VideoAssetMapper assets,
                            PlatformTransactionManager transactionManager) {
        this.sessions = sessions;
        this.assets = assets;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public UploadSession createOrFind(UploadSession candidate) {
        try {
            sessions.insert(candidate);
            return findSession(candidate.uploadId()).orElseThrow();
        } catch (DuplicateKeyException duplicate) {
            // 由数据库唯一约束裁决并发初始化；参数是否一致交给业务层核对。
            return Optional.ofNullable(sessions.findByRequestKey(candidate.requestKey())).orElseThrow(() -> duplicate);
        }
    }

    public Optional<UploadSession> findSession(String uploadId) {
        return Optional.ofNullable(sessions.find(uploadId));
    }

    public Optional<VideoAsset> findAssetByUploadId(String uploadId) {
        return Optional.ofNullable(assets.selectOne(new QueryWrapper<VideoAssetRow>().eq("upload_id", uploadId)))
                .map(VideoAssetRow::toAsset);
    }

    public Optional<VideoAsset> findAsset(long assetId) {
        return Optional.ofNullable(assets.selectById(assetId)).map(VideoAssetRow::toAsset);
    }

    public List<VideoAsset> listAssets(int limit) {
        requirePositiveLimit(limit);
        return assets.listRecent(limit).stream().map(VideoAssetRow::toAsset).toList();
    }

    public void updateSession(String uploadId, UploadStatus status, Instant expiresAt) {
        int changed = sessions.updateStatus(uploadId, status, expiresAt, Instant.now());
        if (changed != 1) {
            throw new NoSuchElementException("上传会话不存在");
        }
    }

    public VideoAsset complete(UploadSession session, String bucket, String objectKey, String sha256) {
        try {
            return Objects.requireNonNull(transaction.execute(status -> {
                // 对象合并和上传已经在事务外完成，此处只持有短暂的数据库行锁。
                UploadSession current = Optional.ofNullable(sessions.lock(session.uploadId()))
                        .orElseThrow(() -> new NoSuchElementException("上传会话不存在"));
                Optional<VideoAsset> existing = findAssetByUploadId(session.uploadId());
                if (existing.isPresent()) {
                    return existing.get();
                }
                if (current.status() != UploadStatus.FINALIZING) {
                    throw new IllegalStateException("上传会话尚未进入完成处理状态");
                }

                Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
                VideoAssetRow asset = new VideoAssetRow(current, bucket, objectKey, sha256, createdAt);
                assets.insert(asset);
                VideoAsset saved = asset.toAsset();
                updateSession(current.uploadId(), UploadStatus.COMPLETED, current.expiresAt());
                sessions.markCleanupPending(current.uploadId());
                return saved;
            }));
        } catch (DuplicateKeyException duplicate) {
            // 等事务回滚完毕后再读取，不能在已经失败的事务中继续查询。
            return findAssetByUploadId(session.uploadId()).orElseThrow(() -> duplicate);
        }
    }

    public List<UploadSession> findExpired(Instant now, int limit) {
        requirePositiveLimit(limit);
        return sessions.findExpired(now, limit);
    }

    public List<UploadSession> findCleanupPending(int limit) {
        requirePositiveLimit(limit);
        return sessions.findCleanupPending(limit);
    }

    public void markCleaned(String uploadId) {
        sessions.markCleaned(uploadId);
    }

    private static void requirePositiveLimit(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("查询条数必须大于零");
        }
    }
}
