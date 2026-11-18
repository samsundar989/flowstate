package com.overhand.app.features

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import com.meta.spatial.toolkit.LayoutXMLPanelRegistration
import com.meta.spatial.toolkit.PanelRegistration
import com.overhand.app.BuildConfig
import com.overhand.app.R
import com.overhand.app.data.LocalReceiptStore
import com.overhand.app.data.ModeGeneration
import com.overhand.app.data.RecordedSource
import com.overhand.app.decisions.DecisionCards
import com.overhand.app.hands.ThumbPose
import com.overhand.app.hostlink.recorded.RecordedBuildBundle
import com.overhand.app.hostlink.recorded.RecordedBuildRun
import com.overhand.app.voice.Route
import com.overhand.app.voice.SlotName
import com.overhand.app.voice.SmartRouter
import com.overhand.app.voice.UiActions
import com.overhand.app.voice.UiCommands
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
 * #1117: the judge's path, walked once per build type, as an event log. Run under testDebugUnitTest and
 * testRcUnitTest (release code) and diff the two logs: every difference must be a row of
 * runs/package/release_diff/branches.tsv. Each step logs what it saw rather than failing, so one broken step
 * cannot hide the rest of the walk. The production Shell over a never-created OverhandActivity (as LayoutLintCapture).
 * Writes $JOURNEY_OUT/journey_<buildType>.log, else build/.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReleaseJourneyTest {
  private val log = mutableListOf<String>()
  private fun ev(step: String, what: String) { log += "$step $what" }
  private val looper get() = shadowOf(Looper.getMainLooper())
  private fun idle(ms: Long) { repeat((ms / 20).toInt()) { Thread.sleep(1); looper.idleFor(Duration.ofMillis(20)) } }
  private fun all(v: View): List<View> = listOf(v) + (if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList())
  private fun buttons(v: View) = all(v).filterIsInstance<Button>().filter { it !is CheckBox }
  private fun bare(c: Class<*>): Any {
    val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, c)
  }
  @Suppress("UNCHECKED_CAST")
  private fun build(reg: PanelRegistration, column: View) {
    val f = LayoutXMLPanelRegistration::class.java.getDeclaredField("panelSetupWithRootView").apply { isAccessible = true }
    (f.get(reg) as Function3<View, Any?, Any?, Unit>).invoke(column,
      bare(com.meta.spatial.runtime.PanelSceneObject::class.java), bare(com.meta.spatial.core.Entity::class.java))
  }
  private fun step(name: String, body: () -> Unit) {
    runCatching(body).onFailure { ev(name, "error=${it.javaClass.simpleName}") }
  }

  @Test fun walkTheJudgeInstructions() {
    val activity = Robolectric.buildActivity(com.overhand.app.OverhandActivity::class.java).get()
    val shell = Shell(activity)
    val assets = File("src/main/assets")

    // 1. The app opens on the build site, in Recorded mode.
    step("open") {
      ev("open", "recorded=${shell.isRecorded()} classic_view=${shell.interaction.classicView} default_project=${shell.recordedContent.defaultProject}")
    }

    // 2-4. The first question card; select then Confirm writes a receipt; Next, then a held thumbs-up writes one.
    step("card") {
      activity.getSharedPreferences("hands-onboarding-v1", 0).edit().putBoolean("recorded", true).commit()
      val receiptsFile = Files.createTempDirectory("journey").resolve("receipts.json").toFile()
      val source = RecordedSource({ File(assets, it).readText() }, LocalReceiptStore(receiptsFile), null)
      val cards = DecisionCards(activity, { "" }, { source }, ModeGeneration()) { }
      val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
      activity.setContentView(column)
      cards.attach(column); cards.open(); idle(500)
      val none = mapOf("right" to ThumbPose.NONE, "left" to ThumbPose.NONE)
      cards.frame(none, true, 1)
      val first = all(column).filterIsInstance<TextView>().map { it.text.toString() }.firstOrNull { it.endsWith("?") }
      ev("card", "first_question=${first != null} text=\"${first?.take(60)}\"")
      fun receipts() = if (receiptsFile.exists()) JSONArray(receiptsFile.readText()).length() else 0
      val option = buttons(column).firstOrNull { it.tag == com.overhand.app.onboarding.HandsOnboarding.OPTION_TAG }
      ev("card", "option_found=${option != null}")
      option?.performClick(); idle(300)
      val confirm = buttons(column).firstOrNull { it.text.startsWith("Confirm") }
      ev("card", "confirm_found=${confirm != null}")
      confirm?.performClick()
      repeat(300) { if (receipts() >= 1) return@repeat; Thread.sleep(5); idle(20) }
      ev("confirm", "receipts=${receipts()}")
      idle(5000)
      buttons(column).firstOrNull { it.text == "Next decision" }?.let { it.performClick(); ev("thumbs", "next=true") } ?: ev("thumbs", "next=false")
      idle(300); cards.frame(none, true, 1)
      buttons(column).firstOrNull { it.tag == com.overhand.app.onboarding.HandsOnboarding.OPTION_TAG }?.performClick(); idle(300)
      // A released hand, then a thumbs-up held 2 s on the frame path (the ring fills, then the receipt).
      repeat(10) { cards.frame(none, true, 1); idle(50) }
      repeat(60) { cards.frame(mapOf("right" to ThumbPose.UP, "left" to ThumbPose.NONE), true, 1); idle(50) }
      repeat(100) { if (receipts() >= 2) return@repeat; Thread.sleep(5); idle(20) }
      ev("thumbs", "receipts=${receipts()}")
    }

    // 5. The dock: each window target queues its open.
    step("dock") {
      val root = activity.layoutInflater.inflate(R.layout.control_dock, null) as LinearLayout
      ControlDock.build(root) { view ->
        ControlDockFeature::class.java.getDeclaredMethod("pick",String::class.java).apply { isAccessible=true }.invoke(ControlDockFeature(shell),view)
      }
      for (b in all(root).filter { it.tag?.toString()?.startsWith("control-dock-") == true }) {
        val before = shell.commands.size
        b.performClick()
        ev("dock", "press ${b.tag} queued=${shell.commands.size - before}")
      }
    }

    // 6. Settings opens; every switch toggles (each listed, then flipped twice); every button is listed.
    step("settings") {
      val sp = WindowLayout.defaultSpecs.first { it.view == "settings" }
      val reg = shell.windows.panels().first { it.registrationId == sp.id }
      val root = activity.layoutInflater.inflate(R.layout.web_panel, null)
      activity.setContentView(root)
      build(reg, root); idle(200)
      for (v in all(root)) when (v) {
        is CheckBox -> {
          val was = v.isChecked
          val r = runCatching { v.isChecked = !was; val mid = v.isChecked; v.isChecked = was; mid != was }
          ev("settings", "switch \"${v.text}\" toggles=${r.getOrNull() ?: ("error=" + r.exceptionOrNull()!!.javaClass.simpleName)}")
        }
        is Button -> ev("settings", "button \"${v.text}\"")
        else -> {}
      }
    }

    // 7. The replay of the night starts (the Recorded day's task replay steps forward from the start).
    step("replay") {
      val r = shell.recordedContent.taskReplay
      r.restart(); val c0 = r.clock; r.seek(r.count / 2)
      ev("replay", "steps=${r.count} start=\"$c0\" moved=${r.clock != c0}")
    }

    // 8. The build-from-nothing replay plays to the end.
    step("bfn") {
      val b = RecordedBuildBundle({ File(assets, it).readText() }, { File(assets, it).readBytes() })
      val queue = mutableListOf<Pair<Long, Runnable>>()
      val run = RecordedBuildRun(b) { at, r -> queue.add(at to r) }
      var events = 0; run.link.eventListeners.add { events++ }
      run.start(); queue.sortedBy { it.first }.forEach { it.second.run() }
      Thread.sleep(200)
      ev("bfn", "frames=${b.frames.size} fed=${run.fed} events=${events > 0} banner=\"${run.banner}\"")
      run.stop()
    }

    // 9. One fixed voice command acts ("open settings").
    step("voice") {
      val did = mutableListOf<String>()
      val ui = object : UiActions {
        override fun windows() = listOf(SlotName("code", "Code"), SlotName("fleet", "Fleet"))
        override fun projects() = emptyList<SlotName>()
        override fun currentProject() = ""
        override fun parts() = emptyList<SlotName>()
        override fun closeAll() = emptyList<String>().also { did += "closeAll" }
        override fun openWindow(key: String) { did += "open:$key" }
        override fun closeWindow(key: String) { did += "close:$key" }
        override fun showArchitecture(project: String) { did += "graph" }
        override fun hideArchitecture() { did += "nograph" }
        override fun openProject(project: String) { did += "project" }
        override fun zoomInto(part: String) { did += "zoom" }
        override fun zoomOut() { did += "zoomout" }
        override fun centerWindows() { did += "center" }
        override fun openSettings() { did += "settings" }
        override fun closeSettings() { did += "nosettings" }
        override fun mute(on: Boolean) { did += "mute:$on" }
      }
      val r = UiCommands(ui).handle("open settings")
      ev("voice", "action=${r.action} acted=${r.acted} did=$did")
    }

    // 10. Smart answers off: a question comes back with a canned answer, no relay.
    step("canned") {
      val intents = VoiceIntents.parse(File("src/main/res/raw/voice_answers.json").readText())
      val m = intents.match("what needs me")
      ev("canned", "kind=${m.kind} route=${SmartRouter.route(m, smartOn = false)} answer_id=${m.answer.id} relay=${SmartRouter.route(m, false) == Route.RELAY}")
    }

    val out = File(System.getenv("JOURNEY_OUT") ?: "build").apply { mkdirs() }
    File(out, "journey_${BuildConfig.BUILD_TYPE}.log").writeText(log.joinToString("\n") + "\n")
    println("journey build_type=${BuildConfig.BUILD_TYPE} events=${log.size}")
    assertTrue("the walk logged nothing", log.size > 10)
  }
}
