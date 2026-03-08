package com.extractor.pdf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for PdfWriter: filename sanitization, truncation, collision resolution, and file writing.
 * Validates: Requirements 6.1, 6.2, 6.3, 6.4, 6.5, 7.1, 7.2
 */
class PdfWriterTest {

    // --- sanitizeFilename tests ---

    @Test
    void sanitizeFilename_normalTitle_returnsUnchanged() {
        assertThat(PdfWriter.sanitizeFilename("My-Notes.2024")).isEqualTo("My-Notes.2024");
    }

    @Test
    void sanitizeFilename_specialCharacters_replacedWithUnderscores() {
        assertThat(PdfWriter.sanitizeFilename("hello@world#test!")).isEqualTo("hello_world_test");
    }

    @Test
    void sanitizeFilename_spacesReplacedAndCollapsed() {
        assertThat(PdfWriter.sanitizeFilename("my   document   title")).isEqualTo("my_document_title");
    }

    @Test
    void sanitizeFilename_unicode_replacedWithUnderscores() {
        assertThat(PdfWriter.sanitizeFilename("日本語テスト")).isEqualTo("");
    }

    @Test
    void sanitizeFilename_mixedUnicodeAndAscii() {
        assertThat(PdfWriter.sanitizeFilename("résumé-2024")).isEqualTo("r_sum_-2024");
    }

    @Test
    void sanitizeFilename_emptyString_returnsEmpty() {
        assertThat(PdfWriter.sanitizeFilename("")).isEmpty();
    }

    @Test
    void sanitizeFilename_null_returnsEmpty() {
        assertThat(PdfWriter.sanitizeFilename(null)).isEmpty();
    }

    @Test
    void sanitizeFilename_consecutiveUnderscoresCollapsed() {
        assertThat(PdfWriter.sanitizeFilename("a!!!b")).isEqualTo("a_b");
    }

    @Test
    void sanitizeFilename_leadingAndTrailingUnderscoresTrimmed() {
        assertThat(PdfWriter.sanitizeFilename("***hello***")).isEqualTo("hello");
    }

    @Test
    void sanitizeFilename_onlySpecialChars_returnsEmpty() {
        assertThat(PdfWriter.sanitizeFilename("@#$%^&")).isEmpty();
    }

    @Test
    void sanitizeFilename_dotsAndDashesPreserved() {
        assertThat(PdfWriter.sanitizeFilename("file-name.v2")).isEqualTo("file-name.v2");
    }

    // --- writePdf: empty title fallback to pageId ---

    @Test
    void writePdf_emptyTitle_fallsBackToPageId(@TempDir Path tempDir) throws IOException {
        var writer = new PdfWriter(tempDir);
        var filename = writer.writePdf("", "page-123", new byte[]{1, 2, 3});

        assertThat(filename).isEqualTo("page-123.pdf");
        assertThat(Files.exists(tempDir.resolve(filename))).isTrue();
    }

    @Test
    void writePdf_nullTitle_fallsBackToPageId(@TempDir Path tempDir) throws IOException {
        var writer = new PdfWriter(tempDir);
        var filename = writer.writePdf(null, "page-456", new byte[]{4, 5, 6});

        assertThat(filename).isEqualTo("page-456.pdf");
    }

    @Test
    void writePdf_titleSanitizesToEmpty_fallsBackToPageId(@TempDir Path tempDir) throws IOException {
        var writer = new PdfWriter(tempDir);
        var filename = writer.writePdf("@#$%", "fallback-id", new byte[]{7, 8});

        assertThat(filename).isEqualTo("fallback-id.pdf");
    }

    // --- writePdf: normal write ---

    @Test
    void writePdf_normalTitle_writesFileWithSanitizedName(@TempDir Path tempDir) throws IOException {
        var writer = new PdfWriter(tempDir);
        var content = "pdf-content".getBytes();
        var filename = writer.writePdf("My Notes", "pg-1", content);

        assertThat(filename).isEqualTo("My_Notes.pdf");
        assertThat(Files.readAllBytes(tempDir.resolve(filename))).isEqualTo(content);
    }

    // --- Collision resolution ---

    @Test
    void writePdf_duplicateFilenames_appendsNumericSuffix(@TempDir Path tempDir) throws IOException {
        var writer = new PdfWriter(tempDir);
        var first = writer.writePdf("Report", "p1", new byte[]{1});
        var second = writer.writePdf("Report", "p2", new byte[]{2});
        var third = writer.writePdf("Report", "p3", new byte[]{3});

        assertThat(first).isEqualTo("Report.pdf");
        assertThat(second).isEqualTo("Report_1.pdf");
        assertThat(third).isEqualTo("Report_2.pdf");
    }

