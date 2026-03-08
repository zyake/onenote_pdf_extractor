package com.extractor.pipeline;

import com.extractor.model.FailedPage;
import java.util.List;

/**
 * Summary of a pipeline export run.
 */
public record PipelineResult(
    int totalPages,
    int exportedCount,
    int skippedCount,
    int failedCount,
    int uploadedToNotebookLmCount,
    long durationMs,
    List<FailedPage> failures
) {}
