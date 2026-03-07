package com.extractor.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Summary of an export operation.
 */
public class ExportResult {
    private int totalPages;
    private int successCount;
    private int failureCount;
    private List<FailedPage> failures;

    public ExportResult() {
        this.failures = new ArrayList<>();
    }

    public ExportResult(int totalPages, int successCount, int failureCount, List<FailedPage> failures) {
        this.totalPages = totalPages;
        this.successCount = successCount;
        this.failureCount = failureCount;
        this.failures = failures != null ? failures : new ArrayList<>();
    }

    public int getTotalPages() { return totalPages; }
    public void setTotalPages(int totalPages) { this.totalPages = totalPages; }

    public int getSuccessCount() { return successCount; }
    public void setSuccessCount(int successCount) { this.successCount = successCount; }

    public int getFailureCount() { return failureCount; }
    public void setFailureCount(int failureCount) { this.failureCount = failureCount; }

    public List<FailedPage> getFailures() { return failures; }
    public void setFailures(List<FailedPage> failures) { this.failures = failures; }
}
