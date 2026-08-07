package com.urlshort.shortener.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.hibernate.validator.constraints.URL;

@Data
public class ShortenerRequest {

    @NotBlank(message = "longUrl must bot be a blank")
    @URL(message = "longUrl must be a valid URL")
    private String longUrl;
}
