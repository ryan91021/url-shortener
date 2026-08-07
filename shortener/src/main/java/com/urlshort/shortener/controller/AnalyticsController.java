package com.urlshort.shortener.controller;

import com.urlshort.shortener.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1")                     // 同 UrlController 的前綴
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    // ★ 2 段路徑：/api/v1/analytics/{shortCode}；與 redirect 的 /api/v1/{shortCode}（1 段）不衝突
    @GetMapping("/analytics/{shortCode}")
    public ResponseEntity<Map<String, Long>> getAnalytics(@PathVariable String shortCode) {
        Map<String, Long> daily = analyticsService.getClickAnalytics(shortCode);
        log.info("analytics endpoint shortCode={} days={}", shortCode, daily.size());
        return ResponseEntity.ok(daily);       // 200 + JSON { "2026-06-19": 5, ... }
    }
}