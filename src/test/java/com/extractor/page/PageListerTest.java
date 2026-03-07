package com.extractor.page;

import com.extractor.client.GraphClientWrapper;
import com.extractor.client.PageResponse;
import com.extractor.model.PageInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for PageLister.
 * Validates: Requirements 4.1, 4.2, 4.3
 */
class PageListerTest {

    private GraphClientWrapper client;
    private PageLister pageLister;

    @BeforeEach
    void setUp() {
        client = mock(GraphClientWrapper.class);
        pageLister = new PageLister(client);
    }

    @Test
    void listPages_paginatedResponse_returnsAllPages() throws Exception {
        var sectionId = "sec-abc";
        var url = "https://graph.microsoft.com/v1.0/me/onenote/sections/" + sectionId
                + "/pages?$orderby=createdDateTime";

        var responses = List.of(
                new PageResponse("p1", "First Page", "2024-01-01T10:00:00Z"),
                new PageResponse("p2", "Second Page", "2024-01-02T10:00:00Z"),
                new PageResponse("p3", "Third Page", "2024-01-03T10:00:00Z")
        );

        when(client.getPaginated(eq(url), eq(PageResponse.class))).thenReturn(responses);

        var result = pageLister.listPages(sectionId);

        assertThat(result).hasSize(3);
        assertThat(result).extracting(PageInfo::pageId)
                .containsExactly("p1", "p2", "p3");
    }

    @Test
    void listPages_mapsPageResponseToPageInfoCorrectly() throws Exception {
        var sectionId = "sec-map";
        var url = "https://graph.microsoft.com/v1.0/me/onenote/sections/" + sectionId
                + "/pages?$orderby=createdDateTime";

        var responses = List.of(
                new PageResponse("page-42", "My Notes", "2024-06-15T14:30:00Z")
        );

        when(client.getPaginated(eq(url), eq(PageResponse.class))).thenReturn(responses);

        var result = pageLister.listPages(sectionId);

        assertThat(result).hasSize(1);
        var page = result.getFirst();
        assertThat(page.pageId()).isEqualTo("page-42");
        assertThat(page.title()).isEqualTo("My Notes");
        assertThat(page.createdDateTime()).isEqualTo(Instant.parse("2024-06-15T14:30:00Z"));
    }

    @Test
    void listPages_nullCreatedDateTime_mapsToNullInstant() throws Exception {
        var sectionId = "sec-null";
        var url = "https://graph.microsoft.com/v1.0/me/onenote/sections/" + sectionId
                + "/pages?$orderby=createdDateTime";

        var responses = List.of(
                new PageResponse("p-no-date", "Undated Page", null)
        );

        when(client.getPaginated(eq(url), eq(PageResponse.class))).thenReturn(responses);

        var result = pageLister.listPages(sectionId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().createdDateTime()).isNull();
    }

    @Test
    void listPages_emptySection_returnsEmptyList() throws Exception {
        var sectionId = "sec-empty";
        var url = "https://graph.microsoft.com/v1.0/me/onenote/sections/" + sectionId
                + "/pages?$orderby=createdDateTime";

        when(client.getPaginated(eq(url), eq(PageResponse.class))).thenReturn(List.of());

        var result = pageLister.listPages(sectionId);

        assertThat(result).isEmpty();
    }

    @Test
    void listPages_apiFailure_throwsRuntimeException() throws Exception {
        var sectionId = "sec-fail";
        var url = "https://graph.microsoft.com/v1.0/me/onenote/sections/" + sectionId
                + "/pages?$orderby=createdDateTime";

        when(client.getPaginated(eq(url), eq(PageResponse.class)))
                .thenThrow(new IOException("HTTP 500: server error"));

        assertThatThrownBy(() -> pageLister.listPages(sectionId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to list pages for section: " + sectionId)
                .hasCauseInstanceOf(IOException.class);
    }
}
