package com.extractor.auth;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Acquires Google API credentials from a service account JSON key string.
 * Scoped for Google Drive API access (used by NotebookLM uploads).
 */
public class GoogleAuthModule {

    private static final List<String> SCOPES = List.of(
            "https://www.googleapis.com/auth/drive"
    );

    private final GoogleCredentials credentials;

    /**
     * Creates a GoogleAuthModule by parsing a service account JSON key.
     *
     * @param serviceAccountJson the full JSON key content for a Google service account
     * @throws IllegalArgumentException if the JSON string is null or blank
     * @throws RuntimeException         if the JSON cannot be parsed into valid credentials
     */
    public GoogleAuthModule(String serviceAccountJson) {
        if (serviceAccountJson == null || serviceAccountJson.isBlank()) {
            throw new IllegalArgumentException("Service account JSON must not be null or blank");
        }
        this.credentials = parseCredentials(serviceAccountJson);
    }

    /**
     * Returns scoped {@link GoogleCredentials} ready for API calls.
     *
     * @return Google credentials scoped for Drive API access
     */
    public GoogleCredentials getCredentials() {
        return credentials;
    }

    private GoogleCredentials parseCredentials(String json) {
        try (var stream = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))) {
            return ServiceAccountCredentials.fromStream(stream).createScoped(SCOPES);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse Google service account credentials: " + e.getMessage(), e);
        }
    }
}
