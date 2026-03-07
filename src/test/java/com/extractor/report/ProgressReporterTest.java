package com.extractor.report;

import com.extractor.model.FailedPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ProgressReporter: output format, log file creation, and dual output.
 * Validates: Requirements 9.1, 9.2, 9.3, 9.4, 9.5
 */
class ProgressReporterTest {

    private final PrintStream originalOut = System.out;
    private ByteArrayOutputStream capturedOut;

    @BeforeEach
    void captureStdout() {
        capturedOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOut));
    }

    @AfterEach
    void restoreStdout() {
        System.setOut(originalOut);
    }

    // --- reportPageStart ---

    @Test
    void reportPageStart_outputsCorrectFormat(@TempDir Path tempDir) throws IOException {
        var logFile = tempDir.resolve("export.log");
        var reporter = new ProgressReporter(logFile);

        reporter.reportPageStart(3, 10, "My Notes");
        reporter.close();

        var stdout = capturedOut.toString();
        assertThat(stdout).contains("Exporting page 3 of 10: My Notes");
    }

    @Test
    void reportPageStart_includesAllFields(@TempDir Path tempDir) throws IOException {
        var logFile = tempDir.resolve("export.log");
        var reporter = new ProgressReporter(logFile);

        reporter.reportPageStart(1, 5, "Introduction");
        reporter.close();

        var stdout = capturedOut.toString();
        assertThat(stdout).contains("1").contains("5").contains("Introduction");
    }

    // --- reportPageSuccess ---

    @Test
    void reportPageSuccess_outputsCorrectFormat(@TempDir Path tempDir) throws IOException {
        var logFile = tempDir.resolve("export.log");
        var reporter = new ProgressReporter(logFile);

        reporter.reportPageSuccess(2, 8, "Chapter One", "Chapter_One.pdf");
        reporter.close();

        var stdout = capturedOut.toString();
        assertThat(stdout).contains("SUCCESS");
        assertThat(stdout).contains("2").contains("8");
        assertThat(stdout).contains("Chapter One");
        assertThat(stdout).contains("Chapter_One.pdf");
    }

    // --- reportPageFailure ---

    @Test
    void reportPageFailure_outputsCorrectFormat(@TempDir Path tempDir) throws IOException {
        var logFile = tempDir.resolve("export.log");
        var reporter = new ProgressReporter(logFile);

        reporter.reportPageFailure(4, 10, "Bad Page", "page-id-42", "Connection timeout");
        reporter.close();

        var stdout = capturedOut.toString();
        assertThat(stdout).contains("FAILED");
        assertThat(stdout).contains("4").contains("10");
        assertThat(stdout).contains("Bad Page");
        assertThat(stdout).contains("page-id-42");
        assertThat(stdout).contains("Connection timeout");
    }

    // --- reportSummary ---

    @Test
    void reportSummary_zeroFailures_showsCountsOnly(@TempDir Path tempDir) throws IOException {
        var logFile = tempDir.resolve("export.log");
        var reporter = new ProgressReporter(logFile);

        reporter.reportSummary(5, 5, List.of());
        reporter.close();

        var stdout = capturedOut.toString();
        assertThat(stdout).contains("Export Summary");
        assertThat(stdout).contains("Total pages: 5");
        assertThat(stdout).contains("Succeeded: 5");
        assertThat(stdout).contains("Failed: 0");
        assertThat(stdout).doesNotContain("Failed pages:");
    }

    @Test
    void reportSummary_withFailures_listsFailedPages(@TempDir Path tempDir) throws IOException {
        var logFile = tempDir.resolve("export.log");
        var reporter = new ProgressReporter(logFile);

        var failures = List.of(
                new FailedPage("id-1", "Page A", "Network error"),
                new FailedPage("id-2", "Page B", "Timeout")
        );
        reporter.reportSummary(10, 8, failures);
        reporter.close();

        var stdout = capturedOut.toString();
        assertThat(stdout).contains("Total pages: 10");
        assertThat(stdout).contains("Succeeded: 8");
        assertThat(stdout).contains("Failed: 2");
        assertThat(stdout).contains("Failed pages:");
        assertThat(stdout).contains("Page A").contains("id-1").contains("Network error");
        assertThat(stdout).contains("Page B").contains("id-2").contains("Timeout");
    }

    @Test
    void reportSummary_nullFailuresList_treatsAsZeroFailures(@TempDir Path tempDir) throws IOException {
        var logFile = tempDir.resolve("export.log");
        var reporter = new ProgressReporter(logFile);

        reporter.reportSummary(3, 3, null);
        reporter.close();

        var stdout = capturedOut.toString();
        assertThat(stdout).contains("Failed: 0");
        assertThat(stdout).doesNotContain("Failed pages:");
    }

    // --- Log file creation and content (Req 9.5) ---

    @Test
    void logFile_isCreatedAndContainsOutput(@TempDir Path tempDir) throws IOException {
        var logFile = tempDir.resolve("export.log");
        var reporter = new ProgressReporter(logFile);

        reporter.reportPageStart(1, 2, "First");
        reporter.reportPageSuccess(1, 2, "First", "First.pdf");
        reporter.reportPageFailure(2, 2, "Second", "pg-2", "Error");
        reporter.reportSummary(2, 1, List.of(new FailedPage("pg-2", "Second", "Error")));
        reporter.close();

        assertThat(Files.exists(logFile)).isTrue();
        var logContent = Files.readString(logFile);
        assertThat(logContent).contains("Exporting page 1 of 2: First");
        assertThat(logContent).contains("SUCCESS");
        assertThat(logContent).contains("FAILED");
        assertThat(logContent).contains("Export Summary");
    }

    @Test
    void logFile_contentMatchesStdout(@TempDir Path tempDir) throws IOException {
        var logFile = tempDir.resolve("export.log");
        var reporter = new ProgressReporter(logFile);

        reporter.reportPageStart(1, 3, "Test Page");
        reporter.reportPageSuccess(1, 3, "Test Page", "Test_Page.pdf");
        reporter.close();

        var stdoutLines = capturedOut.toString().trim();
        var logLines = Files.readString(logFile).trim();
        assertThat(logLines).isEqualTo(stdoutLines);
    }

    @Test
    void logFile_createsParentDirectories(@TempDir Path tempDir) throws IOException {
        var logFile = tempDir.resolve("nested/dir/export.log");
        var reporter = new ProgressReporter(logFile);

        reporter.reportPageStart(1, 1, "Nested");
        reporter.close();

        assertThat(Files.exists(logFile)).isTrue();
        assertThat(Files.readString(logFile)).contains("Nested");
    }
}
