package com.extractor.page;

import com.extractor.client.GraphClientWrapper;
import com.extractor.client.PageResponse;
import com.extractor.model.PageInfo;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

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
            var url = GRAPH_BASE + "/me/onenote/sections/" + sectionId
                    + "/pages?$orderby=createdDateTime";

            var responses = client.getPaginated(url, PageResponse.class);

            return responses.stream()
                    .map(this::toPageInfo)
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to list pages for section: " + sectionId, e);
        }
    }

    private PageInfo toPageInfo(PageResponse response) {
            var created = response.createdDateTime() != null
                    ? Instant.parse(response.createdDateTime())
                    : null;
            var lastModified = response.lastModifiedDateTime() != null
                    ? Instant.parse(response.lastModifiedDateTime())
                    : null;
            return new PageInfo(response.id(), response.title(), created, lastModified);
        }
}
