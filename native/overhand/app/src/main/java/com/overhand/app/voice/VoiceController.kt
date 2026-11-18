package com.overhand.app.voice

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.overhand.app.BuildConfig
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt
import com.overhand.app.hostlink.project.NewProjectFlow
import com.overhand.app.hostlink.project.FollowUpFlow

/** Voice C switch. On only in Recorded, and only when the recogniser is in the build and its model is on the device. */
object Voice {
  const val ENABLED = true
  fun on(recorded: Boolean, earsPresent: Boolean) = ENABLED && recorded && earsPresent
  const val TAG = "OverhandVoice"
  const val PERMISSION_REQUEST = 756
  /** Shown before the system asks, so the judge knows why. #1001 always-on; #1032/#1056 Smart answers. */
  const val PERMISSION_REASON = "To hear your questions, Flowstate listens while you wear the headset and voice is not muted, and understands speech on the headset. " +
    "With Smart answers on, a question meant for the app that it cannot answer itself is sent as text with a short text summary of the screen, never audio or images. " +
    "Turn Smart answers off in Settings to keep everything on the headset."
  /** #1032: shown after a question that left the headset. */
  const val ASKED_ONLINE = "  · asked online"
  const val IDLE_HINT = "Just ask about this day"
  /** #1070: the idle hint while "Answer to my name" is on, and the line the help answer's caption adds. */
  const val NAME_HINT = "Start with Flowstate to get my attention"
  const val NAME_TIP = "Start with \"Flowstate\" to get my attention."
  /** #1070: Settings "Answer to my name" (prefs key in [PREFS]); on by default, off is nov14b exactly. */
  const val NAME_PREF = "answer-to-name"
  const val MUTED_HINT = "Muted. Tap the mic to listen again."
  /** #1051: the one chip shown when a line was probably, not surely, meant for the app. */
  const val DO_IT = "Do it?"
  /** Saying the offered line again within this long counts as pressing the chip. */
  const val OFFER_MS = 10_000L
  /** #1001 caption window: what was heard (dimmed when it is not for the app) and what was answered. */
  const val LISTENING = "Listening"
  const val MUTED = "Muted"
  /** #1206: the microphone permission is refused: say so, and name the path that still works. */
  const val MIC_OFF = "Mic off"
  const val MIC_OFF_HINT = "Microphone not allowed. Everything works by hand."
  const val PREFS = "voice-v1"
}

/** A running microphone thread. */
interface Mic { fun start(); fun stop(); val alive: Boolean }

/** Where replies are spoken. */
interface ReplyOut {
  fun play(pcm: ShortArray, onFirstAudio: (Long) -> Unit, onDone: () -> Unit)
  fun stop()
  fun release()
}

/** Reads 20 ms PCM16 frames from the microphone into [sink] until stopped. #1001: stopping ends the thread and frees the mic. */
@SuppressLint("MissingPermission") // VoiceController checks RECORD_AUDIO before constructing one.
internal class MicRecorder(private val sink: (ShortArray, Int) -> Unit) : Mic {
  @Volatile private var running = true
  private val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
    AudioFormat.ENCODING_PCM_16BIT, maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), RATE / 5 * 2))
  private val thread = Thread({
    val frame = ShortArray(RATE / 50)
    try {
      if (running) record.startRecording()
      while (running) { val n = record.read(frame, 0, frame.size); if (n > 0 && running) sink(frame, n) }
    } finally { runCatching { record.stop() }; record.release() }
  }, "overhand-mic")
  override fun start() = thread.start()
  override val alive get() = thread.isAlive
  /** Stop unblocks read; join guarantees that no audio is read after this returns. */
  override fun stop() { running = false; runCatching { record.stop() }; thread.join() }
  companion object { const val RATE = 16000 }
}

/** One low-latency stream; each play() cancels the previous one. Reports when the first frame actually left the mixer. */
internal class VoicePlayer(private val rate: Int, private val level: (Float) -> Unit = {}) : ReplyOut {
  @Volatile private var generation = 0
  private val track = AudioTrack.Builder()
    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
    .setTransferMode(AudioTrack.MODE_STREAM).setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
    .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), rate / 5 * 2)).build()

  override fun play(pcm: ShortArray, onFirstAudio: (Long) -> Unit, onDone: () -> Unit) {
    val g = ++generation
    Thread({
      synchronized(track) {
        if (g != generation) return@Thread
        track.pause(); track.flush(); track.play()
        val start = track.playbackHeadPosition
        var off = 0; var reported = false
        while (off < pcm.size && g == generation) {
          val n = track.write(pcm, off, minOf(1024, pcm.size - off), AudioTrack.WRITE_NON_BLOCKING)
          if (n < 0) break
          if (n > 0) level(voiceLevel(pcm, off, n))
          off += n
          if (!reported && track.playbackHeadPosition != start) { reported = true; onFirstAudio(SystemClock.elapsedRealtimeNanos()) }
          if (n == 0) Thread.sleep(2)
        }
        while (g == generation && track.playbackHeadPosition - start < pcm.size) {
          if (!reported && track.playbackHeadPosition != start) { reported = true; onFirstAudio(SystemClock.elapsedRealtimeNanos()) }
          Thread.sleep(if (reported) 20 else 2)
        }
        if (g == generation) { track.pause(); level(0f); onDone() }
      }
    }, "overhand-voice-out").start()
  }
  override fun stop() { generation++; track.pause(); track.flush(); level(0f) }
  override fun release() { stop(); synchronized(track) { track.release() } }
}

