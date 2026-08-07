package com.urlshort.shortener.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshort.shortener.dto.ClickEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Async;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClickEventPublisher {

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;      // Spring Boot 已提供（Jackson autoconfig）

    @Value("${aws.sqs.click-queue-name}")
    private String queueName;

    private String queueUrl;

    @PostConstruct
    void resolveQueueUrl() {                        // 啟動時由 name 解析出 URL（fail-fast：queue 不存在/無權限 → 起不來）
        this.queueUrl = sqsClient.getQueueUrl(b -> b.queueName(queueName)).queueUrl();
        log.info("resolved SQS queue url for {} = {}", queueName, queueUrl);
    }

    @Async("clickEventExecutor")
    public void publish(String shortCode, String userAgent, String ip) {
        ClickEvent event = new ClickEvent(shortCode, userAgent, ip, Instant.now().toString());
        try {
            String body = objectMapper.writeValueAsString(event);          // ★ Jackson 序列化，別手拼 JSON
            SendMessageResponse resp = sqsClient.sendMessage(b -> b
                    .queueUrl(queueUrl)
                    .messageBody(body));
            log.info("published click event shortCode={} messageId={}", shortCode, resp.messageId());
        } catch (Exception e) {
            // 今天先記 log；Day 22 fire-and-forget 再談讀路徑上的例外處理策略
            log.error("failed to serialize click event shortCode={}", shortCode, e);
        }
    }
}