package com.extractor.client;

import com.extractor.auth.AuthModule;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Wraps java.net.http.HttpClient to provide authenticated access to the
 * Microsoft Graph API with retry and rate-limit handling.
 */
public class GraphClientWrapper {

    static final int MAX_RETRIES = 3;
    static final long INITIAL_BACKOFF_MS = 1000;
    static final double BACKOFF_MULTIPLIER = 2.0;

    private final AuthModule authModule;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GraphClientWrapper(AuthModule authModule) {
        this(authModule, HttpClient.newHttpClient(), new ObjectMapper());
    }

    /**
     * Package-private constructor for testing with injected dependencies.
     */
    GraphClientWrapper(AuthModule authModule, HttpClient httpClient, ObjectMapper objectMapper) {
        this.authModule = authModule;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Execute a GET request, returning the response body as bytes.
     *
     * @param url     the full URL to request
     * @param headers additional headers to include (may be null or empty)
     * @return the response body as a byte array
     * @throws IOException if the request fails after all retries
     */
    public byte[] getBytes(String url, Map<String, String> headers) throws IOException {
        return executeWithRetry(() -> {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + authModule.getAccessToken())
                    .GET();

            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    builder.header(entry.getKey(), entry.getValue());
                }
            }

            HttpResponse<byte[]> response = httpClient.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofByteArray()
            );

            handleErrorStatus(response);
            return response.body();
        });
    }

    /**
     * Execute a GET request, returning parsed JSON.
     *
     * @param url          the full URL to request
     * @param responseType the class to deserialize the JSON response into
     * @param <T>          the response type
     * @return the deserialized response object
     * @throws IOException if the request fails after all retries
     */
    public <T> T getJson(String url, Class<T> responseType) throws IOException {
        return executeWithRetry(() -> {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + authModule.getAccessToken())
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            handleErrorStatus(response);
            return objectMapper.readValue(response.body(), responseType);
        });
    }

    /**
     * Execute a paginated GET, following @odata.nextLink until all items are collected.
     *
     * @param url      the initial URL to request
     * @param itemType the class of items in the "value" array
     * @param <T>      the item type
     * @return a list of all items across all pages
     * @throws IOException if any page request fails after all retries
     */
    public <T> List<T> getPaginated(String url, Class<T> itemType) throws IOException {
        List<T> allItems = new ArrayList<>();
        String currentUrl = url;

        JavaType pagedType = objectMapper.getTypeFactory()
                .constructParametricType(ODataPagedResponse.class, itemType);

        while (currentUrl != null) {
            final String requestUrl = currentUrl;

            ODataPagedResponse<T> page = executeWithRetry(() -> {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(requestUrl))
                        .header("Authorization", "Bearer " + authModule.getAccessToken())
                        .header("Accept", "application/json")
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

                handleErrorStatus(response);
                return objectMapper.readValue(response.body(), pagedType);
            });

            if (page.getValue() != null) {
                allItems.addAll(page.getValue());
            }
            currentUrl = page.getNextLink();
        }

        return Collections.unmodifiableList(allItems);
    }

    /**
     * Executes a request with retry logic: up to MAX_RETRIES retries with
     * exponential backoff. On HTTP 429, uses the Retry-After header value.
     */
    private <T> T executeWithRetry(RetryableRequest<T> request) throws IOException {
        IOException lastException = null;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return request.execute();
            } catch (HttpRetryException e) {
                lastException = e;
                if (attempt == MAX_RETRIES) {
                    break;
                }
                long backoffMs = e.getRetryAfterMs() > 0
                        ? e.getRetryAfterMs()
                        : (long) (INITIAL_BACKOFF_MS * Math.pow(BACKOFF_MULTIPLIER, attempt));
                sleep(backoffMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Request interrupted", e);
            } catch (IOException e) {
                lastException = e;
                if (attempt == MAX_RETRIES) {
                    break;
                }
                long backoffMs = (long) (INITIAL_BACKOFF_MS * Math.pow(BACKOFF_MULTIPLIER, attempt));
                sleep(backoffMs);
            }
        }

        throw new IOException("Request failed after " + (MAX_RETRIES + 1) + " attempts", lastException);
    }

    /**
     * Checks the HTTP response status and throws an appropriate exception
     * for error responses.
     */
    private void handleErrorStatus(HttpResponse<?> response) throws HttpRetryException, IOException {
        int statusCode = response.statusCode();
        if (statusCode >= 200 && statusCode < 300) {
            return;
        }

        if (statusCode == 429) {
            long retryAfterMs = parseRetryAfter(response);
            throw new HttpRetryException(statusCode, "Rate limited (HTTP 429)", retryAfterMs);
        }

        if (statusCode >= 500) {
            throw new HttpRetryException(statusCode, "Server error (HTTP " + statusCode + ")", -1);
        }

        // Non-retryable client errors (4xx except 429) — wrap in IOException to fail immediately
        throw new IOException("HTTP " + statusCode + ": request failed");
    }

    /**
     * Parses the Retry-After header value from an HTTP response.
     * Returns the value in milliseconds, or -1 if not present or unparseable.
     */
    private long parseRetryAfter(HttpResponse<?> response) {
        return response.headers()
                .firstValue("Retry-After")
                .map(value -> {
                    try {
                        return Long.parseLong(value) * 1000; // seconds to ms
                    } catch (NumberFormatException e) {
                        return -1L;
                    }
                })
                .orElse(-1L);
    }

    /**
     * Sleeps for the specified duration. Extracted for testability.
     */
    void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Functional interface for a request that can be retried.
     */
    @FunctionalInterface
    interface RetryableRequest<T> {
        T execute() throws IOException, InterruptedException;
    }

    /**
     * Exception indicating a retryable HTTP error.
     */
    static class HttpRetryException extends IOException {
        private final int statusCode;
        private final long retryAfterMs;

        HttpRetryException(int statusCode, String message, long retryAfterMs) {
            super(message);
            this.statusCode = statusCode;
            this.retryAfterMs = retryAfterMs;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public long getRetryAfterMs() {
            return retryAfterMs;
        }
    }
}
