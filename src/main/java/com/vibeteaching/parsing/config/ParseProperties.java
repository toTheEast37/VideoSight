package com.vibeteaching.parsing.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 非敏感开发边界与外部配置入口；密钥只从本地 dev 配置读取。 */
@ConfigurationProperties("app.parse")
public record ParseProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("v1") String profileVersion,
        @DefaultValue("30m") Duration maxDuration,
        @DefaultValue("268435456") long maxInputBytes,
        @DefaultValue("5") int frameIntervalSeconds,
        @DefaultValue("360") int maxFrames,
        @DefaultValue("60") int audioSegmentSeconds,
        @DefaultValue("work/parse-tmp") Path tempDirectory,
        @DefaultValue("ffmpeg") String ffmpegCommand,
        @DefaultValue("ffprobe") String ffprobeCommand,
        @DefaultValue("tesseract") String ocrCommand,
        @DefaultValue("") String asrUrl,
        @DefaultValue("") String asrModel,
        @DefaultValue("") String asrApiKey,
        @DefaultValue("3m") Duration asrTimeout,
        @DefaultValue("5m") Duration processTimeout,
        @DefaultValue("5m") Duration leaseDuration,
        @DefaultValue("10m") Duration requeueAfter,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("10s") Duration retryDelay,
        @DefaultValue("") String nameserver,
        @DefaultValue("videosight-parse") String topic,
        @DefaultValue("videosight-parse-producer") String producerGroup,
        @DefaultValue("videosight-parse-worker") String consumerGroup) {
    public ParseProperties {
        if (profileVersion == null || !profileVersion.matches("[a-zA-Z0-9._-]{1,32}")
                || !positive(maxDuration) || maxDuration.compareTo(Duration.ofHours(2)) > 0
                || maxInputBytes < 1 || maxInputBytes > 2147483648L
                || frameIntervalSeconds < 1 || maxFrames < 1 || maxFrames > 1000
                || audioSegmentSeconds < 10 || audioSegmentSeconds > 300
                || !positive(asrTimeout) || !positive(processTimeout) || !positive(leaseDuration)
                || leaseDuration.compareTo(Duration.ofSeconds(30)) < 0 || !positive(requeueAfter)
                || !positive(retryDelay) || maxAttempts < 1 || maxAttempts > 5
                || tempDirectory == null || !topic.matches("[a-zA-Z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("视频解析参数无效，请检查时长、数量和超时边界");
        }
    }

    private static boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }

    @Override public String toString() {
        return "ParseProperties[enabled=" + enabled + ", profileVersion=" + profileVersion + "]";
    }
}
