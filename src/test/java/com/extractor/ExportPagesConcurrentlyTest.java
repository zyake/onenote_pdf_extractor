package com.extractor;

import com.extractor.model.ExportResult;
import com.extractor.model.PageExportOutcome;
import com.extractor.model.PageInfo;
import com.extractor.pdf.PdfDownloader;
import com.extractor.pdf.PdfWriter;
import com.extractor.report.ProgressReporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OneNotePdfExtractor#exportPagesConcurrently}.
 * Validates: Requirements 1.1, 1.2, 1.3, 1.4, 5.1, 5.2, 5.3, 5.4
 */
class ExportPagesConcurrentlyTest {

    @TempDir
    Path tempDir;

    private OneNotePdfExtractor extractor;
    private PdfDownloader downloader;
    private PdfWriter writer;
    private ProgressReporter reporter;

    @BeforeEach
    void setUp() throws IOException {
        extractor = new OneNotePdfExtractor();
        downloader = mock(PdfDownloader.class);
        writer = mock(PdfWriter.class);
        reporter = mock(ProgressReporter.class);
    }

    @Test
    void allPagesSucceed_returnsCorrectCounts() throws Exception {
        var pages = List.of(
                new PageInfo("p1", "Page One", Instant.now(), Instant.now()),
                new PageInfo("p2", "Page Two", Instant.now(), Instant.now()),
                new PageInfo("p3", "Page Three", Instant.now(), Instant.now())
        );

        when(downloader.downloadPageAsPdf(anyString())).thenReturn(new byte[]{1, 2, 3});
        when(writer.writePdf(anyString(), anyString(), any(byte[].class)))
                .thenReturn("Page_One.pdf", "Page_Two.pdf", "Page_Three.pdf");

        var result = extractor.exportPagesConcurrently(pages, downloader, writer, reporter, 4);

        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.successCount()).isEqualTo(3);
        assertThat(result.failureCount()).isEqualTo(0);
        assertThat(result.failures()).isEmpty();
    }

    @Test
    void allPagesFail_returnsAllFailures() throws Exception {
        var pages = List.of(
                new PageInfo("p1", "Page One", Instant.now(), Instant.now()),
                new PageInfo("p2", "Page Two", Instant.now(), Instant.now())
        );

        when(downloader.downloadPageAsPdf(anyString())).thenThrow(new IOException("Network error"));

        var result = extractor.exportPagesConcurrently(pages, downloader, writer, reporter, 4);

        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.successCount()).isEqualTo(0);
        assertThat(result.failureCount()).isEqualTo(2);
        assertThat(result.failures()).hasSize(2);
        assertThat(result.failures()).allSatisfy(f ->
                assertThat(f.errorMessage()).isEqualTo("Network error"));
    }

    @Test
    void mixedSuccessAndFailure_recordsBoth() throws Exception {
        var pages = List.of(
                new PageInfo("p1", "Page One", Instant.now(), Instant.now()),
                new PageInfo("p2", "Page Two", Instant.now(), Instant.now()),
                new PageInfo("p3", "Page Three", Instant.now(), Instant.now())
        );

        when(downloader.downloadPageAsPdf("p1")).thenReturn(new byte[]{1});
        when(downloader.downloadPageAsPdf("p2")).thenThrow(new IOException("Timeout"));
        when(downloader.downloadPageAsPdf("p3")).thenReturn(new byte[]{3});
        when(writer.writePdf(anyString(), anyString(), any(byte[].class)))
                .thenReturn("Page_One.pdf", "Page_Three.pdf");

        var result = extractor.exportPagesConcurrently(pages, downloader, writer, reporter, 4);

        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.successCount()).isEqualTo(2);
        assertThat(result.failureCount()).isEqualTo(1);
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().getFirst().pageId()).isEqualTo("p2");
    }

    @Test
    void emptyPageList_returnsZeroCounts() {
        var result = extractor.exportPagesConcurrently(List.of(), downloader, writer, reporter, 4);

        assertThat(result.totalPages()).isEqualTo(0);
        assertThat(result.successCount()).isEqualTo(0);
        assertThat(result.failureCount()).isEqualTo(0);
        assertThat(result.failures()).isEmpty();
    }

    @Test
    void singlePage_succeeds() throws Exception {
        var pages = List.of(new PageInfo("p1", "Solo Page", Instant.now(), Instant.now()));

        when(downloader.downloadPageAsPdf("p1")).thenReturn(new byte[]{42});
        when(writer.writePdf(anyString(), anyString(), any(byte[].class)))
                .thenReturn("Solo_Page.pdf");

        var result = extractor.exportPagesConcurrently(pages, downloader, writer, reporter, 1);

        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failureCount()).isEqualTo(0);
    }

    @Test
    void reportsProgressForEachPage() throws Exception {
        var pages = List.of(
                new PageInfo("p1", "Page One", Instant.now(), Instant.now()),
                new PageInfo("p2", "Page Two", Instant.now(), Instant.now())
        );

        when(downloader.downloadPageAsPdf(anyString())).thenReturn(new byte[]{1});
        when(writer.writePdf(anyString(), anyString(), any(byte[].class)))
                .thenReturn("Page_One.pdf", "Page_Two.pdf");

        extractor.exportPagesConcurrently(pages, downloader, writer, reporter, 4);

        verify(reporter, times(2)).reportPageStart(anyInt(), eq(2), anyString());
        verify(reporter, times(2)).reportPageSuccess(anyInt(), eq(2), anyString(), anyString());
        verify(reporter, never()).reportPageFailure(anyInt(), anyInt(), anyString(), anyString(), anyString());
    }

    @Test
    void reportsFailureForFailedPages() throws Exception {
        var pages = List.of(new PageInfo("p1", "Failing Page", Instant.now(), Instant.now()));

        when(downloader.downloadPageAsPdf("p1")).thenThrow(new RuntimeException("Boom"));

        extractor.exportPagesConcurrently(pages, downloader, writer, reporter, 4);

        verify(reporter).reportPageStart(1, 1, "Failing Page");
        verify(reporter).reportPageFailure(1, 1, "Failing Page", "p1", "Boom");
    }

    @Test
    void nullPageTitle_fallsBackToPageId() throws Exception {
        var pages = List.of(new PageInfo("p1", null, Instant.now(), Instant.now()));

        when(downloader.downloadPageAsPdf("p1")).thenReturn(new byte[]{1});
        when(writer.writePdf(anyString(), anyString(), any(byte[].class)))
                .thenReturn("p1.pdf");

        var result = extractor.exportPagesConcurrently(pages, downloader, writer, reporter, 4);

        assertThat(result.successCount()).isEqualTo(1);
        // The title used should be the pageId since title is null
        verify(reporter).reportPageStart(1, 1, "p1");
    }

    @Test
    void concurrencyLevelIsRespected() throws Exception {
        var peakConcurrency = new AtomicInteger(0);
        var currentConcurrency = new AtomicInteger(0);

        var pages = List.of(
                new PageInfo("p1", "Page 1", Instant.now(), Instant.now()),
                new PageInfo("p2", "Page 2", Instant.now(), Instant.now()),
                new PageInfo("p3", "Page 3", Instant.now(), Instant.now()),
                new PageInfo("p4", "Page 4", Instant.now(), Instant.now()),
                new PageInfo("p5", "Page 5", Instant.now(), Instant.now()),
                new PageInfo("p6", "Page 6", Instant.now(), Instant.now())
        );

        when(downloader.downloadPageAsPdf(anyString())).thenAnswer(_ -> {
            var current = currentConcurrency.incrementAndGet();
            peakConcurrency.updateAndGet(peak -> Math.max(peak, current));
            Thread.sleep(50); // simulate I/O
            currentConcurrency.decrementAndGet();
            return new byte[]{1};
        });
        when(writer.writePdf(anyString(), anyString(), any(byte[].class)))
                .thenReturn("file.pdf");

        var concurrencyLevel = 2;
        extractor.exportPagesConcurrently(pages, downloader, writer, reporter, concurrencyLevel);

        assertThat(peakConcurrency.get()).isLessThanOrEqualTo(concurrencyLevel);
    }

    @Test
    void writerExceptionIsCapturedAsFailure() throws Exception {
        var pages = List.of(new PageInfo("p1", "Page One", Instant.now(), Instant.now()));

        when(downloader.downloadPageAsPdf("p1")).thenReturn(new byte[]{1});
        when(writer.writePdf(anyString(), anyString(), any(byte[].class)))
                .thenThrow(new IOException("Disk full"));

        var result = extractor.exportPagesConcurrently(pages, downloader, writer, reporter, 4);

        assertThat(result.failureCount()).isEqualTo(1);
        assertThat(result.failures().getFirst().errorMessage()).isEqualTo("Disk full");
    }
}
