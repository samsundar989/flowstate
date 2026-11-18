package com.overhand.app.voice

/** The mic chip's four visible states. */
enum class MicState { IDLE, LISTENING, THINKING, SPEAKING }

/**
 * Push-to-talk turn logic with no Android in it, so every transition is unit tested.
 * Pinch-hold starts listening; release hands the audio to the recogniser (THINKING); a reply plays (SPEAKING);
 * pinching again while it speaks stops the reply and listens to the new question at once.
 * A turn number guards against a late recogniser result from a turn that was interrupted.
 */
class VoiceMachine(private val intents: VoiceIntents, private val effects: Effects) {
  interface Effects {
    fun startMic()
    fun stopMic()
    /** Decode the captured audio off the UI thread, then call [VoiceMachine.heard] with [turn]. */
    fun recognise(turn: Int)
    fun play(answer: VoiceAnswer)
    fun stopPlayback()
    fun show(state: MicState, transcript: String, reply: String)
    /** First pinch-hold without the mic permission. Return true if the permission is already granted. */
    fun ensurePermission(): Boolean
  }

  var state = MicState.IDLE; private set
  var turn = 0; private set
  var transcript = ""; private set
  var reply = ""; private set
  var lastMatch: VoiceIntents.Match? = null; private set
  /** #815: sees each transcript first; returning true means it was handled there (no scripted answer plays). */
  var intercept: ((String) -> Boolean)? = null
  /** #1002: spoken UI commands; a caption means the transcript was a command (it ran, or "Which one?" was asked). */
  var command: ((String) -> String?)? = null
  /**
   * #1032: offered every transcript that has no confident scripted answer. Returning true means a smart answer was
   * started for [turn]: the machine stays THINKING until [smartSentence] (speaking) or [smartFallback] (scripted line).
   */
  var smart: ((turn: Int, text: String, m: VoiceIntents.Match) -> Boolean)? = null
  /**
   * #1035 talk to an agent: asked at speech start; true = an agent on the site is targeted, so this turn's words go to
   * it ([talk]) before anything else. Null, or false (no agent, or "Talk to agents" off): speech behaves as before.
   */
  var talkLock: (() -> Boolean)? = null
  /** #1035: the targeted agent takes the transcript. True = started: it calls [smartSentence] per sentence, then [playbackDone]. */
  var talk: ((turn: Int, text: String) -> Boolean)? = null
  /** #1035: the talking turn is over (answered, stopped, silent or reset). */
  var talkEnd: (() -> Unit)? = null
  /** This turn talks to an agent. */
  var talking = false; private set
  /** This turn's question left the headset (the caption marks it). */
  var askedOnline = false; private set
  /** A smart answer is still owed for this turn (cleared by the fallback, so a late sentence is never spoken). */
  private var smartLive = false

  /** #1070: shown (not spoken) after the help answer: how to always get through. Null = nothing added. */
  var helpTip: String? = null

  fun pinchStart() {
    if (state == MicState.SPEAKING) effects.stopPlayback()
    if (state == MicState.LISTENING) return
    if (!effects.ensurePermission()) { go(MicState.IDLE); return }
    turn++; transcript = ""; reply = ""; askedOnline = false; smartLive = false
    if (talking) { talking = false; talkEnd?.invoke() }
    talking = talkLock?.invoke() == true
    effects.startMic(); go(MicState.LISTENING)
  }

  fun pinchEnd() {
    if (state != MicState.LISTENING) return
    effects.stopMic(); go(MicState.THINKING); effects.recognise(turn)
  }

  /** Recogniser result for [forTurn]. [silent] = the hold had no speech in it (a long pinch, not a question). [gated]: see below. */
  fun heard(forTurn: Int, text: String, silent: Boolean = false, gated: VoiceIntents.Match? = null) {
    if (forTurn != turn || state != MicState.THINKING) return
    if (silent) { go(MicState.IDLE); return }
    if (gated == null && talkTurn(forTurn, text)) return // always-on talks in VoiceController.onFinalTranscript
    if (intercept?.invoke(text.trim()) == true) { transcript = text.trim(); reply = ""; go(MicState.IDLE); return }
    command?.invoke(text.trim())?.let { c -> transcript = text.trim(); reply = c; go(MicState.IDLE); return }
    // #1056: always-on passes the gate's own match, so what plays (or goes to the relay) is what the gate judged.
    val m = gated ?: intents.match(text); lastMatch = m
    transcript = text.trim()
    if (SmartRouter.route(m, smartOn = true) == Route.RELAY && smart != null) {
      // Live before the call: a sentence (or the fallback) may arrive before it returns.
      askedOnline = true; smartLive = true; reply = ""
      if (smart!!.invoke(turn, transcript, m)) { if (state == MicState.THINKING && reply.isEmpty()) go(MicState.THINKING); return }
      askedOnline = false; smartLive = false
    }
    speakScripted(m)
  }

