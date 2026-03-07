package com.extractor.model;

/**
 * Records a page that failed to export.
 */
public record FailedPage(String pageId, String pageTitle, String errorMessage) {
}
