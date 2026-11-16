package com.vibeteaching.upload.progress;

import com.vibeteaching.upload.UploadException;
import com.vibeteaching.upload.model.UploadSession;
import com.vibeteaching.upload.model.UploadStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 显式开启后连接本机真实 Redis；只操作随机会话的两个键，不扫描或清空其他数据。 */
@EnabledIfSystemProperty(named = "upload.redis.integration", matches = "true")
class RedisUploadProgressStoreIntegrationTest {

    @Test
    void productionLuaPreservesProgressIdempotencyExpiryAndMissingKeyBoundaries() {
        LettuceConnectionFactory factory = null;
        StringRedisTemplate redis = null;
        String uploadId = UUID.randomUUID().toString();
        List<String> keys = List.of("upload:{" + uploadId + "}:meta", "upload:{" + uploadId + "}:parts");
        boolean ownNamespaceVerified = false;
        try {
            factory = connectionFactory();
            factory.afterPropertiesSet();
            factory.start();
            redis = new StringRedisTemplate(factory);
            redis.afterPropertiesSet();
            assertThat(redis.countExistingKeys(keys)).as("随机测试命名空间应为空").isZero();
            ownNamespaceVerified = true;

            RedisUploadProgressStore progress = new RedisUploadProgressStore(redis);
            UploadSession initial = session(uploadId, Instant.now().plusSeconds(120));
            progress.initialize(initial);
            assertThat(progress.snapshot(initial)).as("初始化后应为零成功片").isEmpty();
            assertThat(redis.hasKey(keys.get(0))).as("初始化应创建元数据").isTrue();
            assertThat(redis.hasKey(keys.get(1))).as("零成功片时不要求空 Set 实际存在").isFalse();

            progress.recordPart(initial, 0, "1".repeat(64));
            assertThat(progress.snapshot(initial)).as("记录真实 Lua 的序号和摘要")
                    .isEqualTo(Map.of(0, "1".repeat(64)));
            progress.recordPart(initial, 0, "1".repeat(64));
            assertThat(redis.opsForSet().size(keys.get(1))).as("重复同片不增加 Set 成员").isEqualTo(1);
            assertThat(redis.opsForHash().get(keys.get(0), "receivedCount"))
                    .as("重复同片不增加成功计数").isEqualTo("1");
            assertCode("PART_CONTENT_CONFLICT", () -> progress.recordPart(initial, 0, "2".repeat(64)));
            assertThat(progress.snapshot(initial)).as("不同摘要被拒绝后应保留原进度")
                    .isEqualTo(Map.of(0, "1".repeat(64)));
            assertSameDeadline(redis, keys, 90000);

            UploadSession renewed = session(uploadId, Instant.now().plusSeconds(300));
            progress.renew(renewed);
            assertSameDeadline(redis, keys, 270000);
            progress.recordPart(renewed, 2, "3".repeat(64));
            assertThat(progress.snapshot(renewed)).as("返回真实成功序号，不能误认中间缺片已上传")
                    .isEqualTo(Map.of(0, "1".repeat(64), 2, "3".repeat(64)));

            redis.delete(keys.get(1));
            assertCode("UPLOAD_PROGRESS_CORRUPT", () -> progress.snapshot(renewed));
            assertCode("UPLOAD_PROGRESS_CORRUPT", () -> progress.recordPart(renewed, 1, "4".repeat(64)));
            assertThat(redis.hasKey(keys.get(1))).as("缺少 Set 时不能盲目重建零进度").isFalse();

            progress.delete(uploadId);
            assertThat(redis.countExistingKeys(keys)).as("清理只删除当前会话两键").isZero();
            assertCode("UPLOAD_PROGRESS_MISSING", () -> progress.snapshot(renewed));
            assertCode("UPLOAD_PROGRESS_MISSING", () -> progress.renew(renewed));
            assertThat(redis.countExistingKeys(keys)).as("读取和续期不能重新创建丢失进度").isZero();

            redis.opsForSet().add(keys.get(1), "0");
            redis.expire(keys.get(1), Duration.ofSeconds(30));
            assertCode("UPLOAD_PROGRESS_CORRUPT", () -> progress.initialize(renewed));
            assertThat(redis.hasKey(keys.get(0))).as("孤立 Set 不能被初始化为新的零进度").isFalse();
        } catch (Exception failure) {
            // 底层连接错误可能包含私密地址；保留固定失败描述，不附带原异常或配置值。
            throw new AssertionError("真实 Redis 验证失败，请检查本机服务和被忽略的开发配置；私密连接详情已隐藏。");
        } finally {
            try {
                if (redis != null && ownNamespaceVerified) {
                    redis.delete(keys);
                    assertThat(redis.countExistingKeys(keys)).as("finally 应清除本次随机测试两键").isZero();
                }
            } catch (Exception cleanupFailure) {
                throw new AssertionError("本次随机 Redis 测试键清理失败；未操作其他业务键，私密连接详情已隐藏。");
            } finally {
                if (factory != null) factory.destroy();
            }
        }
    }

    private static void assertCode(String code, Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(UploadException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(code));
    }

    private static void assertSameDeadline(StringRedisTemplate redis, List<String> keys, long minimumTtl) {
        Long metadataTtl = redis.getExpire(keys.get(0), TimeUnit.MILLISECONDS);
        Long partsTtl = redis.getExpire(keys.get(1), TimeUnit.MILLISECONDS);
        assertThat(metadataTtl).as("元数据 TTL 应有效且已续期").isGreaterThan(minimumTtl);
        assertThat(partsTtl).as("分片 Set TTL 应有效且已续期").isGreaterThan(minimumTtl);
        assertThat(Math.abs(metadataTtl - partsTtl)).as("两键应在同一个 Lua 中设置相同截止时间").isLessThan(1000L);
    }

    private static UploadSession session(String uploadId, Instant deadline) {
        Instant now = Instant.now();
        return new UploadSession(uploadId, UUID.randomUUID().toString(), "__redis_probe__.mp4", 9, 4, 3,
                "a".repeat(64), "video/mp4", UploadStatus.UPLOADING, deadline, now, now);
    }

    private static LettuceConnectionFactory connectionFactory() throws Exception {
        Map<String, Object> root = map(new Yaml(new SafeConstructor(new LoaderOptions()))
                .load(Files.readString(Path.of("config/application-dev.yaml"))));
        Map<String, Object> settings = map(map(map(root.get("spring")).get("data")).get("redis"));
        if (settings.isEmpty()) throw new IllegalStateException("开发配置缺少 Redis 设置。");
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(text(settings.get("host")),
                Integer.parseInt(text(settings.getOrDefault("port", 6379))));
        standalone.setDatabase(Integer.parseInt(text(settings.getOrDefault("database", 0))));
        if (!text(settings.get("username")).isEmpty()) standalone.setUsername(text(settings.get("username")));
        if (!text(settings.get("password")).isEmpty()) standalone.setPassword(RedisPassword.of(text(settings.get("password"))));
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(3));
        if (Boolean.parseBoolean(text(map(settings.get("ssl")).get("enabled")))) client.useSsl();
        return new LettuceConnectionFactory(standalone, client.build());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }
}
