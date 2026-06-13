package agents_engine.speechserver

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * #4506 — build a mono 16-bit PCM WAV of a sine tone. Used by the demo TTS backend so
 * `java -jar` produces real, playable audio out of the box (a short beep), with no
 * model. Pure JDK — header + samples, no `javax.sound` needed.
 */
internal fun toneWav(freqHz: Double, millis: Int, sampleRate: Int = 16_000): ByteArray {
    val frames = sampleRate * millis / MILLIS_PER_SECOND
    val dataLen = frames * 2
    val buf = ByteBuffer.allocate(WAV_HEADER_BYTES + dataLen).order(ByteOrder.LITTLE_ENDIAN)
    buf.put("RIFF".toByteArray()); buf.putInt(WAV_HEADER_BYTES - 8 + dataLen); buf.put("WAVE".toByteArray())
    buf.put("fmt ".toByteArray()); buf.putInt(FMT_CHUNK_SIZE); buf.putShort(PCM_FORMAT); buf.putShort(MONO)
    buf.putInt(sampleRate); buf.putInt(sampleRate * 2); buf.putShort(BLOCK_ALIGN); buf.putShort(BITS_16)
    buf.put("data".toByteArray()); buf.putInt(dataLen)
    for (n in 0 until frames) {
        val sample = (sin(2.0 * PI * freqHz * n / sampleRate) * AMPLITUDE).toInt().toShort()
        buf.putShort(sample)
    }
    return buf.array()
}

private const val MILLIS_PER_SECOND = 1000
private const val WAV_HEADER_BYTES = 44
private const val FMT_CHUNK_SIZE = 16
private const val PCM_FORMAT: Short = 1
private const val MONO: Short = 1
private const val BLOCK_ALIGN: Short = 2
private const val BITS_16: Short = 16
private const val AMPLITUDE = 9000.0
