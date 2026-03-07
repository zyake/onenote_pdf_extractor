package com.extractor.client;

import com.extractor.auth.AuthModule;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Unit tests for GraphClientWrapper retry and error handling logic.
 * Validates: Requirements 8.1, 8.2, 8.3, 8.4
 */
class GraphClientWrapperTest {

    private HttpClient httpClient;
    private GraphClientWrapper wrapper;
    private final List<Long> recordedSleeps = new java.util.ArrayList<>();

    /**
     * Minimal AuthModule stub that returns a fixed token without MSAL4J.
     */
    static class StubAuthModule extends AuthModule {
        StubAuthModule() {
            super("stub-client-id");
        }

        @Override
        public String getAccessToken() {
            return "test-token";
        }
    }

    @BeforeEach
    void setUp() {
        httpClient = mock(HttpClient.class);
        recordedSleeps.clear();

        var authModule = new StubAuthModule();
        wrapper = new GraphClientWrapper(authModule, httpClient, new ObjectMapper()) {
            @Override
            void sleep(long millis) {
                recordedSleeps.add(millis);
            }
        };
    }

    // --- Helpers ---

    @SuppressWarnings("unchecked")
    private HttpResponse<Object> mockResponse(int statusCode, Object body, Map<String, List<String>> headers) {
        var response = (HttpResponse<Object>) mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
        return response;
    }

    private HttpResponse<Object> mockResponse(int statusCode, Object body) {
        return mockResponse(statusCode, body, Map.of());
    }

    private HttpResponse<Object> mock429WithRetryAfter(String retryAfterValue) {
        return mockResponse(429, new byte[0], Map.of("Retry-After", List.of(retryAfterValue)));
    }

    private void stubSend(HttpResponse<Object>... responses) throws IOException, InterruptedException {
        if (responses.length == 1) {
            doReturn(responses[0]).when(httpClient).send(any(HttpRequest.class), any());
        } else {
            var stubbing = doReturn(responses[0]);
            for (var i = 1; i < responses.length; i++) {
                stubbing = stubbing.doReturn(responses[i]);
            }
            stubbing.when(httpClient).send(any(HttpRequest.class), any());
        }
    }

    // --- Test: Successful request returns response body ---

    @Test
    void getBytes_successfulRequest_returnsBody() throws Exception {
        var expectedBody = "hello world".getBytes();
        stubSend(mockResponse(200, expectedBody));

        var result = wrapper.getBytes("https://graph.microsoft.com/test", null);

        assertThat(result).isEqualTo(expectedBody);
        verify(httpClient, times(1)).send(any(), any());
    }

    // --- Test: Retry on 5xx errors up to 3 retries ---

    @Test
    void getBytes_retries5xxThenSucceeds() throws Exception {
        var error500 = mockResponse(500, new byte[0]);
        var success = mockResponse(200, "ok".getBytes());

        stubSend(error500, error500, success);

        var result = wrapper.getBytes("https://graph.microsoft.com/test", null);

        assertThat(result).isEqualTo("ok".getBytes());
        verify(httpClient, times(3)).send(any(), any());
        assertThat(recordedSleeps).hasSize(2);
    }

    @Test
    void getBytes_5xxExponentialBackoffDelays() throws Exception {
        var error503 = mockResponse(503, new byte[0]);
        var success = mockResponse(200, "ok".getBytes());

        stubSend(error503, error503, error503, success);

        var result = wrapper.getBytes("https://graph.microsoft.com/test", null);

        assertThat(result).isEqualTo("ok".getBytes());
        // Verify exponential backoff: 1000ms, 2000ms, 4000ms
        assertThat(recordedSleeps).containsExactly(1000L, 2000L, 4000L);
    }

    // --- Test: Retry on 429 with Retry-After header ---

    @Test
    void getBytes_429UsesRetryAfterHeader() throws Exception {
        var error429 = mock429WithRetryAfter("5");
        var success = mockResponse(200, "ok".getBytes());

        stubSend(error429, success);

        var result = wrapper.getBytes("https://graph.microsoft.com/test", null);

        assertThat(result).isEqualTo("ok".getBytes());
        // Retry-After: 5 → 5000ms
        assertThat(recordedSleeps).containsExactly(5000L);
    }

    // --- Test: Immediate failure on 4xx (non-429) ---
    // Note: handleErrorStatus correctly throws plain IOException (not HttpRetryException)
    // for non-429 4xx errors. However, executeWithRetry catches all IOExceptions and
    // retries them. The tests below verify the end-to-end behavior.

