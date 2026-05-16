---
description: Source-file knowledge for agents_engine/mcp/McpJson.kt — internal strict JSON encoder for MCP RPC envelopes. ~25 lines. Supports null/Boolean/Number/String/Map/Iterable/Array; falls back to escape(toString()). Full string escapes including \uXXXX for control chars under 0x20. Reads use generation.LenientJsonParser (different concern). Call when the IDE LLM needs to reason about MCP wire encoding.
---

# `agents_engine/mcp/McpJson.kt` — minimal JSON encoder for MCP wire

A tiny internal encoder for building JSON-RPC envelopes the framework sends out. Reads use `generation/LenientJsonParser` — different concern.

## API

```kotlin
internal object McpJson {
    fun encode(value: Any?): String
}
```

Returns a strict JSON string (no trailing commas, fully-escaped strings).

## Supported types

| Kotlin type | JSON output |
|---|---|
| `null` | `null` |
| `Boolean` | `true` / `false` |
| `Number` | numeric literal |
| `String` | `"..."` with full escapes |
| `Map<*, *>` | object, keys via `.toString()` then escaped |
| `Iterable<*>` | array |
| `Array<*>` | array |
| anything else | escaped `.toString()` |

The `else → escape(value.toString())` clause means callers can pass an arbitrary type and get a stringified-and-quoted representation rather than a crash — but should typically pre-convert to a supported type.

## String escapes

`escape(s)` handles:
- `"` → `\"`
- `\` → `\\`
- `\n`, `\r`, `\t`, `\b` → corresponding escapes
- control characters under `0x20` → `\uXXXX` literal

Doesn't escape `/` (legal in JSON strings) or non-ASCII chars (UTF-8 passed through). Consumers writing to non-UTF-8 transports would need to layer encoding on top.

## Why not just use the parent JSON encoder?

The framework has a `LenientJsonParser` for reads, but no general-purpose strict encoder. MCP wire needs strict (servers don't tolerate trailing commas), and the encoder is so small (~25 lines) that pulling in a dependency or building a generic facade isn't worth it.

If a future feature needs richer encoding (custom serializers, JSON Schema validation), promote this to a proper module — but for now, simple suffices.

## Related files

- `McpClient.kt` — encodes outbound RPCs.
- `McpServer.kt` — encodes outbound responses.
- `generation/LenientJsonParser.kt` — the inbound parser (separate concern).
