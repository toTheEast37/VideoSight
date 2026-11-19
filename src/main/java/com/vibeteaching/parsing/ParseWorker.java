package com.vibeteaching.parsing;

import com.vibeteaching.parsing.ParseModels.*;
import com.vibeteaching.parsing.config.ParseProperties;
import com.vibeteaching.parsing.media.*;
import com.vibeteaching.parsing.persistence.ParseRepository;
import com.vibeteaching.upload.model.VideoAsset;
import com.vibeteaching.upload.persistence.UploadRepository;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 同一 MQ 消费线程完成有界步骤，再由数据库检查点交给下一步。 */
public class ParseWorker {
    private static final Logger log = LoggerFactory.getLogger(ParseWorker.class);
    private final ParseRepository repository;
    private final UploadRepository uploads;
    private final MediaPreparation preparation;
    private final AsrClient asr;
    private final OcrClient ocr;
    private final ParseProperties properties;
    private final ParseService service;
    private final Clock clock;
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "parse-lease-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public ParseWorker(ParseRepository repository, UploadRepository uploads, MediaPreparation preparation,
                       AsrClient asr, OcrClient ocr, ParseProperties properties, ParseService service, Clock clock) {
        this.repository = repository;
        this.uploads = uploads;
        this.preparation = preparation;
        this.asr = asr;
        this.ocr = ocr;
        this.properties = properties;
        this.service = service;
        this.clock = clock;
    }

    public void consume(WorkMessage message) {
        if (!properties.enabled() || service.configurationProblem() != null) {
            throw new IllegalStateException("解析工作者配置尚未就绪");
        }
        var task = repository.findTask(message.taskId()).orElse(null);
        if (task == null) return;
        // 工作队列积压后配置可能已变更，不能用新模型静默处理旧配置的任务。
        boolean profileChanged = !task.profileVersion().equals(service.profile());
        String token = UUID.randomUUID().toString();
        Unit unit = repository.claim(message, token, clock.instant(), properties.leaseDuration()).orElse(null);
        if (unit == null) return;
        long beatSeconds = Math.max(5, properties.leaseDuration().toSeconds() / 3);
        ScheduledFuture<?> renewal = heartbeat.scheduleAtFixedRate(() -> {
            try { repository.renew(message, token, clock.instant().plus(properties.leaseDuration())); }
            catch (RuntimeException e) { log.warn("解析执行期限续期失败，任务 {}", message.taskId()); }
        }, beatSeconds, beatSeconds, TimeUnit.SECONDS);
        try {
            if (profileChanged) throw new RecognitionException("PARSE_PROFILE_CHANGED", "解析配置已变更，请从原视频发起新配置的解析", false, false);
            if (message.kind() == Kind.PREPARE) {
                VideoAsset asset = uploads.findAsset(task.assetId()).orElseThrow(() ->
                        new RecognitionException("ASSET_NOT_FOUND", "视频资产不存在，无法继续解析", false, false));
                var prepared = preparation.prepare(asset, task.taskId(), token);
                repository.completePreparation(message, token, prepared.durationMs(), prepared.hasAudio(),
                        prepared.inputs(), clock.instant());
            } else {
                String text = preparation.withInput(unit.inputKey(), path -> message.kind() == Kind.ASR
                        ? asr.transcribe(path) : ocr.recognize(path));
                if (text == null || text.length() > 200_000) {
                    throw new RecognitionException("RECOGNITION_RESPONSE_INVALID", "识别结果格式或大小异常，请核对识别服务", false, false);
                }
                repository.complete(message, token, text, clock.instant());
            }
        } catch (RecognitionException failure) {
            repository.fail(message, token, failure.code(), failure.getMessage(), failure.retryable(),
                    failure.uncertain(), properties.maxAttempts(), clock.instant().plus(properties.retryDelay()), clock.instant());
        } finally {
            // 数据库或进程异常向 MQ 传播；期限恢复仍能找回已经确认接单的任务。
            renewal.cancel(false);
        }
    }

    @PreDestroy public void close() { heartbeat.shutdownNow(); }
}
