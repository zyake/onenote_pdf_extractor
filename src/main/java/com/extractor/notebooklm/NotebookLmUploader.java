package com.extractor.notebooklm;

import com.extractor.util.RetryExecutor;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;

/**
 * Uploads PDFs to a Google Drive folder that NotebookLM can access.
 * Since NotebookLM has no public upload API, files are placed in a shared
 * Drive folder (identified by {@code folderId}) that the NotebookLM project reads from.
 *
 * <p>Validates: Requirements 5.1, 5.3</p>
 */
public class NotebookLmUploader {

    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 500;
    private static final String APPLICATION_NAME = "onenote-pdf-extractor";

    private final String folderId;
    private final Drive driveService;

    /**
     * Creates an uploader targeting the given Drive folder.
     *
     * @param folderId    the Google Drive folder ID where PDFs are uploaded
     * @param credentials scoped Google credentials for Drive API access
     */
    public NotebookLmUploader(String folderId, GoogleCredentials credentials) {
        this.folderId = folderId;
        this.driveService = buildDriveService(credentials);
    }

    /**
     * Package-private constructor for testing — accepts a pre-built {@link Drive} instance.
     */
    NotebookLmUploader(String folderId, Drive driveService) {
        this.folderId = folderId;
        this.driveService = driveService;
    }

    /**
     * Uploads a PDF to the configured Google Drive folder.
     * Retries up to 3 times with exponential backoff on failure (Req 5.3).
     *
     * @param fileName the name for the uploaded file (e.g. "My_Page.pdf")
     * @param pdfBytes the PDF content
     * @throws RuntimeException if the upload fails after all retry attempts
     */
    public void uploadPdf(String fileName, byte[] pdfBytes) {
        var fileMetadata = new File()
                .setName(fileName)
                .setParents(java.util.List.of(folderId));

        var mediaContent = new ByteArrayContent("application/pdf", pdfBytes);

        try {
            RetryExecutor.execute(
                    () -> driveService.files().create(fileMetadata, mediaContent)
                            .setFields("id,name")
                            .execute(),
                    MAX_RETRIES,
                    INITIAL_BACKOFF_MS
            );
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to upload PDF to Google Drive after %d retries: %s".formatted(MAX_RETRIES, fileName), e);
        }
    }

    private static Drive buildDriveService(GoogleCredentials credentials) {
        try {
            var httpTransport = GoogleNetHttpTransport.newTrustedTransport();
            return new Drive.Builder(httpTransport, GsonFactory.getDefaultInstance(),
                    new HttpCredentialsAdapter(credentials))
                    .setApplicationName(APPLICATION_NAME)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Google Drive service", e);
        }
    }
}
