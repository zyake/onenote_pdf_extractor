package com.extractor.report;

import com.extractor.model.FailedPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Thread-safety tests for ProgressReporter.
 * Validates: Requirements 4.1, 4.2, 4.3, 4.4
 */
class ProgressReporterThreadSafetyTest {

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

    @Test
    void concurrentOutputProducesCompleteLines(@TempDir Path tempDir) throws Exception {
        var logFile = tempDir.resolve("concurrent.log");
        var reporter = new ProgressReporter(logFile);
        var threadCount = 20;
        var latch = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threadCount; i++) {
                var index = i;
                executor.submit(() -> {
                    try {
                        latch.await();
                        reporter.reportPageStart(index + 1, threadCount, "Page-" + index);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            latch.countDown();
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
        reporter.close();

        var logContent = Files.readString(logFile);
        var logLines = logContent.lines().filter(l -> !l.isBlank()).toList();

        assertThat(logLines).hasSize(threadCount);
        for (var line : logLines) {
            assertThat(line).matches("Exporting page \\d+ of %d: Page-\\d+".formatted(threadCount));
        }
    }

    @Test
    void concurrentSuccessAndFailureMessagesAreComplete(@TempDir Path tempDir) throws Exception {
        var logFile = tempDir.resolve("mixed.log");
        var reporter = new ProgressReporter(logFile);
        var threadCount = 20;
        var latch = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threadCount; i++) {
                var index = i;
                executor.submit(() -> {
                    try {
                        latch.await();
                        if (index % 2 == 0) {
                            reporter.reportPageSuccess(index + 1, threadCount, "Page-" + index, "Page_" + index + ".pdf");
                        } else {
                            reporter.reportPageFailure(index + 1, threadCount, "Page-" + index, "id-" + index, "Error-" + index);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            latch.countDown();
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
        reporter.close();

        var logContent = Files.readString(logFile);
        var logLines = logContent.lines().filter(l -> !l.isBlank()).toList();

        assertThat(logLines).hasSize(threadCount);

        var successLines = logLines.stream().filter(l -> l.contains("SUCCESS")).toList();
        var failureLines = logLines.stream().filter(l -> l.contains("FAILED")).toList();

        assertThat(successLines).hasSize(threadCount / 2);
        assertThat(failureLines).hasSize(threadCount / 2);

        for (var line : successLines) {
            assertThat(line).contains("SUCCESS").contains("Page-").contains(".pdf");
        }
        for (var line : failureLines) {
            assertThat(line).contains("FAILED").contains("Page-").contains("id-").contains("Error-");
        }
    }

    @Test
    void reportSummaryIsAtomicUnderConcurrentAccess(@TempDir Path tempDir) throws Exception {
        var logFile = tempDir.resolve("summary.log");
        var reporter = new ProgressReporter(logFile);
        var latch = new CountDownLatch(1);

        // Fire reportSummary and several reportPageStart calls concurrently
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> {
                try {
                    latch.await();
                    var failures = List.of(new FailedPage("id-1", "Bad Page", "Timeout"));
                    reporter.reportSummary(10, 9, failures);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            for (int i = 0; i < 10; i++) {
                var index = i;
                executor.submit(() -> {
                    try {
                        latch.await();
                        reporter.reportPageStart(index + 1, 10, "Concurrent-" + index);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            latch.countDown();
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
        reporter.close();

        var logContent = Files.readString(logFile);
        var logLines = logContent.lines().toList();

        // Find the summary block — it must appear as a contiguous sequence with no
        // interleaved "Exporting page" lines between summary lines
        var summaryStartIdx = -1;
        for (int i = 0; i < logLines.size(); i++) {
            if (logLines.get(i).contains("=== Export Summary ===")) {
                summaryStartIdx = i;
                break;
            }
        }
        assertThat(summaryStartIdx).isGreaterThanOrEqualTo(0);

        // The summary block: blank, header, total, succeeded, failed, blank, "Failed pages:", detail
        // That's 8 lines starting from the blank line before the header
        var summaryBlock = logLines.subList(summaryStartIdx - 1, summaryStartIdx + 7);
        assertThat(summaryBlock).anyMatch(l -> l.contains("Total pages: 10"));
        assertThat(summaryBlock).anyMatch(l -> l.contains("Succeeded: 9"));
        assertThat(summaryBlock).anyMatch(l -> l.contains("Failed: 1"));
        assertThat(summaryBlock).anyMatch(l -> l.contains("Bad Page"));

        // No "Exporting page" line should appear inside the summary block
        for (var line : summaryBlock) {
            assertThat(line).doesNotContain("Exporting page");
        }
    }

    @Test
    void logFileMatchesStdoutUnderConcurrency(@TempDir Path tempDir) throws Exception {
        var logFile = tempDir.resolve("dual.log");
        var reporter = new ProgressReporter(logFile);
        var threadCount = 10;
        var latch = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threadCount; i++) {
                var index = i;
                executor.submit(() -> {
                    try {
                        latch.await();
                        reporter.reportPageStart(index + 1, threadCount, "Dual-" + index);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            latch.countDown();
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
        reporter.close();

        var stdoutContent = capturedOut.toString().trim();
        var logContent = Files.readString(logFile).trim();
        assertThat(logContent).isEqualTo(stdoutContent);
    }
}
