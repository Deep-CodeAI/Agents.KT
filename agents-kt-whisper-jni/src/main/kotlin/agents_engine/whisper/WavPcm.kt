package agents_engine.whisper

import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

/**
 * #4505 — decode WAV bytes to mono float PCM in `[-1.0, 1.0]` plus the sample rate,
 * using only the JDK's `javax.sound.sampled` (no native decoder, no audio device —
 * parsing is headless-safe). Non-PCM or non-16-bit input is converted to signed
 * 16-bit PCM when the JDK can; unsupported encodings fail with an actionable message.
 * Stereo is down-mixed by averaging channels.
 */
internal fun decodeWavToMonoFloat(wavBytes: ByteArray): Pair<FloatArray, Int> {
    val raw: AudioInputStream = try {
        AudioSystem.getAudioInputStream(ByteArrayInputStream(wavBytes))
    } catch (e: javax.sound.sampled.UnsupportedAudioFileException) {
        throw IllegalArgumentException(
            "Not a readable WAV stream (${e.message}). Provide PCM WAV (e.g. `ffmpeg -ar 16000 -ac 1 out.wav`).",
            e,
        )
    }

    val pcm = raw.toSignedPcm16()
    val format = pcm.format
    val channels = format.channels
    val bytes = pcm.readAllBytes()
    val bytesPerSample = 2
    val frames = bytes.size / (bytesPerSample * channels)
    val mono = FloatArray(frames)
    var byteIndex = 0
    for (frame in 0 until frames) {
        var acc = 0
        for (ch in 0 until channels) {
            val lo = bytes[byteIndex].toInt() and 0xFF
            val hi = bytes[byteIndex + 1].toInt() // signed high byte
            acc += (hi shl 8) or lo
            byteIndex += bytesPerSample
        }
        mono[frame] = (acc.toFloat() / channels) / PCM16_FULL_SCALE
    }
    return mono to format.sampleRate.toInt()
}

/** Convert to little-endian signed 16-bit PCM if needed; pass through when already so. */
private fun AudioInputStream.toSignedPcm16(): AudioInputStream {
    val f = format
    val alreadyPcm16 = f.encoding == AudioFormat.Encoding.PCM_SIGNED &&
        f.sampleSizeInBits == BITS_16 && !f.isBigEndian
    if (alreadyPcm16) return this
    val target = AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED,
        f.sampleRate,
        BITS_16,
        f.channels,
        f.channels * 2,
        f.sampleRate,
        false,
    )
    return try {
        AudioSystem.getAudioInputStream(target, this)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException(
            "Cannot convert this WAV to 16-bit PCM (${f.encoding}, ${f.sampleSizeInBits}-bit). " +
                "Pre-convert with `ffmpeg -ar 16000 -ac 1 -c:a pcm_s16le out.wav`.",
            e,
        )
    }
}

private const val BITS_16 = 16
private const val PCM16_FULL_SCALE = 32768.0f
