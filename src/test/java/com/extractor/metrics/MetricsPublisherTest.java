package com.extractor.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.CloudWatchException;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataResponse;
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MetricsPublisher.
 * Validates: Requirements 7.3
 */
class MetricsPublisherTest {

    private static final String NAMESPACE = "OneNoteExporter/Pipeline";

    private CloudWatchClient mockCloudWatch;
    private MetricsPublisher publisher;

    @BeforeEach
    void setUp() {
        mockCloudWatch = mock(CloudWatchClient.class);
        publisher = new MetricsPublisher(mockCloudWatch, NAMESPACE);
    }

    @Test
    void publishRunMetrics_sendsAllFourMetrics() {
        when(mockCloudWatch.putMetricData(any(PutMetricDataRequest.class)))
                .thenReturn(PutMetricDataResponse.builder().build());

        publisher.publishRunMetrics(10, 5, 2, 3500L);

        var captor = ArgumentCaptor.forClass(PutMetricDataRequest.class);
        verify(mockCloudWatch).putMetricData(captor.capture());

        var request = captor.getValue();
        assertThat(request.namespace()).isEqualTo(NAMESPACE);
        assertThat(request.metricData()).hasSize(4);
    }

    @Test
    void publishRunMetrics_containsCorrectMetricNames() {
        when(mockCloudWatch.putMetricData(any(PutMetricDataRequest.class)))
                .thenReturn(PutMetricDataResponse.builder().build());

        publisher.publishRunMetrics(10, 5, 2, 3500L);

        var captor = ArgumentCaptor.forClass(PutMetricDataRequest.class);
        verify(mockCloudWatch).putMetricData(captor.capture());

        var metricNames = captor.getValue().metricData().stream()
                .map(MetricDatum::metricName)
                .toList();

        assertThat(metricNames).containsExactly(
                "PagesExported", "PagesSkipped", "PagesFailed", "RunDurationMs"
        );
    }

    @Test
    void publishRunMetrics_containsCorrectValues() {
        when(mockCloudWatch.putMetricData(any(PutMetricDataRequest.class)))
                .thenReturn(PutMetricDataResponse.builder().build());

        publisher.publishRunMetrics(42, 13, 7, 9876L);

        var captor = ArgumentCaptor.forClass(PutMetricDataRequest.class);
        verify(mockCloudWatch).putMetricData(captor.capture());

        var metrics = captor.getValue().metricData();
        assertThat(findMetric(metrics, "PagesExported").value()).isEqualTo(42.0);
        assertThat(findMetric(metrics, "PagesSkipped").value()).isEqualTo(13.0);
        assertThat(findMetric(metrics, "PagesFailed").value()).isEqualTo(7.0);
        assertThat(findMetric(metrics, "RunDurationMs").value()).isEqualTo(9876.0);
    }

    @Test
    void publishRunMetrics_usesCorrectUnits() {
        when(mockCloudWatch.putMetricData(any(PutMetricDataRequest.class)))
                .thenReturn(PutMetricDataResponse.builder().build());

        publisher.publishRunMetrics(1, 2, 3, 1000L);

        var captor = ArgumentCaptor.forClass(PutMetricDataRequest.class);
        verify(mockCloudWatch).putMetricData(captor.capture());

        var metrics = captor.getValue().metricData();
        assertThat(findMetric(metrics, "PagesExported").unit()).isEqualTo(StandardUnit.COUNT);
        assertThat(findMetric(metrics, "PagesSkipped").unit()).isEqualTo(StandardUnit.COUNT);
        assertThat(findMetric(metrics, "PagesFailed").unit()).isEqualTo(StandardUnit.COUNT);
        assertThat(findMetric(metrics, "RunDurationMs").unit()).isEqualTo(StandardUnit.MILLISECONDS);
    }

    @Test
    void publishRunMetrics_setsTimestampOnAllMetrics() {
        when(mockCloudWatch.putMetricData(any(PutMetricDataRequest.class)))
                .thenReturn(PutMetricDataResponse.builder().build());

        publisher.publishRunMetrics(0, 0, 0, 0L);

        var captor = ArgumentCaptor.forClass(PutMetricDataRequest.class);
        verify(mockCloudWatch).putMetricData(captor.capture());

        for (var metric : captor.getValue().metricData()) {
            assertThat(metric.timestamp()).isNotNull();
        }
    }

    @Test
    void publishRunMetrics_handlesZeroCounts() {
        when(mockCloudWatch.putMetricData(any(PutMetricDataRequest.class)))
                .thenReturn(PutMetricDataResponse.builder().build());

        publisher.publishRunMetrics(0, 0, 0, 0L);

        var captor = ArgumentCaptor.forClass(PutMetricDataRequest.class);
        verify(mockCloudWatch).putMetricData(captor.capture());

        var metrics = captor.getValue().metricData();
        assertThat(findMetric(metrics, "PagesExported").value()).isEqualTo(0.0);
        assertThat(findMetric(metrics, "PagesSkipped").value()).isEqualTo(0.0);
        assertThat(findMetric(metrics, "PagesFailed").value()).isEqualTo(0.0);
        assertThat(findMetric(metrics, "RunDurationMs").value()).isEqualTo(0.0);
    }

    @Test
    void publishRunMetrics_propagatesCloudWatchException() {
        when(mockCloudWatch.putMetricData(any(PutMetricDataRequest.class)))
                .thenThrow(CloudWatchException.builder().message("Service unavailable").build());

        assertThatThrownBy(() -> publisher.publishRunMetrics(1, 2, 3, 100L))
                .isInstanceOf(CloudWatchException.class)
                .hasMessageContaining("Service unavailable");
    }

    private MetricDatum findMetric(java.util.List<MetricDatum> metrics, String name) {
        return metrics.stream()
                .filter(m -> m.metricName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Metric not found: " + name));
    }
}
