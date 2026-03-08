package com.extractor.notebooklm;

import com.google.api.client.http.ByteArrayContent;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for NotebookLmUploader.
 * Validates: Requirements 5.1, 5.3
 */
class NotebookLmUploaderTest {

    private static final String FOLDER_ID = "test-folder-id";

    private Drive mockDrive;
    private Drive.Files mockFiles;
    private Drive.Files.Create mockCreate;
    private NotebookLmUploader uploader;

    @BeforeEach
    void setUp() throws IOException {
        mockDrive = mock(Drive.class);
        mockFiles = mock(Drive.Files.class);
        mockCreate = mock(Drive.Files.Create.class);

        when(mockDrive.files()).thenReturn(mockFiles);
        when(mockFiles.create(any(File.class), any(ByteArrayContent.class))).thenReturn(mockCreate);
        when(mockCreate.setFields(anyString())).thenReturn(mockCreate);
        when(mockCreate.execute()).thenReturn(new File().setId("created-id").setName("test.pdf"));

        uploader = new NotebookLmUploader(FOLDER_ID, mockDrive);
    }

    // --- Requirement 5.1: Upload PDF to configured NotebookLM project ---

    @Test
    void uploadPdf_uploadsWithCorrectFileNameAndFolder() throws IOException {
        var pdfBytes = new byte[]{0x25, 0x50, 0x44, 0x46}; // %PDF

        uploader.uploadPdf("My_Page.pdf", pdfBytes);

        var fileCaptor = ArgumentCaptor.forClass(File.class);
        var contentCaptor = ArgumentCaptor.forClass(ByteArrayContent.class);
        verify(mockFiles).create(fileCaptor.capture(), contentCaptor.capture());

        var capturedFile = fileCaptor.getValue();
        assertThat(capturedFile.getName()).isEqualTo("My_Page.pdf");
        assertThat(capturedFile.getParents()).containsExactly(FOLDER_ID);

        assertThat(contentCaptor.getValue().getType()).isEqualTo("application/pdf");
    }

    @Test
    void uploadPdf_requestsIdAndNameFields() throws IOException {
        uploader.uploadPdf("test.pdf", new byte[]{1, 2, 3});

        verify(mockCreate).setFields("id,name");
        verify(mockCreate).execute();
    }

    // --- Requirement 5.3: Retry up to 3 times with exponential backoff ---

    @Test
    void uploadPdf_retriesOnTransientFailure_thenSucceeds() throws IOException {
        when(mockCreate.execute())
                .thenThrow(new IOException("Transient network error"))
                .thenReturn(new File().setId("ok"));

        uploader.uploadPdf("retry.pdf", new byte[]{1});

        verify(mockCreate, times(2)).execute();
    }

    @Test
    void uploadPdf_throwsAfterExhaustingRetries() throws IOException {
        when(mockCreate.execute())
                .thenThrow(new IOException("Persistent failure"));

        assertThatThrownBy(() -> uploader.uploadPdf("fail.pdf", new byte[]{1}))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to upload PDF to Google Drive after 3 retries")
                .hasMessageContaining("fail.pdf");

        // 1 initial + 3 retries = 4 total attempts
        verify(mockCreate, times(4)).execute();
    }

    // --- Edge cases ---

    @Test
    void uploadPdf_handlesEmptyPdfBytes() throws IOException {
        uploader.uploadPdf("empty.pdf", new byte[0]);

        verify(mockCreate).execute();
    }

    @Test
    void uploadPdf_handlesLongFileName() throws IOException {
        var longName = "A".repeat(500) + ".pdf";

        uploader.uploadPdf(longName, new byte[]{1});

        var fileCaptor = ArgumentCaptor.forClass(File.class);
        verify(mockFiles).create(fileCaptor.capture(), any(ByteArrayContent.class));
        assertThat(fileCaptor.getValue().getName()).isEqualTo(longName);
    }
}
