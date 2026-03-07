# OneNote PDF Extractor

A Java CLI tool that exports OneNote Online pages as PDF files via the Microsoft Graph API.

If a page contains an embedded PDF attachment (e.g., an academic paper), the tool downloads it directly. Otherwise, it converts the page's HTML content to PDF.

## Prerequisites

- Java 17+
- Maven 3.6+
- An Azure AD app registration with `Notes.Read` and `Notes.Read.All` permissions

## Azure AD Setup

1. Go to [Azure Portal](https://portal.azure.com) → App registrations → New registration
2. Name: `OneNote PDF Extractor`
3. Account type: "Accounts in any organizational directory and personal Microsoft accounts"
4. Redirect URI: leave blank
5. After registration, copy the **Application (client) ID**
6. Go to Authentication → Advanced settings → set "Allow public client flows" to **Yes**
7. Go to API permissions → Add permission → Microsoft Graph → Delegated → add `Notes.Read` and `Notes.Read.All`

## Build

```bash
mvn clean package -DskipTests
```

## Usage

```bash
export ONENOTE_CLIENT_ID="your-client-id"

# By notebook and section name
java -jar target/onenote-pdf-extractor-1.0-SNAPSHOT.jar \
  --notebook "My Notebook" \
  --section "My Section"

# By section ID directly
java -jar target/onenote-pdf-extractor-1.0-SNAPSHOT.jar \
  --section-id "your-section-id"

# Custom output directory
java -jar target/onenote-pdf-extractor-1.0-SNAPSHOT.jar \
  --notebook "My Notebook" \
  --section "My Section" \
  --output-dir ./my-pdfs
```

On first run, the tool displays a device code and URL. Open the URL in a browser, enter the code, and sign in with your Microsoft account.

## CLI Options

| Option | Description |
|---|---|
| `--notebook` | Notebook name (used with `--section`) |
| `--section` | Section name within the notebook |
| `--section-id` | Direct section ID (overrides `--notebook`/`--section`) |
| `--output-dir` | Output directory (default: `./onenote-export`) |
| `--help` | Show help |

## Output

- PDFs are saved to a flat directory (default: `./onenote-export/`)
- Filenames are sanitized from page titles
- Duplicate titles get numeric suffixes (`_1`, `_2`, etc.)
- A log file (`export.log`) is created in the output directory

## Architecture

See [docs/architecture.md](docs/architecture.md) for the AWS deployment architecture with automated sync.

## License

MIT
