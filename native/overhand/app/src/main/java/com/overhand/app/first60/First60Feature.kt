@file:OptIn(com.meta.spatial.core.SpatialSDKExperimentalAPI::class)
package com.overhand.app.first60

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.PanelSceneObject
import com.meta.spatial.toolkit.SceneObjectSystem
import com.overhand.app.BuildConfig
import com.overhand.app.R
import com.overhand.app.features.Feature
import com.overhand.app.features.Shell
import com.overhand.app.onboarding.HandsOnboarding
import com.overhand.app.voice.Transcriber
import com.overhand.app.voice.VoiceIntents
import com.overhand.app.voice.Wav
import org.json.JSONObject
import java.util.concurrent.Executors

/** The first 60 seconds switch: on in Recorded only. */
object First60 {
  const val ENABLED = true
  fun on(recorded: Boolean) = ENABLED && recorded
  const val TAG = "OverhandFirst60"
}

/**
 * t804: put the headset on, and once the head is steady ([SteadyGate]) the windows are centred (#765 centerAll),
 * the decision card opens and the pre-rendered greeting plays ([GreetingSequencer]). The question's end opens the
 * microphone ([AskState]): "yes" or "no", or a pinch on an option, is confirmed through the card's own option and
 * Confirm controls, which write the practice receipt; then the consequence line plays. Every step is one log line.
 */
