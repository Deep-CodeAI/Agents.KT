# `agents_engine/composition/wrap/Wrap.kt` — teacher-student prompt override

The `teacher wrap student` operator (#1698). Returns a `Pipeline` where:

1. The teacher runs with the input, producing a `String`.
2. The student runs with the SAME input, but using the teacher's string as the system prompt for that one call.

The student's baked-in prompt is NOT mutated — the teacher's prompt is passed via `effectivePrompt` to `executeAgentic` (#1707), avoiding races where two concurrent invocations of the same wrap pipeline would clobber each other's prompt.

## Two framings

| Framing | Story |
|---|---|
| **Education** | The teacher specializes a generalist student. One delegate agent can be reused across many narrow jobs because the teacher hands it the task-specific context. |
| **Security** | The teacher locks down the student's task surface for the call. The student's default prompt does not apply, so it cannot drift to a wider task than the teacher allows. |

## Constraints

Both agents must share the same input type — the same input flows to both. The student's output type is the pipeline's output.

## Why a function and not an operator

Kotlin doesn't permit user types to overload the literal `>>` symbol, so the PRD's "`>>` operator" is implemented as the `wrap` infix function.

## Example

```kotlin
val style = agent<DocId, String>("style") {
    skills {
        skill<DocId, String>("style") {
            implementedBy { docId -> "Write in the style of document $docId. Keep it under 200 words." }
        }
    }
}

val writer = agent<DocId, Draft>("writer") {
    // ... general-purpose writer with broad capability
}

val styledWriter: Pipeline<DocId, Draft> = style wrap writer
```

Each `styledWriter(docId)` call now produces a draft following the style guide encoded by `style` — without `writer` knowing anything about that style guide.

## Related files

- `composition/pipeline/Pipeline.kt` — the returned type.
- `model/AgenticLoop.kt#effectivePrompt` — the race-safe prompt passthrough.
- `core/Agent.kt#markPlaced` — single-placement enforcement.
