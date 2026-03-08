package com.extractor.config;

/**
 * Immutable container for all pipeline credentials loaded from SSM Parameter Store.
 * Each field corresponds to a SecureString (or String) parameter in the Credential_Store.
 */
public record PipelineCredentials(
        String msClientId,
        String msClientSecret,
        String msTenantId,
        String googleServiceAccountJson,
        String notebookLmProjectId,
        String sectionId
) {}
