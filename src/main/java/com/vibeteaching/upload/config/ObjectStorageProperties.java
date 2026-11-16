package com.vibeteaching.upload.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.storage")
public record ObjectStorageProperties(String endpoint, String accessKey, String secretKey, String bucket) {
    @Override
    public String toString() { return "ObjectStorageProperties[私密配置已隐藏]"; }
}
