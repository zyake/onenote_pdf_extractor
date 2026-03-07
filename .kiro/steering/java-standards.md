---
inclusion: always
---

# Java Project Standards

## Security

- Never hardcode secrets, API keys, client IDs, tokens, or passwords in source code
- Always read sensitive values from environment variables, system properties, or a secrets manager (e.g., AWS SSM Parameter Store)
- Never commit secrets to version control — use `.gitignore` and `.env` files
- Never log sensitive values (tokens, credentials) to stdout, stderr, or log files
- Use SecureString for storing secrets in parameter stores

## Java Version

- Target JVM 25 as the minimum runtime version
- Use modern Java features available in JDK 25: records, sealed classes, pattern matching, virtual threads, string templates, unnamed variables, structured concurrency where appropriate
- Set Maven compiler source and target to 25
- Prefer `record` types over traditional POJOs with getters/setters for immutable data
- Use `var` for local variable type inference where the type is obvious
- Prefer `switch` expressions over `switch` statements
- Use `Stream` API and functional patterns where they improve readability
