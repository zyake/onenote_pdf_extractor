package com.extractor.dedup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DeduplicationStore.
 * Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5
 */
class DeduplicationStoreTest {

    private static final String TABLE_NAME = "ExportTracker";

    private DynamoDbClient mockDynamo;
    private DeduplicationStore store;

    @BeforeEach
    void setUp() {
        mockDynamo = mock(DynamoDbClient.class);
        store = new DeduplicationStore(mockDynamo, TABLE_NAME);
    }

    private Map<String, AttributeValue> dynamoItem(ExportRecord record) {
        return Map.of(
                "pageId", AttributeValue.fromS(record.pageId()),
                "lastModifiedTimestamp", AttributeValue.fromS(record.lastModifiedTimestamp().toString()),
                "s3Key", AttributeValue.fromS(record.s3Key()),
                "exportTimestamp", AttributeValue.fromS(record.exportTimestamp().toString()),
                "notebookLmUploaded", AttributeValue.fromBool(record.notebookLmUploaded())
        );
    }

    private void stubGetItem(Map<String, AttributeValue> item) {
        var response = GetItemResponse.builder().item(item).build();
        when(mockDynamo.getItem(any(GetItemRequest.class))).thenReturn(response);
    }

    private void stubGetItemEmpty() {
        var response = GetItemResponse.builder().build();
        when(mockDynamo.getItem(any(GetItemRequest.class))).thenReturn(response);
    }

    // --- Requirement 3.1: Query dedup store for each page ---

    @Test
    void getExportRecord_returnsRecord_whenItemExists() {
        var record = new ExportRecord(
                "page-1",
                Instant.parse("2025-01-15T10:00:00Z"),
                "notebook/section/page.pdf",
                Instant.parse("2025-01-15T12:00:00Z"),
                true
        );
        stubGetItem(dynamoItem(record));

        var result = store.getExportRecord("page-1");

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(record);
    }

    @Test
    void getExportRecord_returnsEmpty_whenNoItemExists() {
        stubGetItemEmpty();

        var result = store.getExportRecord("nonexistent-page");

        assertThat(result).isEmpty();
    }

    // --- Requirement 3.5: Update store after successful export ---

    @Test
    void recordExport_putsItemWithCorrectAttributes() {
        var record = new ExportRecord(
                "page-42",
                Instant.parse("2025-06-01T08:30:00Z"),
                "research/ai/transformers.pdf",
                Instant.parse("2025-06-01T09:00:00Z"),
                false
        );

        store.recordExport(record);

        var captor = org.mockito.ArgumentCaptor.forClass(PutItemRequest.class);
        verify(mockDynamo).putItem(captor.capture());

        var captured = captor.getValue();
        assertThat(captured.tableName()).isEqualTo(TABLE_NAME);
        assertThat(captured.item().get("pageId").s()).isEqualTo("page-42");
        assertThat(captured.item().get("lastModifiedTimestamp").s()).isEqualTo("2025-06-01T08:30:00Z");
        assertThat(captured.item().get("s3Key").s()).isEqualTo("research/ai/transformers.pdf");
        assertThat(captured.item().get("exportTimestamp").s()).isEqualTo("2025-06-01T09:00:00Z");
        assertThat(captured.item().get("notebookLmUploaded").bool()).isFalse();
    }

    // --- Requirement 3.3: Skip when timestamps match ---

    @Test
    void shouldExport_returnsFalse_whenTimestampsMatch() {
        var stored = new ExportRecord(
                "page-1",
                Instant.parse("2025-01-15T10:00:00Z"),
                "nb/sec/page.pdf",
                Instant.parse("2025-01-15T12:00:00Z"),
                true
        );
        stubGetItem(dynamoItem(stored));

        var result = store.shouldExport("page-1", Instant.parse("2025-01-15T10:00:00Z"));

        assertThat(result).isFalse();
    }

    // --- Requirement 3.4: Export when page is new ---

    @Test
    void shouldExport_returnsTrue_whenNoRecordExists() {
        stubGetItemEmpty();

        var result = store.shouldExport("new-page", Instant.now());

        assertThat(result).isTrue();
    }

    // --- Requirement 3.4: Export when page has newer timestamp ---

    @Test
    void shouldExport_returnsTrue_whenPageIsNewer() {
        var stored = new ExportRecord(
                "page-1",
                Instant.parse("2025-01-15T10:00:00Z"),
                "nb/sec/page.pdf",
                Instant.parse("2025-01-15T12:00:00Z"),
                true
        );
        stubGetItem(dynamoItem(stored));

        var result = store.shouldExport("page-1", Instant.parse("2025-01-16T10:00:00Z"));

        assertThat(result).isTrue();
    }

    // --- Edge case: older timestamp should not trigger export ---

    @Test
    void shouldExport_returnsFalse_whenPageIsOlder() {
        var stored = new ExportRecord(
                "page-1",
                Instant.parse("2025-01-15T10:00:00Z"),
                "nb/sec/page.pdf",
                Instant.parse("2025-01-15T12:00:00Z"),
                true
        );
        stubGetItem(dynamoItem(stored));

        var result = store.shouldExport("page-1", Instant.parse("2025-01-14T10:00:00Z"));

        assertThat(result).isFalse();
    }
}
