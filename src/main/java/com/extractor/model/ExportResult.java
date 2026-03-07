package com.extractor.model;

import java.util.List;

/**
 * Summary of an export operation.
 */
public record ExportResult(int totalPages, int successCount, int failureCount, List<FailedPage> failures) {

    public ExportResult {
        failures = failures != null ? List.copyOf(failures) : List.of();
    }
}
