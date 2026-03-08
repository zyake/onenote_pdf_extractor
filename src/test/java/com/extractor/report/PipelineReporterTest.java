package com.extractor.report;

import com.extractor.model.FailedPage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for PipelineReporter: summary formatting and failure detail formatting.
 * Validates: Requirements 7.1, 7.2
 */
class PipelineReporterTest {

    private final PipelineReporter reporter = new PipelineReporter();

    // --- formatSummary ---

    @Test
    void formatSummary_containsAllCounts() {
        var summary = reporter.formatSummary(100, 80, 15, 5, 78);

        assertThat(summary).contains("100");
        assertThat(summary).contains("80");
        assertThat(summary).contains("15");
        assertThat(summary).contains("5");
        assertThat(summary).contains("78");
    }

    @Test
    void formatSummary_containsLabelForEachMetric() {
        var summary = reporter.formatSummary(10, 7, 2, 1, 6);

        assertThat(summary).contains("Total pages:");
        assertThat(summary).contains("Exported:");
        assertThat(summary).contains("Skipped");
        assertThat(summary).contains("Failed:");
        assertThat(summary).contains("NotebookLM:");
    }

    @Test
    void formatSummary_zeroCounts() {
        var summary = reporter.formatSummary(0, 0, 0, 0, 0);

        assertThat(summary).contains("Total pages:");
        assertThat(summary).contains("0");
    }

    @Test
    void formatSummary_containsSummaryHeader() {
        var summary = reporter.formatSummary(1, 1, 0, 0, 1);

        assertThat(summary).contains("Pipeline Run Summary");
    }

    // --- formatFailures ---

    @Test
    void formatFailures_singleFailure_containsAllFields() {
        var failures = List.of(new FailedPage("pg-1", "My Page", "Connection timeout"));

        var result = reporter.formatFailures(failures);

        assertThat(result).contains("pg-1");
        assertThat(result).contains("My Page");
        assertThat(result).contains("Connection timeout");
    }

    @Test
    void formatFailures_multipleFailures_containsAllEntries() {
        var failures = List.of(
                new FailedPage("id-1", "Page A", "Network error"),
                new FailedPage("id-2", "Page B", "Timeout"),
                new FailedPage("id-3", "Page C", "403 Forbidden")
        );

        var result = reporter.formatFailures(failures);

        assertThat(result).contains("id-1").contains("Page A").contains("Network error");
        assertThat(result).contains("id-2").contains("Page B").contains("Timeout");
        assertThat(result).contains("id-3").contains("Page C").contains("403 Forbidden");
    }

    @Test
    void formatFailures_emptyList_returnsEmptyString() {
        var result = reporter.formatFailures(List.of());

        assertThat(result).isEmpty();
    }

    @Test
    void formatFailures_nullList_returnsEmptyString() {
        var result = reporter.formatFailures(null);

        assertThat(result).isEmpty();
    }

    @Test
    void formatFailures_containsHeader() {
        var failures = List.of(new FailedPage("pg-1", "Title", "Error"));

        var result = reporter.formatFailures(failures);

        assertThat(result).contains("Failed Pages");
    }
}
