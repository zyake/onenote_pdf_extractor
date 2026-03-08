package com.extractor.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Maps to the JSON response for a OneNote page from the Microsoft Graph API.
 */
/**
 * Maps to the JSON response for a OneNote page from the Microsoft Graph API.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PageResponse(
        @JsonProperty("id") String id,
        @JsonProperty("title") String title,
        @JsonProperty("createdDateTime") String createdDateTime,
        @JsonProperty("lastModifiedDateTime") String lastModifiedDateTime
) {
}
