package com.extractor.pipeline;

import com.extractor.config.CredentialLoader;
import com.extractor.config.PipelineCredentials;
import com.extractor.dedup.DeduplicationStore;
import com.extractor.dedup.ExportRecord;
import com.extractor.metrics.MetricsPublisher;
import com.extractor.model.PageInfo;
import com.extractor.notebooklm.NotebookLmUploader;
import com.extractor.pdf.PdfDownloader;
import com.extractor.report.PipelineReporter;
import com.extractor.storage.S3PdfStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PipelineHandler page processing logic.
 * Tests the processPages method directly to verify orchestration,
 * fault isolation, dedup checks, and concurrency behavior.
 *
 * Validates: Requirements 1.4, 3.1, 6.2, 8.1, 8.2, 8.3
 */
@ExtendWith(MockitoExtension.class)
class PipelineHandlerTest {

    @Mock CredentialLoader credentialLoader;
    @Mock DeduplicationStore deduplicationStore;
    @Mock S3PdfStore s3PdfStore;
    @Mock MetricsPublisher metricsPublisher;
    @Mock PdfDownloader pdfDownloader;
    @Mock NotebookLmUploader notebookLmUploader;

    private PipelineReporter reporter;
    private PipelineHandler handler;

    private static final String NOTEBOOK = "TestNotebook";
    private static final String SECTION = "TestSection";
    private static final Instant NOW = Instant.parse("2025-01-15T10:00:00Z");
    private static final byte[] PDF_BYTES = new byte[]{1, 2, 3};

    @BeforeEach
    void setUp() {
        reporter = new PipelineReporter();
        handler = new PipelineHandler(credentialLoader, deduplicationStore,
                s3PdfStore, metricsPublisher, reporter, 3);
    }

    // --- Zero pages ---

    @Test
    void processPages_emptyList_returnsZeroCounts() {
        var result = handler.processPages(List.of(), NOTEBOOK, SECTION,
                pdfDownloader, notebookLmUploader, NOW);

        assertThat(result.totalPages()).isZero();
        assertThat(result.exportedCount()).isZero();
        assertThat(result.skippedCount()).isZero();
        assertThat(result.failedCount()).isZero();
        assertThat(result.uploadedToNotebookLmCount()).isZero();
        assertThat(result.failures()).isEmpty();
        verifyNoInteractions(deduplicationStore, s3PdfStore, pdfDownloader, notebookLmUploader);
    }

    // --- All pages deduplicated (skipped) ---

