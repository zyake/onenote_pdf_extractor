package com.extractor.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for PageExportOutcome sealed interface.
 * Validates: Requirements 1.3, 5.1, 5.2
 */
class PageExportOutcomeTest {

    @Test
    void successRecordStoresPageTitleAndFilename() {
        var success = new PageExportOutcome.Success("My Page", "my-page.pdf");

        assertThat(success.pageTitle()).isEqualTo("My Page");
        assertThat(success.filename()).isEqualTo("my-page.pdf");
    }

    @Test
    void failureRecordStoresPageIdTitleAndErrorMessage() {
        var failure = new PageExportOutcome.Failure("page-123", "Bad Page", "Connection timeout");

        assertThat(failure.pageId()).isEqualTo("page-123");
        assertThat(failure.pageTitle()).isEqualTo("Bad Page");
        assertThat(failure.errorMessage()).isEqualTo("Connection timeout");
    }

    @Test
    void successIsInstanceOfPageExportOutcome() {
        PageExportOutcome outcome = new PageExportOutcome.Success("Title", "file.pdf");

        assertThat(outcome).isInstanceOf(PageExportOutcome.Success.class);
    }

    @Test
    void failureIsInstanceOfPageExportOutcome() {
        PageExportOutcome outcome = new PageExportOutcome.Failure("id", "Title", "error");

        assertThat(outcome).isInstanceOf(PageExportOutcome.Failure.class);
    }

    @Test
    void patternMatchingWorksOnSuccess() {
        PageExportOutcome outcome = new PageExportOutcome.Success("Page A", "page-a.pdf");

        var result = switch (outcome) {
            case PageExportOutcome.Success s -> "Exported " + s.pageTitle() + " to " + s.filename();
            case PageExportOutcome.Failure f -> "Failed: " + f.errorMessage();
        };

        assertThat(result).isEqualTo("Exported Page A to page-a.pdf");
    }

    @Test
    void patternMatchingWorksOnFailure() {
        PageExportOutcome outcome = new PageExportOutcome.Failure("id-1", "Page B", "404 Not Found");

        var result = switch (outcome) {
            case PageExportOutcome.Success s -> "Exported " + s.pageTitle();
            case PageExportOutcome.Failure f -> "Failed " + f.pageTitle() + ": " + f.errorMessage();
        };

        assertThat(result).isEqualTo("Failed Page B: 404 Not Found");
    }

    @Test
    void successRecordsWithSameValuesAreEqual() {
        var a = new PageExportOutcome.Success("Title", "file.pdf");
        var b = new PageExportOutcome.Success("Title", "file.pdf");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void failureRecordsWithSameValuesAreEqual() {
        var a = new PageExportOutcome.Failure("id", "Title", "error");
        var b = new PageExportOutcome.Failure("id", "Title", "error");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void successAndFailureAreNotEqual() {
        PageExportOutcome success = new PageExportOutcome.Success("Title", "file.pdf");
        PageExportOutcome failure = new PageExportOutcome.Failure("id", "Title", "error");

        assertThat(success).isNotEqualTo(failure);
    }
}
