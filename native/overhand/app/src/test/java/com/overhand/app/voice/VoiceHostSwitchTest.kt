package com.overhand.app.voice

import android.app.Activity
import android.os.Looper
import com.overhand.app.hands.Finger
import com.overhand.app.hands.HandJoints
import com.overhand.app.hands.Point
import com.overhand.app.hostlink.project.NewProjectFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sin
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * #862: the first spoken request after connecting was dropped on the device in both #815 runs.
 *
 * What runs/host/voice_project/logcat_project.txt shows: every `am start` the device script sent (one per trace poll)
 * paused the activity, and VoiceController.pause() reset the turn. The turn count is exactly one per intent (run 2: 8 at
 * p1 = 2 resets at start + 4 intents + inject's reset and pinch; 8 -> 25 over the ~17 polls before "yes"). The poll
 * right after p1's injection landed while its 7.3 s clip was still being decoded (438 ms), so `heard turn=8` met a
 * machine already on turn 9 and IDLE and VoiceMachine.heard dropped it. p2/p3 decoded faster than the poll's adb round trip.
 * A real wearer pauses the app the same way (a system dialog, the menu, taking the headset off mid-decode).
 *
 * This replays that lifecycle off the device: voice on in Recorded, the switch to host mode (the activity is recreated:
 * the old controller closes, a new one routes transcripts to the project flow), one request injected, a pause while it
 * decodes, then the decode lands.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceHostSwitchTest {
  private val intents = VoiceIntents.parse(File("src/main/res/raw/voice_answers.json").readText())
  private val request = "New project, a command line tool that prints the time. Ask me first whether it should show seconds."
  private val made = mutableListOf<VoiceController>()

  @After fun closeAll() { made.forEach { it.close() } }

  private fun controller(a: Activity, ears: Transcriber) = VoiceController(a, intents, ears, { ShortArray(1600) }, 16000).also { made += it; it.answerToName = false } // #1080: these test nov14b's always-on gate ("Answer to my name" off).

  /** 2 s of a loud tone as a 16 kHz PCM16 WAV: not silent, not too short. */
  private fun wav(): File {
    val n = 32000; val pcm = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until n) pcm.putShort((sin(i * 0.05) * 12000).toInt().toShort())
    val out = ByteArrayOutputStream(); val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    h.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
      .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(n * 2)
    out.write(h.array()); out.write(pcm.array())
    return File.createTempFile("p1_new", ".wav").apply { writeBytes(out.toByteArray()); deleteOnExit() }
  }

  private fun drainUntil(ms: Long, done: () -> Boolean) {
    val end = System.currentTimeMillis() + ms
    while (!done() && System.currentTimeMillis() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
    shadowOf(Looper.getMainLooper()).idle()
  }

  @Test fun theFirstRequestAfterSwitchingToHostSurvivesAPauseWhileItDecodes() {
    val a = Robolectric.buildActivity(Activity::class.java).setup().get()
    // Voice on in Recorded (the app as it launches).
    val recorded = controller(a) { _, _ -> "what needs me" }
    assertEquals(1, VoiceController.live)
    // Pairing switches to host: the activity is recreated, so the Recorded controller closes and a host one starts.
    recorded.close()
    val gate = CountDownLatch(1)
    val host = controller(a) { _, _ -> gate.await(5, TimeUnit.SECONDS); request }
    val flow = NewProjectFlow(); val reached = mutableListOf<String>()
    host.routeTo({ t -> reached += t; flow.heard(t); true }, com.overhand.app.hostlink.project.ProjectScreens.HINT)
    assertEquals("exactly one live controller after the switch", 1, VoiceController.live)

    val turns = mutableListOf(host.machine.turn)
    host.injectWav(wav().path)
    turns += host.machine.turn
    assertEquals(MicState.THINKING, host.machine.state)
    // The device script's next `am start` (a trace poll) pauses the activity while the request is still decoding.
    host.pause(); turns += host.machine.turn
    gate.countDown()
    drainUntil(3000) { reached.isNotEmpty() }
    turns += host.machine.turn

    assertEquals("the decoded request reaches the project flow", listOf(request), reached)
    assertTrue("the chip shows", flow.state is NewProjectFlow.State.Chip)
    assertEquals(MicState.IDLE, host.machine.state)
    assertEquals("turn numbers only go up: $turns", turns.sorted(), turns)
  }

  @Test fun aPauseStillCutsAHoldThatIsListening() {
    val a = Robolectric.buildActivity(Activity::class.java).setup().get()
    shadowOf(a.application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
    val c = controller(a) { _, _ -> "never" }
    c.routeTo({ true }, "hint")
    c.machine.pinchStart(); assertEquals(MicState.LISTENING, c.machine.state)
    val t = c.machine.turn
    c.pause()
    assertEquals(MicState.IDLE, c.machine.state); assertTrue(c.machine.turn > t)
  }

  @Test fun closingTwiceCountsOnce() {
    val a = Robolectric.buildActivity(Activity::class.java).setup().get()
    val c = controller(a) { _, _ -> "" }
    assertEquals(1, VoiceController.live)
    c.close(); c.close()
    assertEquals(0, VoiceController.live)
  }

  /** A hand whose thumb tip is [gap] metres from its index tip (as PinchHoldTest). */
  private fun hand(gap: Double): HandJoints {
    val p = Point(0.0, 0.0, 0.0); val f = Finger(p, p, p, Point(0.1, 0.0, 0.0))
    return HandJoints(p, Finger(p, p, p, Point(0.1 + gap, 0.0, 0.0)), listOf(f, f, f, f))
  }

  /** #1001: turns are found by voice now; a held pinch, worn or asleep, is never a turn (#862's asleep case: VoiceAlwaysOnTest). */
  @Test fun aHeldPinchIsNeverATurn() {
    val a = Robolectric.buildActivity(Activity::class.java).setup().get()
    val c = controller(a) { _, _ -> "" }
    for (worn in listOf(false, true)) {
      c.worn = { worn }
      val t = c.machine.turn
      for (ms in 0L..1200L step 50) c.hands(10_000 + ms, mapOf("right" to hand(0.01)))
      c.hands(11_300, mapOf("right" to hand(0.05)))
      shadowOf(Looper.getMainLooper()).idle()
      assertEquals("worn=$worn: a held pinch starts no turn", t, c.machine.turn)
      assertEquals(MicState.IDLE, c.machine.state)
    }
  }
}
