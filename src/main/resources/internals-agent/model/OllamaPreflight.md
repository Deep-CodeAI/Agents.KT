# `agents_engine/model/OllamaPreflight.kt` — fail-fast Ollama reachability check

A tiny class with one method (#1132):

```kotlin
class OllamaPreflight(host = "localhost", port = 11434, connectTimeout = 2.seconds, requestTimeout = 3.seconds) {
    fun check()
}
```

## Why it exists

Without a preflight, a REPL or CLI that targets a misconfigured Ollama daemon greets the user, waits for input, and then fails mid-turn with a network error behind the spinner. By the time the failure surfaces, the user is confused about whether their prompt was wrong or the agent was broken.

`OllamaPreflight().check()` aborts startup with a clear error naming `host:port` BEFORE the user sees the greeting.

## Usage

```kotlin
LiveRunner.serve(captain, args) {
    prompt = "fib> "
    precheck = OllamaPreflight(host = "localhost", port = 11434)::check
}
```

`precheck` runs once at startup; failure throws `LlmProviderException` and the REPL exits with the message.

## What it checks

- Sends `GET /api/tags` — Ollama's lightweight catalog endpoint. Fast, doesn't require model load.
- Connect timeout `2.seconds`, request timeout `3.seconds`. Generous enough for slow boxes, tight enough to fail fast.
- Throws `LlmProviderException("cannot reach Ollama at $host:$port — $cause", e)` on `IOException`.
- Throws `LlmProviderException` on any non-2xx response.

## Tunables

- `connectTimeout` — TCP-connect deadline.
- `requestTimeout` — full-request deadline (connect + send + receive).

Both are `Duration` so the caller can pass `5.seconds`, `500.milliseconds`, etc.

## Related files

- `OllamaClient.kt` — the adapter this preflight protects.
- `LiveRunner.kt` / `LiveShow.kt` — where `precheck` is wired.
- `LlmProviderException.kt` — the thrown type.
