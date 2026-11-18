package com.overhand.app.voice

import android.app.Activity
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import kotlin.math.sin
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * #1001 on the controller: developer (worn, Nov 13 15:45) asked once, then did not know how to ask again. Now every turn
 * is found by voice activity through the same path the microphone feeds ([VoiceController.injectWav]); no pinch.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceAlwaysOnTest {
  private val intents = VoiceIntents.parse(File("src/main/res/raw/voice_answers.json").readText())
  private val made = mutableListOf<VoiceController>()
  @After fun closeAll() { made.forEach { it.close() } }

  /** Speaks instantly: play() reports the first audio, feeds [echo] of the reply into the mic path, then ends. */
  private class FakeOut(val c: () -> VoiceController) : ReplyOut {
    val played = mutableListOf<Int>(); var echo = false
    override fun play(pcm: ShortArray, onFirstAudio: (Long) -> Unit, onDone: () -> Unit) {
      played += pcm.size; onFirstAudio(System.nanoTime())
      if (echo) { val loud = ShortArray(32000) { (sin(it * 0.05) * 12000).toInt().toShort() }; c().loop.feed(loud, loud.size) }
      onDone()
    }
    override fun stop() {}
    override fun release() {}
  }
  private class FakeMic(val sink: (ShortArray, Int) -> Unit) : Mic {
    @Volatile var running = false; private var t: Thread? = null
    override fun start() { running = true; t = Thread { val z = ShortArray(320); while (running) { sink(z, z.size); Thread.sleep(20) } }.also { it.start() } }
    override fun stop() { running = false; t?.join(500) }
    override val alive get() = t?.isAlive == true
  }

  private fun controller(a: Activity, ears: Transcriber): Pair<VoiceController, FakeOut> {
    lateinit var c: VoiceController
    val out = FakeOut { c }
    c = VoiceController(a, intents, ears, { ShortArray(1600) }, 16000).also { made += it }
    c.player = out; c.newMic = { sink -> FakeMic(sink) }
    c.answerToName = false // #1080: these test nov14b's always-on gate ("Answer to my name" off).
    return c to out
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

  private fun drainUntil(ms: Long, done: () -> Boolean) {
    val end = System.currentTimeMillis() + ms
    while (!done() && System.currentTimeMillis() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
    shadowOf(Looper.getMainLooper()).idle()
  }
  private fun activity() = Robolectric.buildActivity(Activity::class.java).setup().get()

  @Test fun threeQuestionsInARowWithNoPinch() {
    // Partial captions decode too, so the fake answers by the turn being spoken, not by call count.
    val lines = listOf("What needs me?", "What got built today?", "What failed?")
    val k = java.util.concurrent.atomic.AtomicInteger(0)
    val (c, out) = controller(activity()) { _, _ -> lines[k.get()] }
    val answered = mutableListOf<String>()
    repeat(3) { i ->
      k.set(i)
      val before = out.played.size
      c.injectWav(wav().path)
      drainUntil(3000) { out.played.size > before && c.machine.state == MicState.IDLE }
      answered += c.machine.lastMatch!!.answer.id
    }
    assertEquals(listOf("needs_me", "built", "failed"), answered)
    assertEquals(3, out.played.size)
    assertEquals(MicState.IDLE, c.machine.state)
  }

  @Test fun talkThatIsNotForTheAppIsShownDimmedAndDropped() {
    val (c, out) = controller(activity()) { _, _ -> "my brother built a treehouse last summer and it is still standing" }
    c.injectWav(wav().path)
    drainUntil(3000) { c.captionHeard.contains("treehouse") }
    assertTrue(c.captionDimmed); assertEquals(0, out.played.size); assertEquals(MicState.IDLE, c.machine.state)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3100))
    assertEquals("dimmed for 3 s, then gone", "", c.captionHeard)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(6100))
    assertTrue("a thin strip after 6 s idle", c.captionStrip)
  }

  @Test fun itNeverHearsItsOwnReply() {
    val heard = mutableListOf<Int>()
    lateinit var c: VoiceController
    val (cc, out) = controller(activity()) { a, _ -> heard += a.size; "What failed?" }
    c = cc; out.echo = true
    c.injectWav(wav().path)
    drainUntil(3000) { out.played.size == 1 && c.machine.state == MicState.IDLE }
    val turn = c.loop.turn; val decodes = heard.size // partial captions decode too; what matters is nothing after the reply
    c.loop.feed(ShortArray(16000), 16000); drainUntil(200) { false }
    assertEquals("the echo of the reply opened no turn", turn, c.loop.turn)
    assertEquals("no decode of the reply", decodes, heard.size); assertEquals(1, out.played.size)
  }

  @Test fun muteStopsTheMicrophoneThreadAndIsRemembered() {
    val a = activity()
    shadowOf(a.application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
    val (c, _) = controller(a) { _, _ -> "mute" }
    c.hands(0, emptyMap()); drainUntil(200) { c.micRunning }
    assertTrue("unmuted and worn: listening", c.micRunning)
    c.injectWav(wav().path) // "mute", by voice
    drainUntil(3000) { c.muted }
    assertTrue(c.muted); assertFalse("muted means the thread is stopped, not ignored", c.micRunning)
    c.hands(100, emptyMap()); shadowOf(Looper.getMainLooper()).idle()
    assertFalse(c.micRunning)
    c.close()
    val (again, _) = controller(a) { _, _ -> "" }
    assertTrue("remembered across launches", again.muted)
    again.setMuted(false, "control"); again.hands(0, emptyMap()); drainUntil(200) { again.micRunning }
    assertTrue(again.micRunning)
  }

  @Test fun asleepMeansNotListening() {
    val a = activity()
    shadowOf(a.application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
    var worn = false
    val (c, out) = controller(a) { _, _ -> "What failed?" }
    c.worn = { worn }
    c.hands(0, emptyMap()); shadowOf(Looper.getMainLooper()).idle()
    assertFalse("asleep: the mic is off", c.micRunning)
    c.injectWav(wav().path); drainUntil(300) { false }
    assertEquals("asleep: nothing is a turn", 0, out.played.size)
    worn = true
    c.hands(50, emptyMap()); drainUntil(200) { c.micRunning }
    assertTrue(c.micRunning)
    worn = false
    c.hands(100, emptyMap()); shadowOf(Looper.getMainLooper()).idle()
    assertFalse("taken off: the mic stops", c.micRunning)
  }

  @Test fun theInterfaceCardPlugsInThroughOnFinalTranscript() {
    val (c, out) = controller(activity()) { _, _ -> "" }
    val ui = FakeUi(); c.commands = UiCommands(ui)
    c.machine.listen(); c.machine.endOfSpeech()
    assertTrue(c.onFinalTranscript("open settings", VoiceGate.confidence("open settings")))
    // #1056: a layout command with no named window only offers "Do it?"; pressed, it runs.
    c.machine.listen(); c.machine.endOfSpeech()
    assertFalse(c.onFinalTranscript("center the windows", VoiceGate.confidence("center the windows")))
    assertTrue(c.offering); c.pressDoIt()
    // Room talk that names a window is not a command, and is not answered either.
    c.machine.listen(); c.machine.endOfSpeech()
    assertFalse(c.onFinalTranscript("I'm about to open the fleet", VoiceGate.confidence("I'm about to open the fleet")))
    assertEquals(0, out.played.size); assertEquals(listOf("openSettings()", "centerWindows()"), ui.calls)
  }

  /** #1051: a line that is probably, not surely, for the app shows one "Do it?" chip and does nothing until pressed or said again. */
  @Test fun anUnsureLineOffersDoItAndActsOnlyWhenPressedOrRepeated() {
    val (c, out) = controller(activity()) { _, _ -> "" }
    val ui = FakeUi(); c.commands = UiCommands(ui)
    fun say(t: String): Boolean { c.machine.listen(); c.machine.endOfSpeech(); return c.onFinalTranscript(t, VoiceGate.confidence(t)) }
    // "New toy." was answered "built" in #1001's held-out half; now half its words are unexplained: an offer, no action.
    assertFalse(say("New toy.")); assertTrue(c.offering); assertEquals(0, out.played.size)
    assertEquals("“New toy.”", c.captionHeard)
    // Ignored: the next, unrelated line clears it and nothing ran.
    assertFalse(say("We should order pizza tonight.")); assertFalse(c.offering); assertEquals(0, out.played.size)
    // Pressed: the answer plays.
    assertFalse(say("New toy.")); assertTrue(c.offering)
    c.pressDoIt(); assertFalse(c.offering); assertEquals(1, out.played.size)
    // Repeated: a command offered, then said again, runs once.
    assertFalse(say("zoom out please i lost track")); assertTrue(c.offering); assertTrue(ui.calls.isEmpty())
    assertTrue(say("zoom out please i lost track")); assertEquals(listOf("zoomOut()"), ui.calls); assertFalse(c.offering)
    // A question put to someone in the room never acts.
    assertFalse(say("Did you remember to buy milk on the way home?")); assertTrue(ui.calls.size == 1); assertEquals(1, out.played.size)
  }
}
