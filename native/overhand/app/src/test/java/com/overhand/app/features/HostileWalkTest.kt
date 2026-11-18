package com.overhand.app.features

import android.Manifest
import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import com.overhand.app.BuildConfig
import com.overhand.app.data.LocalReceiptStore
import com.overhand.app.data.ModeGeneration
import com.overhand.app.data.RecordedSource
import com.overhand.app.decisions.DecisionCards
import com.overhand.app.hands.ThumbPose
import com.overhand.app.mr.ScenePermissionCoordinator
import com.overhand.app.onboarding.HandsOnboarding
import com.overhand.app.voice.Mic
import com.overhand.app.voice.ReplyOut
import com.overhand.app.voice.VoiceController
import com.overhand.app.voice.VoiceIntents
import java.io.File
import java.nio.file.Files
import java.time.Duration
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #1204: a hostile first-timer's choices, walked on the release code (run under testRcUnitTest; testDebugUnitTest is
 * the control). Each step logs what the card shows first and whether one action ends in a receipt; it never fails a
 * step, so one broken step cannot hide the rest. Writes $WALK_OUT/walk_<buildType>.log, else build/.
 * Not a headset: no compositor, no real permission dialog, no hand tracking, no frame timing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HostileWalkTest {
  private val log = mutableListOf<String>()
  /** #1206: what the mic_refused step saw, asserted after the log is written. */
  private val refused = mutableMapOf<String, String>()
  private fun ev(step: String, what: String) { log += "$step $what" }
  private val looper get() = shadowOf(Looper.getMainLooper())
  private fun idle(ms: Long) { repeat((ms / 20).toInt()) { Thread.sleep(1); looper.idleFor(Duration.ofMillis(20)) } }
  private fun all(v: View): List<View> = listOf(v) + (if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList())
  private fun buttons(v: View) = all(v).filterIsInstance<Button>().filter { it !is CheckBox }
  private fun texts(v: View) = all(v).filterIsInstance<TextView>().filter { it !is Button && it.visibility == View.VISIBLE }.map { it.text.toString() }.filter { it.isNotBlank() }
  private fun flat(lines: List<String>) = "\"" + lines.joinToString(" | ").replace('\n', ' ').take(500) + "\""
  private fun q(s: String?) ="\"" + (s ?: "").replace("\n", " ").take(90) + "\""
  private fun step(name: String, body: () -> Unit) { runCatching(body).onFailure { ev(name, "error=${it.javaClass.simpleName}:${it.message?.take(80)}") } }
  private val assets = File("src/main/assets")
  private val none = mapOf("right" to ThumbPose.NONE, "left" to ThumbPose.NONE)
  private val up = mapOf("right" to ThumbPose.UP, "left" to ThumbPose.NONE)

  /** One recorded card over [receipts]; [tutorialSeen] false = a first launch. */
  private inner class Card(val activity: Activity, val receipts: File, tutorialSeen: Boolean) {
    val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    val cards: DecisionCards
    init {
      if (tutorialSeen) activity.getSharedPreferences("hands-onboarding-v1", 0).edit().putBoolean("recorded", true).commit()
      val source = RecordedSource({ File(assets, it).readText() }, LocalReceiptStore(receipts), null)
      cards = DecisionCards(activity, { "" }, { source }, ModeGeneration()) { }
      activity.setContentView(column)
      cards.attach(column); cards.open(); idle(500); cards.frame(none, true, 1)
    }
    fun count() = if (receipts.exists()) JSONArray(receipts.readText()).length() else 0
    fun first() = texts(column).firstOrNull { it.trim().endsWith("?") }
    fun shown() = texts(column)
    /** Pinch the first option, then pinch Confirm; if the card is still waiting, hold a thumbs-up 2 s. */
    fun act(name: String): Int {
      val before = count()
      buttons(column).firstOrNull { it.tag == HandsOnboarding.OPTION_TAG }?.performClick() ?: ev(name, "option_found=false")
      idle(300)
      buttons(column).firstOrNull { it.text.startsWith("Confirm") }?.performClick() ?: ev(name, "confirm_found=false")
      repeat(200) { if (count() > before) return@repeat; Thread.sleep(5); idle(20) }
      if (count() == before) {
        repeat(10) { cards.frame(none, true, 1); idle(50) }
        repeat(60) { cards.frame(up, true, 1); idle(50) }
        repeat(100) { if (count() > before) return@repeat; Thread.sleep(5); idle(20) }
      }
      val early = texts(column).any { it.startsWith("Practice answer saved") }
      idle(1500) // the receipt is drawn on a later main-loop turn than the file write: read the card after it settles
      ev(name, "receipt_drawn_at_write=$early")
      val receipt = texts(column).firstOrNull { it.contains("saved", ignoreCase = true) || it.contains("Saved") }
      ev(name, "receipts_before=$before receipts_after=${count()} receipt_line=${q(receipt)}")
      ev(name, "shown_after=" + flat(texts(column)) + " buttons=" + buttons(column).map { it.text.toString() })
      return count() - before
    }
  }
  private fun tmp() = Files.createTempDirectory("walk").resolve("receipts.json").toFile()
  private fun activity() = Robolectric.buildActivity(Activity::class.java).setup().get()
  /** The production activity, never created (as ReleaseJourneyTest): DecisionCards needs its type. */
  private fun app() = Robolectric.buildActivity(com.overhand.app.OverhandActivity::class.java).get()

  private class SilentOut : ReplyOut {
    override fun play(pcm: ShortArray, onFirstAudio: (Long) -> Unit, onDone: () -> Unit) { onDone() }
    override fun stop() {}
    override fun release() {}
  }
  private class DeadMic : Mic { override fun start() {}; override fun stop() {}; override val alive = false }

  @Test fun walkTheHostileChoices() {
    val intents = VoiceIntents.parse(File("src/main/res/raw/voice_answers.json").readText())

    // 1. First launch, the hand tutorial in the way: nothing seen before.
    step("tutorial") {
      val c = Card(app(), tmp(), tutorialSeen = false)
      ev("tutorial", "first_question=${q(c.first())} lines=${c.shown().size} shown_before=" + flat(c.shown()) + " buttons=" + buttons(c.column).map { it.text.toString() })
      c.act("tutorial")
      ev("tutorial", "after=${q(c.shown().take(4).joinToString(" | "))}")
    }

    // 2. Microphone refused: the voice header, the mic thread, then the card by hand.
    step("mic_refused") {
      val a = activity()
      shadowOf(a.application).denyPermissions(Manifest.permission.RECORD_AUDIO)
      var heard = 0; var mics = 0
      val v = VoiceController(a, intents, { _, _ -> heard++; "what needs me" }, { ShortArray(1600) }, 16000)
      v.player = SilentOut(); v.newMic = { _ -> mics++; DeadMic() }
      v.resume(); idle(200)
      val asked = shadowOf(a).lastRequestedPermission?.requestedPermissions?.toList()
      // #1230: while the system prompt is open (asked, no answer yet) the window still says main's "Listening" and why.
      refused["prompt_word"] = texts(v.view).firstOrNull() ?: ""
      ev("mic_refused", "prompt_open header=${q(texts(v.view).joinToString(" | "))}")
      // The wearer refuses: the result OverhandActivity passes on (by reflection: main's file has no permissionResult).
      runCatching { v.javaClass.getMethod("permissionResult", Int::class.java, IntArray::class.java) }.getOrNull()
        ?.invoke(v, com.overhand.app.voice.Voice.PERMISSION_REQUEST, intArrayOf(android.content.pm.PackageManager.PERMISSION_DENIED))
      idle(200)
      ev("mic_refused", "asked=$asked mic_running=${v.micRunning} header=${q(texts(v.view).joinToString(" | "))}")
      val words = texts(v.view).maxOfOrNull { it.trim().split(Regex("\\s+")).size } ?: 0
      ev("mic_refused", "longest_header_words=$words")
      refused["word"] = texts(v.view).firstOrNull() ?: ""; refused["words"] = "$words"
      refused["hand"] = "${texts(v.view).any { it.contains("by hand", ignoreCase = true) }}"
      refused["running_first"] = "${v.micRunning}"
      v.pause(); v.resume(); idle(200)
      ev("mic_refused", "after_resume mic_running=${v.micRunning} header_word=${q(texts(v.view).firstOrNull())}")
      ev("mic_refused", "mic_threads_started=$mics recogniser_calls=$heard")
      refused["running_again"] = "${v.micRunning}"; refused["mics"] = "$mics"; refused["heard"] = "$heard"
      val c = Card(app(), tmp(), tutorialSeen = true)
      ev("mic_refused", "first_question=${q(c.first())}")
      refused["hand"] = (c.act("mic_refused") > 0).toString() // The new look teaches by controls; prove the actual hand fallback.
      v.close()
    }

    // 3. Every prompt refused: mic and Spatial Data both denied; startup must not ask for room access.
    step("all_refused") {
      val a = app()
      shadowOf(a.application).denyPermissions(Manifest.permission.RECORD_AUDIO, "com.oculus.permission.USE_SCENE")
      val trace = mutableListOf<String>()
      val sc = ScenePermissionCoordinator({ false }, { true }, { trace += "request" }, { trace += "load" }, { trace += "fallback:$it" })
      sc.refresh(); sc.resumed()
      ev("all_refused", "scene_trace=$trace")
      val c = Card(a, tmp(), tutorialSeen = true)
      ev("all_refused", "first_question=${q(c.first())}")
      c.act("all_refused")
    }

    // 4. Idle five minutes with the card open, then act.
    step("idle_5min") {
      val c = Card(app(), tmp(), tutorialSeen = true)
      val before = c.first()
      repeat(300) { looper.idleFor(Duration.ofSeconds(1)); c.cards.frame(none, false, 0) }
      ev("idle_5min", "first_question_before=${q(before)} after=${q(c.first())} lines=${c.shown().size}")
      c.act("idle_5min")
    }

    // 5. Quit and relaunch: a new activity over the same receipts file and preferences.
    step("relaunch") {
      val receipts = tmp()
      val c1 = Card(app(), receipts, tutorialSeen = false)
      c1.act("relaunch_first")
      val c2 = Card(app(), receipts, tutorialSeen = false)
      ev("relaunch", "second_launch_first_question=${q(c2.first())} first_lines=${q(c2.shown().take(4).joinToString(" | "))}")
      c2.act("relaunch_second")
    }

    // 6. No network from launch: Recorded mode reads only the APK's assets; the card is the same.
    step("no_network") {
      val a = app()
      val cm = a.getSystemService(android.net.ConnectivityManager::class.java)
      shadowOf(cm).setActiveNetworkInfo(null)
      val c = Card(a, tmp(), tutorialSeen = true)
      ev("no_network", "active_network=${cm.activeNetworkInfo} first_question=${q(c.first())}")
      c.act("no_network")
      val m = intents.match("what needs me")
      ev("no_network", "what_needs_me kind=${m.kind} answer_id=${m.answer.id}")
    }

    val out = File(System.getenv("WALK_OUT") ?: "build").apply { mkdirs() }
    File(out, "walk_${BuildConfig.BUILD_TYPE}.log").writeText(log.joinToString("\n") + "\n")
    println("walk build_type=${BuildConfig.BUILD_TYPE} events=${log.size}")
    assertTrue("the walk logged nothing", log.size > 8)
    // #1206: refused means the window says so in a few words, names the hand path, and nothing listens.
    val says = refused["word"]; val n = refused["words"]?.toIntOrNull() ?: 99
    println("mic_refused header_word=\"$says\" longest_words=$n names_hand=${refused["hand"]} mic_threads=${refused["mics"]} recogniser_calls=${refused["heard"]}")
    assertTrue("mic_refused: while the prompt is open the header says \"${refused["prompt_word"]}\"", refused["prompt_word"] == "Listening")
    assertTrue("mic_refused: the header still says \"$says\"", says != null && says != "Listening")
    assertTrue("mic_refused: longest line is $n words", n <= 15)
    assertTrue("mic_refused: hand fallback did not produce a receipt", refused["hand"] == "true")
    assertTrue("mic_refused: a mic thread or recogniser ran $refused",
      refused["running_first"] == "false" && refused["running_again"] == "false" && refused["mics"] == "0" && refused["heard"] == "0")
  }
}
