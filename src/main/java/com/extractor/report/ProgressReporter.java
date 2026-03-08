package com.extractor.report;

import com.extractor.model.FailedPage;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Displays progress to stdout and writes to a log file.
 * Supports reporting page export start, success, failure, and a final summary.
 * Thread-safe: {@code output} and {@code reportSummary} are synchronized to prevent
 * interleaved lines under concurrent access.
 */
public class ProgressReporter {

    private final Path logFilePath;
    private final PrintWriter logWriter;

    /**
     * Creates a ProgressReporter that writes to both stdout and the specified log file.
     *
     * @param logFilePath path to the log file (e.g., output-dir/export.log)
     * @throws IOException if the log file cannot be created or opened
     */
    public ProgressReporter(Path logFilePath) throws IOException {
        this.logFilePath = logFilePath;
        var parent = logFilePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.logWriter = new PrintWriter(
                Files.newBufferedWriter(logFilePath, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND),
                true // auto-flush
        );
    }

    /**
     * Report start of a page export.
     * Prints: "Exporting page X of Y: Title"
     */
    public void reportPageStart(int current, int total, String pageTitle) {
        var message = "Exporting page %d of %d: %s".formatted(current, total, pageTitle);
        output(message);
    }

    /**
     * Report successful page export.
     */
    public void reportPageSuccess(int current, int total, String pageTitle, String filename) {
        var message = "  SUCCESS: page %d of %d: %s -> %s".formatted(current, total, pageTitle, filename);
        output(message);
    }

    /**
     * Report failed page export.
     */
    public void reportPageFailure(int current, int total, String pageTitle, String pageId, String error) {
        var message = "  FAILED: page %d of %d: %s (id: %s) - %s".formatted(
                current, total, pageTitle, pageId, error);
        output(message);
    }

    /**
     * Report final summary showing success/failure counts and listing failed pages.
     * Synchronized to ensure the multi-line summary block is atomic under concurrent access.
     */
    public synchronized void reportSummary(int totalPages, int successCount, List<FailedPage> failures) {
        var failureCount = failures != null ? failures.size() : 0;

        output("");
        output("=== Export Summary ===");
        output("Total pages: %d".formatted(totalPages));
        output("Succeeded: %d".formatted(successCount));
        output("Failed: %d".formatted(failureCount));

        if (failures != null && !failures.isEmpty()) {
            output("");
            output("Failed pages:");
            failures.stream()
                    .map(fp -> "  - %s (id: %s): %s".formatted(fp.pageTitle(), fp.pageId(), fp.errorMessage()))
                    .forEach(this::output);
        }
    }

    /**
     * Writes a message to both stdout and the log file.
     * Synchronized to prevent interleaved lines under concurrent access.
     */
    private synchronized void output(String message) {
        System.out.println(message);
        logWriter.println(message);
    }

    /**
     * Closes the log file writer. Should be called when reporting is complete.
     */
    public void close() {
        logWriter.close();
    }

    /**
     * Returns the path to the log file.
     */
    public Path getLogFilePath() {
        return logFilePath;
    }
}
