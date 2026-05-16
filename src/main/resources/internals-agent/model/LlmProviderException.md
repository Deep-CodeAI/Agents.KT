# `agents_engine/model/LlmProviderException.kt` — provider boundary error

Single-class file (#702):

```kotlin
class LlmProviderException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
```

## What it represents

A failure at the LLM-provider protocol boundary — NOT a model output that failed to parse. Examples:

| Cause | Example |
|---|---|
| Authentication | `401 Unauthorized`, invalid API key |
| Authorization | `403 Forbidden`, missing capability |
| Capability mismatch | "model doesn't support tools" |
| Model not found | `404` for a nonexistent model name |
| Malformed request | Bad JSON, missing required field |
| Quota / rate limit | `429 Too Many Requests` |
| Server errors | `5xx` |

## What it does NOT represent

- **Bad model output** — when `transformOutput { }` or the structured-output decoder fails to parse the LLM's response, that throws `IllegalStateException` (or whatever the user-defined transformer throws). Callers can distinguish these for retry policy.
- **Budget exceeded** — `BudgetExceededException` is its own type (with a `BudgetReason`).
- **Tool execution failures** — propagate as whatever the tool body threw, optionally caught by `onError { }`.

## Where it's thrown

Each `ModelClient` implementation raises it at the HTTP/protocol layer:
- `ClaudeClient` — top-level `{"type":"error", "error":{...}}` envelopes.
- `OllamaClient` — Ollama's `{"error":"..."}` shape (#702 was the unifying issue).
- `OpenAiClient` — OpenAI's `error.message` field.

The agentic loop does not catch it — it propagates to the caller's `invoke` / `invokeSuspend` / session boundary, fires `onError` along the way.

## Related files

- `ModelClient.kt` — the interface whose implementations throw this.
- `ClaudeClient.kt` / `OllamaClient.kt` / `OpenAiClient.kt` — concrete throwers.
- `OnErrorBuilder.kt` — recovery hook that can swallow / convert this.
