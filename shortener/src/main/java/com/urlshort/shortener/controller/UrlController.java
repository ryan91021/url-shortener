package com.urlshort.shortener.controller;

import com.urlshort.shortener.dto.ShortenerRequest;
import com.urlshort.shortener.dto.ShortenerResponse;
import com.urlshort.shortener.model.UrlMapping;
import com.urlshort.shortener.service.UrlService;
import com.urlshort.shortener.exception.ShortCodeNotFoundException;
import com.urlshort.shortener.service.ClickEventPublisher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.Optional;


@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class UrlController {

    private final UrlService urlService;
    private final ClickEventPublisher clickEventPublisher;

    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode,
                                         @RequestHeader(value = "User-Agent", required = false) String userAgent,
                                         @RequestHeader(value = "X-Forwarded-For", required = false) String xff,
                                         HttpServletRequest request){

        UrlMapping mapping = urlService.getByShortCode(shortCode)
                .orElseThrow(() -> new ShortCodeNotFoundException(shortCode));

        String ip = (xff != null && !xff.isBlank()) ? xff.split(",")[0].trim() : request.getRemoteAddr();
        clickEventPublisher.publish(shortCode, userAgent, ip);   // 立刻返回（任務丟給 clickEventExecutor）

        String longUrl = mapping.getLongUrl();
        log.info("Redirecting short code = {} to long url = {}", shortCode, longUrl);

        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(longUrl));
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }

    @PostMapping("/shorten")
    public ResponseEntity<ShortenerResponse> shorten(@Valid @RequestBody ShortenerRequest request){
        UrlMapping saved = urlService.shortenUrl(request.getLongUrl());

        String shortUrl = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/")
                .path(saved.getShortCode())
                .toUriString();

        ShortenerResponse body = new ShortenerResponse(
                saved.getShortCode(),
                shortUrl,
                saved.getLongUrl()
        );

        log.info("Created short code = {} for long url = {}", saved.getShortCode(), saved.getLongUrl());

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION, shortUrl)
                .body(body);
    }
}
