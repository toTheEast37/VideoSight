package com.vibeteaching.upload.persistence;

import com.vibeteaching.upload.model.UploadSession;
import com.vibeteaching.upload.model.UploadStatus;
import com.vibeteaching.upload.model.VideoAsset;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Calendar;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.TimeZone;

@Repository
@ConditionalOnProperty(name = "app.upload.enabled", havingValue = "true")
@DependsOn("uploadFlyway")
public class UploadRepository {

    private static final String SESSION_COLUMNS = "upload_id, request_key, original_name, file_size, "
            + "chunk_size, total_chunks, file_fingerprint, content_type, status, expires_at, created_at, updated_at";
    private static final String ASSET_COLUMNS = "asset_id, upload_id, original_name, file_size, sha256, "
            + "bucket, object_key, content_type, created_at";

    private static final RowMapper<UploadSession> SESSION_MAPPER = (rs, rowNum) -> new UploadSession(
            rs.getString("upload_id"), rs.getString("request_key"), rs.getString("original_name"),
            rs.getLong("file_size"), rs.getInt("chunk_size"), rs.getInt("total_chunks"),
            rs.getString("file_fingerprint"), rs.getString("content_type"),
            UploadStatus.valueOf(rs.getString("status")),
            readInstant(rs, "expires_at"), readInstant(rs, "created_at"), readInstant(rs, "updated_at"));

    private static final RowMapper<VideoAsset> ASSET_MAPPER = (rs, rowNum) -> new VideoAsset(
            rs.getLong("asset_id"), rs.getString("upload_id"), rs.getString("original_name"),
            rs.getLong("file_size"), rs.getString("sha256"), rs.getString("bucket"),
            rs.getString("object_key"), rs.getString("content_type"), readInstant(rs, "created_at"));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public UploadRepository(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public UploadSession createOrFind(UploadSession candidate) {
        try {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("INSERT INTO upload_session ("
                        + SESSION_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                statement.setString(1, candidate.uploadId());
                statement.setString(2, candidate.requestKey());
                statement.setString(3, candidate.originalName());
                statement.setLong(4, candidate.fileSize());
                statement.setInt(5, candidate.chunkSize());
                statement.setInt(6, candidate.totalChunks());
                statement.setString(7, candidate.fileFingerprint());
                statement.setString(8, candidate.contentType());
                statement.setString(9, candidate.status().name());
                setInstant(statement, 10, candidate.expiresAt());
                setInstant(statement, 11, candidate.createdAt());
                setInstant(statement, 12, candidate.updatedAt());
                return statement;
            });
            return findSession(candidate.uploadId()).orElseThrow();
        } catch (DuplicateKeyException duplicate) {
            // 由数据库唯一约束裁决并发初始化；参数是否一致交给业务层核对。
            return jdbc.query("SELECT " + SESSION_COLUMNS + " FROM upload_session WHERE request_key = ?",
                            SESSION_MAPPER, candidate.requestKey()).stream()
                    .findFirst().orElseThrow(() -> duplicate);
        }
    }

    public Optional<UploadSession> findSession(String uploadId) {
        return jdbc.query("SELECT " + SESSION_COLUMNS + " FROM upload_session WHERE upload_id = ?",
                SESSION_MAPPER, uploadId).stream().findFirst();
    }

    public Optional<VideoAsset> findAssetByUploadId(String uploadId) {
        return jdbc.query("SELECT " + ASSET_COLUMNS + " FROM video_asset WHERE upload_id = ?",
                ASSET_MAPPER, uploadId).stream().findFirst();
    }

    public Optional<VideoAsset> findAsset(long assetId) {
        return jdbc.query("SELECT " + ASSET_COLUMNS + " FROM video_asset WHERE asset_id = ?",
                ASSET_MAPPER, assetId).stream().findFirst();
    }

    public List<VideoAsset> listAssets(int limit) {
        requirePositiveLimit(limit);
        return jdbc.query("SELECT " + ASSET_COLUMNS
                        + " FROM video_asset ORDER BY created_at DESC, asset_id DESC LIMIT ?",
                ASSET_MAPPER, limit);
    }

