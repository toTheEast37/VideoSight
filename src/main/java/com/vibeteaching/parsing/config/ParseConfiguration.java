package com.vibeteaching.parsing.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibeteaching.parsing.ParseService;
import com.vibeteaching.parsing.ParseWorker;
import com.vibeteaching.parsing.media.*;
import com.vibeteaching.parsing.persistence.ParseRepository;
import com.vibeteaching.upload.persistence.UploadRepository;
import com.vibeteaching.upload.storage.UploadObjectStore;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration
@EnableConfigurationProperties(ParseProperties.class)
public class ParseConfiguration {
    @Configuration
    @ConditionalOnProperty(name = "app.upload.enabled", havingValue = "true")
    static class EnabledInfrastructure {
        @Bean MediaPreparation parseMediaPreparation(UploadObjectStore objects, ParseProperties properties, ObjectMapper mapper) {
            return new MediaPreparation(objects, properties, mapper);
        }

        @Bean AsrClient parseAsrClient(ParseProperties properties, ObjectMapper mapper) {
            return new DashScopeAsrClient(properties, mapper);
        }

        @Bean OcrClient parseOcrClient(ParseProperties properties, ObjectMapper mapper) {
            return switch (properties.ocrProvider()) {
                case TESSERACT -> new TesseractOcrClient(properties);
                case QWEN -> new DashScopeOcrClient(properties, mapper);
            };
        }

        @Bean ParseWorker parseWorker(ParseRepository repository, UploadRepository uploads, MediaPreparation preparation,
                                     AsrClient asr, OcrClient ocr, ParseProperties properties, ParseService service, Clock clock) {
            return new ParseWorker(repository, uploads, preparation, asr, ocr, properties, service, clock);
        }
    }
}
