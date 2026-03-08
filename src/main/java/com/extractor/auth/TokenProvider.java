package com.extractor.auth;

/**
 * Provides access tokens for API authentication.
 * Implementations handle token acquisition, caching, and refresh.
 */
public interface TokenProvider {
    /**
     * Returns a valid access token, refreshing if necessary.
     *
     * @return a valid access token string
     */
    String getAccessToken();
}