    public void updateSession(String uploadId, UploadStatus status, Instant expiresAt) {
        int changed = jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "UPDATE upload_session SET status = ?, expires_at = ?, updated_at = ? WHERE upload_id = ?");
            statement.setString(1, status.name());
            setInstant(statement, 2, expiresAt);
            setInstant(statement, 3, Instant.now());
            statement.setString(4, uploadId);
            return statement;
        });
        if (changed != 1) {
            throw new NoSuchElementException("上传会话不存在");
        }
    }

    public VideoAsset complete(UploadSession session, String bucket, String objectKey, String sha256) {
        try {
            return Objects.requireNonNull(transaction.execute(status -> {
                // 对象合并和上传已经在事务外完成，此处只持有短暂的数据库行锁。
                UploadSession current = jdbc.query("SELECT " + SESSION_COLUMNS
                                + " FROM upload_session WHERE upload_id = ? FOR UPDATE",
                        SESSION_MAPPER, session.uploadId()).stream().findFirst()
                        .orElseThrow(() -> new NoSuchElementException("上传会话不存在"));
                Optional<VideoAsset> existing = findAssetByUploadId(session.uploadId());
                if (existing.isPresent()) {
                    return existing.get();
                }
                if (current.status() != UploadStatus.FINALIZING) {
                    throw new IllegalStateException("上传会话尚未进入完成处理状态");
                }

                Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
                KeyHolder generated = new GeneratedKeyHolder();
                jdbc.update(connection -> {
                    PreparedStatement statement = connection.prepareStatement("INSERT INTO video_asset "
                                    + "(upload_id, original_name, file_size, sha256, bucket, object_key, content_type, created_at) "
                                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
                    statement.setString(1, current.uploadId());
                    statement.setString(2, current.originalName());
                    statement.setLong(3, current.fileSize());
                    statement.setString(4, sha256);
                    statement.setString(5, bucket);
                    statement.setString(6, objectKey);
                    statement.setString(7, current.contentType());
                    setInstant(statement, 8, createdAt);
                    return statement;
                }, generated);
                long assetId = Objects.requireNonNull(generated.getKey(), "数据库未返回资产编号").longValue();
                updateSession(current.uploadId(), UploadStatus.COMPLETED, current.expiresAt());
                jdbc.update("UPDATE upload_session SET cleanup_pending = TRUE WHERE upload_id = ?", current.uploadId());
                return new VideoAsset(assetId, current.uploadId(), current.originalName(), current.fileSize(),
                        sha256, bucket, objectKey, current.contentType(), createdAt);
            }));
        } catch (DuplicateKeyException duplicate) {
            // 等事务回滚完毕后再读取，不能在已经失败的事务中继续查询。
            return findAssetByUploadId(session.uploadId()).orElseThrow(() -> duplicate);
        }
    }

    public List<UploadSession> findExpired(Instant now, int limit) {
        requirePositiveLimit(limit);
        return jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement("SELECT " + SESSION_COLUMNS
                    + " FROM upload_session WHERE status IN ('INITIALIZING', 'UPLOADING', 'FINALIZING') AND expires_at <= ? "
                    + "ORDER BY expires_at, upload_id LIMIT ?");
            setInstant(statement, 1, now);
            statement.setInt(2, limit);
            return statement;
        }, SESSION_MAPPER);
    }

    public List<UploadSession> findCleanupPending(int limit) {
        requirePositiveLimit(limit);
        return jdbc.query("SELECT " + SESSION_COLUMNS
                        + " FROM upload_session WHERE status = 'COMPLETED' AND cleanup_pending = TRUE "
                        + "ORDER BY updated_at, upload_id LIMIT ?",
                SESSION_MAPPER, limit);
    }

    public void markCleaned(String uploadId) {
        jdbc.update("UPDATE upload_session SET cleanup_pending = FALSE WHERE upload_id = ? AND status = 'COMPLETED'", uploadId);
    }

    private static void requirePositiveLimit(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("查询条数必须大于零");
        }
    }

    private static Instant readInstant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column, utcCalendar()).toInstant();
    }

    private static void setInstant(PreparedStatement statement, int parameter, Instant instant) throws SQLException {
        statement.setTimestamp(parameter, Timestamp.from(instant), utcCalendar());
    }

    private static Calendar utcCalendar() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }
}