class First60Feature(private val s: Shell) : Feature {
  override val name = "first60"
  private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "first60-asr") }
  private var plan: Plan? = null
  private var ears: Transcriber? = null
  private var intents: VoiceIntents? = null
  private val gate = SteadyGate()
  private var seq: GreetingSequencer? = null
  private var ask: AskState? = null
  private val player by lazy { Player() }
  private var mic: Mic? = null
  private val endpointer = Endpointer()
  @Volatile private var injected: ShortArray? = null
  private var mountedAt = 0L
  private var finished = false
  private var steadyLogged = false
  private var lastHeadLog = 0L

  private class Plan(val dataset: String, val brief: First60Brief, val result: First60Brief.Result, val cues: List<Cue>, val askIndex: Int,
                     val totalMs: Long, val greeting: ShortArray, val thenYes: ShortArray, val thenNo: ShortArray, val reprompt: ShortArray,
                     val thenYesText: String, val thenNoText: String)

  private fun log(msg: String) = Log.i(First60.TAG, "$msg t_s=${"%.2f".format((SystemClock.elapsedRealtime() - mountedAt) / 1000.0)}")

  override fun onSceneReady() {
    if (!First60.on(s.isRecorded())) return
    val a = s.activity
    mountedAt = SystemClock.elapsedRealtime()
    gate.mount(mountedAt)
    log("event=mounted why=scene_ready")
    if (BuildConfig.DEBUG) a.registerReceiver(injectReceiver, IntentFilter("com.overhand.app.FIRST60_WAV"), Context.RECEIVER_EXPORTED)
    Thread({
      val p = runCatching { load(a) }.onFailure { Log.w(First60.TAG, "first60 off: $it", it) }.getOrNull() ?: return@Thread
      if (BuildConfig.VOICE) ears = runCatching {
        Class.forName("com.overhand.app.voice.Ears").getMethod("create", Context::class.java).invoke(null, a) as Transcriber?
      }.getOrNull()
      intents = runCatching { VoiceIntents.parse(a.resources.openRawResource(R.raw.voice_answers).bufferedReader().use { it.readText() }) }.getOrNull()
      s.ui.post {
        plan = p
        Log.i(First60.TAG, com.overhand.app.PrivateLog.first60Ready(p.dataset, p.result.decision?.projectName, p.result.decision?.decisionId,
          p.result.decision?.yesOption, p.cues[p.askIndex].endMs, ears != null, micAllowed()))
      }
    }, "first60-load").start()
    s.activity.systemManager.registerSystem(object : SystemBase() {
      var previous = 0L
      override fun execute() {
        val now = SystemClock.elapsedRealtime()
        if (s.paused || s.stopped || now - previous < 50) return
        previous = now
        head(now)
      }
    })
  }

  private fun load(a: Context): Plan {
    val dataset = s.recordedSource.dataset
    val cardsJson = JSONObject(a.resources.openRawResource(R.raw.first60_spoken_cards).bufferedReader().use { it.readText() }).getJSONObject("cards")
    val brief = First60Brief(cardsJson)
    val log = a.assets.open(if (dataset == "day") "judge/real_day.jsonl" else "judge/demo/$dataset/day.jsonl").bufferedReader().use { it.readText() }
    val result = brief.brief(First60Brief.parse(log), "recorded")
    val meta = JSONObject(a.resources.openRawResource(R.raw.first60_cues).bufferedReader().use { it.readText() })
    val set = meta.getJSONObject("sets").getJSONObject(dataset)
    val cuesJson = set.getJSONArray("cues")
    val cues = (0 until cuesJson.length()).map { i -> cuesJson.getJSONObject(i).let {
      Cue(it.getInt("i"), (it.getDouble("start_s") * 1000).toLong(), (it.getDouble("end_s") * 1000).toLong(), it.getString("text")) } }
    // The rendered audio must say what the port would say today.
    check(cues.map { it.text } == result.sentences) { "greeting audio is stale for $dataset: ${result.sentences}" }
    fun wav(name: String) = Wav.read(a.resources.openRawResource(a.resources.getIdentifier("first60_$name", "raw", a.packageName)).use { it.readBytes() }).samples
    return Plan(dataset, brief, result, cues, result.askIndex, (set.getDouble("seconds") * 1000).toLong(), wav("greeting_$dataset"),
      wav("then_yes_$dataset"), wav("then_no"), wav("reprompt"), brief.afterAnswer(result.decision, true), brief.afterAnswer(result.decision, false))
  }

  /** ECS thread, 20 Hz. */
  private fun head(now: Long) {
    if (finished || plan == null) return
    val h = s.head(); val f = h.q * Vector3(0f, 0f, 1f)
    val fw = doubleArrayOf(f.x.toDouble(), f.y.toDouble(), f.z.toDouble())
    val tracked = com.overhand.app.features.HeadSettle.tracked(
      com.overhand.app.features.V3(h.t.x.toDouble(), h.t.y.toDouble(), h.t.z.toDouble()), com.overhand.app.features.V3(fw[0], fw[1], fw[2]))
    val fired = gate.sample(now, fw, tracked)
    // Head pose was not logged on Nov 12; from now on every put-on leaves its trace (5 Hz until steady).
    if (!gate.steady && now - lastHeadLog >= 200) {
      lastHeadLog = now
      Log.i(First60.TAG, "head t_ms=${now - mountedAt} f=%.4f,%.4f,%.4f y=%.3f tracked=$tracked w=%.1f".format(fw[0], fw[1], fw[2], h.t.y, gate.speed))
    }
    if (fired && !steadyLogged) {
      steadyLogged = true
      log("event=steady reason=${gate.reason} w_deg_s=${"%.1f".format(gate.speed)} hold_ms=${gate.holdMs} max_deg_s=${gate.maxDegPerS}")
      s.recenter.centerAll("first60_steady")
      s.ui.post { begin() }
    }
  }

  /** The decision the card showed when the greeting started (#1281); answers are written for it only. */
  private var bound: OpeningBinding.Step.Speak? = null
  private var binding = 0

  /** UI thread: open the card, then play the greeting once the card shows its question ([bind]). */
  private fun begin() {
    val p = plan ?: return
    if (!s.interaction.decisionShowing) s.interaction.decisions.open()
    log("event=greeting_bind greeting=${p.result.decision?.decisionId}")
    bind(p, ++binding, SystemClock.elapsedRealtime())
  }

  /** #1281: one source of truth. The greeting's decision is put on the card; the cue waits for its options to be drawn. */
  private fun bind(p: Plan, token: Int, since: Long) {
    if (s.stopped || finished || token != binding || plan !== p) return
    val cards = s.interaction.decisions
    val shown = cards.shown()
    when (val st = OpeningBinding.step(p.result.decision?.decisionId, cards.openIds(), shown, SystemClock.elapsedRealtime() - since)) {
      OpeningBinding.Step.Wait -> s.ui.postDelayed({ bind(p, token, since) }, 20)
      is OpeningBinding.Step.Move -> { log("event=greeting_move from=${shown?.id} to=${st.id} ok=${cards.showDecision(st.id)}"); s.ui.postDelayed({ bind(p, token, since) }, 20) }
      is OpeningBinding.Step.Skip -> {
        log("event=greeting_skipped why=${st.why} greeting=${p.result.decision?.decisionId} shown=${shown?.id}")
        if (st.why != "card_not_ready") finished = true
      }
      is OpeningBinding.Step.Speak -> speak(p, st)
    }
  }

  private fun speak(p: Plan, st: OpeningBinding.Step.Speak) {
    bound = st
    ask = AskState(st.options, p.result.decision?.yesOption ?: "A").also { it.bind(effects) }
    seq = GreetingSequencer(p.cues, p.askIndex, p.totalMs)
    log("event=greeting_play card_options=${st.options.size} card=${st.id}")
    player.play(p.greeting, onFirstAudio = { s.ui.post { seq?.start(SystemClock.elapsedRealtime()); log("event=greeting_first_audio") } }) {}
    s.ui.post(ticker)
  }

  private val ticker = object : Runnable {
    override fun run() {
      if (s.stopped || finished) return
      val now = SystemClock.elapsedRealtime()
      seq?.tick(now)?.forEach(::apply)
      ask?.tick(now)
      // A pinch on an option is the card's own select: take it as the answer, by position (the paged card says "Select 2").
      val a = ask
      if (a != null && a.phase != AskState.Phase.ANSWERED && s.interaction.decisions.shown()?.id == bound?.id)
        optionButtons().indexOfFirst { it.isSelected }.takeIf { it >= 0 }?.let { i -> a.options.getOrNull(i) }?.let { label ->
          val id = a.idOf(label)
          log("event=pinch_select option=$id"); cut("pinch"); a.pinched(id)
        }
      s.ui.postDelayed(this, 20)
    }
  }

  private fun apply(e: GreetingSequencer.Event) = when (e) {
    is GreetingSequencer.Event.Sentence -> log("event=cue i=${e.cue.index} text=\"${e.cue.text}\"")
    GreetingSequencer.Event.AskEnded -> { log("event=ask_end"); ask?.questionEnded(SystemClock.elapsedRealtime()); Unit }
    GreetingSequencer.Event.Finished -> log("event=greeting_end")
    is GreetingSequencer.Event.Cut -> log("event=greeting_cut why=${e.why}")
  }

  private fun cut(why: String) {
    val sq = seq ?: return
    val now = SystemClock.elapsedRealtime()
    val ev = sq.cut(now, why)
    if (ev.isEmpty()) return
    player.fadeOut(GreetingSequencer.FADE_MS) { ms -> log("event=fade_done fade_ms=$ms") }
    ev.forEach(::apply)
  }

  private val effects = object : AskState.Effects {
    override fun openMic() {
      endpointer.reset()
      val inj = injected
      if (inj != null) { injected = null; feedInjected(inj); return }
      if (ears == null) { log("event=mic_skipped why=no_recogniser"); return }
      if (!micAllowed()) { log("event=mic_skipped why=no_permission"); return }
      mic = Mic { buf, n -> frames(buf, n, fromMic = true) }.also { it.start() }
      log("event=mic_open")
    }
    override fun closeMic() { mic?.stop(); mic = null; log("event=mic_closed") }
    override fun reprompt() { plan?.let { log("event=reprompt"); player.play(it.reprompt, {}) {} } }
    override fun otherSpeech(text: String) {
      val m = intents?.match(text); log("event=other_speech text=\"$text\" voice_answer=${m?.answer?.id}")
      // Voice C's scripted answer for it, through the same pre-rendered clips.
      m?.answer?.id?.let { id -> runCatching {
        val res = s.activity.resources.getIdentifier("voice_$id", "raw", s.activity.packageName)
        player.play(Wav.read(s.activity.resources.openRawResource(res).use { it.readBytes() }).samples, {}) {}
      } }
    }
    override fun answer(a: AskState.Answer) {
      log("event=answer option=${a.option} said_yes=${a.saidYes} input=${a.input} transcript=\"${a.transcript}\"")
      cut(a.input)
      confirm(a)
    }
  }

  /** Mic or injected frames, UI-independent thread. */
  private fun frames(buf: ShortArray, n: Int, fromMic: Boolean) {
    // Half duplex on the real mic: Flowstate must not hear its own voice ("Say yes, or pinch...").
    if (fromMic && player.busy) { endpointer.reset(); return }
    val wasSpeaking = endpointer.speaking
    val ended = endpointer.feed(buf, n)
    if (!wasSpeaking && endpointer.speaking) s.ui.post { log("event=speech_start"); cut("speech") }
    if (ended) {
      val u = endpointer.utterance ?: return
      val e = ears ?: return
      val endAt = SystemClock.elapsedRealtime()
      worker.execute {
        val text = runCatching { e.transcribe(FloatArray(u.size) { u[it] / 32768f }, 16000) }.getOrDefault("").trim()
        val ms = SystemClock.elapsedRealtime() - endAt
        s.ui.post { log("event=heard asr_ms=$ms audio_s=${"%.2f".format(u.size / 16000.0)} text=\"$text\""); ask?.heard(SystemClock.elapsedRealtime(), text) }
      }
    }
  }

  private fun feedInjected(pcm: ShortArray) {
    log("event=mic_open injected=true samples=${pcm.size}")
    Thread({
      // Real time, 50 ms frames, then a second of silence for the end of speech.
      val all = pcm + ShortArray(16000)
      var i = 0
      while (i < all.size && !s.stopped && ask?.phase == AskState.Phase.LISTENING) {
        val n = minOf(800, all.size - i); frames(all.copyOfRange(i, i + n), n, fromMic = false); i += n; Thread.sleep(50)
      }
    }, "first60-inject").start()
  }

  /** The card's own controls: click the option, then Confirm, exactly as a pinch on each would. */
  private fun confirm(a: AskState.Answer) {
    val shownId = s.interaction.decisions.shown()?.id
    if (shownId == null || shownId != bound?.id) { log("event=confirm_failed why=card_moved shown=$shownId card=${bound?.id}"); return }
    val i = ask?.let { q -> q.options.indexOfFirst { q.idOf(it) == a.option } } ?: -1
    val opt = optionButtons().getOrNull(i)
    if (opt == null) { log("event=confirm_failed why=no_option_${a.option}"); return }
    if (!opt.isSelected) opt.performClick()
    s.ui.postDelayed({
      val ok = find(root()) { it.tag == com.overhand.app.decisions.QuestionCard.TAG_CONFIRM }?.performClick() == true
      log("event=confirm_clicked ok=$ok option=${a.option} input=${a.input}")
      waitReceipt(a, SystemClock.elapsedRealtime())
    }, 60)
  }

  private fun waitReceipt(a: AskState.Answer, since: Long) {
    val saved = root()?.let { r -> findTag(r, "card-saved") } != null
    if (saved) {
      log("event=receipt option=${a.option} input=${a.input} ms_after_answer=${SystemClock.elapsedRealtime() - since}")
      val p = plan ?: return
      val (pcm, text) = if (a.saidYes) p.thenYes to p.thenYesText else p.thenNo to p.thenNoText
      player.play(pcm, onFirstAudio = { s.ui.post { log("event=then_start text=\"$text\"") } }) { s.ui.post { log("event=then_end"); finished = true } }
      return
    }
    if (SystemClock.elapsedRealtime() - since > 5000) { log("event=receipt_missing"); return }
    s.ui.postDelayed({ waitReceipt(a, since) }, 30)
  }

  private fun micAllowed() = s.activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

  private fun root(): View? {
    val e = s.interaction.decisionEntity ?: return null
    val f = s.activity.systemManager.findSystem<SceneObjectSystem>().getSceneObject(e) ?: return null
    if (!f.isDone || f.isCompletedExceptionally) return null
    return (f.getNow(null) as? PanelSceneObject)?.rootView
  }
  private fun optionButtons(): List<Button> = root()?.let { buttons(it) }.orEmpty().filter { it.tag == HandsOnboarding.OPTION_TAG }
  private fun buttons(v: View): List<Button> = when (v) {
    is Button -> listOf(v); is ViewGroup -> (0 until v.childCount).flatMap { buttons(v.getChildAt(it)) }; else -> emptyList()
  }
  private fun find(v: View?, p: (Button) -> Boolean): Button? = v?.let { buttons(it).firstOrNull(p) }
  private fun findTag(v: View, tag: String): View? {
    if (v.tag == tag) return v
    if (v is ViewGroup) for (i in 0 until v.childCount) findTag(v.getChildAt(i), tag)?.let { return it }
    return null
  }

  override fun onPause() {
    // Taken off mid-greeting: stop talking and wait for the next steady put-on.
    if (finished || !gate.mounted) return
    player.stop(); mic?.stop(); mic = null; seq = null; ask = null; bound = null; binding++; steadyLogged = false
    gate.unmount(); Log.i(First60.TAG, "event=unmounted")
  }
  override fun onResume() {
    if (finished || plan == null || gate.mounted) return
    mountedAt = SystemClock.elapsedRealtime(); gate.mount(mountedAt); log("event=mounted why=resume")
  }
  override fun onDestroy() {
    player.release(); mic?.stop(); worker.shutdown()
    if (BuildConfig.DEBUG) runCatching { s.activity.unregisterReceiver(injectReceiver) }
  }
  override fun onDebugIntent(intent: Intent) { intent.getStringExtra("first60_wav")?.let(::inject) }

  /** Debug builds: `am broadcast -a com.overhand.app.FIRST60_WAV --es path X` injects without pausing the activity. */
  private val injectReceiver = object : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { i.getStringExtra("path")?.let(::inject) }
  }

  private fun inject(path: String) {
    // Debug only: a WAV on the device as the wearer's speech at the open mic (queued until the question ends).
    run {
      val pcm = runCatching { Wav.read(java.io.File(path).readBytes()) }.getOrElse { Log.w(First60.TAG, "bad wav $path: $it"); return }
      if (pcm.sampleRate != 16000) { Log.w(First60.TAG, "need 16 kHz: $path"); return }
      s.ui.post {
        log("event=inject path=$path phase=${ask?.phase}")
        if (ask?.phase == AskState.Phase.LISTENING) { mic?.stop(); mic = null; endpointer.reset(); feedInjected(pcm.samples) } else injected = pcm.samples
      }
    }
  }
}