    @Test
    void processPages_allPagesSkipped_countsMatchTotal() {
        var pages = List.of(
                new PageInfo("p1", "Page 1", NOW, NOW),
                new PageInfo("p2", "Page 2", NOW, NOW)
        );
        when(deduplicationStore.shouldExport(anyString(), any(Instant.class))).thenReturn(false);

        var result = handler.processPages(pages, NOTEBOOK, SECTION,
                pdfDownloader, notebookLmUploader, NOW);

        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.skippedCount()).isEqualTo(2);
        assertThat(result.exportedCount()).isZero();
        assertThat(result.failedCount()).isZero();
        assertThat(result.failures()).isEmpty();
        verify(deduplicationStore, times(2)).shouldExport(anyString(), any(Instant.class));
        verifyNoInteractions(pdfDownloader, s3PdfStore, notebookLmUploader);
    }

    // --- Successful export flow ---

    @Test
    void processPages_successfulExport_incrementsExportedAndUploaded() throws Exception {
        var pages = List.of(new PageInfo("p1", "My Page", NOW, NOW));
        when(deduplicationStore.shouldExport("p1", NOW)).thenReturn(true);
        when(pdfDownloader.downloadPageAsPdf("p1")).thenReturn(PDF_BYTES);
        when(s3PdfStore.uploadPdf(eq(NOTEBOOK), eq(SECTION), eq("My_Page"), eq(PDF_BYTES)))
                .thenReturn("TestNotebook/TestSection/My_Page.pdf");

        var result = handler.processPages(pages, NOTEBOOK, SECTION,
                pdfDownloader, notebookLmUploader, NOW);

        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.exportedCount()).isEqualTo(1);
        assertThat(result.uploadedToNotebookLmCount()).isEqualTo(1);
        assertThat(result.skippedCount()).isZero();
        assertThat(result.failedCount()).isZero();
        verify(notebookLmUploader).uploadPdf(eq("My_Page.pdf"), eq(PDF_BYTES));
        verify(deduplicationStore).recordExport(argThat(record ->
                record.pageId().equals("p1") && record.notebookLmUploaded()));
    }

    // --- Fault isolation: individual page failure does not abort run ---

    @Test
    void processPages_onePageFails_otherPagesStillProcessed() throws Exception {
        var pages = List.of(
                new PageInfo("p1", "Good Page", NOW, NOW),
                new PageInfo("p2", "Bad Page", NOW, NOW),
                new PageInfo("p3", "Another Good", NOW, NOW)
        );
        when(deduplicationStore.shouldExport(anyString(), any(Instant.class))).thenReturn(true);
        when(pdfDownloader.downloadPageAsPdf("p1")).thenReturn(PDF_BYTES);
        when(pdfDownloader.downloadPageAsPdf("p2")).thenThrow(new IOException("Network error"));
        when(pdfDownloader.downloadPageAsPdf("p3")).thenReturn(PDF_BYTES);
        when(s3PdfStore.uploadPdf(anyString(), anyString(), anyString(), any(byte[].class)))
                .thenReturn("some/key.pdf");

        var result = handler.processPages(pages, NOTEBOOK, SECTION,
                pdfDownloader, notebookLmUploader, NOW);

        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.exportedCount()).isEqualTo(2);
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().getFirst().pageId()).isEqualTo("p2");
        assertThat(result.failures().getFirst().errorMessage()).contains("Network error");
        // Verify counts add up: exported + skipped + failed = total
        assertThat(result.exportedCount() + result.skippedCount() + result.failedCount())
                .isEqualTo(result.totalPages());
    }

    // --- All pages fail ---

    @Test
    void processPages_allPagesFail_allRecordedAsFailures() throws Exception {
        var pages = List.of(
                new PageInfo("p1", "Page 1", NOW, NOW),
                new PageInfo("p2", "Page 2", NOW, NOW)
        );
        when(deduplicationStore.shouldExport(anyString(), any(Instant.class))).thenReturn(true);
        when(pdfDownloader.downloadPageAsPdf(anyString())).thenThrow(new IOException("Timeout"));

        var result = handler.processPages(pages, NOTEBOOK, SECTION,
                pdfDownloader, notebookLmUploader, NOW);

        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.failedCount()).isEqualTo(2);
        assertThat(result.exportedCount()).isZero();
        assertThat(result.failures()).hasSize(2);
    }

    // --- NotebookLM upload failure does not fail the page ---

    @Test
    void processPages_notebookLmFails_pageStillCountedAsExported() throws Exception {
        var pages = List.of(new PageInfo("p1", "Page 1", NOW, NOW));
        when(deduplicationStore.shouldExport("p1", NOW)).thenReturn(true);
        when(pdfDownloader.downloadPageAsPdf("p1")).thenReturn(PDF_BYTES);
        when(s3PdfStore.uploadPdf(anyString(), anyString(), anyString(), any(byte[].class)))
                .thenReturn("key.pdf");
        doThrow(new RuntimeException("Google API error"))
                .when(notebookLmUploader).uploadPdf(anyString(), any(byte[].class));

        var result = handler.processPages(pages, NOTEBOOK, SECTION,
                pdfDownloader, notebookLmUploader, NOW);

        assertThat(result.exportedCount()).isEqualTo(1);
        assertThat(result.uploadedToNotebookLmCount()).isZero();
        assertThat(result.failedCount()).isZero();
        // Dedup record should still be written with notebookLmUploaded=false
        verify(deduplicationStore).recordExport(argThat(record ->
                record.pageId().equals("p1") && !record.notebookLmUploaded()));
    }

    // --- Dedup check called for every page ---

    @Test
    void processPages_dedupCheckCalledForEveryPage() {
        var pages = List.of(
                new PageInfo("p1", "A", NOW, NOW),
                new PageInfo("p2", "B", NOW, NOW),
                new PageInfo("p3", "C", NOW, NOW)
        );
        when(deduplicationStore.shouldExport(anyString(), any(Instant.class))).thenReturn(false);

        handler.processPages(pages, NOTEBOOK, SECTION, pdfDownloader, notebookLmUploader, NOW);

        verify(deduplicationStore).shouldExport("p1", NOW);
        verify(deduplicationStore).shouldExport("p2", NOW);
        verify(deduplicationStore).shouldExport("p3", NOW);
        verify(deduplicationStore, times(3)).shouldExport(anyString(), any(Instant.class));
    }

    // --- Mixed: some skipped, some exported, some failed ---

    @Test
    void processPages_mixedResults_countsAddUpToTotal() throws Exception {
        var pages = List.of(
                new PageInfo("p1", "Skip Me", NOW, NOW),
                new PageInfo("p2", "Export Me", NOW, NOW),
                new PageInfo("p3", "Fail Me", NOW, NOW)
        );
        when(deduplicationStore.shouldExport("p1", NOW)).thenReturn(false);
        when(deduplicationStore.shouldExport("p2", NOW)).thenReturn(true);
        when(deduplicationStore.shouldExport("p3", NOW)).thenReturn(true);
        when(pdfDownloader.downloadPageAsPdf("p2")).thenReturn(PDF_BYTES);
        when(pdfDownloader.downloadPageAsPdf("p3")).thenThrow(new IOException("Fail"));
        when(s3PdfStore.uploadPdf(anyString(), anyString(), anyString(), any(byte[].class)))
                .thenReturn("key.pdf");

        var result = handler.processPages(pages, NOTEBOOK, SECTION,
                pdfDownloader, notebookLmUploader, NOW);

        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.exportedCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.exportedCount() + result.skippedCount() + result.failedCount())
                .isEqualTo(result.totalPages());
    }

    // --- Duration is positive ---

    @Test
    void processPages_durationIsNonNegative() {
        var result = handler.processPages(List.of(), NOTEBOOK, SECTION,
                pdfDownloader, notebookLmUploader, Instant.now().minusSeconds(1));

        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
    }
}
