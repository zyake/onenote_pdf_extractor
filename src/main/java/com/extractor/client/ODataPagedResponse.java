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
public class ODataPagedResponse<T> {

    @JsonProperty("value")
    private List<T> value;

    @JsonProperty("@odata.nextLink")
    private String nextLink;

    public ODataPagedResponse() {
    }

    public List<T> getValue() {
        return value;
    }

    public void setValue(List<T> value) {
        this.value = value;
    }

    public String getNextLink() {
        return nextLink;
    }

    public void setNextLink(String nextLink) {
        this.nextLink = nextLink;
    }
}
