# Implementation Plan: Concurrent Export Pipeline

## Overview

Transform the sequential page export loop into a concurrent pipeline using JDK 25 virtual threads, structured concurrency, and a semaphore-based throttle. Modifications touch `CliArgs`, `PdfWriter`, `ProgressReporter`, `OneNotePdfExtractor`, and introduce a new `PageExportOutcome` sealed interface.

## Tasks

- [ ] 1. Add `PageExportOutcome` sealed interface and update `CliArgs`
  - [x] 1.1 Create `PageExportOutcome` sealed interface with `Success` and `Failure` records in `com.extractor.model`
    - `Success(String pageTitle, String filename)` and `Failure(String pageId, String pageTitle, String errorMessage)`
    - _Requirements: 1.3, 5.1, 5.2_
  - [x] 1.2 Add `concurrency` field to `CliArgs` with default value 4, getter, and setter
    - Add validation in `validate()`: reject values < 1 or > 20 with `IllegalArgumentException`
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_
  - [ ]* 1.3 Write property tests for `CliArgs` concurrency validation
    - **Property 3: Invalid concurrency values are rejected**
    - **Validates: Requirements 2.3, 2.4**
    - **Property 4: Valid concurrency values are accepted**
    - **Validates: Requirements 2.5**

- [ ] 2. Make `PdfWriter` thread-safe
  - [x] 2.1 Replace `HashSet<String> usedFilenames` with `ConcurrentHashMap.newKeySet()` and add `synchronized` to `writePdf` method
    - The check-then-act sequence (collision check → register filename → write file) must be atomic
    - _Requirements: 3.1, 3.2, 3.3_
  - [ ]* 2.2 Write property tests for concurrent `PdfWriter`
    - **Property 5: Concurrent writes produce unique filenames**
    - **Validates: Requirements 3.1**
    - **Property 6: Written files are complete and registered**
    - **Validates: Requirements 3.3**

- [ ] 3. Make `ProgressReporter` thread-safe
  - [x] 3.1 Add `synchronized` to the `output` method and `reportSummary` method
    - Ensures no interleaved lines on stdout or log file under concurrent access
    - _Requirements: 4.1, 4.2, 4.3, 4.4_
  - [ ]* 3.2 Write property tests for `ProgressReporter` message formatting
    - **Property 8: Success messages contain required fields**
    - **Validates: Requirements 4.2**
    - **Property 9: Failure messages contain required fields**
    - **Validates: Requirements 4.3**
    - **Property 10: Summary contains correct counts**
    - **Validates: Requirements 4.4**
  - [ ]* 3.3 Write property test for concurrent `ProgressReporter` output integrity
    - **Property 7: Concurrent progress messages are not interleaved**
    - **Validates: Requirements 4.1**

- [x] 4. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. Implement concurrent export pipeline in `OneNotePdfExtractor`
  - [x] 5.1 Add `--concurrency` CLI option via picocli `@Option` annotation on `OneNotePdfExtractor`
    - Wire the value into `CliArgs` in `buildCliArgs()`
    - _Requirements: 2.1, 2.2_
  - [x] 5.2 Extract the export loop into a new `exportPagesConcurrently` method
    - Use `StructuredTaskScope` with try-with-resources
    - Fork one virtual thread per page, each acquiring a `Semaphore` permit before downloading
    - Each task returns a `PageExportOutcome` (Success or Failure), catching all exceptions internally
    - Collect results into a `ConcurrentLinkedQueue<PageExportOutcome>`
    - After `scope.join()`, aggregate into `ExportResult` using pattern matching on the sealed interface
    - Release semaphore permits in a `finally` block within each task
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 5.1, 5.2, 5.3, 5.4_
  - [x] 5.3 Update `call()` to invoke `exportPagesConcurrently` instead of the sequential loop
    - Pass concurrency level from `CliArgs`
    - Handle `InterruptedException` from `scope.join()` as unrecoverable error
    - Preserve existing behavior for zero pages (early return)
    - _Requirements: 6.1, 6.2, 6.3_

- [x] 6. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 7. Write pipeline-level property and integration tests
  - [ ]* 7.1 Write property test for result completeness
    - **Property 1: All pages produce a result**
    - **Validates: Requirements 1.1, 1.3, 5.1, 5.2**
    - Use a mock `PdfDownloader` that randomly succeeds or fails
    - Verify successes + failures == total pages for any input list and concurrency level
  - [ ]* 7.2 Write property test for concurrency bound
    - **Property 2: Concurrency level is respected**
    - **Validates: Requirements 1.2**
    - Use `AtomicInteger` to track peak concurrent tasks in a mock downloader with controlled delays
    - Verify peak never exceeds the configured concurrency level
  - [ ]* 7.3 Write property test for failure exit code
    - **Property 11: Failures produce non-zero exit code**
    - **Validates: Requirements 5.4**
  - [ ]* 7.4 Write property test for sequential equivalence
    - **Property 12: Concurrent output matches sequential output (model-based)**
    - **Validates: Requirements 6.1**
    - Use a deterministic mock downloader; compare output file sets between sequential and concurrent runs

- [x] 8. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties using jqwik
- Unit tests validate specific examples and edge cases using JUnit 5
- The project already has jqwik, JUnit 5, Mockito, and AssertJ as test dependencies
