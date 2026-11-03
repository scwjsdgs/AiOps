package com.opsagent.service;

import com.opsagent.model.DocumentChangeEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 文档变更事件异步投递 Kafka。
 *
 * Webhook 只负责快速接收并返回 202，实际事件发送放到 Kafka，
 * Python 侧消费后执行切片 / embedding / 增量 upsert / 软删除。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentChangeService {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${opsagent.kafka.doc-change-topic:doc-change}")
    private String topic;

    public Mono<Void> sendAsync(DocumentChangeEvent event) {
        return Mono.fromCallable(() -> {
            try {
                kafkaTemplate.send(topic, event.getDocId(), event).get(5, java.util.concurrent.TimeUnit.SECONDS);
                log.info("文档变更事件已发送 Kafka docId={} status={} topic={}",
                        event.getDocId(), event.getStatus(), topic);
                return true;
            } catch (Exception e) {
                log.error("文档变更事件发送失败 docId={} topic={}: {}",
                        event.getDocId(), topic, e.getMessage());
                return false;
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }
}