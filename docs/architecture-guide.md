# Architecture Guide — OneNote PDF Extractor

## Overview

The OneNote PDF Extractor is a CLI tool that exports OneNote pages as PDF files via the Microsoft Graph API. It authenticates using OAuth2 device code flow, resolves a target notebook section, lists all pages, and exports each page to a local PDF file. The export pipeline uses JDK 25 virtual threads and structured concurrency to process pages in parallel, throttled by a configurable semaphore.

---

## Package Structure

```
com.extractor
├── OneNotePdfExtractor        # CLI entry point & pipeline orchestrator
├── auth/
│   └── AuthModule             # OAuth2 device code flow via MSAL4J
├── cli/
│   └── CliArgs                # Parsed & validated CLI arguments
├── client/
│   ├── GraphClientWrapper     # Authenticated HTTP client with retry logic
│   ├── NotebookResponse       # DTO for notebook JSON
│   ├── SectionResponse        # DTO for section JSON
│   ├── PageResponse           # DTO for page JSON
│   └── ODataPagedResponse     # Generic OData pagination wrapper
├── model/
│   ├── AuthResult             # record(accessToken, refreshToken, expiresAt)
│   ├── PageInfo               # record(pageId, title, createdDateTime)
│   ├── SectionInfo            # record(sectionId, sectionName, notebookName, pageCount)
│   ├── ExportResult           # record(totalPages, successCount, failureCount, failures)
│   ├── FailedPage             # record(pageId, pageTitle, errorMessage)
│   └── PageExportOutcome      # sealed interface { Success | Failure }
├── page/
│   └── PageLister             # Lists pages in a section via Graph API
├── pdf/
│   ├── PdfDownloader          # Downloads page content, converts HTML→PDF
│   └── PdfWriter              # Writes PDF to disk with collision handling (synchronized)
├── report/
│   └── ProgressReporter       # Thread-safe stdout + log file output
└── section/
    └── SectionResolver        # Resolves section by ID or notebook/section name
```

---

## Object Diagram

Shows the runtime object graph during a concurrent export with `--concurrency 4` and 6 pages.

```mermaid
classDiagram
    direction TB

    class extractor_OneNotePdfExtractor {
        concurrency = 4
    }

    class cliArgs_CliArgs {
        sectionId = "abc-123"
        concurrency = 4
        outputDir = "./onenote-export"
    }

    class authModule_AuthModule {
        scopes = Notes.Read, Notes.Read.All
    }

    class graphClient_GraphClientWrapper {
        MAX_RETRIES = 3
    }

    class sectionResolver_SectionResolver

    class pageLister_PageLister

    class downloader_PdfDownloader

    class writer_PdfWriter {
        usedFilenames : ConcurrentHashMap.KeySetView
    }

    class reporter_ProgressReporter {
        logWriter : PrintWriter auto-flush
    }

    class semaphore_Semaphore {
        permits = 4
    }

    class scope_StructuredTaskScope

    class outcomes_ConcurrentLinkedQueue

    extractor_OneNotePdfExtractor --> cliArgs_CliArgs
    extractor_OneNotePdfExtractor --> authModule_AuthModule
    extractor_OneNotePdfExtractor --> graphClient_GraphClientWrapper
    extractor_OneNotePdfExtractor --> sectionResolver_SectionResolver
    extractor_OneNotePdfExtractor --> pageLister_PageLister
    extractor_OneNotePdfExtractor --> downloader_PdfDownloader
    extractor_OneNotePdfExtractor --> writer_PdfWriter
    extractor_OneNotePdfExtractor --> reporter_ProgressReporter
    extractor_OneNotePdfExtractor --> semaphore_Semaphore
    extractor_OneNotePdfExtractor --> scope_StructuredTaskScope
    extractor_OneNotePdfExtractor --> outcomes_ConcurrentLinkedQueue

    sectionResolver_SectionResolver --> graphClient_GraphClientWrapper
    pageLister_PageLister --> graphClient_GraphClientWrapper
    downloader_PdfDownloader --> graphClient_GraphClientWrapper
    authModule_AuthModule ..> graphClient_GraphClientWrapper : provides token
```


---

## Sequence Diagram — Full Export Pipeline

Shows the end-to-end flow from CLI invocation through concurrent page export to final summary.

