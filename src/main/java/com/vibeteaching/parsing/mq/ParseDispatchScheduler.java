package com.vibeteaching.parsing.mq;

import com.vibeteaching.parsing.config.ParseProperties;
import com.vibeteaching.parsing.persistence.ParseRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = {"app.upload.enabled", "app.parse.enabled"}, havingValue = "true")
public class ParseDispatchScheduler {
    private static final Logger log = LoggerFactory.getLogger(ParseDispatchScheduler.class);
    private final ParseRepository repository;
    private final ParseMessagePublisher publisher;
    private final ParseProperties properties;
    private final Clock clock;

    public ParseDispatchScheduler(ParseRepository repository, ParseMessagePublisher publisher, ParseProperties properties, Clock clock) {
        this.repository = repository;
        this.publisher = publisher;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.parse.dispatch-delay-ms:2000}")
    public void dispatch() {
        try {
            for (var pending : repository.pendingDispatches(clock.instant(), 20)) {
                try {
                    publisher.send(pending.message());
                    // 回执更新按单元当前代数匹配，旧发送结果不能影响重试后的派发状态。
                    repository.dispatchSent(pending.message(), clock.instant());
                } catch (RuntimeException failure) {
                    repository.dispatchFailed(pending.message(), clock.instant().plus(properties.retryDelay()));
                    log.warn("后台消息尚未确认发送，处理单元仍等待派发，任务 {}", pending.message().taskId());
                    break;
                }
            }
        } catch (RuntimeException failure) { log.warn("解析消息派发暂不可用，将在后续轮次重试"); }
    }

    @Scheduled(fixedDelayString = "${app.parse.recovery-delay-ms:15000}")
    public void recover() {
        try { repository.recover(clock.instant(), properties.requeueAfter()); }
        catch (RuntimeException failure) { log.warn("解析任务恢复检查暂不可用，将在后续轮次重试"); }
    }
}
