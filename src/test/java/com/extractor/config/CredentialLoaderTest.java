package com.extractor.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for CredentialLoader.
 * Validates: Requirements 6.1, 6.3
 */
@ExtendWith(MockitoExtension.class)
class CredentialLoaderTest {

    private static final String PREFIX = "/pipeline/dev";

    @Mock
    private SsmClient ssm;

    private CredentialLoader loader;

    @BeforeEach
    void setUp() {
        loader = new CredentialLoader(ssm, PREFIX);
    }

    @Test
    void loadsAllCredentialsSuccessfully() {
        stubParameter(PREFIX + "/ms-client-id", "client-id-123");
        stubParameter(PREFIX + "/ms-client-secret", "secret-456");
        stubParameter(PREFIX + "/ms-tenant-id", "tenant-789");
        stubParameter(PREFIX + "/google-service-account-json", "{\"type\":\"service_account\"}");
        stubParameter(PREFIX + "/notebooklm-project-id", "project-abc");
        stubParameter(PREFIX + "/section-id", "section-xyz");

        var creds = loader.loadAll();

        assertThat(creds.msClientId()).isEqualTo("client-id-123");
        assertThat(creds.msClientSecret()).isEqualTo("secret-456");
        assertThat(creds.msTenantId()).isEqualTo("tenant-789");
        assertThat(creds.googleServiceAccountJson()).isEqualTo("{\"type\":\"service_account\"}");
        assertThat(creds.notebookLmProjectId()).isEqualTo("project-abc");
        assertThat(creds.sectionId()).isEqualTo("section-xyz");
    }

    @Test
    void failsWithDescriptiveErrorWhenSingleParameterMissing() {
        stubParameter(PREFIX + "/ms-client-id", "client-id-123");
        stubMissing(PREFIX + "/ms-client-secret");
        stubParameter(PREFIX + "/ms-tenant-id", "tenant-789");
        stubParameter(PREFIX + "/google-service-account-json", "{\"type\":\"service_account\"}");
        stubParameter(PREFIX + "/notebooklm-project-id", "project-abc");
        stubParameter(PREFIX + "/section-id", "section-xyz");

        assertThatThrownBy(() -> loader.loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PREFIX + "/ms-client-secret");
    }

    @Test
    void failsListingAllMissingParametersWhenMultipleAreMissing() {
        stubMissing(PREFIX + "/ms-client-id");
        stubMissing(PREFIX + "/ms-client-secret");
        stubParameter(PREFIX + "/ms-tenant-id", "tenant-789");
        stubMissing(PREFIX + "/google-service-account-json");
        stubParameter(PREFIX + "/notebooklm-project-id", "project-abc");
        stubParameter(PREFIX + "/section-id", "section-xyz");

        assertThatThrownBy(() -> loader.loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PREFIX + "/ms-client-id")
                .hasMessageContaining(PREFIX + "/ms-client-secret")
                .hasMessageContaining(PREFIX + "/google-service-account-json");
    }

    @Test
    void failsWhenAllParametersAreMissing() {
        when(ssm.getParameter(any(GetParameterRequest.class)))
                .thenThrow(ParameterNotFoundException.builder().message("not found").build());

        assertThatThrownBy(() -> loader.loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PREFIX + "/ms-client-id")
                .hasMessageContaining(PREFIX + "/ms-client-secret")
                .hasMessageContaining(PREFIX + "/ms-tenant-id")
                .hasMessageContaining(PREFIX + "/google-service-account-json")
                .hasMessageContaining(PREFIX + "/notebooklm-project-id")
                .hasMessageContaining(PREFIX + "/section-id");
    }

    @Test
    void requestsParametersWithDecryption() {
        // Capture the request to verify withDecryption is set
        when(ssm.getParameter(any(GetParameterRequest.class)))
                .thenAnswer(invocation -> {
                    var request = invocation.getArgument(0, GetParameterRequest.class);
                    assertThat(request.withDecryption()).isTrue();
                    return GetParameterResponse.builder()
                            .parameter(Parameter.builder().value("val").build())
                            .build();
                });

        loader.loadAll();
    }

    private void stubParameter(String name, String value) {
        when(ssm.getParameter(GetParameterRequest.builder()
                .name(name)
                .withDecryption(true)
                .build()))
                .thenReturn(GetParameterResponse.builder()
                        .parameter(Parameter.builder().value(value).build())
                        .build());
    }

    private void stubMissing(String name) {
        when(ssm.getParameter(GetParameterRequest.builder()
                .name(name)
                .withDecryption(true)
                .build()))
                .thenThrow(ParameterNotFoundException.builder().message("not found").build());
    }
}
