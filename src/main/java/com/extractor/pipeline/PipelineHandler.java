package com.extractor.pipeline;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.extractor.auth.GoogleAuthModule;
import com.extractor.auth.GraphCredentialsAuthModule;
import com.extractor.client.GraphClientWrapper;
import com.extractor.config.CredentialLoader;
import com.extractor.dedup.DeduplicationStore;
import com.extractor.dedup.ExportRecord;
import com.extractor.metrics.MetricsPublisher;
import com.extractor.model.FailedPage;
import com.extractor.model.PageInfo;
import com.extractor.notebooklm.NotebookLmUploader;
import com.extractor.page.PageLister;
import com.extractor.pdf.PdfDownloader;
import com.extractor.pdf.PdfWriter;
import com.extractor.report.PipelineReporter;
import com.extractor.section.SectionResolver;
import com.extractor.storage.S3PdfStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AWS Lambda entry point for the OneNote PDF export pipeline.
 * Orchestrates: credential loading → auth → page listing → concurrent export → metrics → reporting.
 *
 * <p>Uses virtual threads with an ExecutorService and a configurable semaphore
 * for concurrent page processing. Individual page failures do not abort the run.</p>
 *
 * <p>Validates: Requirements 1.4, 3.1, 6.2, 8.1, 8.2, 8.3</p>
 */
public class PipelineHandler implements RequestHandler<ScheduledEvent, PipelineResult> {

    private static final String DEFAULT_CONCURRENCY = "5";
    private static final String DEFAULT_SSM_PREFIX = "/onenote-pipeline";
    private static final String DEFAULT_S3_BUCKET = "onenote-pdf-store";
    private static final String DEFAULT_DYNAMO_TABLE = "ExportTracker";
    private static final String DEFAULT_CW_NAMESPACE = "OneNotePipeline";

    private final CredentialLoader credentialLoader;
    private final DeduplicationStore deduplicationStore;
    private final S3PdfStore s3PdfStore;
    private final MetricsPublisher metricsPublisher;
    private final PipelineReporter reporter;
    private final int concurrencyLimit;

    /**
     * Default constructor used by AWS Lambda runtime.
     * Reads configuration from environment variables.
     */
    public PipelineHandler() {
        var ssmPrefix = envOrDefault("SSM_PREFIX", DEFAULT_SSM_PREFIX);
        var s3Bucket = envOrDefault("S3_BUCKET", DEFAULT_S3_BUCKET);
        var dynamoTable = envOrDefault("DYNAMO_TABLE", DEFAULT_DYNAMO_TABLE);
        var cwNamespace = envOrDefault("CW_NAMESPACE", DEFAULT_CW_NAMESPACE);
        var concurrency = envOrDefault("CONCURRENCY_LIMIT", DEFAULT_CONCURRENCY);

        this.credentialLoader = new CredentialLoader(
                software.amazon.awssdk.services.ssm.SsmClient.create(), ssmPrefix);
        this.deduplicationStore = new DeduplicationStore(
                software.amazon.awssdk.services.dynamodb.DynamoDbClient.create(), dynamoTable);
        this.s3PdfStore = new S3PdfStore(
                software.amazon.awssdk.services.s3.S3Client.create(), s3Bucket);
        this.metricsPublisher = new MetricsPublisher(
                software.amazon.awssdk.services.cloudwatch.CloudWatchClient.create(), cwNamespace);
        this.reporter = new PipelineReporter();
        this.concurrencyLimit = Integer.parseInt(concurrency);
    }

    /**
     * Constructor for dependency injection (testing and custom wiring).
     */
    PipelineHandler(CredentialLoader credentialLoader,
                    DeduplicationStore deduplicationStore,
                    S3PdfStore s3PdfStore,
                    MetricsPublisher metricsPublisher,
                    PipelineReporter reporter,
                    int concurrencyLimit) {
        this.credentialLoader = credentialLoader;
        this.deduplicationStore = deduplicationStore;
        this.s3PdfStore = s3PdfStore;
        this.metricsPublisher = metricsPublisher;
        this.reporter = reporter;
        this.concurrencyLimit = concurrencyLimit;
    }

