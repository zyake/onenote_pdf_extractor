# OneNote PDF Extractor

A Java CLI tool that exports OneNote Online pages as PDF files via the Microsoft Graph API.

If a page contains an embedded PDF attachment (e.g., an academic paper), the tool downloads it directly. Otherwise, it converts the page's HTML content to PDF.

## Prerequisites

- Java 25+ (uses preview features: StructuredTaskScope, unnamed variables)
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
java --enable-preview -jar target/onenote-pdf-extractor-1.0-SNAPSHOT.jar \
  --notebook "My Notebook" \
  --section "My Section"

# By section ID directly
java --enable-preview -jar target/onenote-pdf-extractor-1.0-SNAPSHOT.jar \
  --section-id "your-section-id"

# Custom output directory and concurrency level
java --enable-preview -jar target/onenote-pdf-extractor-1.0-SNAPSHOT.jar \
  --notebook "My Notebook" \
  --section "My Section" \
  --output-dir ./my-pdfs \
  --concurrency 8

# Or use the launcher script (passes --enable-preview automatically)
./onenote-export.sh --notebook "My Notebook" --section "My Section"
```

On first run, the tool displays a device code and URL. Open the URL in a browser, enter the code, and sign in with your Microsoft account.

## CLI Options

| Option | Description |
|---|---|
| `--notebook` | Notebook name (used with `--section`) |
| `--section` | Section name within the notebook |
| `--section-id` | Direct section ID (overrides `--notebook`/`--section`) |
| `--output-dir` | Output directory (default: `./onenote-export`) |
| `--concurrency` | Max concurrent page exports, 1–20 (default: `4`) |
| `--help` | Show help |

## Output

- PDFs are saved to a flat directory (default: `./onenote-export/`)
- Filenames are sanitized from page titles
- Duplicate titles get numeric suffixes (`_1`, `_2`, etc.)
- A log file (`export.log`) is created in the output directory

## AWS Automated Pipeline

The project includes an automated pipeline that runs on AWS Lambda on a schedule, exporting OneNote pages to S3 and uploading them to Google NotebookLM. See [docs/aws-architecture.md](docs/aws-architecture.md) for architecture diagrams.

### Prerequisites

- AWS CLI configured with appropriate credentials
- Node.js 18+ and npm (for CDK)
- AWS CDK CLI (`npm install -g aws-cdk`)
- An Azure AD app registration with **application** permissions (not delegated)
- A Google Cloud service account with Drive API access

### Step 1: Azure AD App Registration (Microsoft Graph)

1. Go to [Azure Portal](https://portal.azure.com) → App registrations → New registration
2. Name: `OneNote Pipeline`
3. Account type: "Accounts in this organizational directory only" (single tenant)
4. After registration, note the **Application (client) ID** and **Directory (tenant) ID** from the Overview page
5. Go to Certificates & secrets → New client secret → copy the **Value** (not the Secret ID)
6. Go to API permissions → Add permission → Microsoft Graph → **Application permissions** → add `Notes.Read.All`
7. Click "Grant admin consent" (requires Azure AD admin)

### Step 2: Google Cloud Service Account (NotebookLM)

1. Go to [Google Cloud Console](https://console.cloud.google.com) → IAM & Admin → Service Accounts
2. Create a service account (or use an existing one)
3. Go to Keys → Add Key → Create new key → JSON
4. Download the JSON key file — you'll paste its contents into SSM
5. Enable the Google Drive API in your project
6. Share your NotebookLM project/folder with the service account email (e.g. `name@project.iam.gserviceaccount.com`)
7. Note your NotebookLM project ID from the notebook URL

### Step 3: Find Your OneNote Section ID

Use the Graph API Explorer or a curl call to list your sections:

```bash
curl -H "Authorization: Bearer {token}" \
  "https://graph.microsoft.com/v1.0/me/onenote/sections?\$select=id,displayName"
```

Or reuse the `--section-id` value from your CLI usage.

### Step 4: Build the Fat JAR

```bash
mvn clean package -DskipTests
```

### Step 5: Deploy the CDK Stack

```bash
# Install CDK dependencies
npm install --prefix infra

# Deploy to dev
npx cdk deploy --context env=dev

# Or deploy to prod
npx cdk deploy --context env=prod
```

### Step 6: Configure SSM Parameters

After the first deploy creates placeholder parameters, replace them with real values:

```bash
ENV=dev  # or prod

aws ssm put-parameter \
  --name "/onenote-pipeline/$ENV/ms-client-id" \
  --value "your-azure-app-client-id" \
  --type SecureString --overwrite

aws ssm put-parameter \
  --name "/onenote-pipeline/$ENV/ms-client-secret" \
  --value "your-azure-app-client-secret" \
  --type SecureString --overwrite

aws ssm put-parameter \
  --name "/onenote-pipeline/$ENV/ms-tenant-id" \
  --value "your-azure-tenant-id" \
  --type SecureString --overwrite

aws ssm put-parameter \
  --name "/onenote-pipeline/$ENV/google-service-account-json" \
  --value '{"type":"service_account","project_id":"...","private_key":"..."}' \
  --type SecureString --overwrite

aws ssm put-parameter \
  --name "/onenote-pipeline/$ENV/notebooklm-project-id" \
  --value "your-notebooklm-project-id" \
  --type SecureString --overwrite

aws ssm put-parameter \
  --name "/onenote-pipeline/$ENV/section-id" \
  --value "your-onenote-section-id" \
  --type String --overwrite
```

### SSM Parameter Reference

| Parameter | Type | Source |
|---|---|---|
| `ms-client-id` | SecureString | Azure AD → App registration → Overview |
| `ms-client-secret` | SecureString | Azure AD → App registration → Certificates & secrets |
| `ms-tenant-id` | SecureString | Azure AD → App registration → Overview |
| `google-service-account-json` | SecureString | Google Cloud → Service account → JSON key |
| `notebooklm-project-id` | SecureString | NotebookLM notebook URL |
| `section-id` | String | Graph API or existing CLI `--section-id` value |

### Verifying the Deployment

```bash
# Invoke the Lambda manually
aws lambda invoke --function-name onenote-pipeline-dev /dev/stdout

# Tail the logs
aws logs tail /aws/lambda/onenote-pipeline-dev --follow

# Check what would change before redeploying
npx cdk diff --context env=dev
```

### Environment Differences

| Setting | Dev | Prod |
|---|---|---|
| Schedule | Daily | Every 6 hours |
| Lambda memory | 1024 MB | 2048 MB |
| Lambda timeout | 10 min | 15 min |
| Resource removal | DESTROY | RETAIN |

## Architecture

See [docs/architecture-guide.md](docs/architecture-guide.md) for the component architecture and [docs/aws-architecture.md](docs/aws-architecture.md) for the AWS deployment diagrams.

## License

MIT
