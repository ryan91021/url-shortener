package com.urlshort.shortener.service;

import com.urlshort.shortener.model.UrlMapping;
import com.urlshort.shortener.repository.UrlRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import com.urlshort.shortener.exception.ShortCodeGenerationException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;




@ExtendWith(MockitoExtension.class)
class UrlServiceTest {

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private UrlService urlService;

    // 用 @BeforeEach 統一把「快取一律 miss」設好，避免每個測試重複寫
    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.get(anyString())).thenReturn(null);  // null = cache miss → 落到 DynamoDB
    }

    @Test
    void shortenUrl_persistsMappingAndReturnsShortCode(){
        when(urlRepository.save(any(UrlMapping.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        UrlMapping saved = urlService.shortenUrl("https://www.google.com");

        assertNotNull(saved.getShortCode());
        assertFalse(saved.getShortCode().isEmpty());
        assertEquals("https://www.google.com", saved.getLongUrl());
        assertEquals(0L, saved.getClickCount());
        assertNotNull(saved.getCreatedAt());

        verify(urlRepository, times(1)).save(any(UrlMapping.class));
    }

    @Test
    void getByShortCode_returnsEmpty_whenRepoMisses(){
        when(urlRepository.findByShortCode("missing")).thenReturn(Optional.empty());

        Optional<UrlMapping> result = urlService.getByShortCode("missing");

        assertTrue(result.isEmpty());
        verify(urlRepository).findByShortCode("missing");
    }

    @Test
    void getByShortCode_returnsMapping_whenRepoHits(){
        UrlMapping fixture = new UrlMapping();
        fixture.setShortCode("abc1234");
        fixture.setLongUrl("https://example.com");
        when(urlRepository.findByShortCode("abc1234")).thenReturn(Optional.of(fixture));

        UrlMapping result = urlService.getByShortCode("abc1234").orElseThrow();

        assertEquals("https://example.com", result.getLongUrl());
    }

    @Test
    void shortenUrl_succeedsOnFirstAttempt_whenNoCollision(){
        when(urlRepository.save(any(UrlMapping.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        UrlMapping result = urlService.shortenUrl("https://www.example.com");

        assertNotNull(result.getShortCode());
        verify(urlRepository, times(1)).save(any(UrlMapping.class));
    }

    @Test
    void shortenUrl_retriesAndSucceeds_whenFirstCallCollides(){
        ConditionalCheckFailedException ccfe = ConditionalCheckFailedException.builder()
                .message("condition not met")
                .build();

        // 第一次拋碰撞、第二次成功
        when(urlRepository.save(any(UrlMapping.class)))
                .thenThrow(ccfe)
                .thenAnswer(inv -> inv.getArgument(0));

        UrlMapping result = urlService.shortenUrl("https://www.example.com");

        assertNotNull(result.getShortCode());
        verify(urlRepository, times(2)).save(any(UrlMapping.class));
    }

    @Test
    void shortenUrl_throwsShortCodeGenerationException_whenAllRetriesExhausted(){
        ConditionalCheckFailedException ccfe = ConditionalCheckFailedException.builder()
                .message("condition not met")
                .build();

        when(urlRepository.save(any(UrlMapping.class)))
                .thenThrow(ccfe);

        assertThrows(ShortCodeGenerationException.class,
                () -> urlService.shortenUrl("https://www.example.com"));

        verify(urlRepository, times(3)).save(any(UrlMapping.class));
    }
}