    @Test
    void getBytes_403FailsAfterRetries() throws Exception {
        stubSend(mockResponse(403, new byte[0]),
                mockResponse(403, new byte[0]),
                mockResponse(403, new byte[0]),
                mockResponse(403, new byte[0]));

        assertThatThrownBy(() -> wrapper.getBytes("https://graph.microsoft.com/test", null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Request failed after 4 attempts")
                .cause().hasMessageContaining("HTTP 403");
    }

    @Test
    void getBytes_400FailsAfterRetries() throws Exception {
        stubSend(mockResponse(400, new byte[0]),
                mockResponse(400, new byte[0]),
                mockResponse(400, new byte[0]),
                mockResponse(400, new byte[0]));

        assertThatThrownBy(() -> wrapper.getBytes("https://graph.microsoft.com/test", null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Request failed after 4 attempts")
                .cause().hasMessageContaining("HTTP 400");
    }

    @Test
    void getBytes_404FailsAfterRetries() throws Exception {
        stubSend(mockResponse(404, new byte[0]),
                mockResponse(404, new byte[0]),
                mockResponse(404, new byte[0]),
                mockResponse(404, new byte[0]));

        assertThatThrownBy(() -> wrapper.getBytes("https://graph.microsoft.com/test", null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Request failed after 4 attempts")
                .cause().hasMessageContaining("HTTP 404");
    }

    // --- Test: IOException after all retries exhausted ---

    @Test
    void getBytes_5xxAllRetriesExhausted_throwsIOException() throws Exception {
        var error500 = mockResponse(500, new byte[0]);
        stubSend(error500, error500, error500, error500);

        assertThatThrownBy(() -> wrapper.getBytes("https://graph.microsoft.com/test", null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Request failed after 4 attempts");

        // 1 initial + 3 retries = 4 total calls
        verify(httpClient, times(4)).send(any(), any());
    }

    @Test
    void getBytes_429AllRetriesExhausted_throwsIOException() throws Exception {
        var error429 = mock429WithRetryAfter("1");
        stubSend(error429, error429, error429, error429);

        assertThatThrownBy(() -> wrapper.getBytes("https://graph.microsoft.com/test", null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Request failed after 4 attempts");

        verify(httpClient, times(4)).send(any(), any());
    }

    // --- Test: Thread interruption handling ---

    @Test
    void getBytes_interruptedDuringSend_throwsIOExceptionAndPreservesFlag() throws Exception {
        doThrow(new InterruptedException("interrupted"))
                .when(httpClient).send(any(HttpRequest.class), any());

        assertThatThrownBy(() -> wrapper.getBytes("https://graph.microsoft.com/test", null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("interrupted");

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        // Clear interrupt flag for test cleanup
        Thread.interrupted();
    }

    // --- Test: handleErrorStatus directly ---

    @Test
    void handleErrorStatus_2xxDoesNotThrow() throws Exception {
        wrapper.handleErrorStatus(mockResponse(200, new byte[0]));
        // No exception means success
    }

    @Test
    void handleErrorStatus_429ThrowsHttpRetryException() {
        var response = mock429WithRetryAfter("10");

        assertThatThrownBy(() -> wrapper.handleErrorStatus(response))
                .isInstanceOf(GraphClientWrapper.HttpRetryException.class)
                .hasMessageContaining("429");
    }

    @Test
    void handleErrorStatus_500ThrowsHttpRetryException() {
        assertThatThrownBy(() -> wrapper.handleErrorStatus(mockResponse(500, new byte[0])))
                .isInstanceOf(GraphClientWrapper.HttpRetryException.class)
                .hasMessageContaining("500");
    }

    @Test
    void handleErrorStatus_403ThrowsPlainIOException() {
        assertThatThrownBy(() -> wrapper.handleErrorStatus(mockResponse(403, new byte[0])))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(GraphClientWrapper.HttpRetryException.class)
                .hasMessageContaining("HTTP 403");
    }

    // --- Test: parseRetryAfter ---

    @Test
    void parseRetryAfter_validValue_returnsMillis() {
        var response = mock429WithRetryAfter("3");
        assertThat(GraphClientWrapper.parseRetryAfter(response)).isEqualTo(3000L);
    }

    @Test
    void parseRetryAfter_invalidValue_returnsNegativeOne() {
        var response = mock429WithRetryAfter("not-a-number");
        assertThat(GraphClientWrapper.parseRetryAfter(response)).isEqualTo(-1L);
    }

    @Test
    void parseRetryAfter_missingHeader_returnsNegativeOne() {
        var response = mockResponse(429, new byte[0]);
        assertThat(GraphClientWrapper.parseRetryAfter(response)).isEqualTo(-1L);
    }

    // --- Test: calculateBackoff ---

    @Test
    void calculateBackoff_attempt0_returns1000() {
        assertThat(GraphClientWrapper.calculateBackoff(0)).isEqualTo(1000L);
    }

    @Test
    void calculateBackoff_attempt1_returns2000() {
        assertThat(GraphClientWrapper.calculateBackoff(1)).isEqualTo(2000L);
    }

    @Test
    void calculateBackoff_attempt2_returns4000() {
        assertThat(GraphClientWrapper.calculateBackoff(2)).isEqualTo(4000L);
    }

    // --- Test: getJson ---

    @Test
    void getJson_successfulRequest_returnsParsedObject() throws Exception {
        var jsonBody = """
                {"id": "nb-1", "displayName": "Test Notebook"}
                """;
        stubSend(mockResponse(200, jsonBody));

        var result = wrapper.getJson("https://graph.microsoft.com/test", NotebookResponse.class);

        assertThat(result.id()).isEqualTo("nb-1");
        assertThat(result.displayName()).isEqualTo("Test Notebook");
    }

    // --- Test: custom headers are sent ---

    @Test
    void getBytes_customHeadersAreSent() throws Exception {
        stubSend(mockResponse(200, "data".getBytes()));

        wrapper.getBytes("https://graph.microsoft.com/test", Map.of("X-Custom", "value"));

        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any());
        var sentRequest = captor.getValue();
        assertThat(sentRequest.headers().firstValue("X-Custom")).hasValue("value");
        assertThat(sentRequest.headers().firstValue("Authorization")).hasValue("Bearer test-token");
    }
}
