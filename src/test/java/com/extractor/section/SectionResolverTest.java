package com.extractor.section;

import com.extractor.client.GraphClientWrapper;
import com.extractor.client.NotebookResponse;
import com.extractor.client.SectionResponse;
import com.extractor.model.SectionInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for SectionResolver.
 * Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6
 */
class SectionResolverTest {

    private GraphClientWrapper client;
    private SectionResolver resolver;

    @BeforeEach
    void setUp() {
        client = mock(GraphClientWrapper.class);
        resolver = new SectionResolver(client);
    }

    // --- resolveById tests ---

    @Test
    void resolveById_validSection_returnsSectionInfo() throws Exception {
        var sectionId = "sec-123";
        var detail = new SectionResolver.SectionDetailResponse(
                sectionId, "My Section",
                new SectionResolver.ParentNotebook("nb-1", "My Notebook")
        );

        when(client.getJson(
                eq("https://graph.microsoft.com/v1.0/me/onenote/sections/" + sectionId),
                eq(SectionResolver.SectionDetailResponse.class)
        )).thenReturn(detail);

        when(client.getPaginated(
                eq("https://graph.microsoft.com/v1.0/me/onenote/sections/" + sectionId + "/pages"),
                eq(SectionResolver.PageStub.class)
        )).thenReturn(List.of(
                new SectionResolver.PageStub("p1"),
                new SectionResolver.PageStub("p2"),
                new SectionResolver.PageStub("p3")
        ));

        var result = resolver.resolveById(sectionId);

        assertThat(result).isEqualTo(new SectionInfo(sectionId, "My Section", "My Notebook", 3));
    }

    @Test
    void resolveById_nonExistentSection_throwsRuntimeException() throws Exception {
        var sectionId = "bad-id";

        when(client.getJson(
                eq("https://graph.microsoft.com/v1.0/me/onenote/sections/" + sectionId),
                eq(SectionResolver.SectionDetailResponse.class)
        )).thenThrow(new IOException("HTTP 404: request failed"));

        assertThatThrownBy(() -> resolver.resolveById(sectionId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Section not found with ID: " + sectionId);
    }

    @Test
    void resolveById_nullParentNotebook_usesUnknown() throws Exception {
        var sectionId = "sec-orphan";
        var detail = new SectionResolver.SectionDetailResponse(sectionId, "Orphan Section", null);

        when(client.getJson(any(), eq(SectionResolver.SectionDetailResponse.class)))
                .thenReturn(detail);
        when(client.getPaginated(any(), eq(SectionResolver.PageStub.class)))
                .thenReturn(List.of());

        var result = resolver.resolveById(sectionId);

        assertThat(result.notebookName()).isEqualTo("Unknown");
        assertThat(result.pageCount()).isZero();
    }

    // --- resolveByName tests ---

    @Test
    void resolveByName_matchingNotebookAndSection_returnsSectionInfo() throws Exception {
        var notebooks = List.of(
                new NotebookResponse("nb-1", "Work Notes"),
                new NotebookResponse("nb-2", "Personal")
        );
        var sections = List.of(
                new SectionResponse("sec-10", "Meeting Notes"),
                new SectionResponse("sec-11", "Tasks")
        );

        when(client.getPaginated(
                eq("https://graph.microsoft.com/v1.0/me/onenote/notebooks"),
                eq(NotebookResponse.class)
        )).thenReturn(notebooks);

        when(client.getPaginated(
                eq("https://graph.microsoft.com/v1.0/me/onenote/notebooks/nb-1/sections"),
                eq(SectionResponse.class)
        )).thenReturn(sections);

        when(client.getPaginated(
                eq("https://graph.microsoft.com/v1.0/me/onenote/sections/sec-10/pages"),
                eq(SectionResolver.PageStub.class)
        )).thenReturn(List.of(new SectionResolver.PageStub("p1"), new SectionResolver.PageStub("p2")));

        var result = resolver.resolveByName("work notes", "meeting notes");

        assertThat(result.sectionId()).isEqualTo("sec-10");
        assertThat(result.sectionName()).isEqualTo("Meeting Notes");
        assertThat(result.notebookName()).isEqualTo("Work Notes");
        assertThat(result.pageCount()).isEqualTo(2);
    }

    @Test
    void resolveByName_nonMatchingNotebook_throwsWithAvailableNames() throws Exception {
        var notebooks = List.of(
                new NotebookResponse("nb-1", "Work Notes"),
                new NotebookResponse("nb-2", "Personal")
        );

        when(client.getPaginated(
                eq("https://graph.microsoft.com/v1.0/me/onenote/notebooks"),
                eq(NotebookResponse.class)
        )).thenReturn(notebooks);

        assertThatThrownBy(() -> resolver.resolveByName("School", "Homework"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Notebook not found: 'School'")
                .hasMessageContaining("Work Notes")
                .hasMessageContaining("Personal");
    }

    @Test
    void resolveByName_nonMatchingSection_throwsWithAvailableNames() throws Exception {
        var notebooks = List.of(new NotebookResponse("nb-1", "Work Notes"));
        var sections = List.of(
                new SectionResponse("sec-10", "Meeting Notes"),
                new SectionResponse("sec-11", "Tasks")
        );

        when(client.getPaginated(
                eq("https://graph.microsoft.com/v1.0/me/onenote/notebooks"),
                eq(NotebookResponse.class)
        )).thenReturn(notebooks);

        when(client.getPaginated(
                eq("https://graph.microsoft.com/v1.0/me/onenote/notebooks/nb-1/sections"),
                eq(SectionResponse.class)
        )).thenReturn(sections);

        assertThatThrownBy(() -> resolver.resolveByName("Work Notes", "Ideas"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Section not found: 'Ideas'")
                .hasMessageContaining("notebook 'Work Notes'")
                .hasMessageContaining("Meeting Notes")
                .hasMessageContaining("Tasks");
    }

    @Test
    void resolveByName_ambiguousSectionMatch_throwsWithMatchingIds() throws Exception {
        var notebooks = List.of(new NotebookResponse("nb-1", "Work Notes"));
        var sections = List.of(
                new SectionResponse("sec-10", "Notes"),
                new SectionResponse("sec-11", "Notes")
        );

        when(client.getPaginated(
                eq("https://graph.microsoft.com/v1.0/me/onenote/notebooks"),
                eq(NotebookResponse.class)
        )).thenReturn(notebooks);

        when(client.getPaginated(
                eq("https://graph.microsoft.com/v1.0/me/onenote/notebooks/nb-1/sections"),
                eq(SectionResponse.class)
        )).thenReturn(sections);

        assertThatThrownBy(() -> resolver.resolveByName("Work Notes", "Notes"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Multiple sections found matching 'Notes'")
                .hasMessageContaining("sec-10")
                .hasMessageContaining("sec-11");
    }
}
