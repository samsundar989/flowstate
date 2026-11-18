package com.overhand.app.voice

import java.io.File
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1001: the always-on loop's turn taking and the gate, with a fake recogniser and synthetic audio. */
class VoiceLoopTest {
  private val intents = VoiceIntents.parse(File("src/main/res/raw/voice_answers.json").readText())

  private class Rec : VoiceLoop.Listener {
    val starts = mutableListOf<Int>(); val finals = mutableListOf<Pair<Int, String>>(); val ends = mutableListOf<Long>()
    val dropped = mutableListOf<Int>(); val partials = mutableListOf<String>()
    override fun speechStart(turn: Int, atSample: Long) { starts += turn }
    override fun partial(turn: Int, text: String) { partials += text }
    override fun final(turn: Int, text: String, endSample: Long, voicedEndSample: Long, decodeMs: Long) { finals += turn to text; ends += endSample }
    override fun dropped(turn: Int) { dropped += turn }
  }

  private fun tone(ms: Int, amp: Double = 9000.0) = ShortArray(16 * ms) { (sin(it * 0.06) * amp * (1 + 0.3 * sin(it * 0.002))).toInt().toShort() }
  private fun quiet(ms: Int) = ShortArray(16 * ms) { ((it * 7919) % 41 - 20).toShort() }
  private fun loop(r: Rec, said: () -> String = { "what got built today" }) = VoiceLoop({ _, _ -> said() }, { it.run() }, r)
  private fun VoiceLoop.play(vararg parts: ShortArray) = parts.forEach { feed(it, it.size) }

  @Test fun threeTurnsInARowWithNoPinch() {
    val r = Rec(); val lines = ArrayDeque(listOf("what needs me", "what got built today", "what failed"))
    val l = loop(r) { lines.first() }
    repeat(3) { l.play(quiet(1000), tone(1500), quiet(1000)); lines.removeFirst() }
    assertEquals(listOf(1, 2, 3), r.starts)
    assertEquals(listOf("what needs me", "what got built today", "what failed"), r.finals.map { it.second })
  }

  @Test fun aTurnEndsAfterAboutSevenTenthsOfASecondOfSilence() {
    val r = Rec(); val l = loop(r)
    l.play(quiet(500), tone(1000), quiet(600))
    assertTrue("0.6 s of silence keeps the turn open", r.finals.isEmpty() && l.open)
    l.play(quiet(120))
    assertEquals(1, r.finals.size)
    // The final comes 0.7 s (+/- one frame) after the last voiced frame.
    val toneEnd = 16 * 1500L
    assertTrue("ended at ${r.ends[0]}", r.ends[0] - toneEnd in (16 * 680L)..(16 * 740L))
  }

  @Test fun aPauseInsideASentenceDoesNotSplitIt() {
    val r = Rec(); val l = loop(r)
    l.play(quiet(500), tone(800), quiet(400), tone(800), quiet(1000))
    assertEquals(1, r.finals.size)
  }

  @Test fun aKnockIsNotATurn() {
    val r = Rec(); val l = loop(r)
    l.play(quiet(500), tone(150), quiet(1000))
    assertTrue(r.finals.isEmpty())
  }

  @Test fun longSpeechShowsPartialCaptions() {
    val r = Rec(); val l = loop(r)
    l.play(quiet(300), tone(2500), quiet(1000))
    assertTrue("partials ${r.partials}", r.partials.size >= 3)
  }

  @Test fun itNeverHearsItselfNorForThreeTenthsOfASecondAfter() {
    val r = Rec(); val l = loop(r)
    l.play(quiet(500))
    l.deafen(true); l.play(tone(2000)); l.deafen(false)
    l.play(tone(250)) // the reply's echo tail, inside the 300 ms
    l.play(quiet(1500))
    assertTrue("no turn from our own voice: ${r.starts}", r.starts.isEmpty() && r.finals.isEmpty())
    l.play(tone(1000), quiet(1000))
    assertEquals(1, r.finals.size)
  }

  @Test fun speakingCancelsATurnInProgress() {
    val r = Rec(); val l = loop(r)
    l.play(quiet(300), tone(800))
    assertTrue(l.open)
    l.deafen(true)
    assertFalse(l.open); assertEquals(1, r.dropped.size)
    l.play(quiet(1000)); assertTrue(r.finals.isEmpty())
  }

  @Test fun theGateActsOnlyOnKnownConfidentLines() {
    assertEquals(VoiceGate.Route.ANSWER, VoiceGate.decide(intents, "What got built today?").route)
    assertEquals("built", VoiceGate.decide(intents, "What got built today?").match!!.answer.id)
    assertEquals(VoiceGate.Route.MUTE, VoiceGate.decide(intents, "Mute.").route)
    assertEquals(VoiceGate.Route.MUTE, VoiceGate.decide(intents, "please stop listening").route)
    assertEquals(VoiceGate.Route.DROP, VoiceGate.decide(intents, "I told her to mute the TV before the call").route)
    assertEquals(VoiceGate.Route.DROP, VoiceGate.decide(intents, "pass me the charger").route)
    // A long narration is not said to the app, whatever words are in it.
    assertEquals(VoiceGate.Route.DROP, VoiceGate.decide(intents,
      "Twenty two tasks finished and fifteen were on the headset project, the event format, the experience design and a sound set").route)
    assertTrue(VoiceGate.confidence("um what needs me") >= VoiceGate.THRESHOLD)
  }

  @Test fun noWearerVisibleTextNamesAVendorOrModel() {
    val banned = Regex("(?i)sherpa|moonshine|onnx|kitten|whisper|meta |openai|google|android|llm|model")
    listOf(Voice.PERMISSION_REASON, Voice.IDLE_HINT, Voice.MUTED_HINT, Voice.LISTENING, Voice.MUTED, Voice.DO_IT).forEach { assertFalse(it, banned.containsMatchIn(it)) }
  }

  @Test fun thePromiseSaysAlwaysListeningWhileWornAndNothingLeaves() {
    val p = Voice.PERMISSION_REASON
    assertFalse("no pinch promise left: $p", p.contains("pinch", ignoreCase = true))
    // #1056: always-on (#1001) plus Smart answers (#1032): only text of a for-the-app question may leave, never audio, and a switch keeps all on the headset.
    assertTrue(p, p.contains("wear") && p.contains("muted") && p.contains("on the headset") && p.contains("never audio") && p.contains("keep everything on the headset"))
    assertFalse(Voice.IDLE_HINT.contains("pinch", ignoreCase = true))
  }

  @Test fun theAppsOwnGreetingHeardBackIsNotACommand() {
    val own = VoiceGate.ownLines(File("src/main/res/raw/first60_cues.json").readText())
    assertTrue(VoiceGate.isOwnLine("Say yes or pinch to see the options.", own))
    assertTrue(VoiceGate.isOwnLine("Practice answer saved here. The recorded run keeps its original outcome. Ask me anything, or look around.", own))
    for (q in listOf("Open the settings.", "What needs me right now?", "Show me the options for this project.", "Close all windows."))
      assertFalse(q, VoiceGate.isOwnLine(q, own))
  }
}
