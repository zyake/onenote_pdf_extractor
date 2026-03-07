package com.extractor.model;

import java.time.Instant;

/**
 * Metadata about a single OneNote page.
 */
public class PageInfo {
    private String pageId;
    private String title;
    private Instant createdDateTime;

    public PageInfo() {}

    public PageInfo(String pageId, String title, Instant createdDateTime) {
        this.pageId = pageId;
        this.title = title;
        this.createdDateTime = createdDateTime;
    }

    public String getPageId() { return pageId; }
    public void setPageId(String pageId) { this.pageId = pageId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public Instant getCreatedDateTime() { return createdDateTime; }
    public void setCreatedDateTime(Instant createdDateTime) { this.createdDateTime = createdDateTime; }
}
