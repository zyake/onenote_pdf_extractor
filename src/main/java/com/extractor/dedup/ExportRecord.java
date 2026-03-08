package com.extractor.dedup;

import java.time.Instant;

/**
 * Immutable record tracking a single OneNote page export.
 * Stored in the DynamoDB ExportTracker table for deduplication.
 */
public record ExportRecord(
    String pageId,
    Instant lastModifiedTimestamp,
    String s3Key,
    Instant exportTimestamp,
    boolean notebookLmUploaded
) {}
