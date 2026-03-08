package com.extractor.metrics;

import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit;

import java.time.Instant;
import java.util.List;

/**
 * Publishes pipeline run metrics to CloudWatch.
 * Validates: Requirements 7.3
 */
public class MetricsPublisher {

    private final CloudWatchClient cloudWatch;
    private final String namespace;

    public MetricsPublisher(CloudWatchClient cloudWatch, String namespace) {
        this.cloudWatch = cloudWatch;
        this.namespace = namespace;
    }

    /**
     * Publishes export run metrics to CloudWatch: pages exported, skipped, failed, and run duration.
     */
    public void publishRunMetrics(int exported, int skipped, int failed, long durationMs) {
        var timestamp = Instant.now();

        var metrics = List.of(
                metricDatum("PagesExported", exported, StandardUnit.COUNT, timestamp),
                metricDatum("PagesSkipped", skipped, StandardUnit.COUNT, timestamp),
                metricDatum("PagesFailed", failed, StandardUnit.COUNT, timestamp),
                metricDatum("RunDurationMs", durationMs, StandardUnit.MILLISECONDS, timestamp)
        );

        var request = PutMetricDataRequest.builder()
                .namespace(namespace)
                .metricData(metrics)
                .build();

        cloudWatch.putMetricData(request);
    }

    private MetricDatum metricDatum(String name, double value, StandardUnit unit, Instant timestamp) {
        return MetricDatum.builder()
                .metricName(name)
                .value(value)
                .unit(unit)
                .timestamp(timestamp)
                .build();
    }
}
