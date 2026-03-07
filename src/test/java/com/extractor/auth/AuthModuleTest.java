package com.extractor.auth;

import com.microsoft.aad.msal4j.*;

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
 * Unit tests for AuthModule token caching, silent refresh, and error handling.
 * Validates: Requirements 2.4, 2.5, 2.6
 */
class AuthModuleTest {

    private PublicClientApplication mockClient;
    private AuthModule authModule;
    private static final Set<String> SCOPES = Set.of("Notes.Read", "Notes.Read.All");

    @BeforeEach
    void setUp() {
        mockClient = mock(PublicClientApplication.class);
        authModule = new AuthModule(mockClient, SCOPES);
    }

    private IAuthenticationResult mockAuthResult(String token, Date expiresOn, IAccount account) {
        var result = mock(IAuthenticationResult.class);
        when(result.accessToken()).thenReturn(token);
        when(result.expiresOnDate()).thenReturn(expiresOn);
        when(result.account()).thenReturn(account);
        return result;
    }

    private Date futureDate() {
        return new Date(System.currentTimeMillis() + 3600_000);
    }

    private Date pastDate() {
        return new Date(System.currentTimeMillis() - 3600_000);
    }

    // --- Requirement 2.4: Cached token returned when not expired ---

    @Test
    void getAccessToken_returnsCachedToken_whenNotExpired() throws Exception {
        var account = mock(IAccount.class);
        var authResult = mockAuthResult("cached-token", futureDate(), account);
        when(mockClient.acquireToken(any(DeviceCodeFlowParameters.class)))
                .thenReturn(CompletableFuture.completedFuture(authResult));

        // First call triggers authenticate (device code flow)
        var token1 = authModule.getAccessToken();
        assertThat(token1).isEqualTo("cached-token");

        // Second call should return cached token without re-authenticating
        var token2 = authModule.getAccessToken();
        assertThat(token2).isEqualTo("cached-token");

        // acquireToken should only have been called once (the initial authenticate)
        verify(mockClient, times(1)).acquireToken(any(DeviceCodeFlowParameters.class));
        verify(mockClient, never()).acquireTokenSilently(any(SilentParameters.class));
    }

    // --- Requirement 2.5: Silent refresh when expired ---

    @Test
    void getAccessToken_triggersSilentRefresh_whenTokenExpired() throws Exception {
        var account = mock(IAccount.class);
        var expiredResult = mockAuthResult("expired-token", pastDate(), account);
        var refreshedResult = mockAuthResult("refreshed-token", futureDate(), account);

        when(mockClient.acquireToken(any(DeviceCodeFlowParameters.class)))
                .thenReturn(CompletableFuture.completedFuture(expiredResult));
        when(mockClient.acquireTokenSilently(any(SilentParameters.class)))
                .thenReturn(CompletableFuture.completedFuture(refreshedResult));

        // First call triggers authenticate, caches the expired result
        var token1 = authModule.getAccessToken();
        // The initial authenticate returns the expired token, but getAccessToken
        // then detects it's expired and does a silent refresh
        assertThat(token1).isEqualTo("refreshed-token");

        verify(mockClient, times(1)).acquireToken(any(DeviceCodeFlowParameters.class));
        verify(mockClient, times(1)).acquireTokenSilently(any(SilentParameters.class));
    }

    @Test
    void getAccessToken_fallsBackToDeviceCode_whenSilentRefreshFails() throws Exception {
        var account = mock(IAccount.class);
        var expiredResult = mockAuthResult("expired-token", pastDate(), account);
        var freshResult = mockAuthResult("fresh-token", futureDate(), account);

        // First call: authenticate returns expired token
        // Second call (fallback): authenticate returns fresh token
        when(mockClient.acquireToken(any(DeviceCodeFlowParameters.class)))
                .thenReturn(CompletableFuture.completedFuture(expiredResult))
                .thenReturn(CompletableFuture.completedFuture(freshResult));

        // Silent refresh fails
        when(mockClient.acquireTokenSilently(any(SilentParameters.class)))
                .thenReturn(CompletableFuture.failedFuture(new MsalException("refresh failed", "error")));

        var token = authModule.getAccessToken();
        assertThat(token).isEqualTo("fresh-token");

        // acquireToken called twice: initial + fallback after silent failure
        verify(mockClient, times(2)).acquireToken(any(DeviceCodeFlowParameters.class));
        verify(mockClient, times(1)).acquireTokenSilently(any(SilentParameters.class));
    }

    @Test
    void getAccessToken_fallsBackToDeviceCode_whenNoAccountForSilentRefresh() throws Exception {
        // Expired token with null account — silent refresh should fail
        var expiredResult = mockAuthResult("expired-token", pastDate(), null);
        var freshResult = mockAuthResult("fresh-token", futureDate(), mock(IAccount.class));

        when(mockClient.acquireToken(any(DeviceCodeFlowParameters.class)))
                .thenReturn(CompletableFuture.completedFuture(expiredResult))
                .thenReturn(CompletableFuture.completedFuture(freshResult));

        var token = authModule.getAccessToken();
        assertThat(token).isEqualTo("fresh-token");

        // acquireToken called twice: initial + fallback
        verify(mockClient, times(2)).acquireToken(any(DeviceCodeFlowParameters.class));
        // acquireTokenSilently never called because account was null
        verify(mockClient, never()).acquireTokenSilently(any(SilentParameters.class));
    }

    // --- Requirement 2.6: Authentication failure throws RuntimeException ---

    @Test
    void authenticate_throwsRuntimeException_onMsalFailure() {
        when(mockClient.acquireToken(any(DeviceCodeFlowParameters.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new MsalException("AADSTS error", "invalid_grant")));

        assertThatThrownBy(() -> authModule.authenticate())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Authentication failed");
    }

    @Test
    void authenticate_throwsRuntimeException_withDescriptiveMessage() {
        var cause = new RuntimeException("Network timeout");
        when(mockClient.acquireToken(any(DeviceCodeFlowParameters.class)))
                .thenReturn(CompletableFuture.failedFuture(cause));

        assertThatThrownBy(() -> authModule.authenticate())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Authentication failed")
                .hasMessageContaining("Network timeout");
    }

    @Test
    void authenticate_returnsAuthResult_onSuccess() {
        var account = mock(IAccount.class);
        var authResult = mockAuthResult("access-token-123", futureDate(), account);
        when(mockClient.acquireToken(any(DeviceCodeFlowParameters.class)))
                .thenReturn(CompletableFuture.completedFuture(authResult));

        var result = authModule.authenticate();

        assertThat(result).isNotNull();
        assertThat(result.accessToken()).isEqualTo("access-token-123");
        assertThat(result.expiresAt()).isNotNull();
    }
}
