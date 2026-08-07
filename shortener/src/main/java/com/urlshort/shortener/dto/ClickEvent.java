package com.urlshort.shortener.dto;

public record ClickEvent(
        String shortCode,
        String userAgent,
        String ip,
        String clickedAt     // ISO-8601 UTC（Instant.now().toString()）
) {}