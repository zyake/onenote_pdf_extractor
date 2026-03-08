package com.extractor;

import com.extractor.auth.AuthModule;
import com.extractor.cli.CliArgs;
import com.extractor.client.GraphClientWrapper;
import com.extractor.model.ExportResult;
import com.extractor.model.FailedPage;
import com.extractor.model.PageExportOutcome;
import com.extractor.model.PageInfo;
import com.extractor.model.SectionInfo;
import com.extractor.page.PageLister;
import com.extractor.pdf.PdfDownloader;
import com.extractor.pdf.PdfWriter;
import com.extractor.report.ProgressReporter;
import com.extractor.section.SectionResolver;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Subtask;

/**
 * Entry point for the OneNote PDF Extractor CLI tool.
 * Uses picocli for argument parsing and orchestrates the full export pipeline.
 */
@Command(
        name = "onenote-pdf-extractor",
        mixinStandardHelpOptions = true,
        description = "Export OneNote pages as PDF files via Microsoft Graph API.",
        version = "1.0"
)
public class OneNotePdfExtractor implements Callable<Integer> {

    @Option(names = "--section-id", description = "Direct OneNote section ID")
    private String sectionId;

    @Option(names = "--notebook", description = "Notebook name (used with --section)")
    private String notebook;

    @Option(names = "--section", description = "Section name within the notebook")
    private String section;

    @Option(names = "--output-dir", description = "Output directory (default: ./onenote-export)")
    private Path outputDir;
    @Option(names = "--concurrency",
            description = "Max concurrent page exports (default: ${DEFAULT-VALUE})",
            defaultValue = "4")
    private int concurrency;

