# Security Policy

## Supported versions

Only the latest release and current `main` receive security fixes.

## Threat model and deployment guidance

Before deploying anything that touches money, PII, or production data:

- **[`docs/threat-model.md`](docs/threat-model.md)** — five deployment scenarios (safe-local / internal-tool / MCP-gateway / multi-agent swarm / anti-patterns) with concrete config recipes and the framework's-guarantees-vs-yours boundary at each.
- **[`docs/production-hardening.md`](docs/production-hardening.md)** — actionable pre-launch checklist; every item names which Agents.KT primitive enforces it (or "deployer responsibility — framework doesn't help here yet").
- **[`docs/regulated-deployment.md`](docs/regulated-deployment.md)** — capability inventory, action log, decision points; EU AI Act mapping for regulated JVM teams.

If your deployment is internet-facing, multi-tenant, or in scope for statutory audit obligations, read all three before going live.

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

Agents.KT ships eight first-party providers (`ModelProvider.entries`: Ollama, Anthropic, OpenAI, DeepSeek, Kimi, OpenRouter, Perplexity, Gemini) over five wire-shape adapters — see [`docs/providers.md`](docs/providers.md) for the matrix. All cloud providers (everything except local Ollama) require a real API key. The framework's contract:

- **Keys live outside the working tree.** The integration tests load from `<repo-root>/.secrets/<provider>-key` (gitignored at the project root) or the provider's standard env var (`ANTHROPIC_API_KEY` / `OPENAI_API_KEY` / `PERPLEXITY_API_KEY` / …). The `.secrets/` directory must never be committed; verify via `git check-ignore .secrets/your-key` before use.
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

## Enforcement boundaries (what the framework does and does not confine)

Since 0.7.0 the declared `ToolPolicy` is **enforced**, in two layers:

- **Layer 1 — in-JVM filesystem gate.** Absolute filesystem-path tool arguments are checked against the declared read/write globs *before* the executor runs; paths are normalized to block `..` traversal, and denials surface as auditable events.
- **Layer 2 — OS sandbox for subprocess-shaped tools.** `ProcessSandbox` dispatches to macOS Seatbelt, Linux bubblewrap, or firejail; writes are confined to policy-derived roots, the child environment is controlled, and network is denied by default.

What remains the **deployer's responsibility** (see [`docs/threat-model.md`](docs/threat-model.md)):

- **In-JVM side effects of arbitrary tool lambdas.** Layer 1 gates filesystem-path *arguments*; a Kotlin lambda body can still open sockets, read env vars, or touch the filesystem directly with full JVM permissions. The `agents-kt-detekt` rules (`ToolBodyForbiddenApis`) catch this statically; OS/container isolation is yours.
- **Read confinement and selective network egress.** Sandbox reads remain broad, and the hostname-allowlist proxy is deferred to 0.8 (#2893). If no sandbox backend is available, raw `ProcessSandbox.run` falls back to plain `ProcessBuilder` with a warning.
- **Prompt-injection content filtering** in user inputs and system prompts. `maxToolArgsBytes` and the `untrustedOutput` marker are mitigations, not filters.

### Authentication on `McpServer`

Inbound HTTP callers are authenticated **before** JSON-RPC dispatch. The default `McpServerAuth.TrustedLocal` accepts loopback clients only and rejects non-local requests with 401; configure `McpServerAuth.RequireBearerToken(token)` (or `RequireBearerTokens` for per-client principals) for anything network-reachable. The outgoing MCP client supports `Bearer` as before.