    @Override
    public PipelineResult handleRequest(ScheduledEvent event, Context context) {
        var startTime = Instant.now();
        System.out.println("Pipeline started at " + startTime);

        // 1. Load credentials
        var credentials = credentialLoader.loadAll();
        System.out.println("Section target: " + credentials.sectionId());

        // 2. Acquire MS Graph token
        var graphAuth = new GraphCredentialsAuthModule(
                credentials.msClientId(), credentials.msClientSecret(), credentials.msTenantId());
        var graphClient = new GraphClientWrapper(graphAuth);

        // 3. Resolve section and list pages
        var sectionResolver = new SectionResolver(graphClient);
        var sectionInfo = sectionResolver.resolveById(credentials.sectionId());
        var pageLister = new PageLister(graphClient);
        var pages = pageLister.listPages(credentials.sectionId());
        System.out.println("Found " + pages.size() + " pages in section: " + sectionInfo.sectionName());

        // 4. Set up Google auth and NotebookLM uploader
        var googleAuth = new GoogleAuthModule(credentials.googleServiceAccountJson());
        var notebookLmUploader = new NotebookLmUploader(
                credentials.notebookLmProjectId(), googleAuth.getCredentials());

        // 5. Set up PDF downloader
        var pdfDownloader = new PdfDownloader(graphClient);

        // 6. Process pages concurrently
        var result = processPages(pages, sectionInfo.notebookName(), sectionInfo.sectionName(),
                pdfDownloader, notebookLmUploader, startTime);

        // 7. Publish metrics
        metricsPublisher.publishRunMetrics(
                result.exportedCount(), result.skippedCount(), result.failedCount(), result.durationMs());

        // 8. Log summary
        var summary = reporter.formatSummary(
                result.totalPages(), result.exportedCount(), result.skippedCount(),
                result.failedCount(), result.uploadedToNotebookLmCount());
        System.out.println(summary);

        if (!result.failures().isEmpty()) {
            System.out.println(reporter.formatFailures(result.failures()));
        }

        return result;
    }

    /**
     * Processes all pages concurrently using virtual threads.
     * A semaphore limits the number of concurrent page-processing threads.
     * Individual page failures are captured without aborting the run.
     * Package-private for testing.
     */
    PipelineResult processPages(List<PageInfo> pages, String notebookName, String sectionName,
                                PdfDownloader pdfDownloader, NotebookLmUploader notebookLmUploader,
                                Instant startTime) {
        var exported = new AtomicInteger();
        var skipped = new AtomicInteger();
        var uploadedToNotebookLm = new AtomicInteger();
        var failures = Collections.synchronizedList(new ArrayList<FailedPage>());
        var semaphore = new Semaphore(concurrencyLimit);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var page : pages) {
                executor.submit(() -> {
                    try {
                        semaphore.acquire();
                        try {
                            processPage(page, notebookName, sectionName, pdfDownloader,
                                    notebookLmUploader, exported, skipped, uploadedToNotebookLm, failures);
                        } finally {
                            semaphore.release();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        failures.add(new FailedPage(page.pageId(), page.title(), "Interrupted"));
                    }
                });
            }
            executor.shutdown();
            executor.awaitTermination(15, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Pipeline interrupted during page processing", e);
        }

        var durationMs = java.time.Duration.between(startTime, Instant.now()).toMillis();
        return new PipelineResult(
                pages.size(), exported.get(), skipped.get(), failures.size(),
                uploadedToNotebookLm.get(), durationMs, List.copyOf(failures));
    }

    /**
     * Processes a single page: dedup check → download PDF → upload to S3 → upload to NotebookLM → record.
     * Any exception is caught and recorded as a FailedPage (fault isolation).
     */
    private void processPage(PageInfo page, String notebookName, String sectionName,
                             PdfDownloader pdfDownloader, NotebookLmUploader notebookLmUploader,
                             AtomicInteger exported, AtomicInteger skipped,
                             AtomicInteger uploadedToNotebookLm, List<FailedPage> failures) {
        try {
            // Dedup check (Req 3.1)
            if (!deduplicationStore.shouldExport(page.pageId(), page.lastModifiedDateTime())) {
                skipped.incrementAndGet();
                return;
            }

            // Download PDF
            var pdfBytes = pdfDownloader.downloadPageAsPdf(page.pageId());

            // Upload to S3
            var sanitizedTitle = PdfWriter.sanitizeFilename(page.title());
            if (sanitizedTitle.isEmpty()) {
                sanitizedTitle = PdfWriter.sanitizeFilename(page.pageId());
            }
            var s3Key = s3PdfStore.uploadPdf(notebookName, sectionName, sanitizedTitle, pdfBytes);
            exported.incrementAndGet();

            // Upload to NotebookLM
            var notebookLmUploaded = false;
            try {
                notebookLmUploader.uploadPdf(sanitizedTitle + ".pdf", pdfBytes);
                notebookLmUploaded = true;
                uploadedToNotebookLm.incrementAndGet();
            } catch (Exception e) {
                System.out.println("NotebookLM upload failed for page: " + page.title()
                        + " — continuing with S3 export recorded");
            }

            // Record in dedup store (Req 3.5)
            deduplicationStore.recordExport(new ExportRecord(
                    page.pageId(), page.lastModifiedDateTime(), s3Key,
                    Instant.now(), notebookLmUploaded));

        } catch (Exception e) {
            failures.add(new FailedPage(page.pageId(), page.title(), e.getMessage()));
        }
    }

    private static String envOrDefault(String key, String defaultValue) {
        var value = System.getenv(key);
        return value != null && !value.isBlank() ? value : defaultValue;
    }
}