```mermaid
sequenceDiagram
    actor User
    participant CLI as OneNotePdfExtractor
    participant Args as CliArgs
    participant Auth as AuthModule
    participant Graph as GraphClientWrapper
    participant SecRes as SectionResolver
    participant PgList as PageLister
    participant Scope as StructuredTaskScope
    participant Sem as Semaphore(N)
    participant DL as PdfDownloader
    participant PW as PdfWriter<br/>(synchronized)
    participant PR as ProgressReporter<br/>(synchronized)

    User->>CLI: execute(--section-id, --concurrency 4, --output-dir)
    CLI->>Args: buildCliArgs()
    CLI->>Args: validate()
    Args-->>CLI: OK

    CLI->>Auth: authenticate()
    Auth-->>User: Display device code + URL
    User-->>Auth: Complete browser sign-in
    Auth-->>CLI: AuthResult(accessToken)

    CLI->>Graph: new GraphClientWrapper(authModule)
    CLI->>SecRes: resolveById(sectionId)
    SecRes->>Graph: getJson(/sections/{id})
    Graph-->>SecRes: SectionDetailResponse
    SecRes-->>CLI: SectionInfo

    CLI->>PgList: listPages(sectionId)
    PgList->>Graph: getPaginated(/sections/{id}/pages)
    Graph-->>PgList: List<PageResponse>
    PgList-->>CLI: List<PageInfo> [6 pages]

    CLI->>CLI: exportPagesConcurrently(pages, dl, pw, pr, 4)

    CLI->>Scope: open()

    par Virtual Thread per Page (throttled by Semaphore)
        loop For each page (6 pages, 4 concurrent)
            CLI->>Scope: fork(virtualThread)
            Scope->>Sem: acquire()
            Note over Sem: Blocks if 4 threads active

            Sem->>DL: downloadPageAsPdf(pageId)
            DL->>Graph: getBytes(/pages/{id}/content)
            Graph-->>DL: byte[] (HTML)
            DL-->>Sem: byte[] (PDF)

            Sem->>PW: writePdf(title, pageId, pdfBytes)
            Note over PW: synchronized: sanitize → collision check → write → register
            PW-->>Sem: filename

            Sem->>PR: reportPageSuccess(current, total, title, filename)
            Note over PR: synchronized: stdout + log file

            Sem-->>Scope: PageExportOutcome.Success(title, filename)
            Scope->>Sem: release()
        end
    end

    CLI->>Scope: join()
    Note over Scope: Waits for all virtual threads

    CLI->>CLI: Aggregate outcomes via pattern matching
    Note over CLI: switch(outcome) {<br/>  Success _ → successCount++<br/>  Failure f → failures.add(...)<br/>}

    CLI->>PR: reportSummary(total, successes, failures)
    PR-->>User: === Export Summary ===
    CLI->>PR: close()

    CLI-->>User: exit code (0 = all success, 1 = any failure)
```

---

## Sequence Diagram — Single Page Export Task (Virtual Thread)

Zooms into what happens inside each forked virtual thread, including error handling.

```mermaid
sequenceDiagram
    participant VT as Virtual Thread
    participant Sem as Semaphore
    participant PR as ProgressReporter
    participant DL as PdfDownloader
    participant PW as PdfWriter
    participant Graph as GraphClientWrapper

    VT->>Sem: acquire()
    activate VT
    Note over VT,Sem: Thread parks if no permits available

    VT->>PR: reportPageStart(current, total, title)

    VT->>DL: downloadPageAsPdf(pageId)
    DL->>Graph: getBytes(contentUrl, headers)
    Graph-->>DL: byte[] htmlContent

    alt Embedded PDF found
        DL->>Graph: getBytes(resourceUrl)
        Graph-->>DL: byte[] pdfBytes
    else No embedded PDF
        DL->>DL: convertHtmlToPdf(html)
    end
    DL-->>VT: byte[] pdfBytes

    VT->>PW: writePdf(title, pageId, pdfBytes)
    Note over PW: synchronized block
    PW->>PW: sanitizeFilename(title)
    PW->>PW: resolveCollision(filename)
    PW->>PW: Files.write(filePath, pdfBytes)
    PW->>PW: usedFilenames.add(filename)
    PW-->>VT: filename

    VT->>PR: reportPageSuccess(current, total, title, filename)
    VT-->>VT: return Success(title, filename)

    VT->>Sem: release()
    deactivate VT

    Note over VT: On any exception:
    Note over VT: catch → reportPageFailure()<br/>→ return Failure(pageId, title, errorMsg)<br/>→ finally { semaphore.release() }
```

