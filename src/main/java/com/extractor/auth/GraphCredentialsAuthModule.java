package com.extractor.auth;

import com.extractor.util.RetryExecutor;
import com.microsoft.aad.msal4j.ClientCredentialFactory;
import com.microsoft.aad.msal4j.ClientCredentialParameters;
import com.microsoft.aad.msal4j.ConfidentialClientApplication;
import com.microsoft.aad.msal4j.IAuthenticationResult;

import java.net.MalformedURLException;
import java.time.Instant;
import java.util.Set;

/**
 * Acquires Microsoft Graph API tokens using OAuth2 client credentials flow (app-only).
 * Caches tokens in memory and refreshes before expiration.
 * Uses {@link RetryExecutor} for resilient token acquisition.
 */
public class GraphCredentialsAuthModule implements TokenProvider {

    private static final String AUTHORITY_TEMPLATE = "https://login.microsoftonline.com/%s";
    private static final Set<String> SCOPES = Set.of("https://graph.microsoft.com/.default");
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 500;
    /** Refresh buffer — acquire a new token this many seconds before actual expiry. */
    private static final long EXPIRY_BUFFER_SECONDS = 300;

    private final ConfidentialClientApplication clientApplication;
    private final Set<String> scopes;
    private volatile IAuthenticationResult cachedResult;

    /**
     * Creates a GraphCredentialsAuthModule from Azure AD app registration details.
     *
     * @param clientId     Azure AD application (client) ID
     * @param clientSecret Azure AD client secret
     * @param tenantId     Azure AD tenant ID
     */
    public GraphCredentialsAuthModule(String clientId, String clientSecret, String tenantId) {
        this(buildClientApplication(clientId, clientSecret, tenantId), SCOPES);
    }

    /**
     * Package-private constructor for testing with a pre-built client application.
     */
    GraphCredentialsAuthModule(ConfidentialClientApplication clientApplication, Set<String> scopes) {
        this.clientApplication = clientApplication;
        this.scopes = scopes;
    }

    @Override
    public String getAccessToken() {
        if (cachedResult != null && !isTokenExpiredOrExpiring()) {
            return cachedResult.accessToken();
        }
        return acquireTokenWithRetry();
    }

    private boolean isTokenExpiredOrExpiring() {
        if (cachedResult == null || cachedResult.expiresOnDate() == null) {
            return true;
        }
        var expiresAt = cachedResult.expiresOnDate().toInstant();
        return Instant.now().isAfter(expiresAt.minusSeconds(EXPIRY_BUFFER_SECONDS));
    }

    private String acquireTokenWithRetry() {
        try {
            var result = RetryExecutor.execute(this::acquireToken, MAX_RETRIES, INITIAL_BACKOFF_MS);
            cachedResult = result;
            return result.accessToken();
        } catch (Exception e) {
            throw new RuntimeException("Failed to acquire Graph API token after retries: " + e.getMessage(), e);
        }
    }

    private IAuthenticationResult acquireToken() throws Exception {
        var parameters = ClientCredentialParameters.builder(scopes).build();
        return clientApplication.acquireToken(parameters).join();
    }

    private static ConfidentialClientApplication buildClientApplication(
            String clientId, String clientSecret, String tenantId) {
        try {
            var authority = AUTHORITY_TEMPLATE.formatted(tenantId);
            return ConfidentialClientApplication
                    .builder(clientId, ClientCredentialFactory.createFromSecret(clientSecret))
                    .authority(authority)
                    .build();
        } catch (MalformedURLException e) {
            throw new RuntimeException("Invalid authority URL for tenant: " + tenantId, e);
        }
    }
}
