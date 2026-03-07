package com.extractor.model;

/**
 * Metadata about a resolved OneNote section.
 */
public record SectionInfo(String sectionId, String sectionName, String notebookName, int pageCount) {
}
