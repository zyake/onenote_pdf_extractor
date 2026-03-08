# Requirements Document

## Introduction

The OneNote PDF Extractor currently processes page exports sequentially in a single loop. The primary bottleneck is I/O-bound HTTP downloads in `PdfDownloader`, where each page waits for the previous one to complete before starting. This feature introduces a concurrent export pipeline using JDK 25 virtual threads and structured concurrency to process multiple pages in parallel, improving throughput while maintaining thread safety across shared components (`PdfWriter`, `ProgressReporter`) and providing coherent progress reporting and error handling.

## Glossary

- **Export_Pipeline**: The orchestration logic in `OneNotePdfExtractor.call()` that coordinates page listing, downloading, writing, and reporting
- **PdfDownloader**: The component that fetches page content from the Microsoft Graph API via HTTP and converts it to PDF bytes
- **PdfWriter**: The component that writes PDF bytes to disk, with filename sanitization and collision detection using an internal `usedFilenames` set
- **ProgressReporter**: The component that writes progress messages to both stdout and a log file via `PrintWriter`
- **Concurrency_Level**: The maximum number of pages being exported simultaneously, configurable by the user
- **Virtual_Thread**: A lightweight JDK 25 thread managed by the JVM, ideal for I/O-bound workloads
- **Structured_Concurrency**: A JDK 25 API (`StructuredTaskScope`) that treats groups of concurrent tasks as a unit of work with well-defined lifecycle
- **Page_Export_Task**: A single unit of work that downloads a page's content and writes it to disk as a PDF
- **ExportResult**: A record summarizing the outcome of the full export operation (total, successes, failures)

## Requirements

### Requirement 1: Concurrent Page Export Execution

**User Story:** As a user, I want pages to be exported concurrently rather than sequentially, so that the total export time is reduced when exporting many pages.

#### Acceptance Criteria

1. WHEN the Export_Pipeline processes a list of pages, THE Export_Pipeline SHALL execute Page_Export_Tasks concurrently using Virtual_Threads
2. WHEN the Export_Pipeline starts concurrent export, THE Export_Pipeline SHALL limit the number of simultaneously active Page_Export_Tasks to the configured Concurrency_Level
3. WHEN all Page_Export_Tasks complete, THE Export_Pipeline SHALL aggregate results into a single ExportResult containing the total page count, success count, and list of failures
4. WHEN the Export_Pipeline uses concurrent execution, THE Export_Pipeline SHALL use Structured_Concurrency to manage the lifecycle of all Page_Export_Tasks as a single unit of work

### Requirement 2: Configurable Concurrency Level

**User Story:** As a user, I want to control how many pages are exported in parallel, so that I can tune throughput based on my network and system capacity.

#### Acceptance Criteria

1. THE CLI SHALL accept a `--concurrency` option that specifies the maximum number of concurrent Page_Export_Tasks
2. WHEN the `--concurrency` option is not provided, THE Export_Pipeline SHALL default to a Concurrency_Level of 4
3. WHEN the `--concurrency` option value is less than 1, THE CLI SHALL reject the value and display an error message
4. WHEN the `--concurrency` option value is greater than 20, THE CLI SHALL reject the value and display an error message
5. WHEN a valid `--concurrency` value is provided, THE Export_Pipeline SHALL use that value as the Concurrency_Level

### Requirement 3: Thread-Safe PDF Writing

**User Story:** As a user, I want PDF files to be written correctly even when multiple pages are exported concurrently, so that no files are corrupted or overwritten.

#### Acceptance Criteria

1. WHEN multiple Page_Export_Tasks attempt to write PDFs concurrently, THE PdfWriter SHALL produce unique filenames for each page without data races on the `usedFilenames` set
2. WHEN two pages produce the same sanitized filename, THE PdfWriter SHALL resolve the collision by appending a numeric suffix, even under concurrent access
3. WHEN multiple Page_Export_Tasks write to disk concurrently, THE PdfWriter SHALL ensure each file is written completely before the filename is registered as used

### Requirement 4: Thread-Safe Progress Reporting

**User Story:** As a user, I want progress output to remain coherent and complete when pages are exported concurrently, so that I can track the export status.

#### Acceptance Criteria

1. WHEN multiple Page_Export_Tasks report progress concurrently, THE ProgressReporter SHALL serialize access to stdout and the log file so that messages are not interleaved mid-line
2. WHEN a Page_Export_Task completes successfully, THE ProgressReporter SHALL output a success message containing the page title and output filename
3. WHEN a Page_Export_Task fails, THE ProgressReporter SHALL output a failure message containing the page title, page ID, and error description
4. WHEN all Page_Export_Tasks have completed, THE ProgressReporter SHALL output a summary with the total page count, success count, and failure count

### Requirement 5: Graceful Error Handling Under Concurrency

**User Story:** As a user, I want a single page failure to not abort the entire export batch, so that all other pages still get exported successfully.

#### Acceptance Criteria

1. WHEN a Page_Export_Task fails with an exception, THE Export_Pipeline SHALL record the failure and continue processing remaining pages
2. WHEN multiple Page_Export_Tasks fail, THE Export_Pipeline SHALL collect all failures and include each in the final ExportResult
3. IF an unrecoverable error occurs in the Export_Pipeline itself (not in a single page task), THEN THE Export_Pipeline SHALL cancel all in-flight Page_Export_Tasks and report the error
4. WHEN the Export_Pipeline completes with at least one failure, THE Export_Pipeline SHALL return a non-zero exit code

### Requirement 6: Backward-Compatible CLI Behavior

**User Story:** As an existing user, I want the tool to behave the same as before when I do not use the new concurrency option, so that my existing workflows are not disrupted.

#### Acceptance Criteria

1. WHEN the `--concurrency` option is not provided, THE Export_Pipeline SHALL produce the same output files as the sequential implementation for the same input
2. WHEN a section contains zero pages, THE Export_Pipeline SHALL print "No pages to export." and return exit code 0, regardless of the Concurrency_Level
3. WHEN a section contains exactly one page, THE Export_Pipeline SHALL export it successfully using the concurrent pipeline with no behavioral difference from the sequential implementation
