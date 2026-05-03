[← Back to README](../README.md)

## Agent Memory

Memory persists across invocations — an agent accumulates knowledge over time rather than starting from zero each run. Pass a `MemoryBank` and three tools are auto-injected:

| Tool | Arguments | Returns |
|------|-----------|---------|
| `memory_read` | — | Full memory content |
| `memory_write` | `content` | Overwrites memory |
| `memory_search` | `query` | Lines matching the query (case-insensitive) |

```kotlin
val bank = MemoryBank(maxLines = 200)   // in-memory, optional line cap

val reviewer = agent<CodeDiff, ReviewResult>("reviewer") {
    memory(bank)
    model { ollama("llama3") }
    skills {
        skill<CodeDiff, ReviewResult>("review", "Reviews code changes") {
            tools()   // memory_read, memory_write, memory_search — all auto-available
            knowledge("memory-instructions") {
                "Before reviewing, call memory_read to check for known patterns. " +
                "After reviewing, call memory_write to save new patterns discovered."
            }
        }
    }
}
```

**Shared memory** — pass the same bank to multiple agents. Each agent reads/writes under its own name as key, so data is isolated by default but inspectable from the outside:

```kotlin
val shared = MemoryBank()
val analyst = agent<String, String>("analyst") { memory(shared); /* ... */ }
val writer  = agent<String, String>("writer")  { memory(shared); /* ... */ }

// After runs, inspect what each agent learned:
shared.read("analyst")   // analyst's memory
shared.read("writer")    // writer's memory
shared.entries()          // all keys
```

**Pre-seeding** — load initial knowledge before the first run:

```kotlin
val bank = MemoryBank()
bank.write("reviewer", "Known pattern: prefer val over var\nKnown pattern: avoid nullable returns")
```

**Fibonacci — the canonical memory test.** A single agent, no custom tools — just `memory_read`, `memory_write`, and a system prompt that teaches it the algorithm. Each call reads state, computes the next number, writes back, and returns the result:

```kotlin
val bank = MemoryBank()

val fib = agent<String, Int>("fibonacci") {
    prompt("""You maintain a Fibonacci sequence in memory.
Memory format: "prev|curr". Empty memory means start fresh.

1. Call memory_read
2. If empty → answer=1, write "0|1"
   If "A|B" → answer=A+B, write "B|A+B"
3. Call memory_write with the new state
4. Reply with ONLY the answer number""")
    memory(bank)
    model { ollama("llama3"); temperature = 0.0 }
    skills { skill<String, Int>("fib", "Generate next Fibonacci number") {
        tools()   // memory tools are auto-available
        transformOutput { it.trim().toInt() }
    }}
}

fib("do it")  // → 1   (bank: "0|1")
fib("do it")  // → 1   (bank: "1|1")
fib("do it")  // → 2   (bank: "1|2")
fib("do it")  // → 3   (bank: "2|3")
fib("do it")  // → 5   (bank: "3|5")

// Pre-seed to resume from any point:
bank.write("fibonacci", "21|34")
fib("do it")  // → 55  (bank: "34|55")
```

Memory is optional. Short-lived pipeline stages (parsers, formatters) are stateless. Memory is for agents that improve with experience: reviewers, planners, domain experts.

---
