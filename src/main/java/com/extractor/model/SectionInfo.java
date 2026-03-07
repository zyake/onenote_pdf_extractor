package com.extractor.model;

/**
 * Metadata about a resolved OneNote section.
 */
public class SectionInfo {
    private String sectionId;
    private String sectionName;
    private String notebookName;
    private int pageCount;

    public SectionInfo() {}

    public SectionInfo(String sectionId, String sectionName, String notebookName, int pageCount) {
        this.sectionId = sectionId;
        this.sectionName = sectionName;
        this.notebookName = notebookName;
        this.pageCount = pageCount;
    }

    public String getSectionId() { return sectionId; }
    public void setSectionId(String sectionId) { this.sectionId = sectionId; }

    public String getSectionName() { return sectionName; }
    public void setSectionName(String sectionName) { this.sectionName = sectionName; }

    public String getNotebookName() { return notebookName; }
    public void setNotebookName(String notebookName) { this.notebookName = notebookName; }

    public int getPageCount() { return pageCount; }
    public void setPageCount(int pageCount) { this.pageCount = pageCount; }
}
