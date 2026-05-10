# Security Policy

## Supported versions

Only the latest release and current `main` receive security fixes.

## Reporting a vulnerability

Please email **security@deep-code.ai** with:

- affected version or commit
- reproduction steps
- expected vs actual behavior
- impact assessment

We aim to acknowledge reports within 72 hours.

Please do not file public GitHub issues for security-sensitive reports.

---

## Handling LLM provider credentials

Agents.KT ships three model adapters (Ollama, Anthropic, OpenAI). Anthropic and OpenAI require a real API key. The framework's contract:

- **Keys live outside the working tree.** The integration tests load from `<repo-root>/.secrets/<provider>-key` (gitignored at the project root) or the provider's standard env var (`ANTHROPIC_API_KEY` / `OPENAI_API_KEY`). The `.secrets/` directory must never be committed; verify via `git check-ignore .secrets/your-key` before use.
- **File permissions.** Set `chmod 0600 .secrets/*-key` and `chmod 0700 .secrets/` so other local users on shared machines cannot read them.
- **`ModelConfig.toString()` masks `apiKey`.** The override prints `apiKey=<6-char-prefix>…<N>chars` instead of the raw value, so accidental `log.info("config = $modelConfig")` calls do not leak credentials. `equals` / `hashCode` still consider the key; the masking is observation-only. See `ModelConfigTest`.
- **Headers go straight to the wire.** `Authorization: Bearer …` (OpenAI) and `x-api-key: …` (Anthropic) are passed only to the adapter's HTTP client; they are never logged. Java's `HttpRequest.toString()` does not include headers (verified).
- **Adapter error surface.** Provider error envelopes round-trip the provider's own message text via `LlmProviderException` (e.g. `"Claude returned an error: invalid_request_error: …"`). The provider does not echo your key in those bodies, but if you see a message that does, treat it as a key disclosure event and rotate.
- **CI does not run live-LLM tests.** The default `./gradlew test` excludes `live-llm` tagged tests; integration runs are local-developer-only. Do not add provider keys to CI secrets without first staging an explicit `integrationTest` job.

### If a key is ever committed

`.gitignore` is the first line of defence, but if a key ever reaches a commit:

1. **Rotate immediately** at the provider console (Anthropic / OpenAI) before doing anything else — the commit may already be on a fork, mirror, or transcript.
2. Drop the new key into `.secrets/<provider>-key` and re-run integration tests.
3. Optional: scrub the leaked key from history (`git filter-repo` / `git filter-branch`) and force-push, but treat this as belt-and-braces, not a substitute for rotation.

### Out of scope

- **Prompt-injection content filtering** in user inputs and system prompts.
- **Sandboxing of tool executors.** Tool code runs in-process with full JVM permissions; sandbox at the OS / container layer if tools execute untrusted plans.
- **Authentication on `McpServer`.** Outgoing MCP client supports `Bearer`; the server does not validate credentials. Trusted-network deployments only.
