package com.overhand.app.features

import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import com.overhand.app.LaunchTrace
import com.overhand.app.OverhandActivity
import com.overhand.app.data.LiveSource
import com.overhand.app.data.LocalReceiptStore
import com.overhand.app.data.ModeGeneration
import com.overhand.app.data.RecordedSource
import com.overhand.app.data.SessionSource
import com.overhand.app.windows.RecordedContent
import com.overhand.app.windows.RecordedWindows
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.roundToInt

/** One feature's activity wiring. OverhandActivity only fans its callbacks out to these. */
interface Feature {
  /** Key of this feature's single entry in the debug-intent dispatch map. */
  val name: String
  fun onPreCreate() {}
  fun onCreate() {}
  /** Every scene, including ladder probes. */
  fun onSceneStart() {}
  /** Normal (non-ladder) scenes only. */
  fun onSceneReady() {}
  /** ECS thread, ~10 Hz, after queued commands ran. */
  fun onFrame(now: Long) {}
  fun onPause() {}
  fun onResume() {}
  fun onDestroy() {}
  fun onDebugIntent(intent: Intent) {}
  fun settingsRows(): List<SettingsRow> = emptyList()
}

/** A Settings row; rows from all features are built in ascending [order] onto the page of their [tab] (#1191: Settings
 *  has pages, nothing scrolls). A row can move its later views to another page with [settingsTab]. [first]: built before
 *  every other row, so its views open their page. */
class SettingsRow(val order: Int, val tab: SettingsTab, val first: Boolean = false, val build: (root: LinearLayout, container: LinearLayout) -> Unit)

/** State shared by features, and the features themselves (in dispatch order). */
class Shell(val activity: OverhandActivity) {
  val ui = Handler(Looper.getMainLooper())
  val commands = ConcurrentLinkedQueue<() -> Unit>()
  lateinit var launchTrace: LaunchTrace
  @Volatile var paused = false
  var stopped = false
  /** Earliest wall time the frame loop runs (tracking settles first). */
  var ready = 0L
  /** Debug builds only: an injected head pose for the recenter device check (never real-hand evidence). */
  @Volatile var fakeHead: com.meta.spatial.core.Pose? = null
  /** The head pose every placement uses. */
  fun head(): com.meta.spatial.core.Pose = fakeHead ?: activity.scene.getViewerPose()
  val generation = ModeGeneration()
  val prefs by lazy {
    activity.getSharedPreferences("windows-v1", android.content.Context.MODE_PRIVATE).also { store ->
      if(!store.getBoolean("layout-keys-v2",false)) {
        // t261's resource IDs, before MRUK adds resources. Never interpret them as
        // current R.id values: those can shift (and collide with another window).
        val legacy=mapOf("fleet" to listOf(0x7f050039,0x7f050038),"blueprint" to listOf(0x7f05002d),
          "code" to listOf(0x7f050035),"build" to listOf(0x7f050067,0x7f050066),
          "chat" to listOf(0x7f050033),"settings" to listOf(0x7f050051,0x7f050050))
        val edit=store.edit()
        legacy.forEach { (view,ids) ->
          if(!store.contains("global/$view")) ids.firstNotNullOfOrNull { store.getString("global/$it",null) }?.let {
            edit.putString("global/$view",it)
          }
          ids.forEach { edit.remove("global/$it") }
        }
        edit.putBoolean("layout-keys-v2",true).apply()
      }
    }
  }
  val recordedSource: RecordedSource by lazy {
    RecordedSource({ path -> activity.assets.open(path).bufferedReader().use { it.readText() } },
      LocalReceiptStore(java.io.File(activity.filesDir,"judge-receipts.json")),
      receiptChanged={ ui.post { if(!stopped) recordedWindows.refresh() } },
      requested=prefs.getString("recorded-dataset",RecordedSource.DAY)!!)
  }
  /** Host link (#814, docs/host/CLIENT.md): the paired computer over Wi-Fi. Every send is gated by the Live answers switch. */
  val hostDiscovery by lazy { com.overhand.app.hostlink.NsdHostDiscovery(activity) }
  private val hostLinkLazy = lazy { com.overhand.app.hostlink.HostLink(com.overhand.app.hostlink.KeystorePairedHostStore(activity),
    hostDiscovery,deviceName="Headset",mayTransmit={ com.overhand.app.decisions.LiveSafety.sendReal }) }
  val hostLink by hostLinkLazy
  val hostLinkMade get()=hostLinkLazy.isInitialized()
  val sessionSource: SessionSource by lazy {
    when(prefs.getString("session-mode","recorded")) {
      "host" -> com.overhand.app.hostlink.HostSource(hostLink,com.overhand.app.data.LivePendingStore(java.io.File(activity.filesDir,"host-pending.json"))).also { hostLink.start() }
      "live" -> LiveSource.at(server(),com.overhand.app.data.LivePendingStore(java.io.File(activity.filesDir,"live-pending.json")))
      else -> recordedSource
    }
  }
  fun isRecorded()=sessionSource.recorded
  val recordedContent: RecordedContent by lazy { RecordedContent(recordedSource) }
  val recordedWindows: RecordedWindows by lazy { RecordedWindows(recordedContent,activity).also { // #1382 look K3 hooks
    it.showWindow={ v,why -> windows.open(v,why) };it.closeWindow={ v,why -> windows.dockToggle(v,why) }
    it.askPart={ p,m,n,st -> interaction.site.askPart(p,m,n,st) } } }
  fun server() = prefs.getString("server", USB)!!
  fun label(root: LinearLayout, text: String, size: Float=22f) = Shell.label(root, text, size)

