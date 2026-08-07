package com.urlshort.shortener.service;

import com.urlshort.shortener.exception.ShortCodeGenerationException;
import com.urlshort.shortener.model.UrlMapping;
import com.urlshort.shortener.repository.UrlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.RedisTemplate;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;


import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UrlService {

    private static final int MAX_ATTEMPTS = 3;
    private static final String CACHE_PREFIX = "url:";
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private final UrlRepository urlRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final MetricPublisher metricPublisher;

    public UrlMapping shortenUrl(String longUrl) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String shortCode = generateShortCode();

            UrlMapping mapping = new UrlMapping();
            mapping.setShortCode(shortCode);
            mapping.setLongUrl(longUrl);
            mapping.setCreatedAt(Instant.now());
            mapping.setClickCount(0L);

            try {
                urlRepository.save(mapping);
                log.info("Shortened url: shortCode={}, longUrl={}, attempt={}",
                        shortCode, longUrl, attempt);
                return mapping;
            } catch (ConditionalCheckFailedException ex) {
                // shortCode 撞了，重新生成 retry
                log.warn("Short code collision on attempt {} of {}: shortCode={}",
                        attempt, MAX_ATTEMPTS, shortCode);
                // 不 throw，繼續下一輪 for
            }
        }

        // 三次都撞——這在 7-char base36/62 字元空間幾乎不可能，發生代表系統異常
        log.error("Failed to generate unique short code after {} attempts for longUrl={}",
                MAX_ATTEMPTS, longUrl);
        throw new ShortCodeGenerationException(
                "Failed to generate unique short code after " + MAX_ATTEMPTS + " attempts");
    }

    public Optional<UrlMapping> getByShortCode(String shortCode) {
        String cacheKey = CACHE_PREFIX + shortCode;   // "url:" + shortCode

        // 1. 先查 Redis
        String cachedLongUrl = redisTemplate.opsForValue().get(cacheKey);
        if (cachedLongUrl != null) {
            log.info("cache HIT shortCode={} (skip DynamoDB read)", shortCode);
            metricPublisher.recordCacheHit(true);
            UrlMapping cached = new UrlMapping();
            cached.setShortCode(shortCode);
            cached.setLongUrl(cachedLongUrl);          // 只還原 redirect 需要的 longUrl
            return Optional.of(cached);
        }

        // 2. miss → 查 DynamoDB
        log.info("cache MISS shortCode={} (querying DynamoDB)", shortCode);
        metricPublisher.recordCacheHit(false);
        Optional<UrlMapping> fromDb = urlRepository.findByShortCode(shortCode);

        // 3. 命中才回填 Redis（TTL 1h）；查無【不寫】(避免負快取灌一堆 null)
        fromDb.ifPresent(mapping ->
                redisTemplate.opsForValue().set(cacheKey, mapping.getLongUrl(), CACHE_TTL));

        return fromDb;
    }

    private String generateShortCode() {
        String raw = Long.toString(System.nanoTime(), 36);
        return raw.length() > 7 ? raw.substring(raw.length() - 7) : raw;
    }

    public void recordClick(String shortCode) {
        urlRepository.incrementClickCount(shortCode);
    }
}