package com.extractor.pdf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Writes PDF bytes to disk with filename sanitization and collision handling.
 */
public class PdfWriter {

    private static final int MAX_FILENAME_LENGTH = 200;
    private static final String PDF_EXTENSION = ".pdf";

    private final Path outputDirectory;
    private final Set<String> usedFilenames = new HashSet<>();

    public PdfWriter(Path outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    /**
     * Write PDF bytes to a file. Returns the final filename used.
     */
    public String writePdf(String pageTitle, String pageId, byte[] pdfContent) throws IOException {
        var sanitized = sanitizeFilename(pageTitle);
        if (sanitized.isEmpty()) {
            sanitized = sanitizeFilename(pageId);
        }

        if (sanitized.length() > MAX_FILENAME_LENGTH) {
            sanitized = sanitized.substring(0, MAX_FILENAME_LENGTH);
            sanitized = sanitized.replaceAll("_+$", "");
        }

        var filename = sanitized + PDF_EXTENSION;
        filename = resolveCollision(filename);

        usedFilenames.add(filename);
        var filePath = outputDirectory.resolve(filename);
        Files.write(filePath, pdfContent);

        return filename;
    }

    /**
     * Sanitize a page title for use as a filename.
     */
    public static String sanitizeFilename(String title) {
        if (title == null || title.isEmpty()) {
            return "";
        }

        var sanitized = title.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        sanitized = sanitized.replaceAll("_+", "_");
        sanitized = sanitized.replaceAll("^_+", "");
        sanitized = sanitized.replaceAll("_+$", "");

        return sanitized;
    }

    /**
     * Resolve filename collisions by appending numeric suffix (_1, _2, etc.)
     * before the .pdf extension.
     */
    public String resolveCollision(String baseFilename) {
        if (!usedFilenames.contains(baseFilename) && !Files.exists(outputDirectory.resolve(baseFilename))) {
            return baseFilename;
        }

        var nameWithoutExt = baseFilename.substring(0, baseFilename.length() - PDF_EXTENSION.length());
        var counter = 1;
        String candidate;
        do {
            candidate = nameWithoutExt + "_" + counter + PDF_EXTENSION;
            counter++;
        } while (usedFilenames.contains(candidate) || Files.exists(outputDirectory.resolve(candidate)));

        return candidate;
    }
}
