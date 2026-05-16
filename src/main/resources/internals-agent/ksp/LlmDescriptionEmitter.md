---
description: Source-file knowledge for agents-kt-ksp/agents_engine/ksp/LlmDescriptionEmitter.kt — emits the markdown KClass.toLlmDescription() produces via reflection (#1703). Contract: byte-identical to GenerableSupport.dataClassLlmDescription/sealedLlmDescription. Format: ## ClassName + description + bulleted fields with @Guide text; sealed: 'Choose one of the following variants:' + ### Variant blocks. Prompt-cache determinism depends on this matching. Call when the IDE LLM needs to reason about LLM-facing type descriptions.
---

# `agents-kt-ksp/agents_engine/ksp/LlmDescriptionEmitter.kt` — emits LLM-facing markdown

Emits the markdown the framework's runtime `KClass.toLlmDescription()` produces via reflection (#1703). The string the parent agent's prompt embeds for skill selection.

## Contract: byte-identical to runtime

Output must match `GenerableSupport.dataClassLlmDescription()` and `sealedLlmDescription()` exactly. Consumers see the same prompt either way; only when the work happens changes.

This matters because:
- **Prompt-cache determinism** — identical bytes → identical Anthropic cache key → cache hit. Drift between runtime and KSP would invalidate the cache for half the consumers.
- **Test parity** — tests written against the runtime path also pass for the KSP path without modification.

## Format

### Data class

```
## Person

a person

- **name** (String)
- **age** (Int): how old
```

- `## <ClassName>` — second-level heading.
- Blank line, then the `@Generable` description (or empty when none).
- Blank line, then a bulleted list of fields. Each: `- **<name>** (<TypeName>)` and optionally `: <@Guide description>`.

### Sealed root

```
## Decision

a description

Choose one of the following variants:

### Approved
...
```

- Same `## <ClassName>` heading + description.
- `Choose one of the following variants:` separator.
- One `### <VariantName>` block per variant with the variant's own description / fields.

## API

```kotlin
internal object LlmDescriptionEmitter {
    fun emit(cls: GenerableValidator.GenerableClass): String
}
```

Pure object; no KSP types. The processor builds the `GenerableClass` and passes it here.

## Why a separate emitter from `SchemaEmitter`

The two outputs serve different consumers:
- **`SchemaEmitter`** produces the JSON Schema the LLM provider needs for structured output (tool calls, response_format).
- **`LlmDescriptionEmitter`** produces the markdown the parent agent's prompt embeds for the LLM to read directly.

They are independent — a class can have one without the other (e.g., when validation passes but `canGenerate` returns false for one but not the other).

## Related files

- `SchemaEmitter.kt` — JSON Schema sibling emitter.
- `ConstructFromMapEmitter.kt` — `constructFromMap` sibling emitter.
- `GenerableValidator.kt` — provides the `GenerableClass` model.
- `generation/GenerableSupport.kt` (main module) — the reflection counterpart whose output this matches.
