# agents-kt-speech-server

A **pure-JDK, OpenAI-compatible speech server** — no Docker, no Python, zero external
dependencies (just `com.sun.net.httpserver`). Run it with `java -jar` and point any
OpenAI-compatible client at it, including Agents.KT's `WhisperSttClient` /
`QwenTtsClient`.

Endpoints (subset of the OpenAI audio API):

| Method + path | In | Out |
|---|---|---|
| `POST /v1/audio/transcriptions` | multipart (`file` + `model`) | `{"text": "…"}` |
| `POST /v1/audio/speech` | JSON (`input`, `voice`, `response_format`) | audio bytes |

## Run it now (no Docker)

```bash
./gradlew :agents-kt-speech-server:run --args="--port 8000"
# or, from a built distribution:
#   ./gradlew :agents-kt-speech-server:installDist
#   ./agents-kt-speech-server/build/install/agents-kt-speech-server/bin/agents-kt-speech-server --port 8000
```

Out of the box it runs **demo backends** (fixed-text STT, a WAV-beep TTS), so both
endpoints answer immediately — `curl` `/v1/audio/speech` and you get a real, playable
WAV. Swap in real backends for actual transcription/synthesis:

## Wiring real inference (the seams)

The server owns the HTTP wire; the model is plugged via two one-method seams. It pulls
**no model and no native artifact** itself.

```kotlin
import agents_engine.speechserver.*

val server = SpeechServer(
    stt = ServerSttBackend { audioBytes, contentType -> /* whisper-jni → text */ },
    tts = ServerTtsBackend { text, voice, format -> /* sherpa-onnx / proxy → bytes */ },
    port = 8000,
).start()
```

- **STT — pure-jar Whisper.** Decode the uploaded bytes and run whisper.cpp via
  `io.github.givimad:whisper-jni` (its jar carries the native lib and self-loads).
  `:agents-kt-whisper-jni` already has the `WhisperBackend` seam, the `WavPcm` decoder,
  and `WhisperModelResolver` (weights downloaded + checksummed at runtime, never
  bundled) — adapt them into a `ServerSttBackend`.
- **TTS — pure-jar voice, or proxy Qwen.** For a fully pure-JVM voice use
  **sherpa-onnx** (Kokoro/Piper/VITS — *not* Qwen). For a **Qwen** voice there is no
  pure-JVM port, so proxy a Qwen-TTS endpoint from inside the backend (HTTP call →
  return the bytes).

## Security (#4508)

The server is **unauthenticated** — it's designed for loopback / trusted-network use:

- **Binds `127.0.0.1` by default**, and **refuses** a non-loopback host unless you pass
  `allowNonLoopback = true` (exposing an unauthenticated STT/TTS endpoint is then your call — put
  auth/a proxy in front).
- **Caps the request body** at `maxRequestBytes` (default 25 MB) → over-cap requests get `413`,
  never an unbounded read.
- JSON responses are escaped; the backend seams own model trust (a model file is untrusted input
  to whatever native runtime you plug — pin its checksum via `WhisperModelResolver`).

## Why this shape

Weights and native runtimes live behind the seams, never in this jar — the same
"jar = code, weights/models = runtime config" boundary as the rest of Agents.KT's
multimodal story. The server is hermetic and testable (the round-trip test drives it
with `WhisperSttClient` / `QwenTtsClient` and fake backends — all in one JVM, no Docker).
