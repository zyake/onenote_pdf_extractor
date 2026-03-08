package com.extractor.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies Jackson deserialization works correctly with record-based DTOs.
 * Validates: Requirements 11.3
 */
class DtoRecordDeserializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void notebookResponseDeserializes() throws Exception {
        var json = """
                {"id": "nb-1", "displayName": "My Notebook", "extra": true}
                """;
        var result = mapper.readValue(json, NotebookResponse.class);
        assertThat(result.id()).isEqualTo("nb-1");
        assertThat(result.displayName()).isEqualTo("My Notebook");
    }

    @Test
    void sectionResponseDeserializes() throws Exception {
        var json = """
                {"id": "sec-1", "displayName": "My Section", "extra": true}
                """;
        var result = mapper.readValue(json, SectionResponse.class);
        assertThat(result.id()).isEqualTo("sec-1");
        assertThat(result.displayName()).isEqualTo("My Section");
    }

    @Test
    void pageResponseDeserializes() throws Exception {
        var json = """
                {"id": "page-1", "title": "My Page", "createdDateTime": "2024-01-15T10:30:00Z", "lastModifiedDateTime": "2024-02-20T08:45:00Z", "extra": true}
                """;
        var result = mapper.readValue(json, PageResponse.class);
        assertThat(result.id()).isEqualTo("page-1");
        assertThat(result.title()).isEqualTo("My Page");
        assertThat(result.createdDateTime()).isEqualTo("2024-01-15T10:30:00Z");
        assertThat(result.lastModifiedDateTime()).isEqualTo("2024-02-20T08:45:00Z");
    }

    @Test
    void odataPagedResponseDeserializesWithGenericType() throws Exception {
        var json = """
                {
                  "value": [
                    {"id": "nb-1", "displayName": "Notebook One"},
                    {"id": "nb-2", "displayName": "Notebook Two"}
                  ],
                  "@odata.nextLink": "https://graph.microsoft.com/next"
                }
                """;
        var type = mapper.getTypeFactory()
                .constructParametricType(ODataPagedResponse.class, NotebookResponse.class);
        ODataPagedResponse<NotebookResponse> result = mapper.readValue(json, type);

        assertThat(result.value()).hasSize(2);
        assertThat(result.value().get(0).id()).isEqualTo("nb-1");
        assertThat(result.value().get(0).displayName()).isEqualTo("Notebook One");
        assertThat(result.value().get(1).id()).isEqualTo("nb-2");
        assertThat(result.nextLink()).isEqualTo("https://graph.microsoft.com/next");
    }

    @Test
    void odataPagedResponseDeserializesWithNullNextLink() throws Exception {
        var json = """
                {
                  "value": [{"id": "p-1", "title": "Page", "createdDateTime": "2024-01-01T00:00:00Z", "lastModifiedDateTime": "2024-01-02T00:00:00Z"}]
                }
                """;
        var type = mapper.getTypeFactory()
                .constructParametricType(ODataPagedResponse.class, PageResponse.class);
        ODataPagedResponse<PageResponse> result = mapper.readValue(json, type);

        assertThat(result.value()).hasSize(1);
        assertThat(result.value().get(0).title()).isEqualTo("Page");
        assertThat(result.nextLink()).isNull();
    }
}
