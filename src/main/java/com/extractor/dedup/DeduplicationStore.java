package com.extractor.dedup;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;

import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/**
 * DynamoDB-backed store for tracking exported OneNote pages.
 * Used to deduplicate exports by comparing page last-modified timestamps.
 */
public class DeduplicationStore {

    private static final String ATTR_PAGE_ID = "pageId";
    private static final String ATTR_LAST_MODIFIED = "lastModifiedTimestamp";
    private static final String ATTR_S3_KEY = "s3Key";
    private static final String ATTR_EXPORT_TIMESTAMP = "exportTimestamp";
    private static final String ATTR_NOTEBOOK_LM_UPLOADED = "notebookLmUploaded";

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public DeduplicationStore(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = dynamoDb;
        this.tableName = tableName;
    }

    /**
     * Retrieves the stored export record for a given page ID.
     *
     * @param pageId the OneNote page ID
     * @return the export record if it exists, empty otherwise
     */
    public Optional<ExportRecord> getExportRecord(String pageId) {
        var request = GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(ATTR_PAGE_ID, AttributeValue.fromS(pageId)))
                .build();

        var response = dynamoDb.getItem(request);

        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(fromItem(response.item()));
    }

    /**
     * Stores or overwrites an export record in DynamoDB.
     *
     * @param record the export record to persist
     */
    public void recordExport(ExportRecord record) {
        var item = Map.of(
                ATTR_PAGE_ID, AttributeValue.fromS(record.pageId()),
                ATTR_LAST_MODIFIED, AttributeValue.fromS(record.lastModifiedTimestamp().toString()),
                ATTR_S3_KEY, AttributeValue.fromS(record.s3Key()),
                ATTR_EXPORT_TIMESTAMP, AttributeValue.fromS(record.exportTimestamp().toString()),
                ATTR_NOTEBOOK_LM_UPLOADED, AttributeValue.fromBool(record.notebookLmUploaded())
        );

        var request = PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .build();

        dynamoDb.putItem(request);
    }

    /**
     * Determines whether a page should be exported based on deduplication logic.
     * Returns true if no record exists for the page, or if the page's lastModified
     * is strictly after the stored lastModifiedTimestamp.
     *
     * @param pageId       the OneNote page ID
     * @param lastModified the page's current last-modified timestamp from Graph API
     * @return true if the page should be exported
     */
    public boolean shouldExport(String pageId, Instant lastModified) {
        var existing = getExportRecord(pageId);
        return existing
                .map(record -> lastModified.isAfter(record.lastModifiedTimestamp()))
                .orElse(true);
    }

    private ExportRecord fromItem(Map<String, AttributeValue> item) {
        return new ExportRecord(
                item.get(ATTR_PAGE_ID).s(),
                Instant.parse(item.get(ATTR_LAST_MODIFIED).s()),
                item.get(ATTR_S3_KEY).s(),
                Instant.parse(item.get(ATTR_EXPORT_TIMESTAMP).s()),
                item.get(ATTR_NOTEBOOK_LM_UPLOADED).bool()
        );
    }
}
