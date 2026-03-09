# Implementation Plan: AWS Automated Pipeline

## Overview

Migrate the OneNote PDF Extractor from a CLI tool to an AWS Lambda-based automated pipeline. Work proceeds bottom-up: shared utilities and interfaces first, then new components (auth, dedup, storage, upload, metrics, config), then the pipeline orchestrator, and finally the CDK infrastructure. Each component is wired incrementally so there is no orphaned code.

## Tasks

- [x] 1. Extract TokenProvider interface and add shared retry utility
  - [x] 1.1 Create `TokenProvider` interface in `com.extractor.auth` with a single `getAccessToken()` method
    - Make existing `AuthModule` implement `TokenProvider`
    - Update `GraphClientWrapper` to depend on `TokenProvider` instead of `AuthModule`
    - _Requirements: 2.1, 2.4_
  - [x] 1.2 Create `RetryExecutor` utility in `com.extractor.util` with configurable max retries and exponential backoff
    - Generic `<T> T execute(Callable<T>, int maxRetries, long initialBackoffMs)` method
    - Throw after exhausting retries, return immediately on success
    - _Requirements: 2.3, 4.5, 5.3_
  - [ ]* 1.3 Write property test for RetryExecutor
    - **Property 4: Retry behavior**
    - **Validates: Requirements 2.3, 4.5, 5.3**
  - [x] 1.4 Enhance `PageInfo` record to include `lastModifiedDateTime` field
    - Update `PageLister.toPageInfo` to parse `lastModifiedDateTime` from `PageResponse`
    - Update `PageResponse` record if needed to include the field
    - _Requirements: 3.2_

- [x] 2. Implement CredentialLoader and GraphCredentialsAuthModule
  - [x] 2.1 Create `CredentialLoader` in `com.extractor.config` that reads SSM Parameter Store SecureString parameters
    - Create `PipelineCredentials` record with all required fields
    - Fail immediately with descriptive error if any parameter is missing
    - _Requirements: 6.1, 6.3_
  - [ ]* 2.2 Write property test for missing credential error identification
    - **Property 7: Missing credential error identification**
    - **Validates: Requirements 6.3**
  - [x] 2.3 Create `GraphCredentialsAuthModule` in `com.extractor.auth` implementing `TokenProvider`
    - Use MSAL4J `ConfidentialClientApplication` with client credentials flow
    - Cache tokens in memory, refresh before expiration
    - Use `RetryExecutor` for token acquisition retries
    - _Requirements: 2.1, 2.2, 2.3, 2.4_
  - [ ]* 2.4 Write property test for token caching
    - **Property 5: Token caching**
    - **Validates: Requirements 2.4**

- [x] 3. Checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. Implement DeduplicationStore
  - [x] 4.1 Create `ExportRecord` record and `DeduplicationStore` class in `com.extractor.dedup`
    - `getExportRecord(pageId)` → `Optional<ExportRecord>`
    - `recordExport(ExportRecord)` → void
    - `shouldExport(pageId, lastModified)` → boolean
    - Use DynamoDB SDK v2 `DynamoDbClient`
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_
  - [ ]* 4.2 Write property test for deduplication decision
    - **Property 1: Deduplication decision correctness**
    - **Validates: Requirements 3.3, 3.4**
  - [ ]* 4.3 Write property test for export record round-trip
    - **Property 2: Export record round-trip**
    - **Validates: Requirements 3.2, 3.5**

- [x] 5. Implement S3PdfStore
  - [x] 5.1 Create `S3PdfStore` in `com.extractor.storage` with `uploadPdf` and `generateKey` methods
    - Deterministic key format: `{notebook}/{section}/{sanitized_title}.pdf`
    - Reuse `PdfWriter.sanitizeFilename()` logic (extract to shared utility if needed)
    - Use `RetryExecutor` for upload retries
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5_
  - [ ]* 5.2 Write property test for S3 key determinism
    - **Property 3: S3 key determinism**
    - **Validates: Requirements 4.2, 4.3**

- [x] 6. Implement GoogleAuthModule and NotebookLmUploader
  - [x] 6.1 Create `GoogleAuthModule` in `com.extractor.auth` that loads service account credentials from JSON string
    - Read service account JSON from `PipelineCredentials`
    - Return `GoogleCredentials` for API calls
    - _Requirements: 5.2_
  - [x] 6.2 Create `NotebookLmUploader` in `com.extractor.notebooklm` with `uploadPdf` method
    - Use `RetryExecutor` for upload retries
    - Accept PDF bytes and filename
    - _Requirements: 5.1, 5.3_

