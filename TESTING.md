# Testing & CI gates

This documents **what runs by default, what doesn't, and why** — so "the build is green" means
something specific rather than implying coverage the fast path doesn't actually have (#3089,
de-slop epic #3083).

## What the default gate runs

`./gradlew build` (what CI runs) executes `:test` and the per-module `:test` tasks, plus `detekt`.
The default `:test` task runs **unit tests + `live-cloud-api` tests**, and **excludes** three tags:

| Excluded tag | Why it's out of the fast path |
|---|---|
| `live-llm` | Talks to Ollama / Ollama Cloud — empirically flaky (EOF, 500s, budget-exceeded, intermittent wrong outputs). Opt-in via `:integrationTest` / `testAll`. |
| `live-mcp` | Requires an out-of-process MCP server. |
| `interactive` | Requires a human at the console. |

**`live-cloud-api` is deliberately kept in the default gate** (direct DeepSeek / Anthropic / OpenAI).
Provider regressions are caught alongside unit tests; each test `assumeTrue(key != null)` so it
skips cleanly when the API key is absent. Trade-off: when a provider is down or a key is missing,
those tests fail and CI goes red. That's an accepted cost of catching provider drift early — see
the comment on `tasks.test` in `build.gradle.kts`.

## The security gate

```bash
./gradlew securityCheck
```

An explicit, named aggregate of the **deterministic** security/enforcement suite so it's
addressable on its own and can't be silently dropped:

- **`securityTest`** (root) — sandbox write-confinement (`ProcessSandbox`: Seatbelt / bwrap /
  firejail / fallback), declared tool-policy enforcement (#1916), snapshot manifest guard, and the
  tool-argument size cap (#2888).
- **`:agents-kt-observability:securityTest`** — the tamper-evident audit ledger
  (`ToolAuditLedger.verify` Merkle-chain detection).
- **`:agents-kt-detekt:test`** + **`detekt`** — the static tool-body rules (`ToolBodyForbiddenApis`,
  `ToolCapabilityExtractor`) and their dogfooded run on the framework's own source.

### OS-specific confinement

Sandbox confinement is OS-level, so the relevant tests only run on the matching OS and skip cleanly
elsewhere (`@EnabledOnOs` / `assumeTrue`):

| Backend | Runs on | Off-platform |
|---|---|---|
| macOS Seatbelt (`sandbox-exec`) | macOS | skipped |
| Linux `bwrap` / `firejail` | Linux (CI installs them; relaxes the userns sysctl) | skipped |

To actually exercise Seatbelt confinement in CI, run `securityCheck` on a `macos-latest` job (see
the CI draft below) — otherwise those assertions only run on a developer's Mac.

## detekt baseline

`detekt-baseline.xml` grandfathers pre-existing violations so the build stays green on legacy code
while new code must pass clean. To stop the baseline silently growing, `checkDetektBaseline` (wired
into `check`) fails if the entry count exceeds the recorded ceiling. **The baseline may only
shrink** — lower the `detektBaselineCeiling` constant in `build.gradle.kts` as violations are fixed;
never raise it.

Current ceiling: **424**.

## Other test tasks

| Task | Runs |
|---|---|
| `./gradlew integrationTest` | `live-llm` (Ollama / Ollama Cloud) |
| `./gradlew testAll` | every test task across every subproject (unit + live-cloud-api + live-llm + live-mcp + KSP + no-reflect smoke) |
| `./gradlew pitest` | mutation testing (`agents_engine.*`), excluding `live-llm` / `live-mcp` |

## CI draft — security gate on macOS (apply manually)

The repo's PAT lacks the `workflow` scope, so `.github/workflows/*` must be edited in the GitHub web
editor. To run the Seatbelt confinement assertions in CI, add a macOS job that runs the security
gate (the existing Linux `build` job already covers bwrap/firejail):

```yaml
  security-macos:
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v4
        with: { persist-credentials: false }
      - uses: actions/setup-java@v4
        with: { java-version: '21', distribution: 'corretto' }
      - uses: gradle/actions/setup-gradle@v4
      - name: Security gate (incl. macOS Seatbelt confinement)
        run: ./gradlew securityCheck
```
