package com.extractor.auth;

import com.microsoft.aad.msal4j.ClientCredentialParameters;
import com.microsoft.aad.msal4j.ConfidentialClientApplication;
import com.microsoft.aad.msal4j.IAuthenticationResult;
import com.microsoft.aad.msal4j.MsalException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for GraphCredentialsAuthModule.
 * Validates: Requirements 2.1, 2.2, 2.3, 2.4
 */
class GraphCredentialsAuthModuleTest {

    private static final Set<String> SCOPES = Set.of("https://graph.microsoft.com/.default");

    private ConfidentialClientApplication mockClient;
    private GraphCredentialsAuthModule authModule;

    @BeforeEach
    void setUp() {
        mockClient = mock(ConfidentialClientApplication.class);
        authModule = new GraphCredentialsAuthModule(mockClient, SCOPES);
    }

    private IAuthenticationResult mockResult(String token, Date expiresOn) {
        var result = mock(IAuthenticationResult.class);
        when(result.accessToken()).thenReturn(token);
        when(result.expiresOnDate()).thenReturn(expiresOn);
        return result;
    }

    private Date futureDate() {
        // 1 hour from now — well beyond the 5-minute buffer
        return new Date(System.currentTimeMillis() + 3600_000);
    }

    private Date soonDate() {
        // 2 minutes from now — within the 5-minute expiry buffer
        return new Date(System.currentTimeMillis() + 120_000);
    }

    private Date pastDate() {
        return new Date(System.currentTimeMillis() - 3600_000);
    }

    // --- Requirement 2.1: Client credentials flow ---

    @Test
    void getAccessToken_acquiresTokenViaClientCredentials() {
        var result = mockResult("graph-token-abc", futureDate());
        when(mockClient.acquireToken(any(ClientCredentialParameters.class)))
                .thenReturn(CompletableFuture.completedFuture(result));

        var token = authModule.getAccessToken();

        assertThat(token).isEqualTo("graph-token-abc");
        verify(mockClient, times(1)).acquireToken(any(ClientCredentialParameters.class));
    }

    // --- Requirement 2.4: Token caching ---

    @Test
    void getAccessToken_returnsCachedToken_whenNotExpired() {
        var result = mockResult("cached-token", futureDate());
        when(mockClient.acquireToken(any(ClientCredentialParameters.class)))
                .thenReturn(CompletableFuture.completedFuture(result));

        var token1 = authModule.getAccessToken();
        var token2 = authModule.getAccessToken();

        assertThat(token1).isEqualTo("cached-token");
        assertThat(token2).isEqualTo("cached-token");
        // Only one actual token acquisition
        verify(mockClient, times(1)).acquireToken(any(ClientCredentialParameters.class));
    }

    // --- Requirement 2.4: Refresh before expiration ---

    @Test
    void getAccessToken_refreshesToken_whenAboutToExpire() {
        var expiringResult = mockResult("expiring-token", soonDate());
        var freshResult = mockResult("fresh-token", futureDate());

        when(mockClient.acquireToken(any(ClientCredentialParameters.class)))
                .thenReturn(CompletableFuture.completedFuture(expiringResult))
                .thenReturn(CompletableFuture.completedFuture(freshResult));

        // First call acquires the expiring token
        var token1 = authModule.getAccessToken();
        assertThat(token1).isEqualTo("expiring-token");

        // Second call detects it's within the buffer and refreshes
        var token2 = authModule.getAccessToken();
        assertThat(token2).isEqualTo("fresh-token");

        verify(mockClient, times(2)).acquireToken(any(ClientCredentialParameters.class));
    }

    @Test
    void getAccessToken_refreshesToken_whenExpired() {
        var expiredResult = mockResult("expired-token", pastDate());
        var freshResult = mockResult("fresh-token", futureDate());

        when(mockClient.acquireToken(any(ClientCredentialParameters.class)))
                .thenReturn(CompletableFuture.completedFuture(expiredResult))
                .thenReturn(CompletableFuture.completedFuture(freshResult));

        authModule.getAccessToken();
        var token = authModule.getAccessToken();

        assertThat(token).isEqualTo("fresh-token");
        verify(mockClient, times(2)).acquireToken(any(ClientCredentialParameters.class));
    }

    // --- Requirement 2.3: Retry on failure ---

    @Test
    void getAccessToken_retriesOnTransientFailure() {
        var result = mockResult("retry-token", futureDate());

        when(mockClient.acquireToken(any(ClientCredentialParameters.class)))
                .thenReturn(CompletableFuture.failedFuture(new MsalException("transient", "error")))
                .thenReturn(CompletableFuture.completedFuture(result));

        var token = authModule.getAccessToken();

        assertThat(token).isEqualTo("retry-token");
        verify(mockClient, times(2)).acquireToken(any(ClientCredentialParameters.class));
    }

    @Test
    void getAccessToken_throwsAfterAllRetriesExhausted() {
        when(mockClient.acquireToken(any(ClientCredentialParameters.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new MsalException("persistent failure", "error")));

        assertThatThrownBy(() -> authModule.getAccessToken())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to acquire Graph API token after retries");
    }

    // --- Edge case: null expiresOnDate ---

    @Test
    void getAccessToken_treatsNullExpirationAsExpired() {
        var nullExpiryResult = mockResult("null-expiry-token", null);
        var freshResult = mockResult("fresh-token", futureDate());

        when(mockClient.acquireToken(any(ClientCredentialParameters.class)))
                .thenReturn(CompletableFuture.completedFuture(nullExpiryResult))
                .thenReturn(CompletableFuture.completedFuture(freshResult));

        // First call gets the null-expiry token
        authModule.getAccessToken();
        // Second call should re-acquire since null expiry is treated as expired
        var token = authModule.getAccessToken();

        assertThat(token).isEqualTo("fresh-token");
        verify(mockClient, times(2)).acquireToken(any(ClientCredentialParameters.class));
    }
}
