package com.urlshort.shortener.filter;

import com.urlshort.shortener.security.ApiKeyProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

@Slf4j
@Component
@Order(2)                 // ★ 跑在 RequestIdFilter(@Order(1)) 之後 → 連 401 的 log 都帶得到 requestId
@RequiredArgsConstructor
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-API-Key";

    private final ApiKeyProvider apiKeyProvider;

    /**
     * ★★ 白名單思維的反面：這裡回傳 true = 【放行不檢查】。
     *    只有「寫入類 / 除錯類」端點回傳 false（＝要檢查）。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();

        // ① 建短碼：唯一的公開寫入端點
        if ("POST".equals(request.getMethod()) && "/api/v1/shorten".equals(path)) {
            return false;
        }
        // ② 除錯用的快取端點（會寫 Redis，本來就不該對公網開放）
        if (path.startsWith("/api/v1/cache-test")) {
            return false;
        }

        // 其餘一律放行，而且每一條都有理由：
        //   GET /api/v1/{shortCode}        → 產品本體，擋掉＝短網址壞掉
        //   GET /api/v1/analytics/{code}   → 唯讀（index 說「先保護寫入類」；它的 IDOR 問題見 README）
        //   GET /actuator/health           → ★★ ALB target group 的健康檢查打這裡，
        //                                      擋掉 = target unhealthy = 服務被判死（卡點 4）
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String expected = apiKeyProvider.apiKey();

        // ★★ fail closed：伺服器端沒有設好金鑰時，【全部擋掉】，絕不「沒設定就放行」（卡點 5）
        if (expected == null || expected.isBlank()) {
            log.error("API key is not configured on this instance -> rejecting {} {}",
                    request.getMethod(), request.getRequestURI());
            reject(response, "API key is not configured");
            return;
        }

        String provided = request.getHeader(HEADER);
        if (provided == null || !constantTimeEquals(provided, expected)) {
            // ★★ 絕不 log 對方送來的 key（連前 4 碼都不要）——log 會進 CloudWatch，那就是另一份外洩點
            log.warn("rejected request without a valid API key: {} {}",
                    request.getMethod(), request.getRequestURI());
            reject(response, "Missing or invalid API key");
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * ★ 用 MessageDigest.isEqual 而不是 String.equals：
     *   String.equals 逐字元比、遇到第一個不同就 return false → 回應時間會【洩漏你猜對了幾個字元】，
     *   攻擊者能一個字元一個字元地把 key 試出來（timing attack）。
     *   MessageDigest.isEqual 對【等長】輸入是常數時間。（長度不同仍會提早回，所以長度會洩漏
     *   —— 對固定長度的 API key 而言可以接受，面試講得出這個取捨就是加分題。）
     */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    /** 回一個跟 GlobalExceptionHandler 同形狀的 JSON（filter 在 @ControllerAdvice 之外，要自己寫） */
    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("""
                {"timestamp":"%s","status":401,"error":"Unauthorized","message":"%s"}"""
                .formatted(Instant.now(), message));
    }
}