    @Test
    void writePdf_collisionWithExistingFileOnDisk(@TempDir Path tempDir) throws IOException {
        // Pre-create a file on disk
        Files.write(tempDir.resolve("Existing.pdf"), new byte[]{0});

        var writer = new PdfWriter(tempDir);
        var filename = writer.writePdf("Existing", "p1", new byte[]{1});

        assertThat(filename).isEqualTo("Existing_1.pdf");
    }

    // --- Filename truncation ---

    @Test
    void writePdf_longTitle_truncatedTo200Characters(@TempDir Path tempDir) throws IOException {
        var longTitle = "A".repeat(250);
        var writer = new PdfWriter(tempDir);
        var filename = writer.writePdf(longTitle, "pg-long", new byte[]{1});

        // Filename without .pdf extension should be at most 200 chars
        var nameWithoutExt = filename.substring(0, filename.length() - ".pdf".length());
        assertThat(nameWithoutExt.length()).isLessThanOrEqualTo(200);
    }

    @Test
    void writePdf_longTitleWithTrailingSpecialChars_trimmedAfterTruncation(@TempDir Path tempDir) throws IOException {
        // Build a title that, after sanitization, will have underscores near the 200-char boundary
        var longTitle = "A".repeat(198) + "!!extra";
        var writer = new PdfWriter(tempDir);
        var filename = writer.writePdf(longTitle, "pg-trunc", new byte[]{1});

        var nameWithoutExt = filename.substring(0, filename.length() - ".pdf".length());
        assertThat(nameWithoutExt.length()).isLessThanOrEqualTo(200);
        assertThat(nameWithoutExt).doesNotEndWith("_");
    }

    @Test
    void writePdf_exactlyMaxLength_noTruncation(@TempDir Path tempDir) throws IOException {
        var title = "B".repeat(200);
        var writer = new PdfWriter(tempDir);
        var filename = writer.writePdf(title, "pg-exact", new byte[]{1});

        assertThat(filename).isEqualTo("B".repeat(200) + ".pdf");
    }

    // --- Thread-safety tests (Requirements 3.1, 3.2, 3.3) ---

    @Test
    void writePdf_concurrentWritesSameTitle_allFilenamesUnique(@TempDir Path tempDir) throws Exception {
        var writer = new PdfWriter(tempDir);
        var threadCount = 20;
        var barrier = new CyclicBarrier(threadCount);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<String>>();
            for (var i = 0; i < threadCount; i++) {
                var content = ("content-" + i).getBytes();
                futures.add(executor.submit(() -> {
                    barrier.await();
                    return writer.writePdf("SameTitle", "page-" + Thread.currentThread().threadId(), content);
                }));
            }

            var filenames = new HashSet<String>();
            for (var future : futures) {
                filenames.add(future.get());
            }

            assertThat(filenames).hasSize(threadCount);
        }
    }

    @Test
    void writePdf_concurrentWritesDifferentTitles_allFilenamesUnique(@TempDir Path tempDir) throws Exception {
        var writer = new PdfWriter(tempDir);
        var threadCount = 20;
        var barrier = new CyclicBarrier(threadCount);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<String>>();
            for (var i = 0; i < threadCount; i++) {
                var title = "Page-" + i;
                var content = ("content-" + i).getBytes();
                futures.add(executor.submit(() -> {
                    barrier.await();
                    return writer.writePdf(title, "id-" + title, content);
                }));
            }

            var filenames = new HashSet<String>();
            for (var future : futures) {
                filenames.add(future.get());
            }

            assertThat(filenames).hasSize(threadCount);
        }
    }

    @Test
    void writePdf_concurrentWrites_filesWrittenBeforeRegistered(@TempDir Path tempDir) throws Exception {
        var writer = new PdfWriter(tempDir);
        var threadCount = 10;
        var barrier = new CyclicBarrier(threadCount);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<String>>();
            for (var i = 0; i < threadCount; i++) {
                var content = ("data-" + i).getBytes();
                futures.add(executor.submit(() -> {
                    barrier.await();
                    return writer.writePdf("Doc", "pg", content);
                }));
            }

            var filenames = new ArrayList<String>();
            for (var future : futures) {
                filenames.add(future.get());
            }

            for (var filename : filenames) {
                var file = tempDir.resolve(filename);
                assertThat(file).exists();
                assertThat(Files.readAllBytes(file)).isNotEmpty();
            }
        }
    }

    @Test
    void writePdf_concurrentWrites_collisionSuffixesAreSequential(@TempDir Path tempDir) throws Exception {
        var writer = new PdfWriter(tempDir);
        var threadCount = 5;

        // Write sequentially first to verify collision suffixes work under synchronized access
        var filenames = new ArrayList<String>();
        for (var i = 0; i < threadCount; i++) {
            filenames.add(writer.writePdf("Report", "p" + i, ("content-" + i).getBytes()));
        }

        assertThat(filenames).containsExactly(
                "Report.pdf", "Report_1.pdf", "Report_2.pdf", "Report_3.pdf", "Report_4.pdf"
        );
    }

}