/** RMS from actual PCM only; no clock, oscillator or decorative animation. */
internal fun voiceLevel(pcm: ShortArray, offset: Int, count: Int): Float {
  if (count <= 0) return 0f
  var energy = 0.0
  for (i in offset until offset + count) energy += pcm[i].toDouble() * pcm[i]
  return (sqrt(energy / count) / 8192.0).toFloat().coerceIn(0f, 1f)
}

enum class AssistantState { Listening, Speaking, Confirm, Muted }

/** Assistant.dc.html: 76 dp circle, 2 dp strokes, cyan, caption white at 70%. */
internal class MicRing(context: Context) : View(context) {
  var state = MicState.IDLE; set(v) { field = v; invalidate() }
  var muted = false; set(v) { field = v; invalidate() }
  var level = 0f; set(v) { field = v.coerceIn(0f, 1f); invalidate() }
  private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
  override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
  override fun onDraw(c: Canvas) {
    c.save(); c.scale(width / 92f, height / 92f)
    if (isPressed) { p.style = Paint.Style.FILL; p.color = 0x475ce1e6; c.drawCircle(46f,46f,38f,p) }
    p.style = Paint.Style.STROKE; p.strokeWidth = 2f
    p.color = if (muted && !isHovered && !isFocused) 0x38ffffff else 0xff5ce1e6.toInt()
    c.drawCircle(46f,46f,38f,p)
    if (!muted && state != MicState.SPEAKING && level > 0f) {
      p.color = 0x2e5ce1e6; p.strokeWidth = 2f + 6f * level
      c.drawCircle(46f,46f,40f + 3f * level,p)
    }
    p.strokeWidth = 2f; p.color = if(muted && !isHovered && !isFocused) 0xb3ffffff.toInt() else 0xff5ce1e6.toInt()
    if (!muted && state == MicState.SPEAKING) {
      for (i in 0..4) {
        val h = 2f + level * listOf(7f,16f,24f,14f,7f)[i]
        val x = 28f + i * 9f
        c.drawLine(x,46f-h,x,46f+h,p)
      }
    } else {
      c.drawRoundRect(40f,28f,52f,48f,6f,6f,p)
      c.drawArc(RectF(34f,38f,58f,58f),0f,180f,false,p)
      c.drawLine(46f,58f,46f,65f,p)
      if(muted) c.drawLine(28f,28f,64f,64f,p)
    }
    c.restore()
  }
}

/**
 * Voice on Android, #1001: always listening while worn and unmuted. [VoiceLoop] finds each turn by voice activity
 * (no pinch); every final transcript goes to [onFinalTranscript]; only a known command or a confident question acts.
 * The caption window ([view]) shows the words as they arrive, the final line, and the answer; it shrinks to a strip
 * after 6 s idle. Muted means the microphone thread is stopped; so do asleep, paused and closed (#862).
 * [ears] is the recogniser. The ECS frame calls [hands] (~20 Hz), which also re-checks whether to listen.
 */
