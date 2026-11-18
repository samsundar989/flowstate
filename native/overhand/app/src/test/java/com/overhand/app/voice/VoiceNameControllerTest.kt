package com.overhand.app.voice

import android.app.Activity
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** #1070 on the controller: "Answer to my name" on, a named line acts through the pinch path; off, it is nov14b's gate. */
@RunWith(RobolectricTestRunner::class)
class VoiceNameControllerTest {
  private val intents = VoiceIntents.parse(File("src/main/res/raw/voice_answers.json").readText())
  private val made = mutableListOf<VoiceController>()
  @After fun closeAll() { made.forEach { it.close() } }

  private fun controller(): Pair<VoiceController, FakeUi> {
    val a = Robolectric.buildActivity(Activity::class.java).setup().get()
    val ui = FakeUi()
    val c = VoiceController(a, intents, { _, _ -> "" }, { ShortArray(1600) }, 16000).also { made += it }
    c.commands = UiCommands(ui)
    return c to ui
  }

  @Test fun onByDefaultANamedCloseAllActs() {
    val (c, ui) = controller()
    assertTrue(c.answerToName)
    assertTrue(c.onFinalTranscript("Flowstate, close all windows", 1.0))
    assertFalse(c.offering)
    assertTrue(ui.calls.toString(), ui.calls.isNotEmpty())
  }

  @Test fun offTheSameLineIsWhatNov14bDoes() {
    val (c, ui) = controller()
    c.answerToName = false
    val gate = VoiceGate.decide(intents, "Flowstate, close all windows", commands = UiCommands(FakeUi()))
    val acted = c.onFinalTranscript("Flowstate, close all windows", 1.0)
    assertEquals(gate.route == VoiceGate.Route.COMMAND, acted)
    assertEquals(gate.route == VoiceGate.Route.CONFIRM, c.offering)
    if (!acted) assertTrue(ui.calls.toString(), ui.calls.isEmpty())
  }

  /** #1080: "flow state" as a noun is not the name, and with the switch on an unnamed line never acts. */
  @Test fun theNameAsAWordIsNotNamedAndNeverActs() {
    val line = "I was in a flow state all morning"
    assertNull(VoiceGate.named(line))
    val (c, ui) = controller()
    assertFalse(c.onFinalTranscript(line, 1.0))
    assertTrue(ui.calls.toString(), ui.calls.isEmpty())
  }

  private fun idleFor(ms: Long) = org.robolectric.shadows.ShadowLooper.idleMainLooper(ms, java.util.concurrent.TimeUnit.MILLISECONDS)

  /** #1080: where the old gate would have acted, an unnamed line only shows the chip; it acts on a press, never by itself. */
  @Test fun anUnnamedLineTheGateWouldRunShowsTheChipAndActsOnlyWhenPressed() {
    val line = "open settings"
    assertEquals(VoiceGate.Route.COMMAND, VoiceGate.decide(intents, line, commands = UiCommands(FakeUi())).route)
    val (c, ui) = controller()
    assertFalse(c.onFinalTranscript(line, 1.0))
    assertTrue(c.offering); assertTrue(ui.calls.toString(), ui.calls.isEmpty())
    assertFalse("a repeat does not confirm an unnamed chip", c.onFinalTranscript(line, 1.0))
    assertFalse("nor does an unnamed yes", c.onFinalTranscript("yes", 1.0))
    assertTrue(ui.calls.toString(), ui.calls.isEmpty())
    c.onFinalTranscript(line, 1.0); c.pressDoIt()
    assertEquals(listOf("openSettings()"), ui.calls)
  }

  @Test fun theSilentChipGoesByItself() {
    val (c, _) = controller()
    c.onFinalTranscript("open settings", 1.0)
    assertTrue(c.offering)
    idleFor(Voice.OFFER_MS + 500)
    assertFalse(c.offering)
  }

  /** #1080: an unnamed "be quiet" does not mute either; "Flowstate, mute" does. */
  @Test fun anUnnamedMuteOnlyOffers() {
    val (c, _) = controller()
    assertFalse(c.onFinalTranscript("be quiet", 1.0)); assertFalse(c.muted); assertTrue(c.offering)
    assertTrue(c.onFinalTranscript("Flowstate, mute", 1.0)); assertTrue(c.muted)
  }

  /** #1080: the old gate's own offers ("Do it?" on a half-covered line) are dropped when the line is unnamed. */
  @Test fun theOldGatesOffersAreDroppedForUnnamedLines() {
    val lines = listOf("let's undo this whole thing and order pizza", "change the settings on the thermostat", "can you close the window it's cold")
    for (line in lines) {
      val gate = VoiceGate.decide(intents, line, commands = UiCommands(FakeUi()))
      val (c, ui) = controller()
      assertFalse(line, c.onFinalTranscript(line, 1.0)); assertTrue(ui.calls.isEmpty())
      assertEquals(line, gate.route == VoiceGate.Route.COMMAND || gate.route == VoiceGate.Route.ANSWER, c.offering)
    }
  }

  /** #1080: a named close-all that is not fully covered asks "Did you mean ...?"; yes runs it. */
  @Test fun aNamedUnsureCloseAllAsksAndYesRunsIt() {
    val line = "Flowstate, close all of these floating things"
    val d = VoiceGate.decideCalled(intents, VoiceGate.named(line)!!, UiCommands(FakeUi()))
    assertEquals("close_all", d.command?.action); assertEquals(VoiceGate.Route.CONFIRM, d.route)
    val (c, ui) = controller()
    assertFalse(c.onFinalTranscript(line, 1.0))
    assertTrue(c.offering); assertTrue(ui.calls.isEmpty())
    assertTrue(c.onFinalTranscript("yes", 1.0))
    assertEquals(listOf("closeAll()"), ui.calls); assertFalse(c.offering)
  }

  @Test fun aNamedUnsureLineIsRunByThePinchedChipOrARepeat() {
    val line = "Flowstate, close all of these floating things"
    val (c, ui) = controller()
    c.onFinalTranscript(line, 1.0); c.pressDoIt()
    assertEquals(listOf("closeAll()"), ui.calls)
    val (c2, ui2) = controller()
    c2.onFinalTranscript(line, 1.0); assertTrue(c2.onFinalTranscript(line, 1.0))
    assertEquals(listOf("closeAll()"), ui2.calls)
  }

  /** #1080: a named open question nothing matches goes to the answer path; a named statement about the app is not address. */
  @Test fun aNamedOpenQuestionIsAnsweredAndTalkAboutTheAppIsNot() {
    assertEquals(VoiceGate.Route.ANSWER, VoiceGate.decideCalled(intents, VoiceGate.named("Flowstate, what is a monad?")!!, UiCommands(FakeUi())).route)
    assertNull(VoiceGate.named("Flowstate is the thing I am demoing tonight"))
    assertNull(VoiceGate.named("I'll show you Flowstate after dinner"))
    assertNull(VoiceGate.named("have you tried Flowstate yet"))
    assertEquals("is anything broken", VoiceGate.named("Flowstate, is anything broken?"))
    assertEquals("what failed please", VoiceGate.named("what failed, Flowstate, please"))
  }
}
