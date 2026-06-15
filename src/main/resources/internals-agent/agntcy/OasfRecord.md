---
description: Source-file knowledge for agents_engine/agntcy/OasfRecord.kt + OasfTaxonomy.kt + OasfLocator.kt — OASF 1.0.0 record export (#4518, PRD §12.6), AGNTCY's content-addressed discovery metadata. Agent<*,*>.toOasfRecord(version, authors, locators, domains, description, createdAt, annotations) is the third discovery exporter beside A2A toAgentCard() and native agent.json. Skills become OASF skills[] only when annotated with .oasf("path"); the vendored OasfTaxonomy resolves path -> uid (lookup, not formula). Deterministic/byte-stable; createdAt/authors/locators caller-supplied (no hidden now()). Call when the IDE LLM reasons about exporting an agent into the AGNTCY directory.
---

# `agents_engine/agntcy/OasfRecord.kt` — OASF 1.0.0 record export (#4518)

The **third discovery exporter** over the native typed agent, beside `A2AServer`/`toAgentCard()` (§12.5)
and `toAgentJson()` (§12.2). AGNTCY's [OASF](https://github.com/agntcy/oasf) record is the discovery
metadata that the DIR directory (a later subtask of epic #4517) stores content-addressed. The native
agent stays the source of truth; this is a projection, exactly parallel to `toAgentCard()`.

```kotlin
val record: String = agent.toOasfRecord(
    version = "1.2.0",
    authors = listOf("Ada Lovelace <ada@example.com>"),
    locators = listOf(OasfLocator("source_code", listOf("https://example.com/agent"))),
    createdAt = "2026-06-15T00:00:00Z", // RFC3339, caller-supplied — see Determinism
)
```

## Skills are taxonomy entries, not free text

Only skills annotated with `.oasf("agent_orchestration/multi_agent_planning")` (the `Skill.oasf(path)`
mutator, `core/Skill.kt`) become OASF `skills[]`. Each path resolves to its uid via `OasfTaxonomy`.
Un-annotated skills — and annotated paths absent from the vendored taxonomy — are **omitted** with a
`java.util.logging` warning (they remain in `agent.json`, which carries free-form skills). This is why
an agent with no `.oasf(...)` annotations exports an empty `skills[]`.

## OasfTaxonomy — vendored lookup, not a formula

OASF uids are **explicitly assigned per node**: top-level categories are multiples of 100, but level-2
is `category + n` while level-3 is `level2*100 + nn` — there is *no* single formula. So `OasfTaxonomy`
is a `path -> uid` lookup loaded from `resources/oasf/skills-1.0.0.tsv` (+ `domains-1.0.0.tsv`).
- **Slice 1 (#4518):** the exporter + the confirmed core of the skills tree.
- **Slice 2 (#4518, done):** the **complete** trees (122 skills, 181 domains) regenerated directly from
  the hosted schema, plus `OasfTaxonomyCrossCheckTest` — a `live-cloud-api`-tagged test that asserts the
  vendored TSVs equal `schema.oasf.outshift.com/api/{skills,domains}` and self-skips offline. So the
  vendored snapshot cannot silently drift from upstream. `OasfTaxonomy.skillEntries()`/`domainEntries()`
  are internal accessors backing that test.

## Determinism

Fixed key order; same inputs → byte-identical JSON (like `toAgentJson`). Wall-clock and authorship
(`createdAt`, `authors`, `locators`) are **caller-supplied**, never sampled — no hidden `now()`, so the
record is reproducible and CI-stable. Record key order: `name, version, schema_version, description?,
authors, created_at?, skills, domains, locators, modules, annotations`.

## Shared provenance with agent.json

`toAgentJson(..., authors, createdAt, locators)` gained the same optional provenance fields (additive,
omitted when not supplied → existing callers stay byte-identical): `metadata.authors`,
`metadata.createdAt`, `spec.locators`. `OasfLocator` (its own file for the one-type-per-file guard,
#3199) is the shared `{type, urls}` value.

## Out of scope (deferred, PRD §12.6)

Record **signing** (Sigstore/cosign over OCI) is external to the record JSON. OASF **import/validate**,
the **DIR** gRPC client, and **Identity-verify** are the other subtasks of epic #4517.

## Related files

- `core/AgentJson.kt` — sibling exporter; shares the provenance fields and `OasfLocator`.
- `a2a/A2AAgentCard.kt` — the structural sibling discovery exporter (`toAgentCard()`).
- `core/Skill.kt` — `oasf(path)` annotation + `oasfPath`.
- `resources/oasf/skills-1.0.0.tsv` — the vendored taxonomy.