  /**
   * #1035: hands this turn's words to the targeted agent ([talk]); false = no agent locked for it, or not taken, and
   * the caller goes on as before. Live before the call, as for a smart answer: the answer may arrive before it returns.
   */
  fun talkTurn(forTurn: Int, text: String): Boolean {
    if (forTurn != turn || state != MicState.THINKING || !talking || talk == null) return false
    transcript = text.trim(); reply = ""; askedOnline = false; smartLive = true
    if (talk!!.invoke(turn, transcript)) { if (state == MicState.THINKING && reply.isEmpty()) go(MicState.THINKING); return true }
    smartLive = false; return false
  }

  /** One streamed sentence of the smart answer for [forTurn]: captioned (the caller speaks it). False if the turn is gone. */
  fun smartSentence(forTurn: Int, sentence: String): Boolean {
    if (forTurn != turn || !smartLive || (state != MicState.THINKING && state != MicState.SPEAKING)) return false
    reply = if (reply.isEmpty()) sentence else "$reply $sentence"
    go(MicState.SPEAKING); return true
  }

  /** The smart answer failed before any sentence (offline, timeout, cap, filtered...): the scripted line plays, no error shown. */
  fun smartFallback(forTurn: Int) {
    if (forTurn != turn || state != MicState.THINKING || !smartLive) return
    smartLive = false
    speakScripted(lastMatch ?: return)
  }

  private fun speakScripted(m: VoiceIntents.Match) {
    // #946: a low-confidence match plays the nearest answer, then one line naming three things to ask.
    reply = intents.suggest?.takeIf { m.kind == MatchKind.NEAREST }?.let { m.answer.text + " " + it.text } ?: m.answer.text
    if (m.answer.id == "help") helpTip?.let { reply = "$reply $it" }
    go(MicState.SPEAKING); effects.play(m.answer)
  }

  /** #1001 always-on: speech began (no pinch). Ignored while a reply plays: the loop is deaf then anyway. */
  fun listen() {
    if (state == MicState.LISTENING || state == MicState.SPEAKING) return
    turn++; transcript = ""; reply = ""; askedOnline = false; smartLive = false
    if (talking) { talking = false; talkEnd?.invoke() }
    talking = talkLock?.invoke() == true // #1035: the agent is fixed when speech starts, not when it ends
    go(MicState.LISTENING)
  }

  /** #1001 always-on: the silence after the speech ended the turn; the final decode is running. */
  fun endOfSpeech() { if (state == MicState.LISTENING) go(MicState.THINKING) }

  /** #1001 always-on: the turn ended without an answer (not for the app, a command, too short, or cancelled). */
  fun drop() { if (state == MicState.LISTENING || state == MicState.THINKING) go(MicState.IDLE) }

  /** Fingers closed while the reply plays: stop it at once, before the hold is long enough to listen again. */
  fun interrupt() { if (state == MicState.SPEAKING) { effects.stopPlayback(); turn++; go(MicState.IDLE) } }

  /** Playback ended on its own (not by interruption). */
  fun playbackDone(forTurn: Int) { if (forTurn == turn && state == MicState.SPEAKING) go(MicState.IDLE) }

  /**
   * App paused (a system dialog, the headset taken off, a debug `am start`): a hold is cut and a reply stops, but a turn
   * already being decoded is kept, so its result still lands. #862: resetting here dropped the first spoken request.
   */
  fun pause() { if (state != MicState.THINKING) reset() }

  /** Voice switched off mid-turn. */
  fun reset() {
    if (state == MicState.LISTENING) effects.stopMic()
    if (state == MicState.SPEAKING) effects.stopPlayback()
    turn++; go(MicState.IDLE)
  }

  private fun go(s: MicState) {
    state = s; effects.show(s, transcript, reply)
    if (s == MicState.IDLE && talking) { talking = false; talkEnd?.invoke() }
  }
}
