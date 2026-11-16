package com.vibeteaching.upload.progress;

import com.vibeteaching.upload.UploadException;
import com.vibeteaching.upload.model.UploadSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 两个键在同一次 Lua 执行中校验和修改，避免把丢失的进度当成零进度。 */
@Component
@ConditionalOnProperty(name = "app.upload.enabled", havingValue = "true")
public class RedisUploadProgressStore implements UploadProgressStore {

    static final String SCRIPT = """
            local operation = ARGV[1]
            local total = tonumber(ARGV[2])
            local deadline = tonumber(ARGV[6])
            if redis.call('EXISTS', KEYS[1]) == 0 then
                if operation ~= 'initialize' then return {'MISSING'} end
                if redis.call('EXISTS', KEYS[2]) ~= 0 then return {'CORRUPT'} end
                -- 单字段 HSET 兼容本地 Redis 3.2，所有写入仍在同一次 Lua 内执行。
                redis.call('HSET', KEYS[1], 'totalChunks', ARGV[2])
                redis.call('HSET', KEYS[1], 'fileSize', ARGV[3])
                redis.call('HSET', KEYS[1], 'chunkSize', ARGV[4])
                redis.call('HSET', KEYS[1], 'fingerprint', ARGV[5])
                redis.call('HSET', KEYS[1], 'receivedCount', '0')
                redis.call('PEXPIREAT', KEYS[1], deadline)
                return {'OK'}
            end
            local metadata = redis.call('HMGET', KEYS[1], 'totalChunks', 'fileSize',
                'chunkSize', 'fingerprint', 'receivedCount')
            if metadata[1] ~= ARGV[2] or metadata[2] ~= ARGV[3]
                or metadata[3] ~= ARGV[4] or metadata[4] ~= ARGV[5] then
                return {'CORRUPT'}
            end
            local count = tonumber(metadata[5])
            local cardinality = redis.call('SCARD', KEYS[2])
            if not count or count < 0 or count > total or count ~= cardinality
                or redis.call('HLEN', KEYS[1]) ~= 5 + count then return {'CORRUPT'} end
            local members = redis.call('SMEMBERS', KEYS[2])
            local result = {'OK'}
            for _, member in ipairs(members) do
                local index = tonumber(member)
                if not index or index < 0 or index >= total or index ~= math.floor(index)
                    or tostring(index) ~= member then return {'CORRUPT'} end
                local digest = redis.call('HGET', KEYS[1], 'part:' .. member)
                if not digest or #digest ~= 64 or not string.match(digest, '^[0-9a-f]+$') then
                    return {'CORRUPT'}
                end
                table.insert(result, member)
                table.insert(result, digest)
            end
            if operation == 'record' then
                local field = 'part:' .. ARGV[7]
                local previous = redis.call('HGET', KEYS[1], field)
                if previous and previous ~= ARGV[8] then return {'CONFLICT'} end
                if not previous then
                    redis.call('SADD', KEYS[2], ARGV[7])
                    redis.call('HSET', KEYS[1], field, ARGV[8])
                    redis.call('HSET', KEYS[1], 'receivedCount', count + 1)
                end
            end
            if operation ~= 'snapshot' then
                redis.call('PEXPIREAT', KEYS[1], deadline)
                if redis.call('EXISTS', KEYS[2]) == 1 then
                    redis.call('PEXPIREAT', KEYS[2], deadline)
                end
            end
            return result
            """;

    private static final DefaultRedisScript<List> PROGRESS_SCRIPT = new DefaultRedisScript<>(SCRIPT, List.class);

    private final StringRedisTemplate redis;

    public RedisUploadProgressStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void initialize(UploadSession session) {
        execute("initialize", session, "", "");
    }

    @Override
    public Map<Integer, String> snapshot(UploadSession session) {
        List<?> result = execute("snapshot", session, "", "");
        Map<Integer, String> parts = new LinkedHashMap<>();
        try {
            if (result.size() % 2 != 1) throw new IllegalArgumentException();
            for (int index = 1; index < result.size(); index += 2) {
                int part = Integer.parseInt(result.get(index).toString());
                String digest = result.get(index + 1).toString();
                if (part < 0 || part >= session.totalChunks() || !digest.matches("[0-9a-f]{64}")
                        || parts.put(part, digest) != null) throw new IllegalArgumentException();
            }
            return Map.copyOf(parts);
        } catch (RuntimeException invalidResult) {
            throw unavailable("UPLOAD_PROGRESS_CORRUPT", "上传进度不完整，已暂停上传，请重新建立上传会话。");
        }
    }

    @Override
    public void recordPart(UploadSession session, int index, String sha256) {
        if (index < 0 || index >= session.totalChunks() || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new UploadException(HttpStatus.BAD_REQUEST, "INVALID_PART", "分片序号或摘要无效。");
        }
        execute("record", session, Integer.toString(index), sha256);
    }

    @Override
    public void renew(UploadSession session) {
        execute("renew", session, "", "");
    }

    @Override
    public void delete(String uploadId) {
        try {
            redis.delete(keys(uploadId));
        } catch (RuntimeException failure) {
            throw unavailable("UPLOAD_PROGRESS_UNAVAILABLE", "暂时无法清理上传进度，请稍后重试。");
        }
    }

    private List<?> execute(String operation, UploadSession session, String index, String digest) {
        List<?> result;
        try {
            result = redis.execute(PROGRESS_SCRIPT, keys(session.uploadId()), operation,
                    Integer.toString(session.totalChunks()), Long.toString(session.fileSize()),
                    Integer.toString(session.chunkSize()), session.fileFingerprint(),
                    Long.toString(session.expiresAt().toEpochMilli()), index, digest);
        } catch (RuntimeException failure) {
            throw unavailable("UPLOAD_PROGRESS_UNAVAILABLE", "暂时无法读取或保存上传进度，请稍后重试。");
        }
        if (result == null || result.isEmpty()) {
            throw unavailable("UPLOAD_PROGRESS_UNAVAILABLE", "上传进度服务未返回有效结果，请稍后重试。");
        }
        String code = result.get(0).toString();
        return switch (code) {
            case "OK" -> result;
            case "CONFLICT" -> throw new UploadException(HttpStatus.CONFLICT, "PART_CONTENT_CONFLICT",
                    "同一分片序号已上传了不同内容，请重新选择原文件。");
            case "MISSING" -> throw unavailable("UPLOAD_PROGRESS_MISSING",
                    "上传进度已丢失或过期，无法安全续传，请重新建立上传会话。");
            default -> throw unavailable("UPLOAD_PROGRESS_CORRUPT",
                    "上传进度不完整，已暂停上传，请重新建立上传会话。");
        };
    }

    private static List<String> keys(String uploadId) {
        String prefix = "upload:{" + uploadId + "}:";
        return List.of(prefix + "meta", prefix + "parts");
    }

    private static UploadException unavailable(String code, String message) {
        return new UploadException(HttpStatus.SERVICE_UNAVAILABLE, code, message);
    }
}
