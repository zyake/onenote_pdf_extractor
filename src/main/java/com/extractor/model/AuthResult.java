package com.extractor.model;

import java.time.Instant;

/**
 * Holds the result of an OAuth2 authentication flow.
 */
public record AuthResult(String accessToken, String refreshToken, Instant expiresAt) {
}