/** One stream for the greeting and the lines after it; [fadeOut] ramps the volume to 0 and stops. */
private class Player {
  @Volatile private var generation = 0
  @Volatile var busy = false; private set
  private val track = AudioTrack.Builder()
    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
    .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
    .setTransferMode(AudioTrack.MODE_STREAM).setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
    .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), 3200)).build()

  fun play(pcm: ShortArray, onFirstAudio: () -> Unit, onDone: () -> Unit) {
    val g = ++generation
    Thread({
      synchronized(track) {
        if (g != generation) return@Thread
        busy = true
        track.pause(); track.flush(); track.setVolume(1f); track.play()
        val start = track.playbackHeadPosition
        var off = 0; var reported = false
        while (g == generation && (off < pcm.size || track.playbackHeadPosition - start < pcm.size)) {
          if (off < pcm.size) {
            val n = track.write(pcm, off, minOf(800, pcm.size - off), AudioTrack.WRITE_NON_BLOCKING)
            if (n < 0) break
            off += n
            if (n == 0) Thread.sleep(2)
          } else Thread.sleep(10)
          if (!reported && track.playbackHeadPosition != start) { reported = true; onFirstAudio() }
        }
        if (g == generation) { track.pause(); busy = false; onDone() }
      }
    }, "first60-out").start()
  }

  /** Volume ramp to 0 in 10 ms steps, then stop. Reports the measured fade time. */
  fun fadeOut(ms: Long, done: (Long) -> Unit) {
    val g = generation
    Thread({
      val t0 = SystemClock.elapsedRealtime(); val steps = (ms / 10).coerceAtLeast(1)
      for (i in 1..steps) { if (g != generation) break; track.setVolume(1f - i.toFloat() / steps); Thread.sleep(10) }
      if (g == generation) stop()
      done(SystemClock.elapsedRealtime() - t0)
    }, "first60-fade").start()
  }

  fun stop() { generation++; busy = false; runCatching { track.pause(); track.flush() } }
  fun release() { stop(); synchronized(track) { track.release() } }
}

/** 50 ms PCM16 frames from the microphone until stopped. */
@SuppressLint("MissingPermission") // First60Feature checks RECORD_AUDIO first.
private class Mic(private val sink: (ShortArray, Int) -> Unit) {
  @Volatile private var running = true
  private val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO,
    AudioFormat.ENCODING_PCM_16BIT, maxOf(AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 6400))
  private val thread = Thread({
    val frame = ShortArray(800)
    record.startRecording()
    while (running) { val n = record.read(frame, 0, frame.size); if (n > 0) sink(frame, n) }
    record.stop(); record.release()
  }, "first60-mic")
  fun start() = thread.start()
  fun stop() { running = false; thread.join(300) }
}
