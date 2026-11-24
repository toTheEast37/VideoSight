package com.vibeteaching.parsing.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibeteaching.parsing.ParseModels.WorkMessage;
import com.vibeteaching.parsing.ParseWorker;
import com.vibeteaching.parsing.config.ParseProperties;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = {"app.upload.enabled", "app.parse.enabled"}, havingValue = "true")
public class RocketMqParseTransport implements ParseMessagePublisher {
    private static final Logger log = LoggerFactory.getLogger(RocketMqParseTransport.class);
    private final ParseProperties properties;
    private final ParseWorker worker;
    private final ObjectMapper mapper;
    private final String instance = "videosight-" + UUID.randomUUID();
    private DefaultMQProducer producer;
    private DefaultMQPushConsumer consumer;
    private boolean closed;

    public RocketMqParseTransport(ParseProperties properties, ParseWorker worker, ObjectMapper mapper) {
        this.properties = properties;
        this.worker = worker;
        this.mapper = mapper;
    }

    @Override public synchronized void send(WorkMessage work) {
        if (closed) throw new IllegalStateException("任务发送器已关闭");
        try {
            startProducer();
            var sent = producer.send(new Message(properties.topic(), mapper.writeValueAsBytes(work)), 3000);
            if (sent.getSendStatus() != SendStatus.SEND_OK) throw new IllegalStateException("任务消息尚未确认可靠写入");
        } catch (Exception e) {
            // 不向上暴露 SDK 原始端点、请求或鉴权日志。
            throw new IllegalStateException("后台任务服务暂不可用，待发送记录已保留");
        }
    }

    @Scheduled(initialDelayString = "${app.parse.mq-initial-delay-ms:3000}", fixedDelayString = "${app.parse.mq-check-delay-ms:15000}")
    public synchronized void connectConsumer() {
        if (consumer != null || closed || properties.nameserver().isBlank()) return;
        DefaultMQPushConsumer candidate = new DefaultMQPushConsumer(properties.consumerGroup());
        candidate.setNamesrvAddr(properties.nameserver());
        candidate.setInstanceName(instance);
        candidate.setConsumeThreadMin(1);
        candidate.setConsumeThreadMax(1);
        candidate.setConsumeMessageBatchMaxSize(1);
        candidate.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        candidate.setConsumeTimeout(Math.max(10, properties.processTimeout().toMinutes() + 2));
        candidate.setMaxReconsumeTimes(2);
        candidate.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            for (var message : messages) {
                WorkMessage work;
                try {
                    if (message.getBody() == null || message.getBody().length > 4096) throw new IllegalArgumentException();
                    work = mapper.readValue(message.getBody(), WorkMessage.class);
                    if (work == null || work.taskId() == null || work.kind() == null || work.unitIndex() < 0
                            || work.generation() < 0 || !UUID.fromString(work.taskId()).toString().equals(work.taskId())) {
                        throw new IllegalArgumentException();
                    }
                } catch (Exception invalid) {
                    log.warn("忽略不符合解析消息格式的消息，messageId={}", message.getMsgId());
                    continue;
                }
                try { worker.consume(work); }
                catch (RuntimeException failure) {
                    log.warn("解析阶段未能确认交接，将由消息重投或数据库恢复，任务 {}", work.taskId());
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        try {
            candidate.subscribe(properties.topic(), "*");
            candidate.start();
            consumer = candidate;
        } catch (Exception failure) {
            candidate.shutdown();
            log.warn("后台任务消费者暂未连接，将继续重试；持久任务仍可查询");
        }
    }

    private void startProducer() throws Exception {
        if (producer != null) return;
        if (properties.nameserver().isBlank()) throw new IllegalStateException("后台任务地址尚未配置");
        DefaultMQProducer candidate = new DefaultMQProducer(properties.producerGroup());
        candidate.setNamesrvAddr(properties.nameserver());
        candidate.setInstanceName(instance);
        candidate.setRetryTimesWhenSendFailed(0);
        try { candidate.start(); producer = candidate; }
        catch (Exception failure) { candidate.shutdown(); throw failure; }
    }

    @PreDestroy public synchronized void close() {
        closed = true;
        if (consumer != null) consumer.shutdown();
        if (producer != null) producer.shutdown();
    }
}
