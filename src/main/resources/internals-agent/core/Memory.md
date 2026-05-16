# `agents_engine/core/Memory.kt` — memory bank + `memory_*` tools

A simple per-agent scratch-pad backed by a `ConcurrentHashMap<String, String>`. Each agent reads/writes under its own name; sharing is opt-in.

## MemoryBank

```kotlin
val bank = MemoryBank()                  // unbounded
val capped = MemoryBank(maxLines = 200)  // keep only the last 200 lines per write
```

API:
- `read(key: String): String` — returns the slot content, or empty string if absent.
- `write(key: String, content: String)` — overwrites the slot. If `maxLines` is set, only the LAST `maxLines` lines are kept.
- `entries(): Map<String, String>` — snapshot of all slots (read-only copy).

Thread-safe — the backing map is concurrent.

## Sharing model

| Topology | How |
|---|---|
| Isolated | One `MemoryBank()` per agent. Each agent only sees its own slot. |
| Shared workspace | One `MemoryBank()` passed to many agents. Each agent writes under its own name; they can read each other's slots by passing different keys. |
| Hub/spoke | Multiple agent banks, plus a shared "common" bank some agents are also attached to. |

The framework does NOT enforce a particular topology — `MemoryBank` is a value passed into agent construction.

## The three built-in tools

`buildMemoryTools(bank, agentName)` (called internally) builds three `ToolDef`s, all keyed by `agentName`:

| Tool | Arg | Behavior |
|---|---|---|
| `memory_read` | — | Returns `bank.read(agentName)` as the tool result. |
| `memory_write` | `content` (string) | `bank.write(agentName, content)`. Returns `"ok"`. Falls back to the first arg value if `content` is missing. |
| `memory_search` | `query` (string) | Case-insensitive line filter over the agent's slot. Empty content or empty query → empty result. |

All three are internally constructed `ToolDef`s — they don't go through the public `tool(...)` DSL because they're framework-provided.

## Opt-in (#856)

The memory tools are only exposed to skills that called `useMemory()` (in `Skill.kt`):

```kotlin
agent<String, String>("notes") {
    memory(bank)                                 // attach the bank
    skills {
        skill<String, String>("remember") {
            tools()
            useMemory()                          // opt in — gets memory_*
        }
        skill<String, String>("describe") {
            tools()
            // no useMemory() — does NOT get memory_*
        }
    }
}
```

**Backward compatibility:** when NO skill on an agent opts in, the legacy behavior applies — every skill gets memory if a bank is set. This is the v0 path; new code should opt in explicitly.

## Sizing and persistence

- `MemoryBank` is in-memory only. There is no built-in persistence — the bank's `entries()` map can be serialized externally.
- `maxLines` truncates by lines, not bytes. Useful for keeping a streaming append-only log bounded.
- For larger or persistent stores, the framework's `KnowledgeProvider` / external resource loaders are the right tool — memory is for ephemeral scratch.

## Related files

- `Skill.kt` — `useMemory()` opt-in.
- `Agent.kt` — `memory(bank)` DSL.
- `AgenticLoop.kt` — wires memory tools into the per-skill allowlist.
- `model/ToolDef.kt` — the shape of the three built-in tools.