class VoiceController(private val activity: Activity, private val intents: VoiceIntents, private val ears: Transcriber,
                      private val audioFor: (String) -> ShortArray, rate: Int) {
  private val worker = Executors.newSingleThreadExecutor()
  /** Tests swap these for fakes; the app uses the real microphone and speaker. */
  internal var player: ReplyOut = VoicePlayer(rate) { level -> activity.runOnUiThread { if (!muted) ring.level = level } }
  internal var newMic: ((ShortArray, Int) -> Unit) -> Mic = { sink -> MicRecorder(sink) }
  private val pinch = PinchHold()
  private val prefs = activity.getSharedPreferences(Voice.PREFS, Context.MODE_PRIVATE)
  private var mic: Mic? = null
  private var askedPermission = false
  /** #1230: a denial result came back for our request (also how a "don't ask again" system answers at once). Asked alone is not refused. */
  private var refusedPermission = false
  @Volatile private var injecting = false
  @Volatile private var minimumAudioTurn = 0
  @Volatile private var paused = false
  private var closed = false
  /** When the current turn's final transcript arrived (elapsedRealtimeNanos), for reply latency. */
  @Volatile private var finalAt = 0L

  /** #1001: remembered across launches. */
  @Volatile var muted = prefs.getBoolean("muted", false); private set
  /** #1070: a line that names the app takes the pinch path ([VoiceGate.decideNamed]); false = nov14b. */
  var answerToName = prefs.getBoolean(Voice.NAME_PREF, true)
    set(v) { field = v; prefs.edit().putBoolean(Voice.NAME_PREF, v).apply(); machine.helpTip = if (v) Voice.NAME_TIP else null; activity.runOnUiThread { header() } }
  /** #862: false while the headset is not being worn (asleep): then the mic is off. */
  var worn: () -> Boolean = { true }
  /** Called when a turn starts, so the window can come into view if it was moved away. */
  var onTurnStart: () -> Unit = {}
  /** #815 host mode: every transcript goes here first (the project flow has its own "new project" + confirm grammar). */
  private var intercept: ((String) -> Boolean)? = null
  /** #815: the idle words; host mode asks for a new project instead of questions about the recorded day. */
  var idleHint = Voice.IDLE_HINT

  // Caption window: header (mic icon + one word, the mute control), up to 2 lines heard, up to 3 lines answered.
  val view: FrameLayout
  private val card: LinearLayout
  private val ring = MicRing(activity)
  private val word = text(40f, 0xfff2f6fa.toInt()).apply { typeface = android.graphics.Typeface.create(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL),600,false); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
  private val hint = text(30f, 0xb3ffffff.toInt()).apply { visibility = View.GONE }
  private val heardLine = text(30f, 0xb3ffffff.toInt()).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
  private val replyLine = text(30f, 0xfff2f6fa.toInt()).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
  private val shrink = Runnable { if (assistantState != AssistantState.Confirm && assistantState != AssistantState.Speaking) { heardLine.visibility = View.GONE; replyLine.visibility = View.GONE; hint.visibility = View.GONE } }
  /** Timers run on the main looper, not view.post: an unattached view (panel not yet shown) never runs its queue. */
  private val ui = android.os.Handler(android.os.Looper.getMainLooper())
  private var lastHeard = ""
  private val clearDim = Runnable { lastHeard = ""; heardLine.text = ""; heardLine.alpha = 1f }

  val loop: VoiceLoop = VoiceLoop(ears, { r -> worker.execute(r) }, object : VoiceLoop.Listener {
    override fun speechStart(turn: Int, atSample: Long) {
      Log.i(Voice.TAG, "turn_start turn=$turn")
      activity.runOnUiThread { if (muted || closed) return@runOnUiThread; machine.listen(); onTurnStart(); caption(heard = "…", dim = false) }
    }
    override fun partial(turn: Int, text: String) {
      activity.runOnUiThread { if (!muted && !closed && machine.state == MicState.LISTENING) caption(heard = text, dim = false) }
    }
    override fun final(turn: Int, text: String, endSample: Long, voicedEndSample: Long, decodeMs: Long) {
      val conf = VoiceGate.confidence(text)
      asrMs = decodeMs; thinkingMs = null
      Log.i(Voice.TAG, "heard turn=$turn asr_ms=$decodeMs conf=${"%.2f".format(conf)}" + (if (BuildConfig.DEBUG) " text=\"$text\"" else ""))
      activity.runOnUiThread { if (!muted && !closed && turn == loop.turn && turn >= minimumAudioTurn) { machine.endOfSpeech(); onFinalTranscript(text, conf) } }
    }
    override fun ended(turn: Int) {
      activity.runOnUiThread { machine.endOfSpeech() }
    }
    override fun dropped(turn: Int) { activity.runOnUiThread { machine.drop() } }
  })
  /**
   * #1032: the relay route; null = scripted answers only (nothing leaves the headset). #1056: reached only through
   * [onFinalTranscript]'s gate: a line it drops or offers "Do it?" for never gets here.
   */
  var smart: SmartVoice? = null
    set(v) { field = v; if (v != null && v.userOn() && v.speaker.ready) relayExec.execute { v.relay.probe() } }
  private val relayExec = Executors.newSingleThreadExecutor()
  private var asrMs: Long? = null
  private var thinkingMs: Long? = null
  /** #1035: talk to an agent on the build site; null = off. [talkSpeaker] reads its answers aloud. */
  var agents: AgentVoice? = null
  var talkSpeaker: Speaker? = null
  /** #1035: the site's target changed: the idle hint names it (UI thread). */
  fun refreshHint() = header()
  /** Relay questions asked (tests: the gate must keep this at 0 for dropped and offered lines). */
  @Volatile var relayAsks = 0; private set
  /** #1002: spoken commands that run the interface; null = answers only. */
  var commands: UiCommands? = null
  /** Lines the app itself speaks; heard back they are dropped before anything can act on them ([VoiceGate.isOwnLine]). */
  var ownLines: List<String> = intents.answers.map { it.text }
  /** "Which one?" choices, pinchable. */
  private val choiceRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
  /** #1051: the line a "Do it?" chip is waiting on. */
  private class Offer(val text: String, val decision: VoiceGate.Decision, val atMs: Long, val named: Boolean = false)
  private var offer: Offer? = null
  private val doIt = pill("Start", filled = true).apply {
    visibility = View.GONE
    setOnClickListener { if (muted) return@setOnClickListener; offer?.let { o -> offer = null; showOffer(false); Log.i(Voice.TAG, "voice confirm_yes turn=${machine.turn} cmd=${o.decision.target} by=chip")
      perform(o.decision, o.text, machine.turn, fromTurn = false) } }
  }
  private fun showOffer(on: Boolean) {
    doIt.visibility = if (on && !muted) View.VISIBLE else View.GONE
    discard.visibility = doIt.visibility
    header()
  }
  private val discard = pill("Discard").apply {
    visibility = View.GONE
    setOnClickListener { offer = null; showOffer(false); header(); Log.i(Voice.TAG, "voice event=discard why=button") }
  }
  private val projectRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
  private var projectKey: String? = null
  private var projectStart: (() -> Unit)? = null
  private var projectDiscard: (() -> Unit)? = null
  private var projectLine = ""
  private val projectStartButton = pill("Start", filled = true).apply { tag = "assistant-start"; setOnClickListener { if (!muted) projectStart?.invoke(); Log.i(Voice.TAG, "voice event=start why=button") } }
  private val projectDiscardButton = pill("Discard").apply { tag = "assistant-discard"; setOnClickListener { if (!muted) projectDiscard?.invoke(); Log.i(Voice.TAG, "voice event=discard why=button") } }
  var assistantState = AssistantState.Listening; private set
  /** Subject from real current context for spoken answers. */
  var answerSubject: () -> String = { machine.lastMatch?.answer?.id?.replace('_', ' ') ?: "Answer" }

  /** Bind the same actions as the project chips; no alternate send path. Called on UI thread. */
  fun projectConfirmation(new: NewProjectFlow.State, follow: FollowUpFlow.State, action: (String) -> Unit) {
    val n = new as? NewProjectFlow.State.Chip
    val f = follow as? FollowUpFlow.State.Chip
    val key = f?.let { "follow:${it.projectId}:${it.text}" } ?: n?.let { "new:${it.description}" }
    if (key == projectKey) return
    projectKey = key; projectLine = f?.text ?: n?.description ?: ""
    projectStart = key?.let { { action(if (f != null) "followup_send" else "start") } }
    projectDiscard = key?.let { { action(if (f != null) "followup_discard" else "discard") } }
    if (key != null) { offer = null; showOffer(false); ui.removeCallbacks(shrink) }
    else idleSoon()
    header()
  }

  private fun pill(label: String, filled: Boolean = false) = TextView(activity).apply {
    text = label; textSize = 30f; gravity = Gravity.CENTER; isClickable = true; isFocusable = true
    @SuppressLint("WrongConstant") // Weight overload (API 28); minSdk 34 guarantees availability, 500/600 are weights.
    val weightedTypeface = android.graphics.Typeface.create(android.graphics.Typeface.create("sans-serif",0),if (filled) 600 else 500,false)
    typeface = weightedTypeface
    setTextColor(if (filled) 0xff06222a.toInt() else 0xfff2f6fa.toInt())
    val d = resources.displayMetrics.density
    setPadding((24*d).toInt(),0,(24*d).toInt(),0)
    minHeight = (72*d).toInt()
    background = android.graphics.drawable.StateListDrawable().apply {
      fun face(color: Int, focus: Boolean = false) = GradientDrawable().apply { cornerRadius = 999*d; setColor(color); setStroke((2*d).toInt(), if (filled || focus) 0xff5ce1e6.toInt() else 0x38ffffff) }
      addState(intArrayOf(android.R.attr.state_pressed), face(0x475ce1e6,true))
      addState(intArrayOf(android.R.attr.state_hovered), face(if (filled) 0xff5ce1e6.toInt() else Color.TRANSPARENT,true))
      addState(intArrayOf(android.R.attr.state_focused), face(if (filled) 0xff5ce1e6.toInt() else Color.TRANSPARENT,true))
      addState(intArrayOf(), face(if (filled) 0xff5ce1e6.toInt() else Color.TRANSPARENT))
    }
    layoutParams = LinearLayout.LayoutParams(-2,(72*d).toInt()).apply { marginEnd = (16*d).toInt() }
  }
  private fun brief(line: String, limit: Int): String {
    val words = line.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return words.take(limit).joinToString(" ") + if (words.size > limit) "…" else ""
  }

  private fun command(text: String): String? {
    val r = commands?.handle(text) ?: return null
    Log.i(Voice.TAG, "command " + (if (BuildConfig.DEBUG) "text=\"$text\" " else "") + "action=${r.action} choices=${r.choices.size}" + (if (BuildConfig.DEBUG) " slot=${r.slot} caption=\"${r.caption}\"" else ""))
    showChoices(r)
    return r.caption.ifEmpty { null }
  }
  private fun showChoices(r: UiResult) {
    choiceRow.removeAllViews()
    r.choices.forEachIndexed { i, c -> choiceRow.addView(pill(brief(c.label, 2)).apply {
      setOnClickListener { if (!muted) commands?.choose(i)?.let { done -> caption(heard = lastHeard.trim('“', '”'), dim = false, reply = done.caption); choiceRow.removeAllViews() } }
    }) }
  }

  val machine: VoiceMachine = VoiceMachine(intents, object : VoiceMachine.Effects {
    override fun ensurePermission() = true
    override fun startMic() {}
    override fun stopMic() {}
    override fun recognise(turn: Int) {}
    override fun play(answer: VoiceAnswer) {
      val turn = machine.turn; val t0 = finalAt
      val m = machine.lastMatch
      Log.i(Voice.TAG, "answer turn=$turn id=${answer.id} score=${m?.score} kind=${m?.kind} lex=${"%.2f".format(m?.lex ?: 0.0)}")
      loop.deafen(true) // #1001: never hear our own reply (nor for 300 ms after it)
      player.play(audioFor(answer.id),
        onFirstAudio = { t -> Log.i(Voice.TAG, "first_audio turn=$turn id=${answer.id} reply_start_ms=${"%.1f".format((t - t0) / 1e6)}") },
        onDone = { loop.deafen(false); activity.runOnUiThread { machine.playbackDone(turn) } })
    }
    override fun stopPlayback() { player.stop(); smart?.speaker?.stop(); talkSpeaker?.takeIf { it !== smart?.speaker }?.stop(); loop.deafen(false) }
    override fun show(state: MicState, transcript: String, reply: String) {
      ring.state = state
      if (state == MicState.THINKING && thinkingMs == null) thinkingMs = (SystemClock.elapsedRealtimeNanos() - finalAt) / 1_000_000
      if (state == MicState.SPEAKING) caption(heard = transcript, dim = false, reply = reply, online = machine.askedOnline)
      header()
    }
  })

  init {
    machine.helpTip = if (answerToName) Voice.NAME_TIP else null
    card = LinearLayout(activity).apply {
      // look_nov17 s2 + Assistant.dc.html: glass, 32 dp radius, 2 dp edge; 28/24 dp inset, 22 dp gap.
      val d = resources.displayMetrics.density
      orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP
      setPadding((28*d).toInt(),(24*d).toInt(),(28*d).toInt(),(24*d).toInt())
      background = object : GradientDrawable() {
        init { setColor(0xa60c121a.toInt()); cornerRadius = 32*d; setStroke((2*d).toInt(),0x26ffffff) }
        override fun draw(canvas: Canvas) {
          super.draw(canvas)
          // look_nov17 s2: top edge white at 30%, remaining edge at 15%.
          canvas.save(); canvas.clipRect(0f,0f,bounds.width().toFloat(),2*d)
          val top = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2*d; color = 0x4dffffff }
          canvas.drawRoundRect(RectF(d,d,bounds.width()-d,bounds.height()-d),32*d,32*d,top); canvas.restore()
        }
      }
      ring.tag = "transcript-mic"; ring.isFocusable = true
      ring.setOnClickListener { setMuted(!muted, "button") }
      addView(ring, LinearLayout.LayoutParams((92*d).toInt(),(92*d).toInt()).apply { marginEnd = (22*d).toInt() })
      addView(LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(word); addView(hint); addView(replyLine); addView(heardLine); addView(choiceRow)
        addView(LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; addView(doIt); addView(discard) })
        projectRow.addView(projectStartButton); projectRow.addView(projectDiscardButton); addView(projectRow)
      }, LinearLayout.LayoutParams(0,-2,1f))
    }
    view = FrameLayout(activity).apply {
      addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }
    // #1056: no machine.command: the gate below has already run the command table; a second pass would bypass it.
    machine.smart = { turn, text, _ -> startSmart(turn, text) }
    // #1035: an agent targeted when speech starts takes the turn; its answer is captioned and read aloud.
    machine.talkLock = { agents?.lock() != null }
    machine.talkEnd = { agents?.end(); ui.post { header() } }
    machine.talk = { turn, text -> startTalk(turn, text) }
    machine.reset(); header(); idleSoon()
    synchronized(Voice) { live++ }
  }

  private fun startTalk(turn: Int, text: String): Boolean {
    val a = agents ?: return false
    val sp = talkSpeaker
    sp?.observeLevel { level -> activity.runOnUiThread { if (!muted) ring.level = level } }
    var spoke = false
    return a.take(text, speak = { s -> activity.runOnUiThread {
      if (machine.smartSentence(turn, s)) {
        caption(heard = text, dim = false, reply = machine.reply)
        if (sp?.ready == true) { spoke = true; loop.deafen(true); sp.say(turn, s) {} }
      }
    } }, done = { activity.runOnUiThread {
      if (spoke) sp!!.end(turn) { loop.deafen(false); activity.runOnUiThread { machine.playbackDone(turn) } }
      else ui.postDelayed({ machine.playbackDone(turn) }, 1_500)
    } })
  }

  /**
   * #1032: asks the relay for [turn]; false = not asked (switch off, relay not set up, no speech engine), so the scripted
   * line plays at once. Every failure after asking also plays the scripted line, with no error shown. The answer only
   * reaches the caption and the speaker, never [commands].
   */
  private fun startSmart(turn: Int, text: String): Boolean {
    val v = smart ?: return false
    v.speaker.observeLevel { level -> activity.runOnUiThread { if (!muted) ring.level = level } }
    if (!v.on()) {
      Log.i(Voice.TAG, SmartRouter.logLine(turn, Route.FALLBACK, asrMs, thinkingMs, null, null, v.offReason(), 0, 0)); return false
    }
    val t0 = finalAt
    relayAsks++
    var first: Long? = null
    var firstAudio: Long? = null
    var gotSentence = false
    ui.postDelayed({
      if (!gotSentence && machine.turn == turn && machine.state == MicState.THINKING) {
        Log.i(Voice.TAG, SmartRouter.logLine(turn, Route.FALLBACK, asrMs, thinkingMs, null, null, SmartFail.TIMEOUT, 0, 0))
        machine.smartFallback(turn)
      }
    }, Smart.FIRST_CHUNK_TIMEOUT_MS)
    val screen = runCatching { v.screen() }.getOrDefault("")
    relayExec.execute {
      val res = v.relay.ask(text, screen, cancelled = { machine.turn != turn }) { s ->
        if (first == null) first = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000
        activity.runOnUiThread {
          if (machine.smartSentence(turn, s)) {
            gotSentence = true
            loop.deafen(true) // never hear the spoken smart answer
            v.speaker.say(turn, s) { t -> firstAudio = (t - t0) / 1_000_000 }
          }
        }
      }
      activity.runOnUiThread {
        if (res.spoken.isEmpty()) {
          Log.i(Voice.TAG, SmartRouter.logLine(turn, Route.FALLBACK, asrMs, thinkingMs, first, null, res.fail, 0, res.dropped))
          machine.smartFallback(turn)
        } else v.speaker.end(turn) {
          Log.i(Voice.TAG, SmartRouter.logLine(turn, Route.RELAY, asrMs, thinkingMs, first, firstAudio, res.fail, res.spoken.size, res.dropped))
          loop.deafen(false); activity.runOnUiThread { machine.playbackDone(turn) }
        }
      }
    }
    return true
  }

  /** #815/#862 host mode: every transcript goes to [intercept] (the project flow); idle words ask for a new project. */
  fun routeTo(intercept: (String) -> Boolean, hint: String) { this.intercept = intercept; idleHint = hint; machine.reset(); header() }

  /**
   * #1001: the one entry point for a final transcript. [confidence] is [VoiceGate.confidence] (said to the app or not).
   * "mute" mutes; host mode hands the words to the project flow; the [commands] table may take it; a question about
   * the day is answered only when confident AND matched; anything else is shown dimmed for 3 s and dropped.
   * Returns true when it acted.
   */
  fun onFinalTranscript(text: String, confidence: Double): Boolean {
    if (muted || closed) return false // Late decode, injected text and host commands cannot bypass mute.
    val t = text.trim()
    finalAt = SystemClock.elapsedRealtimeNanos()
    val turn = machine.turn
    if (t.isEmpty()) { machine.drop(); return false }
    // #1187: a lag check is measuring: the line was decoded (the load stays), but nothing acts and nothing is said.
    if (lagCheck) { Log.i(Voice.TAG, "drop turn=$turn lagcheck"); machine.drop(); caption(heard = t, dim = true); return false }
    // #1080: with "Answer to my name" on, an unnamed "mute" only shows the silent chip, like any other unnamed line.
    if (VoiceGate.isMute(t) && !(answerToName && VoiceGate.named(t) == null)) {
      Log.i(Voice.TAG, "act turn=$turn route=mute"); machine.drop(); caption(heard = t, dim = false); setMuted(true, "voice"); return true
    }
    if (VoiceGate.isOwnLine(t, ownLines)) {
      Log.i(Voice.TAG, "drop turn=$turn own_line"); machine.drop(); caption(heard = t, dim = true); return false
    }
    // #1035: an agent was targeted when this speech started: the words go to it and nowhere else (no command runs).
    if (machine.talking && machine.talkTurn(turn, t)) {
      Log.i(Voice.TAG, "act turn=$turn route=talk"); offer = null; showOffer(false); caption(heard = t, dim = false, reply = ""); return true
    }
    intercept?.let { f -> if (f(t)) { Log.i(Voice.TAG, "act turn=$turn route=host"); offer = null; machine.drop(); caption(heard = t, dim = false); return true } }
    // A "Which one?" the table just asked is answered by the next line ("the second one"), as before.
    if (commands?.asking == true) command(t)?.let { r ->
      Log.i(Voice.TAG, "act turn=$turn route=command"); machine.drop(); caption(heard = t, dim = false, reply = r); return true
    }
    // #1070: a line that names the app ("Flowstate, what needs me?") takes the pinch path, without the name.
    val rest = if (answerToName) VoiceGate.named(t) else null
    // #1080: "yes" (named or not) accepts a "Did you mean ...?" the app asked within OFFER_MS; the silent chip takes a press only.
    offer?.takeIf { it.named && SystemClock.elapsedRealtime() - it.atMs <= Voice.OFFER_MS && VoiceGate.isYes(rest ?: t) }?.let { o ->
      offer = null; showOffer(false); Log.i(Voice.TAG, "voice confirm_yes turn=$turn cmd=${o.decision.target} by=yes")
      return perform(o.decision, o.text, turn, fromTurn = true)
    }
    if (rest != null) {
      // #1080: ask when unsure ("Did you mean ...?") instead of acting wrongly; an open question goes to the answer path.
      val d = VoiceGate.decideCalled(intents, rest, commands)
      val was = offer; offer = null; showOffer(false)
      Log.i(Voice.TAG, "voice named turn=$turn route=${d.route} cmd=${d.target} cov=${"%.2f".format(d.coverage)} margin=${"%.2f".format(d.margin)} why=${d.why}")
      if (was != null && was.named && d.route != VoiceGate.Route.DROP && d.target == was.decision.target &&
        SystemClock.elapsedRealtime() - was.atMs <= Voice.OFFER_MS) {
        Log.i(Voice.TAG, "voice confirm_yes turn=$turn cmd=${d.target} by=repeat"); return perform(was.decision, was.text, turn, fromTurn = true)
      }
      when (d.route) {
        VoiceGate.Route.MUTE -> { Log.i(Voice.TAG, "act turn=$turn route=mute"); machine.drop(); caption(heard = t, dim = false); setMuted(true, "voice"); return true }
        VoiceGate.Route.COMMAND, VoiceGate.Route.ANSWER -> return perform(d, rest, turn, fromTurn = true)
        VoiceGate.Route.CONFIRM -> {
          Log.i(Voice.TAG, "voice named_offer turn=$turn cmd=${d.target}")
          offer = Offer(rest, d, SystemClock.elapsedRealtime(), named = true)
          machine.drop(); caption(heard = t, dim = false, reply = "Did you mean ${VoiceGate.meaning(d)}?"); showOffer(true)
          return false
        }
        else -> { machine.drop(); caption(heard = t, dim = true); return false }
      }
    }
    // #1080: "Answer to my name" on: an unnamed line never acts. Where the old gate would have acted, only the "Do it?"
    // chip shows, silently, and goes by itself; the old gate's own offers are dropped; a repeat does not confirm.
    if (answerToName) {
      val d = if (VoiceGate.isMute(t)) VoiceGate.Decision(VoiceGate.Route.MUTE, confidence, null, why = "exact") else VoiceGate.decide(intents, t, commands = commands)
      offer = null; showOffer(false)
      if (d.route == VoiceGate.Route.COMMAND || d.route == VoiceGate.Route.ANSWER || d.route == VoiceGate.Route.MUTE) {
        Log.i(Voice.TAG, "voice unnamed_chip turn=$turn cmd=${d.target} cov=${"%.2f".format(d.coverage)} why=${d.why}")
        val o = Offer(t, d, SystemClock.elapsedRealtime()); offer = o
        machine.drop(); caption(heard = t, dim = true); showOffer(true)
        ui.postDelayed({ if (offer === o) { offer = null; showOffer(false) } }, Voice.OFFER_MS)
        return false
      }
      Log.i(Voice.TAG, "drop turn=$turn unnamed route=${d.route} cmd=${d.target} why=${d.why}")
      machine.drop(); caption(heard = t, dim = true)
      return false
    }
    // #1051: coverage + confirm-when-unsure over the command table and the answers (VoiceGate.decide).
    val d = VoiceGate.decide(intents, t, commands = commands)
    val was = offer; offer = null; showOffer(false)
    val repeated = was != null && d.route != VoiceGate.Route.DROP && d.target == was.decision.target &&
      SystemClock.elapsedRealtime() - was.atMs <= Voice.OFFER_MS
    if (repeated) { Log.i(Voice.TAG, "voice confirm_yes turn=$turn cmd=${d.target} by=repeat"); return perform(was!!.decision, was.text, turn, fromTurn = true) }
    when (d.route) {
      VoiceGate.Route.COMMAND, VoiceGate.Route.ANSWER -> return perform(d, t, turn, fromTurn = true)
      VoiceGate.Route.CONFIRM -> {
        // Nothing runs: the heard line and one "Do it?" chip, until it is pressed or the line is said again.
        Log.i(Voice.TAG, "voice confirm_offer turn=$turn cmd=${d.target} cov=${"%.2f".format(d.coverage)} why=${d.why}")
        offer = Offer(t, d, SystemClock.elapsedRealtime())
        machine.drop(); caption(heard = t, dim = false, reply = ""); showOffer(true)
        return false
      }
      else -> {}
    }
    Log.i(Voice.TAG, "drop turn=$turn conf=${"%.2f".format(confidence)} kind=${d.match?.kind} cov=${"%.2f".format(d.coverage)} why=${d.why}")
    machine.drop(); caption(heard = t, dim = true)
    return false
  }

  /** True while a "Do it?" chip is showing (tests). */
  val offering get() = offer != null
  /** Presses the "Do it?" chip (tests). */
  fun pressDoIt() = doIt.performClick()

  /** Runs what [d] decided for [t]: the command table's action, or the scripted answer. */
  private fun perform(d: VoiceGate.Decision, t: String, turn: Int, fromTurn: Boolean): Boolean {
    if (d.route == VoiceGate.Route.MUTE) { Log.i(Voice.TAG, "act turn=$turn route=mute"); machine.drop(); setMuted(true, "voice"); return true }
    if (d.command != null) {
      val r = command(t) ?: run { machine.drop(); caption(heard = t, dim = true); return false }
      Log.i(Voice.TAG, "act turn=$turn route=command"); machine.drop(); caption(heard = t, dim = false, reply = r); return true
    }
    Log.i(Voice.TAG, "act turn=$turn route=answer id=${d.match?.answer?.id}")
    if (!fromTurn) { machine.listen(); machine.endOfSpeech() }
    machine.heard(machine.turn, t, gated = d.match); return true
  }

  fun setMuted(on: Boolean, by: String) {
    if (muted == on) return
    muted = on; prefs.edit().putBoolean("muted", on).apply()
    Log.i(Voice.TAG, "mute=$on by=$by")
    Log.i(Voice.TAG, "voice event=mic_toggle on=${if(on) 0 else 1} from=$by")
    Log.i(Voice.TAG, "voice event=mute on=${if (on) 1 else 0} why=$by")
    if (on) { minimumAudioTurn = loop.turn + 1; offer = null; machine.reset(); ring.level = 0f }
    updateMic(); header()
  }

  private fun text(size: Float, color: Int) = TextView(activity).apply {
    textSize = size; setTextColor(color)
    @SuppressLint("WrongConstant") // Weight overload (API 28); minSdk 34 guarantees availability, 500 is a weight.
    val weightedTypeface = android.graphics.Typeface.create(android.graphics.Typeface.create("sans-serif",0),500,false)
    typeface = weightedTypeface
    layoutParams = LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = (10*resources.displayMetrics.density).toInt() }
  }

  private fun header() {
    val state = when {
      muted -> AssistantState.Muted
      projectKey != null || offer != null -> AssistantState.Confirm
      ring.state == MicState.SPEAKING -> AssistantState.Speaking
      else -> AssistantState.Listening
    }
    if (state != assistantState) {
      Log.i(Voice.TAG, "voice event=state from=$assistantState to=$state why=update")
      if (assistantState == AssistantState.Speaking && state == AssistantState.Listening) { ring.level = 0f; idleSoon() }
      assistantState = state
    }
    val refused = !muted && refusedPermission && !granted()
    ring.muted = muted || refused
    word.text = when (state) {
      AssistantState.Muted -> "Muted"
      AssistantState.Confirm -> "Build this?"
      AssistantState.Speaking -> brief(answerSubject(), 4).replaceFirstChar { it.uppercaseChar() }
      AssistantState.Listening -> if (refused) "Mic off" else "Listening"
    }
    word.setTextColor(if (muted || refused) 0xb3ffffff.toInt() else 0xfff2f6fa.toInt())
    ring.contentDescription = if(muted) "Unmute microphone" else "Mute microphone"
    ring.isSelected = !muted
    hint.text = when { refused -> "Mic access"; askedPermission && !granted() -> "Allow microphone"; else -> "" }
    hint.visibility = if (hint.text.isEmpty()) View.GONE else View.VISIBLE
    projectRow.visibility = if (state == AssistantState.Confirm && projectKey != null) View.VISIBLE else View.GONE
    doIt.visibility = if (state == AssistantState.Confirm && offer != null) View.VISIBLE else View.GONE
    discard.visibility = doIt.visibility
    if (state == AssistantState.Confirm) {
      ui.removeCallbacks(clearDim); heardLine.alpha = 1f
      heardLine.text = "\u201c${brief(if (projectKey != null) projectLine else offer?.text.orEmpty(), 16)}\u201d"
      heardLine.visibility = View.VISIBLE; replyLine.visibility = View.GONE
      choiceRow.visibility = View.GONE
    } else if (state == AssistantState.Muted) {
      lastHeard = ""; heardLine.visibility = View.GONE; replyLine.visibility = View.GONE; hint.visibility = View.GONE; choiceRow.visibility = View.GONE
    } else {
      choiceRow.visibility = if (choiceRow.childCount > 0) View.VISIBLE else View.GONE
      if (choiceRow.childCount > 0) { heardLine.visibility = View.GONE; replyLine.visibility = View.GONE }
    }
  }

  private fun caption(heard: String, dim: Boolean, reply: String? = null, online: Boolean = false) {
    if (muted) return
    ui.removeCallbacks(shrink); ui.removeCallbacks(clearDim)
    heardLine.visibility = View.VISIBLE
    lastHeard = if (heard.isBlank() || heard == "…") heard else "\u201c$heard\u201d"
    heardLine.text = if (heard.isBlank() || heard == "…") heard else "\u201c${brief(heard, 8)}\u201d"
    heardLine.alpha = if (dim) 0.4f else 1f
    if (reply != null) replyLine.text = brief(reply, 10)
    replyLine.visibility = if (replyLine.text.isEmpty()) View.GONE else View.VISIBLE
    if (dim) ui.postDelayed(clearDim, 3000)
    header(); idleSoon()
  }
  /** Shrinks to a strip (the mute control alone) after 6 s with nothing new. */
  private fun idleSoon() { ui.removeCallbacks(shrink); ui.postDelayed(shrink, 6000) }

  /** What the caption window shows now (tests and the debug log). */
  val captionHeard get() = lastHeard
  val captionDimmed get() = heardLine.alpha < 1f
  val captionStrip get() = heardLine.visibility == View.GONE

  /** #1230: the activity's permission result. An empty result (the prompt was dismissed unanswered) is not a refusal. */
  fun permissionResult(requestCode: Int, grantResults: IntArray) {
    if (requestCode != Voice.PERMISSION_REQUEST || grantResults.isEmpty()) return
    refusedPermission = grantResults.any { it != PackageManager.PERMISSION_GRANTED }
    header(); updateMic()
  }

  private fun granted() = activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

  /** Starts or stops the microphone thread to match: unmuted, worn, not paused, not closed, permission granted. */
  private fun updateMic() {
    val why = when { closed -> "closed"; muted -> "muted"; paused -> "paused"; !worn() -> "asleep"; else -> null }
    if (why == null && mic == null) {
      if (!granted()) {
        if (!askedPermission) { askedPermission = true; header(); activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), Voice.PERMISSION_REQUEST) }
        return
      }
      mic = newMic { f, n -> if (!injecting && !muted && !closed) {
        val level = voiceLevel(f, 0, n)
        activity.runOnUiThread { if (!muted && ring.state != MicState.SPEAKING) ring.level = level }
        loop.feed(f, n)
      } }.also { it.start() }
      Log.i(Voice.TAG, "listen on"); header()
    } else if (why != null && mic != null) {
      mic?.stop(); mic = null; loop.cancel(); ring.level = 0f
      Log.i(Voice.TAG, "listen off reason=$why"); header()
    }
  }

  /** ECS thread, ~20 Hz: hand joints per side (null = untracked). Closing the fingers stops a reply; it never starts a turn. */
  fun hands(now: Long, joints: Map<String, com.overhand.app.hands.HandJoints?>) {
    activity.runOnUiThread { if (!closed) updateMic() }
    val closedNow = joints.values.any { it != null && pinch.gap(it) < 0.022 }
    pinch.update(now, joints)
    if (closedNow && worn()) activity.runOnUiThread { if (machine.state == MicState.SPEAKING) machine.interrupt() }
  }

  /**
   * Debug: a 16 kHz PCM16 WAV goes into the always-on path as if spoken at the microphone, then 1 s of silence so
   * the turn ends. The real microphone's frames are ignored meanwhile. Muted or asleep: nothing is heard.
   */
  fun injectWav(path: String) {
    if (muted || !worn() || closed) { Log.i(Voice.TAG, "inject ignored muted=$muted worn=${worn()} $path"); return }
    val pcm = runCatching { Wav.read(java.io.File(path).readBytes()) }.getOrElse { Log.w(Voice.TAG, "bad wav $path: $it"); return }
    if (pcm.sampleRate != 16000) { Log.w(Voice.TAG, "need 16 kHz: $path"); return }
    Log.i(Voice.TAG, "inject $path")
    injecting = true
    try {
      var i = 0
      while (i < pcm.samples.size) { val n = minOf(VoiceLoop.FRAME, pcm.samples.size - i); loop.feed(pcm.samples.copyOfRange(i, i + n), n); i += n }
      val quiet = ShortArray(VoiceLoop.FRAME); repeat(50) { loop.feed(quiet, quiet.size) }
    } finally { injecting = false }
  }

  /** Debug: route a typed line as a final transcript, without the recogniser. */
  fun injectText(q: String) {
    if (muted || closed) return
    val c = VoiceGate.confidence(q); val d = VoiceGate.decide(intents, q)
    Log.i(Voice.TAG, "text q=\"$q\" conf=${"%.2f".format(c)} route=${d.route} id=${d.match?.answer?.id} kind=${d.match?.kind}")
    activity.runOnUiThread { machine.listen(); machine.endOfSpeech(); onFinalTranscript(q, c) }
  }

  fun pause() { paused = true; updateMic(); machine.pause() }
  fun resume() { paused = false; updateMic() }
  fun close() {
    if (closed) return
    closed = true
    updateMic(); machine.reset(); player.release(); worker.shutdown(); relayExec.shutdownNow(); smart?.speaker?.release(); talkSpeaker?.takeIf { it !== smart?.speaker }?.release()
    ui.removeCallbacks(shrink); ui.removeCallbacks(clearDim)
    Log.i(Voice.TAG, "voice off")
    synchronized(Voice) { live-- }
  }

  /** True while the microphone thread is running (muted means stopped, not ignored). */
  val micRunning get() = mic?.alive == true

  companion object {
    /** Controllers constructed and not yet closed in this process (#862: must be 1 once voice is on). */
    @Volatile var live = 0; private set
    /** #1187: true while a lag check runs (set by [com.overhand.app.features.LagCheckFeature]): heard lines act on nothing. */
    @Volatile var lagCheck = false
  }
}
