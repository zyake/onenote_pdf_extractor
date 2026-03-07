package com.extractor.model;

/**
 * Records a page that failed to export.
 */
public class FailedPage {
    private String pageId;
    private String pageTitle;
    private String errorMessage;

    public FailedPage() {}

    public FailedPage(String pageId, String pageTitle, String errorMessage) {
        this.pageId = pageId;
        this.pageTitle = pageTitle;
        this.errorMessage = errorMessage;
    }

    public String getPageId() { return pageId; }
    public void setPageId(String pageId) { this.pageId = pageId; }

    public String getPageTitle() { return pageTitle; }
    public void setPageTitle(String pageTitle) { this.pageTitle = pageTitle; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
