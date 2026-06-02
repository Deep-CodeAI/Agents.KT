# Source layout & the one-type-per-file convention

Agents.KT keeps **one top-level type per file**, with the file named after that type (the spirit of
Kotlin's `MatchingDeclarationName`). A library that sells *typed boundaries* should have a source
tree you can navigate by filename: `McpToolInfo` lives in `McpToolInfo.kt`, full stop. This keeps
diffs small, `git blame` per-type meaningful, and stops God-files from re-accreting (the
file/package-level version of the `Agent.kt` decomposition in #3088).

## The convention

- One public/internal top-level `class` / `interface` / `object` / `enum` per file.
- File name == the type name.
- Prefer a **subpackage** over a fat file when a cluster grows (e.g. `mcp/`, `model/`, `core/`).
- Same-package splits are free — no `import` changes, no FQN change, no public-API impact. Move a
  type to its own file in the **same package** rather than inventing a new package for public types.

## Documented exceptions (do NOT over-split)

Some files legitimately hold more than one type. These are allowlisted with a reason:

- **Sealed ADTs** — a `sealed class` / `sealed interface` co-located with its direct subtypes /
  permitted implementations. Co-locating the parent and its cases is idiomatic Kotlin and aids
  exhaustiveness reasoning (e.g. `content/Content.kt`, the `LlmResponse` hierarchy). Exploding these
  across files is *wrong*.
- **A type + a single tiny companion** it is meaningless without.

## Enforcement: `checkOneTypePerFile` (#3199)

A Gradle guard (wired into `check`, alongside `checkReadmeVersion` and `checkDetektBaseline`) fails
the build if a main-source `.kt` file declares more than one top-level type **and isn't on the
allowlist** at `config/one-type-per-file-allowlist.txt`.

The allowlist is a **ratchet — it may only shrink**:

- A *new* multi-type file that isn't listed fails the build → split it (or, for a sealed ADT, add it
  to the allowlist with a `# sealed-ADT: keep` reason).
- A *stale* entry (a listed file that no longer violates, e.g. after you split it) also fails the
  build → remove it from the list in the same PR.

This converts the one-time cleanup into an enforced invariant: you can't add a new God-file, and you
can't split one without recording the burndown.

### Burndown status

**Complete (#3199).** Every main-source `.kt` file now declares exactly one top-level type — the
allowlist is empty, so `checkOneTypePerFile` enforces the convention strictly across the whole tree
with no exceptions. detekt's `MatchingDeclarationName` (on by default) additionally keeps each file
named after its single declaration. Any new multi-type file fails the build outright; if a genuine
sealed-ADT exception ever arises, add it to `config/one-type-per-file-allowlist.txt` with a
`# sealed-ADT: keep` reason.

To inspect, run `./gradlew checkOneTypePerFile` (lists any file with more than one top-level type).
