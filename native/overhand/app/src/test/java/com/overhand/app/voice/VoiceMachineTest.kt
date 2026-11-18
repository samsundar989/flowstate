package com.overhand.app.voice

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** Voice C turn logic: idle, listening, thinking, speaking; interruption; stale results; the mic permission. */
class VoiceMachineTest {
  private val intents = VoiceIntents.parse(File("src/main/res/raw/voice_answers.json").readText())
  private class Log : VoiceMachine.Effects {
    val calls = mutableListOf<String>(); var granted = true; val states = mutableListOf<MicState>()
    override fun startMic() { calls += "mic" }
    override fun stopMic() { calls += "unmic" }
    override fun recognise(turn: Int) { calls += "asr$turn" }
    override fun play(answer: VoiceAnswer) { calls += "play:${answer.id}" }
    override fun stopPlayback() { calls += "stop" }
    override fun show(state: MicState, transcript: String, reply: String) { states += state }
    override fun ensurePermission() = granted.also { if (!it) calls += "ask" }
  }

  @Test fun aTurnGoesListeningThinkingSpeakingIdle() {
    val l = Log(); val m = VoiceMachine(intents, l)
    m.pinchStart(); assertEquals(MicState.LISTENING, m.state)
    m.pinchEnd(); assertEquals(MicState.THINKING, m.state)
    m.heard(m.turn, "What needs me?"); assertEquals(MicState.SPEAKING, m.state)
    assertEquals("What needs me?", m.transcript); assertEquals(intents["needs_me"]!!.text, m.reply)
    m.playbackDone(m.turn); assertEquals(MicState.IDLE, m.state)
    assertEquals(listOf("mic", "unmic", "asr1", "play:needs_me"), l.calls)
    assertEquals(listOf(MicState.LISTENING, MicState.THINKING, MicState.SPEAKING, MicState.IDLE), l.states)
  }

  @Test fun pinchingWhileSpeakingStopsTheReplyAndListensAgain() {
    val l = Log(); val m = VoiceMachine(intents, l)
    m.pinchStart(); m.pinchEnd(); m.heard(1, "what got built")
    m.pinchStart()
    assertEquals(MicState.LISTENING, m.state); assertEquals(2, m.turn)
    assertEquals(listOf("mic", "unmic", "asr1", "play:built", "stop", "mic"), l.calls)
    m.playbackDone(1); assertEquals(MicState.LISTENING, m.state) // the old reply's end does not end the new turn
  }

  @Test fun closingTheFingersInterruptsAtOnce() {
    val l = Log(); val m = VoiceMachine(intents, l)
    m.pinchStart(); m.pinchEnd(); m.heard(1, "what failed")
    m.interrupt(); assertEquals(MicState.IDLE, m.state); assertEquals("stop", l.calls.last())
    m.interrupt(); assertEquals(1, l.calls.count { it == "stop" }) // idle: nothing to stop
  }

  @Test fun aLateResultFromAnInterruptedTurnIsIgnored() {
    val l = Log(); val m = VoiceMachine(intents, l)
    m.pinchStart(); m.pinchEnd(); m.reset()
    m.heard(1, "what needs me"); assertEquals(MicState.IDLE, m.state)
    assertEquals(0, l.calls.count { it.startsWith("play") })
  }

  @Test fun aSilentHoldGoesBackToIdleWithoutAReply() {
    val l = Log(); val m = VoiceMachine(intents, l)
    m.pinchStart(); m.pinchEnd(); m.heard(1, "", silent = true)
    assertEquals(MicState.IDLE, m.state); assertEquals(0, l.calls.count { it.startsWith("play") })
  }

  @Test fun speechThatIsNotUnderstoodGetsTheDidNotCatchLine() {
    val l = Log(); val m = VoiceMachine(intents, l)
    m.pinchStart(); m.pinchEnd(); m.heard(1, "")
    assertEquals("play:not_caught", l.calls.last())
  }

  @Test fun withoutThePermissionTheFirstHoldAsksAndDoesNotListen() {
    val l = Log().apply { granted = false }; val m = VoiceMachine(intents, l)
    m.pinchStart(); assertEquals(MicState.IDLE, m.state); assertEquals(listOf("ask"), l.calls)
    m.pinchEnd(); assertEquals(listOf("ask"), l.calls) // release of a refused hold does nothing
    l.granted = true; m.pinchStart(); assertEquals(MicState.LISTENING, m.state)
  }

  /** #862: the app pausing while a turn decodes (a system dialog, a debug am start) keeps the turn, so its result lands. */
  @Test fun aPauseWhileThinkingKeepsTheTurnButAPauseWhileListeningCutsIt() {
    val l = Log(); val m = VoiceMachine(intents, l)
    m.pinchStart(); m.pinchEnd(); val t = m.turn
    m.pause(); assertEquals(MicState.THINKING, m.state); assertEquals(t, m.turn)
    m.heard(t, "What needs me?"); assertEquals(MicState.SPEAKING, m.state)
    m.playbackDone(m.turn)
    m.pinchStart(); m.pause(); assertEquals(MicState.IDLE, m.state); assertEquals(t + 2, m.turn)
  }
}
