package com.extractor.config;

import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads all pipeline credentials from AWS SSM Parameter Store.
 * Fails immediately with a descriptive error if any required parameter is missing.
 */
public class CredentialLoader {

    private static final List<String> PARAMETER_SUFFIXES = List.of(
            "ms-client-id",
            "ms-client-secret",
            "ms-tenant-id",
            "google-service-account-json",
            "notebooklm-project-id",
            "section-id"
    );

    private final SsmClient ssm;
    private final String parameterPrefix;

    public CredentialLoader(SsmClient ssm, String parameterPrefix) {
        this.ssm = ssm;
        this.parameterPrefix = parameterPrefix;
    }

    /**
     * Loads all required credentials from SSM Parameter Store.
     *
     * @return a {@link PipelineCredentials} record with all credential values
     * @throws IllegalStateException if one or more required parameters are missing
     */
    public PipelineCredentials loadAll() {
        var missing = new ArrayList<String>();
        var values = new String[PARAMETER_SUFFIXES.size()];

        for (var i = 0; i < PARAMETER_SUFFIXES.size(); i++) {
            var path = parameterPrefix + "/" + PARAMETER_SUFFIXES.get(i);
            var value = getParameter(path);
            if (value == null) {
                missing.add(path);
            } else {
                values[i] = value;
            }
        }

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Missing required SSM parameters: " + String.join(", ", missing)
            );
        }

        return new PipelineCredentials(
                values[0], // ms-client-id
                values[1], // ms-client-secret
                values[2], // ms-tenant-id
                values[3], // google-service-account-json
                values[4], // notebooklm-project-id
                values[5]  // section-id
        );
    }

    private String getParameter(String name) {
        try {
            var request = GetParameterRequest.builder()
                    .name(name)
                    .withDecryption(true)
                    .build();
            var response = ssm.getParameter(request);
            return response.parameter().value();
        } catch (ParameterNotFoundException _) {
            return null;
        }
    }
}
