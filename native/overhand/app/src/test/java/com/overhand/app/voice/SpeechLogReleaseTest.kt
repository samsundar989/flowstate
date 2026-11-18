package com.overhand.app.voice

import android.app.Activity
import android.os.Looper
import com.overhand.app.BuildConfig
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog

/**
 * #1127: what the wearer said never reaches the system log in release. A recognised line carrying an unusual marker
 * word goes in where the recogniser hands it over ([VoiceLoop.Listener.final]) and through every branch: acted, offered,
 * dimmed, sent to Smart answers, sent to an agent. Every log entry written meanwhile (any tag, any thread) is captured;
 * under release none may contain the marker. Debug keeps its log (it is the developer's tool) and is only counted.
 * Writes SPEECH_LOG_OUT (default build/speech_log_<debug|release>.txt): one line per branch.
 */
@RunWith(RobolectricTestRunner::class)
class SpeechLogReleaseTest {
  private val marker = "zorblax"
  private val intents = VoiceIntents.parse(File("src/main/res/raw/voice_answers.json").readText())
  private val made = mutableListOf<VoiceController>()
  @After fun closeAll() { made.forEach { it.close() } }

  private class SilentOut : ReplyOut {
    override fun play(pcm: ShortArray, onFirstAudio: (Long) -> Unit, onDone: () -> Unit) { onFirstAudio(System.nanoTime()); onDone() }
    override fun stop() {}
    override fun release() {}
  }
  private class NoMic : Mic { override fun start() {}; override fun stop() {}; override val alive get() = false }
  private class Agent : AgentVoice {
    val got = ArrayList<String>()
    override fun lock() = "Talk to W1"
    override fun hint() = "Talk to W1"
    override fun take(text: String, speak: (String) -> Unit, done: () -> Unit): Boolean { got += text; speak("W1 here."); done(); return true }
    override fun end() {}
  }
  private fun drain(ms: Long, done: () -> Boolean = { false }) {
    val end = System.currentTimeMillis() + ms
    while (!done() && System.currentTimeMillis() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
    shadowOf(Looper.getMainLooper()).idle()
  }

  private class Rig(val c: VoiceController, val ui: FakeUi, val relay: FakeRelay, val agent: Agent?)
  private fun rig(named: Boolean, agent: Boolean = false): Rig {
    val a = Robolectric.buildActivity(Activity::class.java).setup().get()
    val ui = FakeUi()
    val relay = FakeRelay(VClock()) { Script(chunks = listOf(10L to "Here is the answer. ")) }
    val client = RelayClient(relay, "test-install-01")
    val c = VoiceController(a, intents, { _, _ -> "" }, { ShortArray(160) }, 16000).also { made += it }
    c.player = SilentOut(); c.newMic = { NoMic() }; c.commands = UiCommands(ui); c.answerToName = named
    client.probe(); c.smart = SmartVoice(client, ReadySpeaker(), { "screen" }, { true })
    val ag = if (agent) Agent().also { c.agents = it; c.talkSpeaker = ReadySpeaker() } else null
    return Rig(c, ui, relay, ag)
  }

  /** The recogniser's hand-over, as the microphone loop makes it: speech starts, then the final transcript arrives. */
  private fun hear(r: Rig, text: String) {
    r.c.machine.listen()
    val f = VoiceLoop::class.java.getDeclaredField("listener").apply { isAccessible = true }
    (f.get(r.c.loop) as VoiceLoop.Listener).final(r.c.loop.turn, text, 16000, 16000, 7)
    drain(400) { r.c.machine.state == MicState.IDLE }
  }

  /** A candidate ending in AGAIN is heard twice in a row (the wearer repeats it). */
  private val AGAIN = " (again)"
  private fun hearAll(r: Rig, line: String) { val l = line.removeSuffix(AGAIN); hear(r, l); if (line.endsWith(AGAIN)) hear(r, l) }

  private fun asked(r: Rig) = r.relay.bodies.count { it.contains("\"question\"") }

  /** Each branch: candidate lines (all carry the marker), and how to tell the line took that branch. */
  private val branches: List<Triple<String, () -> Rig, Pair<List<String>, (Rig) -> Boolean>>> = listOf(
    // A named line the marker makes unsure is offered ("Did you mean ...?"); saying it again runs it (#1080's repeat).
    Triple("acted", { rig(named = true) }, listOf("Flowstate, close all windows $marker", "Flowstate, close all of these $marker things$AGAIN",
      "Flowstate, open settings $marker$AGAIN") to { r -> r.ui.calls.isNotEmpty() || r.c.muted }),
    Triple("offered", { rig(named = false) }, listOf("open settings $marker", "close all windows $marker", "open the $marker settings")
      to { r -> r.c.offering }),
    Triple("dimmed", { rig(named = false) }, listOf("I had $marker soup for lunch", "the $marker was late again today")
      to { r -> !r.c.offering && r.ui.calls.isEmpty() && asked(r) == 0 }),
    Triple("smart", { rig(named = false) }, listOf("Which agent spent the most on the $marker graph?",
      "Which agent spent the most on the geothermal $marker graph?", "Which agent is blocked on the $marker graph?")
      to { r -> drain(2000) { asked(r) > 0 }; asked(r) > 0 }),
    Triple("agent", { rig(named = false, agent = true) }, listOf("please rename the $marker module", "check the $marker build")
      to { r -> r.agent!!.got.any { marker in it } }),
  )

  @Test fun recognisedWordsNeverReachTheReleaseLog() {
    val rows = ArrayList<String>(); val leaking = ArrayList<String>(); val unreached = ArrayList<String>()
    for ((name, make, spec) in branches) {
      val (lines, took) = spec
      var hit: String? = null; var leaks = 0; var where = ""
      for (line in lines) {
        val r = make(); val from = ShadowLog.getLogs().size
        hearAll(r, line)
        if (!took(r)) { r.c.close(); continue }
        drain(100)
        val bad = ShadowLog.getLogs().drop(from).filter { (it.msg ?: "").contains(marker, ignoreCase = true) }
        hit = line; leaks = bad.size; where = bad.joinToString(" | ") { it.msg.substringBefore(' ') + "@" + it.tag }
        break
      }
      if (hit == null) unreached += name
      if (leaks > 0) leaking += name
      rows += "$name\treached=${hit != null}\tlog_entries_with_marker=$leaks\t$where"
    }
    val variant = if (BuildConfig.DEBUG) "debug" else "release"
    val out = File(System.getenv("SPEECH_LOG_OUT") ?: "build/speech_log_$variant.txt")
    out.parentFile?.mkdirs()
    out.writeText("variant=$variant branches=${branches.size} failing_release=${if (BuildConfig.DEBUG) "n/a" else leaking.size}\n" +
      rows.joinToString("\n") + "\n")
    println("SPEECH_LOG variant=$variant leaking=${leaking.size} unreached=$unreached\n" + rows.joinToString("\n"))
    assertTrue("every branch reached: missing $unreached", unreached.isEmpty())
    if (!BuildConfig.DEBUG) assertEquals("release log carries the wearer's words in: $leaking\n${rows.joinToString("\n")}", 0, leaking.size)
  }
}
