# Agents.KT v0.7.1 — Verify-gate hardening

**Release date:** 2026-05-31

A hardening release on top of 0.7.0, driven by external review. The headline fix makes the manifest **`verify`** gate honest; the rest corrects docs/KDoc that lagged the code. Drop-in for 0.7.0 — no API changes.

```kotlin
implementation("ai.deep-code:agents-kt:0.7.1")
implementation("ai.deep-code:agents-kt-ksp:0.7.1")
```

## Fixed — manifest `verify` compares policy sets, not coarse scores (#1923)

0.7.0's `ManifestVerifier` compared coarse per-tool **scores** (network `allowAll`=2/`hosts`=1/none=0; filesystem any-globs=1/none=0), so genuine widenings with an unchanged score slipped through the CI gate:

- `network.hosts: ["api.internal"] → ["api.internal", "evil.example"]` — **now caught** (set difference)
- `filesystem.write: a narrow upload glob → a root-level glob` — **now caught** (set difference)

It also keyed tools by **name** with `putIfAbsent`, so two agents with a same-named tool collided and one agent's widening was hidden.

Now `ManifestVerifier` compares the actual policy **sets**, keyed by `agentName.toolName`:

- **network** widened = mode escalation (`denyAll`/unspecified → `hosts` → `allowAll`) or a host the baseline didn't list
- **filesystem** write/read widened = a glob the baseline's set didn't contain
- **environment** widened (new `tool.environment.widened`) = a variable the baseline's allow-list lacked

Pure narrowing (removing entries) is not flagged. The comparison is conservative on *added* entries — semantic glob-**coverage** subset-checking (a broad glob subsuming a narrow one) is a documented later refinement. Regression tests pin every previously-missed case. Both the CLI `verify` and the Gradle `verifyAgentManifest` task inherit the fix.

## Fixed — docs/KDoc drift

- **Provider count:** docs said four providers; six ship (`OLLAMA`, `ANTHROPIC`, `OPENAI`, `DEEPSEEK`, `KIMI`, `OPENROUTER`). Kimi (#2697) and OpenRouter (#2701) are first-party providers extending the OpenAI adapter.
- **Layer-2 KDoc:** `SandboxedTools.kt` no longer says "macOS only; Linux is #2892" — bwrap + firejail shipped in 0.7.0; `processTool` documented as the fail-closed public path vs. the low-level `ProcessSandbox.run` warn-and-continue primitive.
- **PUBLISHING.md** bundle example bumped off stale `0.5.0` paths.

## Compatibility

Drop-in for 0.7.0. The only behavior change is that `verify` now reports widenings it previously missed — if you have a checked-in baseline, re-run `verify` and review any newly-surfaced findings (they were always real; the old gate just didn't see them).
