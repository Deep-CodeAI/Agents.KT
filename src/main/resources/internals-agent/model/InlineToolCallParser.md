---
description: Source-file knowledge for agents_engine/model/InlineToolCallParser.kt — parses {"tool":"name","arguments":{...}} text into ToolCall and the reverse JSON encoder. Used by providers without native function-calling that instruct the LLM to emit inline tool-call JSON. Lenient parsing via generation.LenientJsonParser, strict encoding. Call when the IDE LLM needs to reason about how LLM text becomes a ToolCall.
---

# `agents_engine/model/InlineToolCallParser.kt` — inline tool-call JSON

Parses `{"tool": "name", "arguments": {...}}` text into a `ToolCall`, and the reverse JSON encoder.

## When this is used

Providers that don't expose native function-calling instruct the LLM to emit tool calls as inline JSON inside the assistant content. The framework intercepts that text, parses it via this object, and routes it through the agentic loop as if it were a structured tool call.

The structured-output path also uses the JSON encoder to round-trip tool calls into prompt text for replay scenarios.

## API

```kotlin
fun parse(content: String): ToolCall?         // null on parse failure
fun toJson(call: ToolCall): String            // round-trip encoder
fun argsToJson(args: Map<String, Any?>): String
```

## Shape

```json
{
  "tool": "addNumbers",
  "arguments": { "a": 12, "b": 30 }
}
```

`tool` is the tool name (must match a known `ToolDef.name`). `arguments` is a JSON object mapped to `Map<String, Any?>`. Lenient — extra keys at the top level are ignored; missing `arguments` defaults to empty.

## Lenient parsing

Goes through `agents_engine.generation.LenientJsonParser` — tolerates trailing commas, single-quoted strings, JS-style comments, and other malformed-but-recoverable JSON the LLM emits. See `internals-agent/generation/LenientJsonParser.md`.

## Encoding

`toJson` and `argsToJson` produce strict JSON — escaped strings, no trailing commas. Supports `null`, `Number`, `Boolean`, `String`, `Map`, and `List`. Other types fall back to `toString().toJsonString()`.

The private `String.toJsonString()` extension handles full JSON string escapes: `\\`, `\"`, `\b`, `\f`, `\n`, `\r`, `\t`, plus `\uXXXX` for control characters under `0x20`.

## Related files

- `Tool.kt` / `ToolDef.kt` — the `ToolCall` shape parsed/built.
- `generation/LenientJsonParser.kt` — the underlying tolerant parser.
- `ModelClient.kt` — adapters wire inline parsing into their `chat` implementations as needed.
