package com.extractor.storage;

import com.extractor.pdf.PdfWriter;
import com.extractor.util.RetryExecutor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Uploads PDFs to S3 with deterministic key paths derived from
 * notebook name, section name, and sanitized page title.
 * Replaces {@link PdfWriter} for cloud storage.
 *
 * Validates: Requirements 4.1, 4.2, 4.3, 4.4, 4.5
 */
public class S3PdfStore {

    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 500;

    private final S3Client s3;
    private final String bucketName;

    public S3PdfStore(S3Client s3, String bucketName) {
        this.s3 = s3;
        this.bucketName = bucketName;
    }

    /**
     * Uploads PDF bytes to S3 and returns the S3 key used.
     * Retries up to 3 times with exponential backoff on failure.
     * Overwrites any existing object with the same key (Req 4.4).
     *
     * @param notebook       the notebook name
     * @param section        the section name
     * @param sanitizedTitle the already-sanitized page title
     * @param pdfBytes       the PDF content
     * @return the S3 key where the PDF was stored
     */
    public String uploadPdf(String notebook, String section, String sanitizedTitle, byte[] pdfBytes) {
        var key = generateKey(notebook, section, sanitizedTitle);
        var request = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType("application/pdf")
                .build();

        try {
            RetryExecutor.execute(
                    () -> s3.putObject(request, RequestBody.fromBytes(pdfBytes)),
                    MAX_RETRIES,
                    INITIAL_BACKOFF_MS
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload PDF to S3 after %d retries: %s".formatted(MAX_RETRIES, key), e);
        }

        return key;
    }

    /**
     * Generates a deterministic S3 key from notebook, section, and title.
     * Format: {@code {notebook}/{section}/{sanitized_title}.pdf}
     * Reuses {@link PdfWriter#sanitizeFilename(String)} for sanitization.
     */
    static String generateKey(String notebook, String section, String title) {
        var sanitizedNotebook = PdfWriter.sanitizeFilename(notebook);
        var sanitizedSection = PdfWriter.sanitizeFilename(section);
        var sanitizedTitle = PdfWriter.sanitizeFilename(title);
        return "%s/%s/%s.pdf".formatted(sanitizedNotebook, sanitizedSection, sanitizedTitle);
    }
}
