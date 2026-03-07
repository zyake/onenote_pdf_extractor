package com.extractor;

import com.extractor.auth.AuthModule;
import com.extractor.client.GraphClientWrapper;
import com.extractor.model.AuthResult;
import com.extractor.model.PageInfo;
import com.extractor.model.SectionInfo;
import com.extractor.page.PageLister;
import com.extractor.pdf.PdfDownloader;
import com.extractor.pdf.PdfWriter;
import com.extractor.report.ProgressReporter;
import com.extractor.section.SectionResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OneNotePdfExtractor orchestration.
 * Validates: Requirements 10.1, 10.2, 10.3, 10.4, 10.5
 */
class OneNotePdfExtractorTest {

    @TempDir
    Path tempDir;

    /**
     * Test subclass that overrides getClientId() to avoid
     * depending on the ONENOTE_CLIENT_ID environment variable.
     */
    static class TestableExtractor extends OneNotePdfExtractor {
        private final String clientId;

        TestableExtractor(String clientId) {
            this.clientId = clientId;
        }

        @Override
        String getClientId() {
            return clientId;
        }
    }

    // --- Requirement 10.3: Exit code 1 when output directory is not writable ---

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void execute_returnsExitCode1_whenOutputDirectoryNotWritable() throws IOException {
        var readOnlyDir = tempDir.resolve("readonly");
        Files.createDirectories(readOnlyDir);
        readOnlyDir.toFile().setWritable(false);

        try {
            var exitCode = new CommandLine(new TestableExtractor("test-client-id"))
                    .execute("--section-id", "some-id",
                            "--output-dir", readOnlyDir.resolve("subdir").toString());

            assertThat(exitCode).isEqualTo(1);
        } finally {
            readOnlyDir.toFile().setWritable(true);
        }
    }

    // --- Exit code 1 when ONENOTE_CLIENT_ID is missing ---

    @Test
    void execute_returnsExitCode1_whenClientIdMissing() {
        var outputDir = tempDir.resolve("output");

        var exitCode = new CommandLine(new TestableExtractor(null))
                .execute("--section-id", "some-id",
                        "--output-dir", outputDir.toString());

        assertThat(exitCode).isEqualTo(1);
    }

    @Test
    void execute_returnsExitCode1_whenClientIdBlank() {
        var outputDir = tempDir.resolve("output");

        var exitCode = new CommandLine(new TestableExtractor("  "))
                .execute("--section-id", "some-id",
                        "--output-dir", outputDir.toString());

        assertThat(exitCode).isEqualTo(1);
    }

    // --- CLI validation: exit code 1 when required args missing ---

    @Test
    void execute_returnsExitCode1_whenNoSectionArgsProvided() {
        var exitCode = new CommandLine(new OneNotePdfExtractor())
                .execute("--output-dir", tempDir.resolve("out").toString());

        assertThat(exitCode).isEqualTo(1);
    }

    // --- Requirement 10.4: Creates output directory if it doesn't exist ---

    @Test
    void execute_createsOutputDirectory_whenItDoesNotExist() {
        var newDir = tempDir.resolve("new-output-dir");
        assertThat(newDir).doesNotExist();

        // Will fail at client ID check, but output dir is created before that
        new CommandLine(new TestableExtractor(null))
                .execute("--section-id", "some-id",
                        "--output-dir", newDir.toString());

        assertThat(newDir).exists().isDirectory();
    }


    // --- Requirement 10.1: Exit code 0 when all pages succeed (mocked components) ---

    @Test
    void execute_returnsExitCode0_whenAllPagesSucceed() throws Exception {
        var outputDir = tempDir.resolve("success-output");

        var sectionInfo = new SectionInfo("sec-1", "Test Section", "Test Notebook", 2);
        var pages = List.of(
                new PageInfo("p1", "Page One", Instant.now()),
                new PageInfo("p2", "Page Two", Instant.now())
        );

        try (
                var authMock = mockConstruction(AuthModule.class, (mock, ctx) ->
                        when(mock.authenticate()).thenReturn(
                                new AuthResult("fake-token", null, Instant.now().plusSeconds(3600))));
                var graphMock = mockConstruction(GraphClientWrapper.class);
                var resolverMock = mockConstruction(SectionResolver.class, (mock, ctx) ->
                        when(mock.resolveById(anyString())).thenReturn(sectionInfo));
                var listerMock = mockConstruction(PageLister.class, (mock, ctx) ->
                        when(mock.listPages(anyString())).thenReturn(pages));
                var downloaderMock = mockConstruction(PdfDownloader.class, (mock, ctx) ->
                        when(mock.downloadPageAsPdf(anyString())).thenReturn(new byte[]{1, 2, 3}));
                var writerMock = mockConstruction(PdfWriter.class, (mock, ctx) ->
                        when(mock.writePdf(anyString(), anyString(), any(byte[].class)))
                                .thenReturn("Page_One.pdf", "Page_Two.pdf"));
                var reporterMock = mockConstruction(ProgressReporter.class)
        ) {
            var exitCode = new CommandLine(new TestableExtractor("test-client-id"))
                    .execute("--section-id", "sec-1",
                            "--output-dir", outputDir.toString());

            assertThat(exitCode).isEqualTo(0);

            // Verify all pages were downloaded
            var downloader = downloaderMock.constructed().getFirst();
            verify(downloader).downloadPageAsPdf("p1");
            verify(downloader).downloadPageAsPdf("p2");
        }
    }

