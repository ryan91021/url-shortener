package com.urlshort.shortener.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshort.shortener.security.ApiKeyProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

@Slf4j
@Configuration
public class ApiKeyConfig {

    /**
     * 正式路徑：啟動時去 Secrets Manager 讀一次，之後常駐記憶體。
     * ★ 為什麼只讀一次：getSecretValue 是網路呼叫，放在每個 request 上＝每次都多一段 RTT、又多一筆費用。
     * ★ 代價（誠實記下來）：secret 輪替後要【重啟服務】才會生效。真要熱更新得加排程刷新 + 雙金鑰並存期。
     */
    @Bean
    @ConditionalOnProperty(name = "app.security.api-key-source", havingValue = "aws-secrets",
            matchIfMissing = true)   // ★ 預設就是雲端來源：忘了設 = 走安全的那條路
    public ApiKeyProvider secretsManagerApiKeyProvider(
            @Value("${aws.region}") String region,
            @Value("${app.security.secret-name}") String secretName,
            ObjectMapper objectMapper) {

        try (SecretsManagerClient client = SecretsManagerClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())  // 本機撿 aws.env / 雲上撿 task role
                .build()) {

            String secretJson = client.getSecretValue(b -> b.secretId(secretName)).secretString();
            String key = objectMapper.readTree(secretJson).path("apiKey").asText(null);

            if (key == null || key.isBlank()) {
                throw new IllegalStateException("secret '" + secretName + "' has no non-empty 'apiKey' field");
            }
            log.info("API key loaded from Secrets Manager: secret={} length={}", secretName, key.length());
            return () -> key;          // ★ ApiKeyProvider 是 @FunctionalInterface → lambda 就是實作

        } catch (Exception e) {
            // ★★ 刻意 fail-fast：讀不到金鑰的服務不該啟動成功（承 ClickEventPublisher 的同一個設計，卡點 17）
            throw new IllegalStateException("failed to load API key from Secrets Manager: " + secretName, e);
        }
    }

    /** 本機/測試路徑：從屬性或環境變數拿（CI 就是走這條，所以 CI 一滴 AWS 都不需要） */
    @Bean
    @ConditionalOnProperty(name = "app.security.api-key-source", havingValue = "property")
    public ApiKeyProvider propertyApiKeyProvider(@Value("${app.security.api-key:}") String key) {
        log.warn("★ API key source = local property/env (dev & test only, length={})",
                key == null ? 0 : key.length());
        return () -> key;
    }
}