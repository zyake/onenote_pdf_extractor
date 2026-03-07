package com.extractor;

import com.extractor.auth.AuthModule;
import com.extractor.cli.CliArgs;
import com.extractor.client.GraphClientWrapper;
import com.extractor.model.FailedPage;
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

        // 7. Export each page
        var successCount = 0;
        var failures = new ArrayList<FailedPage>();

        try {
            for (var i = 0; i < totalPages; i++) {
                var page = pages.get(i);
                var current = i + 1;
                var title = page.title() != null ? page.title() : page.pageId();

                reporter.reportPageStart(current, totalPages, title);

                try {
                    var pdfBytes = pdfDownloader.downloadPageAsPdf(page.pageId());
                    var filename = pdfWriter.writePdf(page.title(), page.pageId(), pdfBytes);
                    reporter.reportPageSuccess(current, totalPages, title, filename);
                    successCount++;
                } catch (Exception e) {
                    var errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    reporter.reportPageFailure(current, totalPages, title, page.pageId(), errorMsg);
                    failures.add(new FailedPage(page.pageId(), title, errorMsg));
                }
            }

            // 8. Report summary
            reporter.reportSummary(totalPages, successCount, failures);
        } finally {
            reporter.close();
        }

        return failures.isEmpty() ? 0 : 1;
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
        return args;
    }
}
