package com.urlshort.shortener.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshort.shortener.model.UrlMapping;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class UrlControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean(name = "urlMappingTable")
    private DynamoDbTable<UrlMapping> urlMappingTable;

    @Test
    void postShorten_validLongUrl_returns201AndShortCode() throws Exception {
        // putItem 是 void，預設 mock 行為就是 no-op，不必 stub
        String body = """
                {"longUrl":"https://www.example.com/some/path"}
                """;

        mockMvc.perform(post("/api/v1/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").isNotEmpty())
                .andExpect(jsonPath("$.shortUrl").isNotEmpty())
                .andExpect(jsonPath("$.longUrl").value("https://www.example.com/some/path"));
    }

    @Test
    void postThenGet_redirectsToLongUrl() throws Exception {
        String body = """
                {"longUrl":"https://httpbin.org/get"}
                """;
        MvcResult created = mockMvc.perform(post("/api/v1/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        JsonNode json = objectMapper.readTree(created.getResponse().getContentAsString());
        String shortCode = json.get("shortCode").asText();
        assertThat(shortCode).isNotBlank();

        // stub getItem 回對應的 UrlMapping，模擬 DynamoDB 命中
        UrlMapping stub = new UrlMapping();
        stub.setShortCode(shortCode);
        stub.setLongUrl("https://httpbin.org/get");
        stub.setCreatedAt(Instant.now());
        stub.setClickCount(0L);
        when(urlMappingTable.getItem(any(Key.class))).thenReturn(stub);

        mockMvc.perform(get("/api/v1/{shortCode}", shortCode))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://httpbin.org/get"));
    }

    @Test
    void getMissingShortCode_returns404WithJsonBody() throws Exception {
        when(urlMappingTable.getItem(any(Key.class))).thenReturn(null);

        mockMvc.perform(get("/api/v1/{shortCode}", "nope1234"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Short code not found: nope1234"));
    }

    @Test
    void postShorten_blankLongUrl_returns400() throws Exception {
        String body = """
                {"longUrl":""}
                """;

        mockMvc.perform(post("/api/v1/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.longUrl").isNotEmpty());
    }
}