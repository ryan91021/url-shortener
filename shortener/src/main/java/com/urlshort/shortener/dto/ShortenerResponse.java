package com.urlshort.shortener.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShortenerResponse {
    private String shortCode;
    private String shortUrl;
    private String longUrl;
}
