---
description: Source-file knowledge for agents_engine/generation/LenientJsonParser.kt — tolerant JSON parser for LLM output. Strips markdown fences, removes trailing commas, extracts first balanced {...}/[...] from explanatory text. MAX_NESTING_DEPTH=64 guards StackOverflowError (#854 — Error not Exception so try/catch can't catch it). Returns null on any failure (never throws). Call when the IDE LLM needs to reason about parsing LLM-emitted JSON.
---

# `agents_engine/generation/LenientJsonParser.kt` — JSON for LLM output

A small JSON parser tuned for the messy reality of LLM text output.

## API

```kotlin
internal object LenientJsonParser {
    const val MAX_NESTING_DEPTH: Int = 64
    fun parse(input: String): Any?    // null on any failure
}
```

Returns `Map<String, Any?>` or `List<Any?>` or scalar (`String`, `Number`, `Boolean`, `null`). Returns `null` on any parse failure — callers MUST handle absence.

## Tolerances

1. **Markdown code fences** — strips ` ```json ` and ` ``` ` before parsing. LLMs love wrapping JSON in code blocks.
2. **Trailing commas** — removed before strict parse: `{"a": 1,}` → `{"a": 1}`. Same for arrays.
3. **Pre/post explanation text** — extracts the first balanced `{...}` or `[...]` block, ignoring everything before the opening brace and after the matching close. So a response like:
   ```
   Sure! Here's the JSON:
   {"name": "Alice", "age": 30}
   Let me know if you need more.
   ```
   parses correctly to `{name: Alice, age: 30}`.
4. **Quoted edge cases** — handles standard JSON escapes (`\\`, `\"`, `\b`, `\f`, `\n`, `\r`, `\t`, `\uXXXX`).

## Nesting cap (#854)

`MAX_NESTING_DEPTH = 64` is a hard cap. Without it, adversarial input like `{"a":{"a":{...10000 levels...}}}` overflows the JVM stack with `StackOverflowError` — which is an `Error`, NOT an `Exception`, so the `try/catch (Exception)` inside `parse` would NOT catch it. The cap is enforced inside the recursive `parseValue` so the parser bails before the stack blows.

64 is comfortably more than any legitimate LLM-emitted structure and keeps stack usage in the kilobytes.

## Failure mode: return null, never throw

Callers see `null` for every failure:
- Malformed JSON
- Truncated input
- Nesting cap exceeded
- Internal exceptions

The framework's policy is to NOT propagate parser exceptions because LLM output reliability is the wrong place to surface stack traces. Use the absence of a result as the signal.

## Where it's used

- `InlineToolCallParser.parse(content)` — the inline tool-call shape.
- `constructFromMap(args: Map, ...)` indirectly — args come from upstream parsers using this.
- Adapter argument-parsing paths (`OllamaClient`'s `parseToolArguments`, etc.).

## Internal: the `Parser` state machine

The private `Parser` class is a recursive descent over the raw text, advancing a cursor. Each `parseX` method handles one shape:
- `parseValue` — dispatch on first char.
- `parseObject` — `{` ... `}` with optional trailing comma.
- `parseArray` — `[` ... `]` with optional trailing comma.
- `parseString` — quoted, with escape handling.
- `parseNumber` — leading digit / minus, optional decimal + exponent.
- `parseLiteral` — `true` / `false` / `null`.

Depth is incremented before `parseObject` / `parseArray` and checked against `MAX_NESTING_DEPTH`.

## Related files

- `InlineToolCallParser.kt` — heavy caller.
- `GenerableSupport.kt` — `fromLlmOutput(rawText)` parses with this first.
- `OllamaClient.kt` / `ClaudeClient.kt` / `OpenAiClient.kt` — adapters use this for tool-call args.
