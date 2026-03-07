package com.extractor.auth;

import com.extractor.model.AuthResult;
import com.microsoft.aad.msal4j.*;

import java.net.MalformedURLException;
import java.time.Instant;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Handles OAuth2 token acquisition and refresh via MSAL4J.
 * Uses device code flow for CLI-friendly authentication.
 */
public class AuthModule {

    private static final String DEFAULT_AUTHORITY = "https://login.microsoftonline.com/common";
    private static final Set<String> SCOPES = Set.of("Notes.Read", "Notes.Read.All");

    private final PublicClientApplication clientApplication;
    private final Set<String> scopes;
    private IAuthenticationResult cachedAuthResult;

    /**
     * Creates an AuthModule with the given client ID and default authority.
     *
     * @param clientId the Azure AD application client ID
     */
    public AuthModule(String clientId) {
        this(clientId, DEFAULT_AUTHORITY);
    }

    /**
     * Creates an AuthModule with the given client ID and authority URL.
     *
     * @param clientId  the Azure AD application client ID
     * @param authority the authority URL
     */
    public AuthModule(String clientId, String authority) {
        this(buildClientApplication(clientId, authority), SCOPES);
    }

    /**
     * Package-private constructor for testing with a pre-built client application.
     */
    AuthModule(PublicClientApplication clientApplication, Set<String> scopes) {
        this.clientApplication = clientApplication;
        this.scopes = scopes;
    }

    /**
     * Acquires a token using device code flow. Displays the device code
     * and verification URL to the user via System.out.
     *
     * @return an AuthResult containing the access token and expiration info
     */
    public AuthResult authenticate() {
        try {
            Consumer<DeviceCode> deviceCodeConsumer = deviceCode ->
                    System.out.println("To sign in, use a web browser to open the page "
                            + deviceCode.verificationUri()
                            + " and enter the code " + deviceCode.userCode()
                            + " to authenticate.");

            var parameters = DeviceCodeFlowParameters
                    .builder(scopes, deviceCodeConsumer)
                    .build();

            var result = clientApplication
                    .acquireToken(parameters)
                    .join();

            this.cachedAuthResult = result;

            return toAuthResult(result);
        } catch (Exception e) {
            var message = "Authentication failed: " + e.getMessage();
            System.err.println(message);
            throw new RuntimeException(message, e);
        }
    }

    /**
     * Returns a valid access token, refreshing silently if expired.
     * Falls back to device code flow if silent refresh fails.
     *
     * @return a valid access token string
     */
    public String getAccessToken() {
        if (cachedAuthResult == null) {
            authenticate();
        }

        if (isTokenExpired()) {
            try {
                cachedAuthResult = acquireTokenSilently();
            } catch (Exception _) {
                // Silent refresh failed — fall back to device code flow
                authenticate();
            }
        }

        return cachedAuthResult.accessToken();
    }

    private boolean isTokenExpired() {
        if (cachedAuthResult == null || cachedAuthResult.expiresOnDate() == null) {
            return true;
        }
        return Instant.now().isAfter(cachedAuthResult.expiresOnDate().toInstant());
    }

    private IAuthenticationResult acquireTokenSilently() throws Exception {
        var account = cachedAuthResult.account();
        if (account == null) {
            throw new MsalException("No account available for silent token acquisition", "no_account");
        }

        var silentParameters = SilentParameters
                .builder(scopes, account)
                .build();

        return clientApplication
                .acquireTokenSilently(silentParameters)
                .join();
    }

    private static PublicClientApplication buildClientApplication(String clientId, String authority) {
        try {
            return PublicClientApplication
                    .builder(clientId)
                    .authority(authority)
                    .build();
        } catch (MalformedURLException e) {
            throw new RuntimeException("Invalid authority URL: " + authority, e);
        }
    }

    private static AuthResult toAuthResult(IAuthenticationResult result) {
        var expiresAt = result.expiresOnDate() != null
                ? result.expiresOnDate().toInstant()
                : Instant.now().plusSeconds(3600);

        return new AuthResult(
                result.accessToken(),
                null, // MSAL4J manages refresh tokens internally
                expiresAt
        );
    }
}
