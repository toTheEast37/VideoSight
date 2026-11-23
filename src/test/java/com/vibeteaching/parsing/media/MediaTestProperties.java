package com.vibeteaching.parsing.media;

import com.vibeteaching.parsing.config.ParseProperties;

import java.nio.file.Path;
import java.time.Duration;

final class MediaTestProperties {
    private MediaTestProperties() { }

    static ParseProperties properties(Path temp, String url, String model, Duration timeout) {
        return new ParseProperties(true, "test", Duration.ofMinutes(30), 256L * 1024 * 1024,
                5, 360, 60, temp, "ffmpeg", "ffprobe", "tesseract", url, model,
                "local-test-key", timeout, Duration.ofSeconds(5), Duration.ofMinutes(5),
                Duration.ofMinutes(10), 3, Duration.ofSeconds(10), "", "parse-test", "producer", "consumer");
    }
}
