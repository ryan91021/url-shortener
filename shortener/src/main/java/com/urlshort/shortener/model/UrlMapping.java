package com.urlshort.shortener.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
public class UrlMapping {


    private String shortCode;
    private String longUrl;
    private Instant createdAt;
    private Long clickCount;

    @DynamoDbPartitionKey
    public String getShortCode() {
        return shortCode;
    }
}
