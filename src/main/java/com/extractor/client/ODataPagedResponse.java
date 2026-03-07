package com.extractor.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Generic model for OData paginated responses from the Microsoft Graph API.
 * Parses the "value" array and "@odata.nextLink" field.
 *
 * @param <T> the type of items in the value array
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ODataPagedResponse<T>(
        @JsonProperty("value") List<T> value,
        @JsonProperty("@odata.nextLink") String nextLink
) {
}
