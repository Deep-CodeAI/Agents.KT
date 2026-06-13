# agents-kt-whisper-jni

In-process **Whisper speech-to-text** for Agents.KT — a `SpeechToTextClient` that runs
locally with **no server**, as the separate-module counterpart to the HTTP
`WhisperSttClient` in core. This is the pattern for native/heavy modality backends:
they live in their own opt-in module so `agents-kt` stays dependency-light (same as
`:agents-kt-otel` / the RAG adapters).

## This module ships no weights and no native artifact

The jar is **code only**. Two things are provisioned at runtime, never bundled:

- **The model file** (a GGML `.bin`, hundreds of MB) — resolved by `WhisperModelResolver`
  (download → cache → SHA-256 verify → reuse), or pointed at a local file. Model weights
  are licensed by their authors, separately from this Apache code.
- **The whisper.cpp JNI library** — supplied by *you* through the `WhisperBackend` seam,
  so this module pulls no native dependency. Add `io.github.givimad:whisper-jni` to *your*
  build when you wire the backend.

## Pieces

| Type | Role |
|---|---|
| `WhisperModelResolver` | Provision a model file at runtime; never bundles weights. |
| `WhisperBackend` | One-method native seam: `transcribe(samples: FloatArray, sampleRate: Int): String`. |
| `WhisperJniSttClient` | `SpeechToTextClient`: resolves audio from the `BlobStore`, decodes WAV → mono float PCM on the JVM, delegates to the backend. |

## Wiring (consumer side)

```kotlin
// your build.gradle.kts
implementation("ai.deep-code:agents-kt-whisper-jni:<version>")
implementation("io.github.givimad:whisper-jni:<version>")   // the native lib — yours to add
```

```kotlin
import agents_engine.whisper.*
import io.github.givimad.whisperjni.WhisperJNI
import io.github.givimad.whisperjni.WhisperFullParams

// 1. Provision the model (no weights in any jar):
val modelPath = WhisperModelResolver().fromUrl(
    "ggml-base.bin",
    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin",
    // sha256 = "…",  // optional integrity pin
)

// 2. Bind whisper.cpp behind the WhisperBackend seam (verify the API against your
//    whisper-jni version; whisper.cpp expects 16 kHz mono — resample if needed):
WhisperJNI.loadLibrary()
val whisper = WhisperJNI()
val ctx = whisper.init(modelPath)
val backend = WhisperBackend { samples, _ ->
    whisper.full(ctx, WhisperFullParams(), samples, samples.size)
    (0 until whisper.fullNSegments(ctx))
        .joinToString(" ") { whisper.fullGetSegmentText(ctx, it).trim() }
}

// 3. A SpeechToTextClient — drop it into the transcribe_audio tool or call directly:
val stt = WhisperJniSttClient(backend)
```

Input must be WAV PCM (whisper.cpp's native shape). Convert other formats first:
`ffmpeg -ar 16000 -ac 1 -c:a pcm_s16le out.wav`.

## Tests

Hermetic — no native lib, no model, no network beyond a localhost stub:
`WhisperModelResolverTest` (download/cache/checksum) and `WhisperJniSttClientTest`
(real WAV decode through a fake backend). The whisper.cpp binding itself is exercised
in your deployment; keep it behind a `live`-tagged test there.
