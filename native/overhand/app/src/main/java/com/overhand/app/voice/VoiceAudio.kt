package com.overhand.app.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Turns recorded audio into text. The real one is SherpaTranscriber; tests use a fake. */
fun interface Transcriber { fun transcribe(samples: FloatArray, sampleRate: Int): String }

/** PCM16 capture buffer for one held utterance, capped so a stuck pinch cannot grow memory without bound. */
class PcmBuffer(val sampleRate: Int = 16000, maxSeconds: Int = 30) {
  private val cap = sampleRate * maxSeconds
  private var data = ShortArray(sampleRate)
  var size = 0; private set
  var truncated = false; private set

  fun append(src: ShortArray, n: Int) {
    val take = minOf(n, cap - size)
    if (take < n) truncated = true
    if (take <= 0) return
    if (size + take > data.size) data = data.copyOf(minOf(cap, maxOf(data.size * 2, size + take)))
    System.arraycopy(src, 0, data, size, take); size += take
  }
  fun clear() { size = 0; truncated = false }
  fun seconds() = size.toFloat() / sampleRate
  fun toFloats() = FloatArray(size) { data[it] / 32768f }
}

/** Minimal RIFF reader: PCM16 mono only, which is what the capture path and the test corpus produce. */
object Wav {
  class Pcm(val sampleRate: Int, val samples: ShortArray)

  fun read(bytes: ByteArray): Pcm {
    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    require(bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a WAVE file" }
    var pos = 12; var rate = 0; var channels = 0; var bits = 0
    while (pos + 8 <= bytes.size) {
      val id = String(bytes, pos, 4); val len = b.getInt(pos + 4)
      if (id == "fmt ") {
        require(b.getShort(pos + 8).toInt() == 1) { "not PCM" }
        channels = b.getShort(pos + 10).toInt(); rate = b.getInt(pos + 12); bits = b.getShort(pos + 22).toInt()
      } else if (id == "data") {
        require(channels == 1 && bits == 16) { "need PCM16 mono, got $channels ch $bits bit" }
        val n = minOf(len, bytes.size - pos - 8) / 2
        return Pcm(rate, ShortArray(n) { b.getShort(pos + 8 + 2 * it) })
      }
      pos += 8 + len + (len and 1)
    }
    error("no data chunk")
  }
}

/** One push-to-talk utterance: press clears, frames append while held, release transcribes. */
class PushToTalkSession(private val transcriber: Transcriber, private val clock: () -> Long = System::nanoTime) {
  data class Result(val text: String, val decodeMs: Long, val audioSeconds: Float, val tooShort: Boolean = false)

  val buffer = PcmBuffer()
  @Volatile var holding = false; private set

  fun press() { buffer.clear(); holding = true }
  fun frames(src: ShortArray, n: Int) { if (holding) buffer.append(src, n) }
  fun release(): Result {
    holding = false
    val secs = buffer.seconds()
    if (secs < MIN_SECONDS) return Result("", 0, secs, tooShort = true)
    val t0 = clock()
    val text = transcriber.transcribe(buffer.toFloats(), buffer.sampleRate).trim()
    return Result(text, (clock() - t0) / 1_000_000, secs)
  }

  companion object { const val MIN_SECONDS = 0.3f }
}