    public static void main(String[] args) {
        var exitCode = new CommandLine(new OneNotePdfExtractor()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        // 1. Build and validate CLI args
        var cliArgs = buildCliArgs();
        try {
            cliArgs.validate();
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            new CommandLine(this).usage(System.err);
            return 1;
        }

        var outDir = cliArgs.getOutputDir();

        // 2. Create output directory if needed, validate writability
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            System.err.println("Error: Cannot create output directory: " + outDir + " - " + e.getMessage());
            return 1;
        }
        if (!Files.isWritable(outDir)) {
            System.err.println("Error: Output directory is not writable: " + outDir);
            return 1;
        }

        // 3. Authenticate with Microsoft Graph
        var clientId = getClientId();
        if (clientId == null || clientId.isBlank()) {
            System.err.println("Error: ONENOTE_CLIENT_ID environment variable is not set. "
                    + "Please set it to your Azure AD application client ID.");
            return 1;
        }

        AuthModule authModule;
        try {
            authModule = new AuthModule(clientId);
            System.out.println("Authenticating with Microsoft Graph...");
            authModule.authenticate();
            System.out.println("Authentication successful.");
        } catch (Exception e) {
            System.err.println("Error: Authentication failed - " + e.getMessage());
            return 1;
        }

        // 4. Create Graph client and resolve section
        var graphClient = new GraphClientWrapper(authModule);
        var sectionResolver = new SectionResolver(graphClient);

        SectionInfo sectionInfo;
        try {
            if (cliArgs.getSectionId() != null && !cliArgs.getSectionId().isBlank()) {
                sectionInfo = sectionResolver.resolveById(cliArgs.getSectionId());
            } else {
                sectionInfo = sectionResolver.resolveByName(
                        cliArgs.getNotebookName(), cliArgs.getSectionName());
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }

        // 5. List all pages in the section
        var pageLister = new PageLister(graphClient);
        List<PageInfo> pages;
        try {
            pages = pageLister.listPages(sectionInfo.sectionId());
        } catch (Exception e) {
            System.err.println("Error: Failed to list pages - " + e.getMessage());
            return 1;
        }

        var totalPages = pages.size();
        System.out.println("Found " + totalPages + " page(s) to export.");

        if (totalPages == 0) {
            System.out.println("No pages to export.");
            return 0;
        }

        // 6. Set up export components
        var pdfDownloader = new PdfDownloader(graphClient);
        var pdfWriter = new PdfWriter(outDir);
        ProgressReporter reporter;
        try {
            reporter = new ProgressReporter(outDir.resolve("export.log"));
        } catch (IOException e) {
            System.err.println("Error: Cannot create log file - " + e.getMessage());
            return 1;
        }

        // 7. Export pages concurrently and report summary
        try {
            var result = exportPagesConcurrently(pages, pdfDownloader, pdfWriter, reporter, cliArgs.getConcurrency());
            reporter.reportSummary(result.totalPages(), result.successCount(), result.failures());
            return result.failures().isEmpty() ? 0 : 1;
        } catch (RuntimeException e) {
            if (e.getCause() instanceof InterruptedException) {
                System.err.println("Error: Export pipeline interrupted - " + e.getMessage());
                Thread.currentThread().interrupt();
                return 1;
            }
            throw e;
        } finally {
            reporter.close();
        }
    }

    /**
     * Exports pages concurrently using virtual threads and structured concurrency.
     * Each page is processed in its own virtual thread, throttled by a semaphore.
     * Returns an ExportResult summarizing successes and failures.
     */
    ExportResult exportPagesConcurrently(
            List<PageInfo> pages,
            PdfDownloader downloader,
            PdfWriter writer,
            ProgressReporter reporter,
            int concurrencyLevel
    ) {
        var totalPages = pages.size();
        var semaphore = new Semaphore(concurrencyLevel);
        var outcomes = new ConcurrentLinkedQueue<PageExportOutcome>();

        try (var scope = StructuredTaskScope.open()) {
            var subtasks = new ArrayList<Subtask<PageExportOutcome>>();

            for (var i = 0; i < totalPages; i++) {
                var page = pages.get(i);
                var current = i + 1;
                var title = page.title() != null ? page.title() : page.pageId();

                var subtask = scope.<PageExportOutcome>fork(() -> {
                    try {
                        semaphore.acquire();
                        try {
                            reporter.reportPageStart(current, totalPages, title);
                            var pdfBytes = downloader.downloadPageAsPdf(page.pageId());
                            var filename = writer.writePdf(title, page.pageId(), pdfBytes);
                            reporter.reportPageSuccess(current, totalPages, title, filename);
                            return new PageExportOutcome.Success(title, filename);
                        } catch (Exception e) {
                            var errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                            reporter.reportPageFailure(current, totalPages, title, page.pageId(), errorMsg);
                            return new PageExportOutcome.Failure(page.pageId(), title, errorMsg);
                        } finally {
                            semaphore.release();
                        }
                    } catch (InterruptedException e) {
                        var errorMsg = "Interrupted while waiting for semaphore";
                        reporter.reportPageFailure(current, totalPages, title, page.pageId(), errorMsg);
                        return new PageExportOutcome.Failure(page.pageId(), title, errorMsg);
                    }
                });
                subtasks.add(subtask);
            }

            scope.join();

            for (var subtask : subtasks) {
                outcomes.add(subtask.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Export pipeline interrupted", e);
        }

        // Aggregate results using pattern matching on the sealed interface
        var successCount = 0;
        var failures = new ArrayList<FailedPage>();

        for (var outcome : outcomes) {
            switch (outcome) {
                case PageExportOutcome.Success _ -> successCount++;
                case PageExportOutcome.Failure f -> failures.add(
                        new FailedPage(f.pageId(), f.pageTitle(), f.errorMessage())
                );
            }
        }

        return new ExportResult(totalPages, successCount, failures.size(), failures);
    }

    /**
     * Returns the client ID from the ONENOTE_CLIENT_ID environment variable.
     * Package-private for testability.
     */
    String getClientId() {
        return System.getenv("ONENOTE_CLIENT_ID");
    }

    private CliArgs buildCliArgs() {
        var args = new CliArgs();
        args.setSectionId(sectionId);
        args.setNotebookName(notebook);
        args.setSectionName(section);
        if (outputDir != null) {
            args.setOutputDir(outputDir);
        }
        args.setConcurrency(concurrency);
        return args;
    }
}
