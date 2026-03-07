package com.extractor.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Maps to a OneNote notebook object from the Microsoft Graph API.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class NotebookResponse {

    @JsonProperty("id")
    private String id;

    @JsonProperty("displayName")
    private String displayName;

    public NotebookResponse() {
    }

    public NotebookResponse(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }
}
