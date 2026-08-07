package com.urlshort.shortener.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/cache-test")
@RequiredArgsConstructor
public class CacheTestController {

    private final RedisTemplate<String, String> redisTemplate;

    private static final Duration TTL = Duration.ofSeconds(60);

    // SET key（60 秒 TTL）
    @PostMapping("/{key}")
    public Map<String, Object> set(@PathVariable String key, @RequestBody String value) {
        String redisKey = "test:" + key;
        redisTemplate.opsForValue().set(redisKey, value, TTL);
        log.info("cache SET key={} ttl={}s", redisKey, TTL.toSeconds());
        return Map.of("status", "ok", "key", redisKey, "ttlSeconds", TTL.toSeconds());
    }

    // GET key
    @GetMapping("/{key}")
    public Map<String, Object> get(@PathVariable String key) {
        String redisKey = "test:" + key;
        String value = redisTemplate.opsForValue().get(redisKey);
        boolean hit = (value != null);
        log.info("cache GET key={} hit={}", redisKey, hit);
        return Map.of("key", redisKey, "hit", hit, "value", value == null ? "(nil)" : value);
    }
}