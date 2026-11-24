package com.vibeteaching.qa;

import java.time.Duration;
import okhttp3.HttpUrl;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.qa")
public record QaProperties(boolean enabled, String baseUrl, String apiKey, String embeddingModel,
                           String answerModel, String rerankModel, Duration timeout) {
    HttpUrl endpoint() {
        HttpUrl url = baseUrl == null ? null : HttpUrl.parse(baseUrl);
        if (url == null || !url.isHttps() || !url.username().isEmpty() || !url.password().isEmpty()
                || url.query() != null || url.fragment() != null) return null;
        return url;
    }

    public boolean ready() {
        return endpoint() != null && configured(apiKey) && configured(embeddingModel) && configured(answerModel)
                && timeout != null && !timeout.isNegative() && !timeout.isZero();
    }

    boolean rerankReady() { return configured(rerankModel); }

    private static boolean configured(String value) {
        return value != null && !value.isBlank() && !value.startsWith("<");
    }
}
