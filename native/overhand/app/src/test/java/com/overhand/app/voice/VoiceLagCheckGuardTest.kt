package com.overhand.app.voice

import android.app.Activity
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * #1187: during a lag check, heard speech is decoded as usual (the recogniser load the check must measure) but acts on
 * nothing: no command, no relay question, no spoken reply, no mute. After `lagcheck done` it works again. Every line
 * goes the microphone's way ([VoiceController.injectWav]: voice activity, partial and final decodes on the worker).
 * Prints `lagcheck_voice_actions_during_run=N lagcheck_voice_decodes_during_run=D` for runs/perf/t1187.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceLagCheckGuardTest {
  private val intents = VoiceIntents.parse(File("src/main/res/raw/voice_answers.json").readText())
  private val made = mutableListOf<VoiceController>()
  @After fun closeAll() { VoiceController.lagCheck = false; made.forEach { it.close() } }

  private class FakeOut : ReplyOut {
    var played = 0
    override fun play(pcm: ShortArray, onFirstAudio: (Long) -> Unit, onDone: () -> Unit) { played++; onFirstAudio(System.nanoTime()); onDone() }
    override fun stop() {}
    override fun release() {}
  }

  /** Ten lines for the app: named commands, questions and a mute ("Answer to my name" on, the default). */
  private val lines = listOf("Flowstate, close all windows", "Flowstate, open settings", "Flowstate, mute", "Flowstate, what needs me?",
    "Flowstate, what failed?", "Flowstate, what got built today?", "Flowstate, open fleet", "Flowstate, undo",
    "Flowstate, is anything broken?", "Flowstate, close all")

  private class Rig(val c: VoiceController, val ui: FakeUi, val out: FakeOut, val decodes: java.util.concurrent.atomic.AtomicInteger, val say: java.util.concurrent.atomic.AtomicReference<String>)
  private fun rig(): Rig {
    val a = Robolectric.buildActivity(Activity::class.java).setup().get()
    val decodes = java.util.concurrent.atomic.AtomicInteger(); val say = java.util.concurrent.atomic.AtomicReference("")
    val c = VoiceController(a, intents, { _, _ -> decodes.incrementAndGet(); say.get() }, { ShortArray(1600) }, 16000).also { made += it }
    val ui = FakeUi(); val out = FakeOut()
    c.commands = UiCommands(ui); c.player = out
    return Rig(c, ui, out, decodes, say)
  }

  private fun wav(ms: Int = 1500): File {
    val n = 16 * ms; val pcm = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until n) pcm.putShort((sin(i * 0.05) * 12000).toInt().toShort())
    val out = ByteArrayOutputStream(); val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    h.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
      .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(n * 2)
    out.write(h.array()); out.write(pcm.array())
    return File.createTempFile("turn", ".wav").apply { writeBytes(out.toByteArray()); deleteOnExit() }
  }
  private val speech by lazy { wav() }

  private fun drainUntil(ms: Long, done: () -> Boolean) {
    val end = System.currentTimeMillis() + ms
    while (!done() && System.currentTimeMillis() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
    shadowOf(Looper.getMainLooper()).idle()
  }

  /** Speak [line] into [r] the microphone's way and wait (3 s at most) for [done]. Returns [done]. */
  private fun speak(r: Rig, line: String, done: () -> Boolean): Boolean {
    r.say.set(line); r.c.injectWav(speech.path); drainUntil(3000, done); return done()
  }
  /** The final transcript reached the lag-check guard: shown dimmed (a partial caption is never dimmed). */
  private fun dropped(r: Rig, line: String) = { r.c.captionHeard == "“$line”" && r.c.captionDimmed }

  private fun actions(r: Rig) = r.ui.calls.size + r.out.played + r.c.relayAsks + (if (r.c.muted) 1 else 0) + (if (r.c.offering) 1 else 0)

  @Test fun tenLinesDuringARunAreDecodedButActOnNothingAndAfterTheRunCloseAllWorks() {
    // Control: the same ten lines with no run act (so the guard, not the lines, is what keeps the count at 0).
    var controlActs = 0
    for (line in lines) { val r = rig(); if (speak(r, line) { actions(r) > 0 }) controlActs++; r.c.setMuted(false, "test") }
    assertTrue("the lines are for the app: $controlActs of ${lines.size} act with no run", controlActs >= 5)

    val r = rig()
    VoiceController.lagCheck = true // what LagCheck.Machine.start does through LagCheckFeature.hush
    var reached = 0
    for (line in lines) if (speak(r, line, dropped(r, line))) reached++
    val decodes = r.decodes.get()
    val acted = actions(r)
    println("lagcheck_voice_actions_during_run=$acted lagcheck_voice_decodes_during_run=$decodes lines=${lines.size} reached_gate=$reached control_acts=$controlActs")
    assertEquals("every line was decoded to its final transcript", lines.size, reached)
    assertTrue("the decode load is still there: $decodes", decodes >= lines.size)
    assertEquals("no command, question, reply, mute or chip: ${r.ui.calls}", 0, acted)
    assertEquals(MicState.IDLE, r.c.machine.state)

    // After `lagcheck done`: the same "close all" works.
    VoiceController.lagCheck = false
    assertTrue(speak(r, "Flowstate, close all windows") { r.ui.calls.isNotEmpty() })
    assertEquals(listOf("closeAll()"), r.ui.calls)
  }

  @Test fun aTypedCloseAllMidRunChangesNothingAndWorksAfter() {
    val r = rig()
    VoiceController.lagCheck = true
    assertFalse(r.c.onFinalTranscript("Flowstate, close all windows", 1.0)); assertTrue(r.ui.calls.isEmpty())
    VoiceController.lagCheck = false
    assertTrue(r.c.onFinalTranscript("Flowstate, close all windows", 1.0)); assertEquals(listOf("closeAll()"), r.ui.calls)
  }
}