  // Constructors only store references; cross-feature access happens at runtime.
  val layout = WindowLayout(this)
  val dashboards = Dashboards(this)
  val ladder = LadderProbe(this)
  val audio = AudioFeature(this)
  val mr = MixedRealityFeature(this)
  val polish = PolishFeature(this)
  val interaction = InteractionFeature(this)
  val onboarding = OnboardingFeature(this)
  val windows = WindowsFeature(this)
  val session = SessionModeFeature(this)
  val settings = SettingsFeature(this)
  val voice = VoiceFeature(this)
  val opening = OpeningFeature(this)
  val tapAll = TapAllFeature(this)
  val recenter = RecenterFeature(this)
  val grab = GrabFeature(this)
  val connect = com.overhand.app.features.connect.ConnectFeature(this)
  val project = com.overhand.app.features.project.ProjectWindowFeature(this)
  val recordedBuild = com.overhand.app.hostlink.recorded.RecordedBuildFeature(this)
  val frameRate = FrameRateFeature(this)
  val poseLog = PoseLogFeature(this)
  val lagCheck = LagCheckFeature(this)
  /** Order is the dispatch order for every hook, and for debug intents. */
  // One registration per line, append new features at the end (merge-friendly, #863).
  val features: List<Feature> = listOf(
    LaunchCutsFeature(this), // #1201: first, so its launch prefs are written before any feature reads them
    audio,
    mr,
    polish,
    interaction,
    grab,
    onboarding,
    recenter,
    windows,
    session,
    settings,
    voice,
    opening,
    com.overhand.app.places.PlacesFeature(this),
    tapAll,
    connect,
    project,
    com.overhand.app.first60.First60Feature(this),
    com.overhand.app.design.ComposePreview(this),
    recordedBuild,
    frameRate,
    lagCheck,
    run { val dock = ControlDockFeature(this); dock },
    poseLog,
  )
  val debugIntents: Map<String, (Intent) -> Unit> = features.associateTo(linkedMapOf()) { f -> f.name to { i: Intent -> f.onDebugIntent(i) } }

  companion object {
    const val TAG="Overhand"
    const val USB="http://127.0.0.1:8790"
    /** The one label style (#816): white text on the shared inset. Every window line uses it, so all share one left edge. */
    fun label(root: LinearLayout, text: String, size: Float=22f) = inset(TextView(root.context).apply {
      this.text=text; textSize=size; setTextColor(Color.WHITE)
    })
    /** The shared inset ([com.overhand.app.windows.PolishRules.INSET_DP]) in this view's pixels. */
    fun <T: TextView> inset(v: T): T = v.apply {
      val d=resources.displayMetrics.density
      val h=(com.overhand.app.windows.PolishRules.INSET_DP*d).roundToInt();val t=(com.overhand.app.windows.PolishRules.INSET_V_DP*d).roundToInt()
      setPadding(h,t,h,t)
    }
  }
}
