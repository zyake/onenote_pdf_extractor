package com.extractor.model;

import java.time.Instant;

/**
 * Metadata about a single OneNote page.
 */
/**
 * Metadata about a single OneNote page.
 */
public record PageInfo(String pageId, String title, Instant createdDateTime, Instant lastModifiedDateTime) {
}
