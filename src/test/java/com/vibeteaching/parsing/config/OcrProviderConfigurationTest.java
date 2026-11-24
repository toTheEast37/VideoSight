package com.vibeteaching.parsing.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibeteaching.parsing.ParseService;
import com.vibeteaching.parsing.media.DashScopeOcrClient;
import com.vibeteaching.parsing.media.TesseractOcrClient;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

class OcrProviderConfigurationTest {
    @Test
    void choosesLocalByDefaultAndKeepsSeparateProfilesForExplicitCloudOcr() {
        var bindings = new HashMap<>(Map.of(
                "app.parse.ocr-base-url", "https://example.invalid/compatible-mode/v1",
                "app.parse.ocr-api-key", "fixture"));
        var configuration = new ParseConfiguration.EnabledInfrastructure();
        var mapper = new ObjectMapper();
        ParseProperties local = properties(bindings);
        assertThat(configuration.parseOcrClient(local, mapper)).isInstanceOf(TesseractOcrClient.class);

        bindings.put("app.parse.ocr-provider", "qwen");
        ParseProperties cloud = properties(bindings);
        assertThat(configuration.parseOcrClient(cloud, mapper)).isInstanceOf(DashScopeOcrClient.class);
        var localService = new ParseService(null, null, local, null);
        var cloudService = new ParseService(null, null, cloud, null);
        assertThat(cloudService.profile()).isNotEqualTo(localService.profile());
        assertThat(cloudService.localOcrProfile()).isEqualTo(localService.profile());
    }

    private ParseProperties properties(Map<String, String> bindings) {
        return new Binder(new MapConfigurationPropertySource(bindings))
                .bind("app.parse", Bindable.of(ParseProperties.class)).get();
    }
}