---

## Concurrency Model

```
┌─────────────────────────────────────────────────────────┐
│                  OneNotePdfExtractor.call()              │
│                                                         │
│  ┌───────────────────────────────────────────────────┐  │
│  │         StructuredTaskScope (try-with-resources)   │  │
│  │                                                    │  │
│  │   Semaphore(concurrencyLevel)                      │  │
│  │   ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐             │  │
│  │   │ VT-1 │ │ VT-2 │ │ VT-3 │ │ VT-4 │  ← active  │  │
│  │   └──┬───┘ └──┬───┘ └──┬───┘ └──┬───┘             │  │
│  │      │        │        │        │                  │  │
│  │   ┌──────┐ ┌──────┐                               │  │
│  │   │ VT-5 │ │ VT-6 │  ← waiting for permit         │  │
│  │   └──────┘ └──────┘                               │  │
│  │                                                    │  │
│  │   scope.join() ← waits for all 6 to complete       │  │
│  └───────────────────────────────────────────────────┘  │
│                                                         │
│  Aggregate: ConcurrentLinkedQueue<PageExportOutcome>    │
│  Pattern match → ExportResult(total, success, failures) │
└─────────────────────────────────────────────────────────┘
```

### Thread Safety Summary

| Component | Strategy | Why |
|---|---|---|
| `PdfWriter.writePdf` | `synchronized` method | Filename collision check → register → write must be atomic |
| `PdfWriter.usedFilenames` | `ConcurrentHashMap.newKeySet()` | Safe concurrent reads during collision checks |
| `ProgressReporter.output` | `synchronized` method | Prevents interleaved lines on stdout and log |
| `ProgressReporter.reportSummary` | `synchronized` method | Multi-line summary block must be atomic |
| `PdfDownloader` | Stateless | No shared mutable state; `GraphClientWrapper` is read-only per request |
| Result collection | `ConcurrentLinkedQueue` | Lock-free collection from virtual threads |

---

## Error Handling Flow

```mermaid
flowchart TD
    A[Virtual Thread starts] --> B{Semaphore.acquire}
    B -->|InterruptedException| C[Return Failure<br/>interrupted waiting]
    B -->|Permit acquired| D[Download page]
    D -->|IOException| E[Catch exception]
    D -->|Success| F[Write PDF]
    F -->|IOException| E
    F -->|Success| G[Report success]
    G --> H[Return Success]
    E --> I[Report failure]
    I --> J[Return Failure]
    H --> K[finally: semaphore.release]
    J --> K

    K --> L{All threads done?}
    L -->|No| A
    L -->|Yes| M[scope.join returns]
    M --> N[Aggregate via pattern matching]
    N --> O{Any failures?}
    O -->|Yes| P[Exit code 1]
    O -->|No| Q[Exit code 0]

    style C fill:#f96
    style E fill:#f96
    style J fill:#f96
    style P fill:#f96
    style H fill:#6f9
    style Q fill:#6f9
```

---

## Key Design Decisions

1. **Virtual threads over platform thread pool** — Each page export is I/O-bound (HTTP download + disk write). Virtual threads have near-zero creation cost and the JVM efficiently parks them during I/O waits.

2. **Semaphore over fixed thread pool** — A `Semaphore(N)` naturally throttles concurrency without constraining the thread model. All pages get their own virtual thread immediately; the semaphore gates the expensive I/O work.

3. **`synchronized` over `ReentrantLock`** — The critical sections in `PdfWriter` and `ProgressReporter` are short. Method-level `synchronized` is simpler and sufficient; finer-grained locking would add complexity without meaningful throughput gain since the bottleneck is HTTP downloads.

4. **Sealed interface for outcomes** — `PageExportOutcome` with `Success | Failure` records enables exhaustive pattern matching via `switch` expressions, making result aggregation type-safe and compiler-verified.

5. **Structured concurrency** — `StructuredTaskScope` ensures all forked virtual threads are joined before the scope closes, preventing thread leaks and providing clean lifecycle management via try-with-resources.
