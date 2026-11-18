package com.overhand.app.first60

import kotlin.math.sqrt

/**
 * Open-mic end of speech, by frame energy (no pinch to hold here). Speech starts after [startMs] above [onRms];
 * it ends after [endMs] below [offRms]. Frames before the start are kept ([preMs]) so the first word is not clipped.
 * Pure: feed PCM16 frames, read [utterance] when [feed] returns true.
 */
class Endpointer(val rate: Int = 16000, val onRms: Double = 0.02, val offRms: Double = 0.012, val startMs: Int = 90,
                 val endMs: Int = 600, val preMs: Int = 300, val maxMs: Int = 6000) {
  private var loud = 0; private var quiet = 0
  var speaking = false; private set
  private val pre = ArrayDeque<ShortArray>()
  private var preSamples = 0
  private val buf = ArrayList<Short>()
  var utterance: ShortArray? = null; private set

  fun reset() { loud = 0; quiet = 0; speaking = false; pre.clear(); preSamples = 0; buf.clear(); utterance = null }

  /** Returns true when an utterance ended on this frame. */
  fun feed(src: ShortArray, n: Int): Boolean {
    val f = src.copyOf(n)
    val rms = sqrt(f.sumOf { (it / 32768.0) * (it / 32768.0) } / n.coerceAtLeast(1))
    val ms = n * 1000 / rate
    if (!speaking) {
      pre.addLast(f); preSamples += n
      while (preSamples - pre.first().size > preMs * rate / 1000) preSamples -= pre.removeFirst().size
      loud = if (rms >= onRms) loud + ms else 0
      if (loud >= startMs) { speaking = true; quiet = 0; pre.forEach { a -> a.forEach { buf.add(it) } }; pre.clear(); preSamples = 0 }
      return false
    }
    f.forEach { buf.add(it) }
    quiet = if (rms < offRms) quiet + ms else 0
    if (quiet >= endMs || buf.size >= maxMs * rate / 1000) {
      utterance = buf.toShortArray(); buf.clear(); speaking = false; loud = 0; quiet = 0
      return true
    }
    return false
  }
}
