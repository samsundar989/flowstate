package com.overhand.app.first60

/** One sentence of a pre-rendered greeting: where it starts and ends in the WAV (runs/first60/render.json). */
data class Cue(val index: Int, val startMs: Long, val endMs: Long, val text: String)

/**
 * Plays one pre-rendered greeting and fires each sentence's cue at its offset. Pure: the caller passes the clock.
 * [askIndex] is the question ("Do you agree?"); its end opens the microphone. A pinch or speech fades the audio
 * out over [FADE_MS] (under the 50 ms the card allows) and no later cue fires.
 */
class GreetingSequencer(val cues: List<Cue>, val askIndex: Int, val totalMs: Long) {
  sealed class Event {
    data class Sentence(val cue: Cue) : Event()
    /** The question has been spoken: open the microphone. */
    object AskEnded : Event()
    object Finished : Event()
    data class Cut(val why: String) : Event()
  }
  companion object { const val FADE_MS = 40L }

  var startedAt = -1L; private set
  private var next = 0
  private var askSent = false
  var cutAt = -1L; private set
  var done = false; private set
  val started get() = startedAt >= 0

  fun start(now: Long) { if (startedAt < 0) startedAt = now }

  /** Everything due by [now], in order. */
  fun tick(now: Long): List<Event> {
    if (startedAt < 0 || done) return emptyList()
    val at = now - startedAt
    val out = mutableListOf<Event>()
    while (next < cues.size && cues[next].startMs <= at) out += Event.Sentence(cues[next++])
    if (!askSent && askIndex in cues.indices && cues[askIndex].endMs <= at) { askSent = true; out += Event.AskEnded }
    if (at >= totalMs) { done = true; out += Event.Finished }
    return out
  }

  /** Barge-in. Returns the events to apply now (the cut, and the mic opening if the question had not ended). */
  fun cut(now: Long, why: String): List<Event> {
    if (startedAt < 0 || done) return emptyList()
    done = true; cutAt = now
    val out = mutableListOf<Event>(Event.Cut(why))
    if (!askSent) { askSent = true; out += Event.AskEnded }
    return out
  }

  /** Output gain at [now]: 1 while playing, a linear ramp to 0 over [FADE_MS] after a cut. */
  fun gain(now: Long): Float = if (cutAt < 0) 1f else (1f - (now - cutAt).toFloat() / FADE_MS).coerceIn(0f, 1f)
  /** Position in the greeting, ms. */
  fun position(now: Long) = if (startedAt < 0) 0L else now - startedAt
}
