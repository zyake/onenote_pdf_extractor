package com.extractor.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for S3PdfStore.
 * Validates: Requirements 4.1, 4.2, 4.3, 4.4, 4.5
 */
class S3PdfStoreTest {

    private static final String BUCKET = "test-pdf-bucket";

    private S3Client mockS3;
    private S3PdfStore store;

    @BeforeEach
    void setUp() {
        mockS3 = mock(S3Client.class);
        store = new S3PdfStore(mockS3, BUCKET);
    }

    // --- Requirement 4.2, 4.3: Deterministic key from notebook/section/title ---

    @Test
    void generateKey_producesDeterministicFormat() {
        var key = S3PdfStore.generateKey("Research Papers", "Distributed Systems", "Spanner TrueTime and the CAP Theorem");

        assertThat(key).isEqualTo("Research_Papers/Distributed_Systems/Spanner_TrueTime_and_the_CAP_Theorem.pdf");
    }

    @Test
    void generateKey_sanitizesSpecialCharacters() {
        var key = S3PdfStore.generateKey("My Notebook!", "Section #1", "Page: A/B\\C");

        assertThat(key).isEqualTo("My_Notebook/Section_1/Page_A_B_C.pdf");
    }

    @Test
    void generateKey_sameInputsProduceSameKey() {
        var key1 = S3PdfStore.generateKey("Notebook", "Section", "Title");
        var key2 = S3PdfStore.generateKey("Notebook", "Section", "Title");

        assertThat(key1).isEqualTo(key2);
    }

    @Test
    void generateKey_handlesUnicodeCharacters() {
        var key = S3PdfStore.generateKey("日本語ノート", "セクション", "ページタイトル");

        // Non-ASCII chars are replaced with underscores and collapsed
        assertThat(key).endsWith(".pdf");
        assertThat(key).doesNotContain(" ");
        assertThat(key.split("/")).hasSize(3);
    }

    // --- Requirement 4.2: Upload PDF to S3 with derived key ---

    @Test
    void uploadPdf_uploadsWithCorrectKeyAndBucket() {
        when(mockS3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        var pdfBytes = new byte[]{0x25, 0x50, 0x44, 0x46}; // %PDF
        var key = store.uploadPdf("Notebook", "Section", "My_Page", pdfBytes);

        assertThat(key).isEqualTo("Notebook/Section/My_Page.pdf");

        var captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(mockS3).putObject(captor.capture(), any(RequestBody.class));

        var captured = captor.getValue();
        assertThat(captured.bucket()).isEqualTo(BUCKET);
        assertThat(captured.key()).isEqualTo("Notebook/Section/My_Page.pdf");
        assertThat(captured.contentType()).isEqualTo("application/pdf");
    }

    // --- Requirement 4.4: Overwrite existing PDF with same key ---

    @Test
    void uploadPdf_overwrites_whenCalledTwiceWithSameInputs() {
        when(mockS3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        var pdfBytes = new byte[]{1, 2, 3};
        var key1 = store.uploadPdf("NB", "Sec", "Page", pdfBytes);
        var key2 = store.uploadPdf("NB", "Sec", "Page", pdfBytes);

        assertThat(key1).isEqualTo(key2);
        verify(mockS3, times(2)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    // --- Requirement 4.5: Retry up to 3 times on failure ---

    @Test
    void uploadPdf_retriesOnFailure_thenSucceeds() {
        when(mockS3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().message("Transient error").build())
                .thenReturn(PutObjectResponse.builder().build());

        var key = store.uploadPdf("NB", "Sec", "Page", new byte[]{1});

        assertThat(key).isEqualTo("NB/Sec/Page.pdf");
        verify(mockS3, times(2)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void uploadPdf_throwsAfterExhaustingRetries() {
        when(mockS3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().message("Persistent failure").build());

        assertThatThrownBy(() -> store.uploadPdf("NB", "Sec", "Page", new byte[]{1}))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to upload PDF to S3 after 3 retries")
                .hasCauseInstanceOf(S3Exception.class);

        // 1 initial + 3 retries = 4 total attempts
        verify(mockS3, times(4)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    // --- Edge case: empty title falls back to empty sanitized string ---

    @Test
    void generateKey_handlesEmptyTitle() {
        var key = S3PdfStore.generateKey("Notebook", "Section", "");

        assertThat(key).isEqualTo("Notebook/Section/.pdf");
    }

    // --- Edge case: title with only special characters ---

    @Test
    void generateKey_handlesAllSpecialCharTitle() {
        var key = S3PdfStore.generateKey("Notebook", "Section", "!!!@@@###");

        // All special chars replaced and collapsed, then leading/trailing underscores stripped
        assertThat(key).endsWith(".pdf");
        assertThat(key).startsWith("Notebook/Section/");
    }
}
