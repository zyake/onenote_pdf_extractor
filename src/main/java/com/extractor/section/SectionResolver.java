package com.extractor.section;

import com.extractor.client.GraphClientWrapper;
import com.extractor.client.NotebookResponse;
import com.extractor.client.SectionResponse;
import com.extractor.model.SectionInfo;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Resolves a OneNote section by ID or by notebook/section name.
 * Uses the Microsoft Graph API via GraphClientWrapper.
 */
public class SectionResolver {

    private static final String GRAPH_BASE = "https://graph.microsoft.com/v1.0";

    private final GraphClientWrapper client;

    public SectionResolver(GraphClientWrapper client) {
        this.client = client;
    }

    /**
     * Resolve a section by its direct ID. Validates the section exists
     * by querying the Graph API.
     *
     * @param sectionId the OneNote section ID
     * @return SectionInfo with section metadata and page count
     * @throws RuntimeException if the section does not exist or the API call fails
     */
    public SectionInfo resolveById(String sectionId) {
        try {
            String url = GRAPH_BASE + "/me/onenote/sections/" + sectionId;
            SectionDetailResponse detail = client.getJson(url, SectionDetailResponse.class);

            String notebookName = detail.parentNotebook != null
                    ? detail.parentNotebook.displayName
                    : "Unknown";

            int pageCount = countPages(sectionId);

            SectionInfo info = new SectionInfo(
                    detail.id,
                    detail.displayName,
                    notebookName,
                    pageCount
            );

            displaySectionInfo(info);
            return info;
        } catch (IOException e) {
            throw new RuntimeException("Section not found with ID: " + sectionId + ". " + e.getMessage(), e);
        }
    }

    /**
     * Resolve a section by notebook name and section name.
     * Lists all notebooks, finds the matching one, then lists its sections
     * to find the matching section.
     *
     * @param notebookName the notebook display name
     * @param sectionName  the section display name
     * @return SectionInfo with section metadata and page count
     * @throws RuntimeException if notebook/section not found or ambiguous match
     */
    public SectionInfo resolveByName(String notebookName, String sectionName) {
        try {
            // Step 1: List all notebooks
            List<NotebookResponse> notebooks = client.getPaginated(
                    GRAPH_BASE + "/me/onenote/notebooks",
                    NotebookResponse.class
            );

            // Step 2: Find matching notebook (case-insensitive)
            List<NotebookResponse> matchingNotebooks = notebooks.stream()
                    .filter(nb -> nb.getDisplayName() != null
                            && nb.getDisplayName().equalsIgnoreCase(notebookName))
                    .collect(Collectors.toList());

            if (matchingNotebooks.isEmpty()) {
                String available = notebooks.stream()
                        .map(NotebookResponse::getDisplayName)
                        .collect(Collectors.joining(", "));
                throw new RuntimeException(
                        "Notebook not found: '" + notebookName + "'. Available notebooks: " + available
                );
            }

            // Step 3: List sections in the matching notebook
            NotebookResponse notebook = matchingNotebooks.get(0);
            List<SectionResponse> sections = client.getPaginated(
                    GRAPH_BASE + "/me/onenote/notebooks/" + notebook.getId() + "/sections",
                    SectionResponse.class
            );

            // Step 4: Find matching section (case-insensitive)
            List<SectionResponse> matchingSections = sections.stream()
                    .filter(s -> s.getDisplayName() != null
                            && s.getDisplayName().equalsIgnoreCase(sectionName))
                    .collect(Collectors.toList());

            if (matchingSections.isEmpty()) {
                String available = sections.stream()
                        .map(SectionResponse::getDisplayName)
                        .collect(Collectors.joining(", "));
                throw new RuntimeException(
                        "Section not found: '" + sectionName + "' in notebook '" + notebookName
                                + "'. Available sections: " + available
                );
            }

            // Step 5: Handle ambiguous matches
            if (matchingSections.size() > 1) {
                String details = matchingSections.stream()
                        .map(s -> "  - " + s.getDisplayName() + " (ID: " + s.getId() + ")")
                        .collect(Collectors.joining("\n"));
                throw new RuntimeException(
                        "Multiple sections found matching '" + sectionName + "' in notebook '"
                                + notebookName + "'. Please specify a section ID:\n" + details
                );
            }

            // Step 6: Build SectionInfo with page count
            SectionResponse matchedSection = matchingSections.get(0);
            int pageCount = countPages(matchedSection.getId());

            SectionInfo info = new SectionInfo(
                    matchedSection.getId(),
                    matchedSection.getDisplayName(),
                    notebook.getDisplayName(),
                    pageCount
            );

            displaySectionInfo(info);
            return info;
        } catch (IOException e) {
            throw new RuntimeException("Failed to resolve section by name: " + e.getMessage(), e);
        }
    }

    /**
     * Counts the number of pages in a section by listing them via the Graph API.
     */
    private int countPages(String sectionId) throws IOException {
        String url = GRAPH_BASE + "/me/onenote/sections/" + sectionId + "/pages";
        List<?> pages = client.getPaginated(url, PageStub.class);
        return pages.size();
    }

    /**
     * Displays section name and page count to stdout (Requirement 2.5).
     */
    private void displaySectionInfo(SectionInfo info) {
        System.out.println("Section: " + info.getSectionName()
                + " (Notebook: " + info.getNotebookName() + ")");
        System.out.println("Pages found: " + info.getPageCount());
    }

    /**
     * Minimal stub for counting pages — only needs the id field.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class PageStub {
        @JsonProperty("id")
        public String id;
    }

    /**
     * Extended section response that includes parentNotebook info,
     * used when resolving by ID.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class SectionDetailResponse {
        @JsonProperty("id")
        public String id;

        @JsonProperty("displayName")
        public String displayName;

        @JsonProperty("parentNotebook")
        public ParentNotebook parentNotebook;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ParentNotebook {
        @JsonProperty("id")
        public String id;

        @JsonProperty("displayName")
        public String displayName;
    }
}
