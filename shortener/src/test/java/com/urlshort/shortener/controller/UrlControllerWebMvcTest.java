package com.urlshort.shortener.controller;

import com.urlshort.shortener.model.UrlMapping;
import com.urlshort.shortener.security.ApiKeyProvider;
import com.urlshort.shortener.service.ClickEventPublisher;
import com.urlshort.shortener.service.UrlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * ★ Web 切片測試：只載入 web 層（controller / @ControllerAdvice / Filter），
 *   不碰 DynamoDB、不碰 Redis、不碰 AWS —— 所以它能在 CI 的空機器上跑。
 */
@WebMvcTest(UrlController.class)
class UrlControllerWebMvcTest {

    private static final String VALID_KEY = "test-api-key-do-not-use-in-prod";
    private static final String BODY = "{\"longUrl\":\"https://www.example.com/x\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private UrlService urlService;
    @MockitoBean private ClickEventPublisher clickEventPublisher;
    @MockitoBean private ApiKeyProvider apiKeyProvider;   // ★ ApiKeyFilter 的依賴，不 mock 會起不來

    @BeforeEach
    void setUp() {
        when(apiKeyProvider.apiKey()).thenReturn(VALID_KEY);
    }

    @Test
    void postShorten_withoutApiKey_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"));

        // ★★ 這一行才是這個測試的靈魂：證明 filter 在【進到 controller 之前】就擋掉了
        verifyNoInteractions(urlService);
    }

    @Test
    void postShorten_withWrongApiKey_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/shorten")
                        .header("X-API-Key", "definitely-not-the-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(urlService);
    }

    @Test
    void postShorten_withValidApiKey_returns201() throws Exception {
        UrlMapping saved = new UrlMapping();
        saved.setShortCode("abc1234");
        saved.setLongUrl("https://www.example.com/x");
        when(urlService.shortenUrl(anyString())).thenReturn(saved);

        mockMvc.perform(post("/api/v1/shorten")
                        .header("X-API-Key", VALID_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").value("abc1234"));
    }

    @Test
    void getMissingShortCode_returns404_andNeedsNoApiKey() throws Exception {
        when(urlService.getByShortCode("nope1234")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/{shortCode}", "nope1234"))   // ★ 故意不帶 API key
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Short code not found: nope1234"));
    }

    @Test
    void getRedirect_isPublic_returns302() throws Exception {
        UrlMapping found = new UrlMapping();
        found.setShortCode("abc1234");
        found.setLongUrl("https://httpbin.org/get");
        when(urlService.getByShortCode("abc1234")).thenReturn(Optional.of(found));

        mockMvc.perform(get("/api/v1/{shortCode}", "abc1234"))    // ★ 產品本體必須公開
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://httpbin.org/get"));
    }
}