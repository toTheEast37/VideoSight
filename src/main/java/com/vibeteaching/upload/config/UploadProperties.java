package com.vibeteaching.upload.config;

import java.time.Duration;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.upload")
public record UploadProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("5242880") int chunkSize,
        @DefaultValue("2147483648") long maxFileSize,
        @DefaultValue("3") int maxRounds,
        @DefaultValue("3") int concurrency,
        @DefaultValue("60000") int requestTimeoutMs,
        @DefaultValue("1000") int retryDelayMs,
        @DefaultValue("24h") Duration sessionTtl,
        @DefaultValue("work/upload-tmp") Path tempDirectory) {
    public UploadProperties {
        if (chunkSize < 1 || chunkSize > 16777216 || maxFileSize < 1 || maxFileSize / chunkSize > 100000
                || maxRounds < 1 || concurrency < 1 || concurrency > 8 || requestTimeoutMs < 1000
                || retryDelayMs < 0 || sessionTtl == null || sessionTtl.isNegative() || sessionTtl.isZero()) {
            throw new IllegalArgumentException("上传参数无效，请检查非敏感配置");
        }
    }
}
