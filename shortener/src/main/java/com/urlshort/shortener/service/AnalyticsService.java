package com.urlshort.shortener.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final DynamoDbClient dynamoDbClient;    // 復用 DynamoDBConfig 既有的 @Bean

    @Value("${aws.dynamodb.analytics-table-name}")
    private String analyticsTableName;              // = ClickAnalytics（來自 application.yml）

    public Map<String, Long> getClickAnalytics(String shortCode) {
        QueryRequest request = QueryRequest.builder()
                .tableName(analyticsTableName)
                // ★ PK-only：撈某短碼「所有日期」的列（date 不進 KeyCondition！）
                .keyConditionExpression("shortCode = :sc")
                .expressionAttributeValues(Map.of(
                        ":sc", AttributeValue.builder().s(shortCode).build()))
                // ★ 只投影需要的兩欄；date 是保留字 → 用 #d 別名（保留字就咬在這裡）
                .projectionExpression("#d, clickCount")
                .expressionAttributeNames(Map.of("#d", "date"))
                .build();

        QueryResponse response = dynamoDbClient.query(request);

        // query 回來已按 SK(date) 升序 → LinkedHashMap 保留日期順序
        Map<String, Long> result = new LinkedHashMap<>();
        for (Map<String, AttributeValue> item : response.items()) {
            String date = item.get("date").s();                 // ★ 讀回傳用【真名 date】、不是 #d
            long count = Long.parseLong(item.get("clickCount").n()); // ★ Number → .n() 取字串再轉 long
            result.put(date, count);
        }
        log.info("analytics query shortCode={} days={}", shortCode, result.size());
        return result;
    }
}