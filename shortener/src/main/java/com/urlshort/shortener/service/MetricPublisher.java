package com.urlshort.shortener.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetricPublisher {

    private static final String NAMESPACE = "UrlShortener";

    private final CloudWatchClient cloudWatchClient;

    @Async("clickEventExecutor")   // ★ 復用 Day 22 有界池；fire-and-forget，別在讀路徑上同步打 CloudWatch
    public void recordCacheHit(boolean hit) {
        try {
            MetricDatum datum = MetricDatum.builder()
                    .metricName("CacheHit")
                    .value(hit ? 1.0 : 0.0)          // hit=1 / miss=0 → CloudWatch Average ≈ 命中率
                    .unit(StandardUnit.COUNT)
                    .build();
            cloudWatchClient.putMetricData(b -> b
                    .namespace(NAMESPACE)
                    .metricData(datum));
        } catch (Exception e) {
            // 觀測失敗絕不能拖累主流程：吞成 warn（別讓 metric 掛掉影響 redirect）
            log.warn("failed to publish CacheHit metric hit={}", hit, e);
        }
    }
}