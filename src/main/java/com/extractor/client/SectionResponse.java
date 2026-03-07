package com.extractor.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Maps to a OneNote section object from the Microsoft Graph API.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SectionResponse(
        @JsonProperty("id") String id,
        @JsonProperty("displayName") String displayName
) {
}
