# Requirements Document

## Introduction

Migrate the existing OneNote PDF Extractor CLI tool to run as an automated, periodic pipeline on AWS. The pipeline extracts OneNote pages as PDFs via the Microsoft Graph API, deduplicates already-exported pages, stores PDFs in S3, and uploads them to Google NotebookLM. Authentication is fully automated with no interactive device code flow.

## Glossary

- **Pipeline**: The AWS-hosted automated system that orchestrates the full export-and-upload workflow on a schedule
- **Extractor**: The Java 25 application component that authenticates with Microsoft Graph, lists OneNote pages, and downloads page content as PDF
- **Deduplication_Store**: A DynamoDB table that tracks which OneNote pages have been exported, keyed by page ID and last-modified timestamp
- **PDF_Store**: An S3 bucket where exported PDF files are persisted
- **NotebookLM_Uploader**: The component that uploads PDF files to a Google NotebookLM project via the Google API
- **Credential_Store**: AWS SSM Parameter Store entries holding OAuth tokens and API keys as SecureString parameters
- **Scheduler**: An Amazon EventBridge rule that triggers the Pipeline on a configurable cron schedule
- **Graph_Auth_Module**: The component that acquires Microsoft Graph API access tokens using client credentials flow (app-only, no user interaction)
- **Google_Auth_Module**: The component that acquires Google API access tokens using stored service account credentials or refresh tokens

## Requirements

### Requirement 1: Scheduled Pipeline Execution

**User Story:** As a user, I want the export pipeline to run automatically on a recurring schedule, so that new OneNote pages are exported without manual intervention.

#### Acceptance Criteria

1. WHEN the Scheduler fires at the configured cron interval, THE Pipeline SHALL start a new export run
2. THE Scheduler SHALL support configurable cron expressions via an environment variable or infrastructure parameter
3. IF the Pipeline is already running when the Scheduler fires, THEN THE Scheduler SHALL skip the invocation and log a warning
4. WHEN the Pipeline starts, THE Pipeline SHALL log the start timestamp and configured section target

### Requirement 2: Automated Microsoft Graph Authentication

**User Story:** As a user, I want the pipeline to authenticate with Microsoft Graph without any interactive prompts, so that it can run unattended in the cloud.

#### Acceptance Criteria

1. THE Graph_Auth_Module SHALL acquire access tokens using the OAuth2 client credentials flow (app-only permissions)
2. WHEN the Graph_Auth_Module acquires a token, THE Graph_Auth_Module SHALL read the client ID, client secret, and tenant ID from the Credential_Store
3. IF token acquisition fails, THEN THE Graph_Auth_Module SHALL retry up to 3 times with exponential backoff before reporting failure
4. THE Graph_Auth_Module SHALL cache tokens in memory and refresh them before expiration

### Requirement 3: Page Deduplication

**User Story:** As a user, I want the pipeline to skip pages that have already been exported and have not changed, so that redundant work and storage costs are avoided.

#### Acceptance Criteria

1. WHEN the Pipeline lists pages from a OneNote section, THE Pipeline SHALL query the Deduplication_Store for each page's export status
2. THE Deduplication_Store SHALL track each page by its page ID and last-modified timestamp
3. WHEN a page's last-modified timestamp matches the stored timestamp, THE Pipeline SHALL skip that page
4. WHEN a page is newly created or has a modified timestamp newer than the stored value, THE Pipeline SHALL mark that page for export
5. WHEN a page is successfully exported, THE Pipeline SHALL update the Deduplication_Store with the page ID, last-modified timestamp, S3 key, and export timestamp

### Requirement 4: PDF Export to S3

**User Story:** As a user, I want exported PDFs to be stored durably in S3, so that they are available for downstream processing and archival.

#### Acceptance Criteria

1. WHEN a page is marked for export, THE Extractor SHALL download the page content and convert it to PDF
2. WHEN a PDF is generated, THE Extractor SHALL upload the PDF to the PDF_Store with a key derived from the notebook name, section name, and sanitized page title
3. THE Extractor SHALL use a deterministic S3 key format: `{notebook}/{section}/{sanitized_title}.pdf`
4. IF a PDF with the same S3 key already exists, THEN THE Extractor SHALL overwrite it with the updated content
5. IF the PDF upload to S3 fails, THEN THE Extractor SHALL retry up to 3 times with exponential backoff before recording the page as failed

### Requirement 5: Google NotebookLM Upload

**User Story:** As a user, I want exported PDFs to be automatically uploaded to my NotebookLM project, so that I can search and interact with my OneNote content in NotebookLM.

#### Acceptance Criteria

1. WHEN a PDF is successfully stored in the PDF_Store, THE NotebookLM_Uploader SHALL upload the PDF to the configured NotebookLM project
2. THE Google_Auth_Module SHALL authenticate using service account credentials or a stored refresh token read from the Credential_Store
3. IF the NotebookLM upload fails, THEN THE NotebookLM_Uploader SHALL retry up to 3 times with exponential backoff before recording the page as failed
4. WHEN the NotebookLM upload succeeds, THE Pipeline SHALL record the upload status in the Deduplication_Store

### Requirement 6: Secure Credential Management

**User Story:** As a user, I want all secrets and credentials stored securely in AWS, so that no sensitive values are hardcoded or exposed in logs.

#### Acceptance Criteria

1. THE Pipeline SHALL read all OAuth credentials, API keys, and tokens from the Credential_Store using SecureString parameters
2. THE Pipeline SHALL log no sensitive values (tokens, client secrets, API keys) to stdout, stderr, or CloudWatch Logs
3. WHEN a required credential is missing from the Credential_Store, THE Pipeline SHALL fail immediately with a descriptive error message identifying the missing parameter

### Requirement 7: Export Run Reporting

**User Story:** As a user, I want a summary of each export run, so that I can monitor pipeline health and identify failures.

#### Acceptance Criteria

1. WHEN an export run completes, THE Pipeline SHALL log a summary containing: total pages found, pages skipped (deduplicated), pages exported, pages failed, and pages uploaded to NotebookLM
2. IF any pages fail during export or upload, THEN THE Pipeline SHALL log the page ID, page title, and error message for each failure
3. THE Pipeline SHALL emit CloudWatch metrics for: pages exported, pages skipped, pages failed, and run duration

### Requirement 8: Concurrent Page Processing

**User Story:** As a user, I want pages to be processed concurrently, so that the export pipeline completes within a reasonable time.

#### Acceptance Criteria

1. THE Pipeline SHALL process pages concurrently using virtual threads with a configurable concurrency limit
2. THE Pipeline SHALL use structured concurrency to ensure all page-processing threads complete before the run finishes
3. WHEN a single page export fails, THE Pipeline SHALL continue processing remaining pages without aborting the run

### Requirement 9: Infrastructure as Code

**User Story:** As a developer, I want the AWS infrastructure defined as code, so that the pipeline can be deployed and updated reproducibly.

#### Acceptance Criteria

1. THE Infrastructure SHALL be defined using AWS CDK
2. THE Infrastructure SHALL provision the Scheduler, compute environment, PDF_Store, Deduplication_Store, and Credential_Store parameters
3. THE Infrastructure SHALL configure IAM roles with least-privilege permissions for each component
4. THE Infrastructure SHALL support parameterized deployment for different environments (dev, prod)
