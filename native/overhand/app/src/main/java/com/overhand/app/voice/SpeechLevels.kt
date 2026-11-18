package com.overhand.app.voice

import android.media.AudioFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Real synthesized PCM, aligned to each utterance's playback start; silent without PCM callbacks. */
internal class SpeechLevels {
  var listener: (Float) -> Unit = {}
  private val ui = Handler(Looper.getMainLooper())
  private data class Audio(val rate: Int, val format: Int, val channels: Int,
    var samples: Long = 0, var start: Long? = null, val levels: MutableList<Pair<Long,Float>> = mutableListOf())
  private val audio = mutableMapOf<String,Audio>()
  private var playing: String? = null
  private var startedAt: Long? = null
  private var generation = 0

  @Synchronized fun begin(id: String, rate: Int, format: Int, channels: Int) {
    audio[id] = Audio(rate,format,channels,start = if (playing == id) startedAt else null)
  }
  @Synchronized fun pcm(id: String, bytes: ByteArray) {
    val a = audio[id] ?: return
    if (a.rate <= 0 || a.channels <= 0) return
    val width = when(a.format) { AudioFormat.ENCODING_PCM_8BIT -> 1; AudioFormat.ENCODING_PCM_16BIT -> 2; AudioFormat.ENCODING_PCM_FLOAT -> 4; else -> return }
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val chunk = maxOf(1,a.rate * a.channels / 50)
    while (buffer.remaining() >= width) {
      val n = minOf(chunk,buffer.remaining()/width)
      var energy = 0.0
      repeat(n) {
        val sample = when(width) { 1 -> ((buffer.get().toInt() and 255)-128)/128.0; 2 -> buffer.short/32768.0; else -> buffer.float.toDouble() }
        energy += sample * sample
      }
      val at = a.samples * 1000 / (a.rate * a.channels)
      val level = (sqrt(energy/n) * 4).toFloat().coerceIn(0f,1f)
      a.samples += n
      if (a.start == null) a.levels += at to level else schedule(id,a.start!!+at,level)
    }
  }
  @Synchronized fun start(id: String) {
    playing = id; startedAt = SystemClock.uptimeMillis()
    val a = audio[id] ?: return
    a.start = startedAt
    a.levels.forEach { (at,level) -> schedule(id,a.start!!+at,level) }; a.levels.clear()
  }
  private fun schedule(id: String, at: Long, level: Float) {
    val g = generation
    ui.postAtTime({ synchronized(this) { if (g == generation && playing == id) listener(level) } },at.coerceAtLeast(SystemClock.uptimeMillis()))
  }
  @Synchronized fun done(id: String) {
    audio.remove(id)
    if (playing == id) { playing = null; startedAt = null; ui.post { listener(0f) } }
  }
  @Synchronized fun stop() {
    generation++; playing = null; startedAt = null; audio.clear(); ui.removeCallbacksAndMessages(null); ui.post { listener(0f) }
  }
}
