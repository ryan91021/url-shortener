package com.urlshort.shortener.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
public class SqsConfig {

    @Value("${aws.region}")
    private String region;

    @Bean
    public SqsClient sqsClient() {
        return SqsClient.builder()
                .region(Region.of(region))                       // 跟 DynamoDbClient 同：ap-east-2
                .credentialsProvider(DefaultCredentialsProvider.create()) // 本機撿 aws.env 金鑰 / 雲上撿 task role
                .build();
    }
}