package com.urlshort.shortener.repository;
import com.urlshort.shortener.model.UrlMapping;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Value;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.util.Optional;
import java.util.Map;


@Repository
@RequiredArgsConstructor
public class UrlRepository {

    private final DynamoDbTable<UrlMapping> urlMappingTable;
    private final DynamoDbClient dynamoDbClient;

    @Value("${aws.dynamodb.table-name}")
    private String tableName;

    /**
     * 條件式寫入：只有當 shortCode 不存在時才允許 putItem。
     * 若已存在，DynamoDB 會回 ConditionalCheckFailedException，由 caller 決定 retry / 拋出。
     */
    public UrlMapping save(UrlMapping mapping) {
        Expression condition = Expression.builder()
                .expression("attribute_not_exists(shortCode)")
                .build();

        PutItemEnhancedRequest<UrlMapping> request = PutItemEnhancedRequest
                .builder(UrlMapping.class)
                .item(mapping)
                .conditionExpression(condition)
                .build();

        urlMappingTable.putItem(request);
        return mapping;
    }

    public Optional<UrlMapping> findByShortCode(String shortCode) {
        UrlMapping item = urlMappingTable.getItem(Key.builder()
                .partitionValue(shortCode)
                .build());
        return Optional.ofNullable(item);
    }

    public void incrementClickCount(String shortCode) {
        UpdateItemRequest request = UpdateItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("shortCode",
                        AttributeValue.builder().s(shortCode).build()))
                .updateExpression("ADD clickCount :inc")
                .expressionAttributeValues(Map.of(
                        ":inc", AttributeValue.builder().n("1").build()))
                .build();
        dynamoDbClient.updateItem(request);
    }
}

