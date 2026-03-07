package com.extractor.page;

import com.extractor.client.GraphClientWrapper;
import com.extractor.client.PageResponse;
import com.extractor.model.PageInfo;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Retrieves all pages from a OneNote section with pagination support.
 */
public class PageLister {

    private static final String GRAPH_BASE = "https://graph.microsoft.com/v1.0";

    private final GraphClientWrapper client;

    public PageLister(GraphClientWrapper client) {
        this.client = client;
    }

    /**
     * List all pages in a section, handling pagination.
     *
     * @param sectionId the OneNote section ID
     * @return list of PageInfo objects ordered by creation date
     * @throws RuntimeException if the API call fails
     */
    public List<PageInfo> listPages(String sectionId) {
        try {
            String url = GRAPH_BASE + "/me/onenote/sections/" + sectionId
                    + "/pages?$orderby=createdDateTime";

            List<PageResponse> responses = client.getPaginated(url, PageResponse.class);

            return responses.stream()
                    .map(this::toPageInfo)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException("Failed to list pages for section: " + sectionId, e);
        }
    }

    private PageInfo toPageInfo(PageResponse response) {
        Instant created = response.getCreatedDateTime() != null
                ? Instant.parse(response.getCreatedDateTime())
                : null;
        return new PageInfo(response.getId(), response.getTitle(), created);
    }
}
