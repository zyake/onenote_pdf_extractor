package com.extractor.model;

/**
 * Sealed interface representing the outcome of a single page export attempt.
 * Each page export either succeeds (producing a file) or fails (recording the error).
 */
public sealed interface PageExportOutcome
        permits PageExportOutcome.Success, PageExportOutcome.Failure {

    /**
     * A successful page export, recording the page title and the output filename.
     */
    record Success(String pageTitle, String filename) implements PageExportOutcome {}

    /**
     * A failed page export, recording the page ID, title, and error message.
     */
    record Failure(String pageId, String pageTitle, String errorMessage) implements PageExportOutcome {}
}
