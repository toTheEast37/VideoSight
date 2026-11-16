package com.vibeteaching.upload.web;

import com.vibeteaching.upload.UploadService;
import com.vibeteaching.upload.config.UploadProperties;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UploadSettingsController {
    private final UploadProperties properties;
    public UploadSettingsController(UploadProperties properties) { this.properties = properties; }

    @GetMapping("/api/upload-settings")
    public Map<String, Object> settings() {
        return Map.of("enabled", properties.enabled(), "chunkSize", properties.chunkSize(),
                "maxFileSize", properties.maxFileSize(), "maxRounds", properties.maxRounds(),
                "concurrency", properties.concurrency(), "requestTimeoutMs", properties.requestTimeoutMs(),
                "retryDelayMs", properties.retryDelayMs(), "fingerprintAlgorithm", UploadService.FINGERPRINT_ALGORITHM);
    }
}
