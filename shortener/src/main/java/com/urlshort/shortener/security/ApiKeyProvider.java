package com.urlshort.shortener.security;

/**
 * 「目前有效的 API key 是什麼」——來源可能是 Secrets Manager（雲上/本機）或本機屬性（測試）。
 * ★ 抽成介面的唯一理由：讓 @WebMvcTest 能用一個 mock 取代它，測試才不需要 AWS。
 */
@FunctionalInterface
public interface ApiKeyProvider {
    String apiKey();
}