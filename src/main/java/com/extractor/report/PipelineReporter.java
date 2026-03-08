package com.extractor.report;

import com.extractor.model.FailedPage;

import java.util.List;

/**
 * Formats pipeline run summaries and failure details for logging to stdout.
 * CloudWatch Logs captures Lambda stdout automatically.
 */
public class PipelineReporter {

    /**
     * Formats a summary of the pipeline run with all key counts.
     *
     * @param totalPages              total pages found in the section
     * @param exportedCount           pages successfully exported to S3
     * @param skippedCount            pages skipped (deduplicated)
     * @param failedCount             pages that failed during export or upload
     * @param uploadedToNotebookLmCount pages uploaded to NotebookLM
     * @return formatted summary string
     */
    public String formatSummary(int totalPages, int exportedCount, int skippedCount,
                                int failedCount, int uploadedToNotebookLmCount) {
        return """
                === Pipeline Run Summary ===
                Total pages:             %d
                Exported:                %d
                Skipped (deduplicated):  %d
                Failed:                  %d
                Uploaded to NotebookLM:  %d"""
                .formatted(totalPages, exportedCount, skippedCount, failedCount, uploadedToNotebookLmCount);
    }

    /**
     * Formats failure details for each failed page.
     * Returns an empty string if the list is null or empty.
     *
     * @param failures list of failed pages
     * @return formatted failure details string
     */
    public String formatFailures(List<FailedPage> failures) {
        if (failures == null || failures.isEmpty()) {
            return "";
        }

        var sb = new StringBuilder("=== Failed Pages ===\n");
        for (var fp : failures) {
            sb.append("  - Page ID: %s | Title: %s | Error: %s\n"
                    .formatted(fp.pageId(), fp.pageTitle(), fp.errorMessage()));
        }
        return sb.toString().stripTrailing();
    }
}