- [x] 7. Implement MetricsPublisher and PipelineReporter
  - [x] 7.1 Create `MetricsPublisher` in `com.extractor.metrics` that emits CloudWatch metrics
    - Publish: pages exported, skipped, failed, and run duration
    - _Requirements: 7.3_
  - [x] 7.2 Create `PipelineReporter` in `com.extractor.report` with `formatSummary` and `formatFailures` methods
    - Summary includes: total, exported, skipped, failed, uploaded to NotebookLM counts
    - Failure details include: pageId, pageTitle, errorMessage for each
    - Log to stdout (CloudWatch Logs captures Lambda stdout)
    - _Requirements: 7.1, 7.2_
  - [ ]* 7.3 Write property test for summary completeness
    - **Property 8: Run summary completeness**
    - **Validates: Requirements 7.1**
  - [ ]* 7.4 Write property test for failure detail reporting
    - **Property 9: Failure detail reporting**
    - **Validates: Requirements 7.2**
  - [ ]* 7.5 Write property test for metrics matching pipeline result
    - **Property 10: Metrics match pipeline result**
    - **Validates: Requirements 7.3**

- [x] 8. Checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 9. Implement PipelineHandler (Lambda orchestrator)
  - [x] 9.1 Create `PipelineHandler` in `com.extractor.pipeline` implementing `RequestHandler<ScheduledEvent, PipelineResult>`
    - Wire all components: CredentialLoader → Auth → GraphClientWrapper → PageLister → DeduplicationStore → PdfDownloader → S3PdfStore → NotebookLmUploader → MetricsPublisher → PipelineReporter
    - Use structured concurrency with virtual threads and configurable semaphore for concurrent page processing
    - Implement fault isolation: individual page failures do not abort the run
    - Log start timestamp and section target at startup
    - Ensure no sensitive values appear in logs
    - _Requirements: 1.4, 3.1, 6.2, 8.1, 8.2, 8.3_
  - [x] 9.2 Create `PipelineResult` record in `com.extractor.pipeline`
    - Fields: totalPages, exportedCount, skippedCount, failedCount, uploadedToNotebookLmCount, durationMs, failures
    - _Requirements: 7.1_
  - [ ]* 9.3 Write property test for fault isolation
    - **Property 12: Fault isolation**
    - **Validates: Requirements 8.3**
  - [ ]* 9.4 Write property test for dedup check coverage
    - **Property 13: Every page gets dedup check**
    - **Validates: Requirements 3.1**
  - [ ]* 9.5 Write property test for no sensitive values in logs
    - **Property 6: No sensitive values in logs**
    - **Validates: Requirements 6.2**
  - [ ]* 9.6 Write property test for NotebookLM upload status recorded
    - **Property 11: NotebookLM upload status recorded**
    - **Validates: Requirements 5.4**

- [x] 10. Update Maven dependencies and build configuration
  - Add AWS SDK v2 dependencies: `dynamodb`, `s3`, `ssm`, `cloudwatch`
  - Add AWS Lambda Java runtime dependency: `aws-lambda-java-core`, `aws-lambda-java-events`
  - Add Google API client dependencies for NotebookLM/Drive API
  - Configure Maven Shade plugin to produce Lambda-compatible fat JAR
  - _Requirements: 9.1_

- [x] 11. Checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 12. Create AWS CDK infrastructure
  - [x] 12.1 Create CDK app in `infra/` directory using Python
    - Define stack with: EventBridge scheduled rule, Lambda function (fat JAR), S3 bucket, DynamoDB table, SSM parameters, CloudWatch alarm on failures
    - Configure Lambda with reserved concurrency = 1 to prevent overlapping runs
    - Configure IAM roles with least-privilege: Lambda role gets DynamoDB read/write, S3 put, SSM get-parameter, CloudWatch put-metric-data
    - Support parameterized deployment (dev/prod) via CDK context
    - _Requirements: 1.1, 1.2, 1.3, 9.1, 9.2, 9.3, 9.4_

- [x] 13. Final checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties using jqwik
- Unit tests validate specific examples and edge cases
- The existing `AuthModule` (device code flow) is preserved for local CLI usage; `GraphCredentialsAuthModule` is the cloud counterpart
- `SectionResolver` will need minor updates to use `/users/{userId}/onenote/sections` for app-only permissions, handled as part of task 9.1 wiring