    // --- Requirement 10.2: Exit code 1 when some pages fail ---

    @Test
    void execute_returnsExitCode1_whenSomePagesFail() throws Exception {
        var outputDir = tempDir.resolve("partial-failure-output");

        var sectionInfo = new SectionInfo("sec-1", "Test Section", "Test Notebook", 2);
        var pages = List.of(
                new PageInfo("p1", "Page One", Instant.now()),
                new PageInfo("p2", "Page Two", Instant.now())
        );

        try (
                var authMock = mockConstruction(AuthModule.class, (mock, ctx) ->
                        when(mock.authenticate()).thenReturn(
                                new AuthResult("fake-token", null, Instant.now().plusSeconds(3600))));
                var graphMock = mockConstruction(GraphClientWrapper.class);
                var resolverMock = mockConstruction(SectionResolver.class, (mock, ctx) ->
                        when(mock.resolveById(anyString())).thenReturn(sectionInfo));
                var listerMock = mockConstruction(PageLister.class, (mock, ctx) ->
                        when(mock.listPages(anyString())).thenReturn(pages));
                var downloaderMock = mockConstruction(PdfDownloader.class, (mock, ctx) -> {
                    when(mock.downloadPageAsPdf("p1")).thenReturn(new byte[]{1, 2, 3});
                    when(mock.downloadPageAsPdf("p2")).thenThrow(new IOException("Download failed"));
                });
                var writerMock = mockConstruction(PdfWriter.class, (mock, ctx) ->
                        when(mock.writePdf(anyString(), anyString(), any(byte[].class)))
                                .thenReturn("Page_One.pdf"));
                var reporterMock = mockConstruction(ProgressReporter.class)
        ) {
            var exitCode = new CommandLine(new TestableExtractor("test-client-id"))
                    .execute("--section-id", "sec-1",
                            "--output-dir", outputDir.toString());

            assertThat(exitCode).isEqualTo(1);
        }
    }

    // --- Requirement 10.5: Continues exporting after individual page failure ---

    @Test
    void execute_continuesExporting_afterIndividualPageFailure() throws Exception {
        var outputDir = tempDir.resolve("continue-output");

        var sectionInfo = new SectionInfo("sec-1", "Test Section", "Test Notebook", 3);
        var pages = List.of(
                new PageInfo("p1", "Page One", Instant.now()),
                new PageInfo("p2", "Page Two", Instant.now()),
                new PageInfo("p3", "Page Three", Instant.now())
        );

        try (
                var authMock = mockConstruction(AuthModule.class, (mock, ctx) ->
                        when(mock.authenticate()).thenReturn(
                                new AuthResult("fake-token", null, Instant.now().plusSeconds(3600))));
                var graphMock = mockConstruction(GraphClientWrapper.class);
                var resolverMock = mockConstruction(SectionResolver.class, (mock, ctx) ->
                        when(mock.resolveById(anyString())).thenReturn(sectionInfo));
                var listerMock = mockConstruction(PageLister.class, (mock, ctx) ->
                        when(mock.listPages(anyString())).thenReturn(pages));
                var downloaderMock = mockConstruction(PdfDownloader.class, (mock, ctx) -> {
                    when(mock.downloadPageAsPdf("p1")).thenReturn(new byte[]{1, 2, 3});
                    when(mock.downloadPageAsPdf("p2")).thenThrow(new IOException("Network error"));
                    when(mock.downloadPageAsPdf("p3")).thenReturn(new byte[]{4, 5, 6});
                });
                var writerMock = mockConstruction(PdfWriter.class, (mock, ctx) ->
                        when(mock.writePdf(anyString(), anyString(), any(byte[].class)))
                                .thenReturn("Page_One.pdf", "Page_Three.pdf"));
                var reporterMock = mockConstruction(ProgressReporter.class)
        ) {
            var exitCode = new CommandLine(new TestableExtractor("test-client-id"))
                    .execute("--section-id", "sec-1",
                            "--output-dir", outputDir.toString());

            // Exit code 1 because p2 failed
            assertThat(exitCode).isEqualTo(1);

            // Verify p3 was still attempted after p2 failed
            var downloader = downloaderMock.constructed().getFirst();
            verify(downloader).downloadPageAsPdf("p1");
            verify(downloader).downloadPageAsPdf("p2");
            verify(downloader).downloadPageAsPdf("p3");

            // Verify reporter recorded the failure and continued
            var reporter = reporterMock.constructed().getFirst();
            verify(reporter, times(3)).reportPageStart(anyInt(), anyInt(), anyString());
            verify(reporter, times(2)).reportPageSuccess(anyInt(), anyInt(), anyString(), anyString());
            verify(reporter, times(1)).reportPageFailure(
                    anyInt(), anyInt(), anyString(), anyString(), anyString());
            verify(reporter).reportSummary(eq(3), eq(2), any());
        }
    }
}
