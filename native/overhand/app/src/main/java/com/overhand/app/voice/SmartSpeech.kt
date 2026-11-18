package com.overhand.app.voice

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Speaks a smart answer sentence by sentence as the stream arrives. [ready] false = no speech engine: Smart answers stay off. */
interface Speaker {
  fun observeLevel(listener: (Float) -> Unit) {}
  val ready: Boolean
  /** Queue one sentence of [turn]; [onFirstAudio] fires once, when the turn's first sentence starts playing. */
  fun say(turn: Int, sentence: String, onFirstAudio: (Long) -> Unit)
  /** No more sentences for [turn]; [onDone] fires when the last queued one has finished (at once if none is left). */
  fun end(turn: Int, onDone: () -> Unit)
  fun stop()
  fun release()
}

/** The platform speech engine, on the headset only (no network voice: the engine is asked for an offline voice). */
class TtsSpeaker(context: Context) : Speaker {
  @Volatile override var ready = false; private set
  private val tts: TextToSpeech
  private var turn = -1
  private var queued = 0
  private var finished = 0
  private var firstAudio: ((Long) -> Unit)? = null
  private var done: (() -> Unit)? = null
  private val lock = Any()
  private val levels = SpeechLevels()
  override fun observeLevel(listener: (Float) -> Unit) { levels.listener = listener }

  init {
    tts = TextToSpeech(context.applicationContext) { status ->
      ready = status == TextToSpeech.SUCCESS && tts.setLanguage(Locale.US) >= TextToSpeech.LANG_AVAILABLE
      if (ready) tts.voices?.firstOrNull { it.locale.language == "en" && !it.isNetworkConnectionRequired }?.let { tts.voice = it }
      android.util.Log.i(Voice.TAG, "speech ready=$ready")
    }
    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
      override fun onBeginSynthesis(id: String, rate: Int, format: Int, channels: Int) { if (id.startsWith("t$turn-")) levels.begin(id,rate,format,channels) }
      override fun onAudioAvailable(id: String, audio: ByteArray) { if (id.startsWith("t$turn-")) levels.pcm(id,audio) }
      override fun onStart(id: String) {
        if (id.startsWith("t$turn-")) levels.start(id)
        val cb = synchronized(lock) { if (id.startsWith("t$turn-")) firstAudio.also { firstAudio = null } else null }
        cb?.invoke(SystemClock.elapsedRealtimeNanos())
      }
      override fun onDone(id: String) { finishOne(id) }
      @Deprecated("old API") override fun onError(id: String) { finishOne(id) }
      override fun onError(id: String, code: Int) { finishOne(id) }
    })
  }

  private fun finishOne(id: String) {
    levels.done(id)
    val cb = synchronized(lock) {
      if (!id.startsWith("t$turn-")) return
      finished++
      if (finished >= queued) done.also { done = null } else null
    }
    cb?.invoke()
  }

  override fun say(turn: Int, sentence: String, onFirstAudio: (Long) -> Unit) {
    val n = synchronized(lock) {
      if (turn != this.turn) { this.turn = turn; queued = 0; finished = 0; firstAudio = onFirstAudio; done = null }
      queued++
    }
    tts.speak(sentence, TextToSpeech.QUEUE_ADD, Bundle(), "t$turn-$n")
  }

  override fun end(turn: Int, onDone: () -> Unit) {
    val now = synchronized(lock) {
      if (turn != this.turn || finished >= queued) true else { done = onDone; false }
    }
    if (now) onDone()
  }

  override fun stop() { synchronized(lock) { turn = -1; done = null; firstAudio = null }; tts.stop(); levels.stop() }
  override fun release() { stop(); tts.shutdown() }
}
