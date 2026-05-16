# `agents_engine/core/Resources.kt` — classpath resource loading

Two free functions for pulling UTF-8 text resources off the classpath. The canonical "load a prompt from a .md file" helper.

## API

```kotlin
fun loadResource(path: String): String          // throws on missing
fun loadResourceOrNull(path: String): String?   // null on missing
```

## Canonical usage

```kotlin
agent<IN, OUT>("coder") {
    prompt(loadResource("prompts/coder.md"))
    skills {
        skill<X, Y>("review") {
            knowledge("style-guide") { loadResource("style/guide.md") }
            implementedBy { ... }
        }
    }
}
```

The InternalsAgent itself is the heaviest user: every per-file skill loads its adjunct via `loadResource("internals-agent/<path>.md")`.

## Behavior

- **UTF-8** decoded — `bufferedReader(Charsets.UTF_8).readText()`.
- **Leading slash tolerated** — `prompts/x.md` and `/prompts/x.md` resolve identically. The path is `trimStart('/')`'d before lookup. This normalization papers over a footgun where `ClassLoader.getResourceAsStream` and `Class#getResource` disagree on leading-slash semantics.
- **Lookup order** — `Thread.currentThread().contextClassLoader`, falling back to the class loader of this file. Works in shaded jars, fat jars, OSGi-flavored classloading.
- **Fail-fast on miss** — `loadResource` throws `IllegalArgumentException` at the call site (normally inside `agent { }`), so typos surface at agent construction rather than at first invocation.
- **No caching** — every call re-reads the resource. For hot paths, cache the returned `String` yourself; the helper stays minimal.

## When to use which

| Function | When |
|---|---|
| `loadResource` | The resource MUST be present. A typo or missing file is a bug — fail fast. |
| `loadResourceOrNull` | The resource is optional — the agent should run with or without it (feature toggles, optional knowledge entries). |

## Related files

- `Skill.kt` — `knowledge(key) { loadResource(...) }` is the most common pattern.
- `Agent.kt` — `prompt(loadResource(...))` for system prompts.
- `runtime/internals/InternalsAgent.kt` — every internals skill loads its adjunct via this helper.
