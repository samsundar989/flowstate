package com.overhand.app.hostlink.recorded
import com.overhand.app.features.windowPose
import com.overhand.app.features.windowVisible

import android.os.SystemClock
import android.content.Intent
import android.util.Log
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.meta.spatial.isdk.IsdkGrabbable
import com.meta.spatial.toolkit.DpDisplayOptions
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.ViewPanelRegistration
import com.meta.spatial.toolkit.Visible
import com.overhand.app.BuildConfig
import com.overhand.app.features.Feature
import com.overhand.app.features.Shell
import com.overhand.app.graph.LivePlan
import com.overhand.app.hostlink.project.PreviewModel
import com.overhand.app.hostlink.project.ProjectPanelView
import com.overhand.app.hostlink.project.ProjectScreen
import com.overhand.app.hostlink.project.ProjectTimeline
import com.overhand.app.windows.PreviewPanelView
import com.overhand.app.windows.Theme
import org.json.JSONObject
import kotlin.math.atan2

/**
 * #1008: Recorded mode's third project, "Build from nothing": the golden host run replayed through the live path
 * ([RecordedBuildRun] feeds [com.overhand.app.hostlink.HostLink]'s own frame handler), so the plan lands on the graph
 * ([com.overhand.app.graph.ArchitectureGraph.liveEvent]), the commit receipt and the playable Preview come from
 * [PreviewModel] fetching the committed files by `get_file`, and the follow-up reloads it at the second commit.
 * Started only from the Recorded project list ([start]); it never starts by itself. Every screen says it is recorded.
 * Log marks for a worn check (tag Overhand): `bfn replay started`, `bfn parts built n of n`, `bfn preview ready`, `bfn page taps`.
 */
class RecordedBuildFeature(private val s: Shell) : Feature {
  override val name = "recorded_build"
  val bundle: RecordedBuildBundle? by lazy {
    RecordedBuildBundle.load({ p -> s.activity.assets.open(p).bufferedReader().use { it.readText() } },
      { p -> s.activity.assets.open(p).use { it.readBytes() } })
  }
  private var replay: RecordedBuildRun? = null
  private var runNo = 0
  private var began = 0L
  private var timeline: ProjectTimeline? = null
  private var plan: LivePlan? = null
  private var preview: PreviewModel? = null
  private var caption = ""
  private var question: JSONObject? = null
  private var pickNote = ""
  private var lastBuilt = -1
  private var pageTaps = ""
  private var column: LinearLayout? = null
  private var entity: Entity? = null
  private var registered = false
  private var showing = false
  private var previewParts: PreviewPanelView.Parts? = null
  private var previewEntity: Entity? = null
  private var previewRegistered = false
  private var previewShowing = false
  private var previewLoadedSha = ""

  /** The Recorded project list's entry chose it: (re)start the replay from the first frame. */
  fun start() {
    val b = bundle ?: run { log("bundle missing"); return }
    stopRun()
    val gen = ++runNo
    val r = RecordedBuildRun(b) { at, task -> s.ui.postDelayed({ if (gen == runNo && !s.stopped) task.run() }, at) }
    replay = r; began = SystemClock.uptimeMillis()
    timeline = ProjectTimeline(b.projectId); plan = LivePlan(b.projectId)
    preview = PreviewModel(b.projectId, { f -> r.link.request(f) })
    caption = ""; question = null; pickNote = ""; lastBuilt = -1; pageTaps = ""; previewLoadedSha = ""
    s.interaction.architecture.track(b.projectId, emptyList(), fresh = true, recorded = true)
    r.link.eventListeners.add { e -> s.ui.post { if (gen == runNo && !s.stopped) onEvent(b, e) } }
    r.link.frameListeners.add { f -> s.ui.post { if (gen == runNo && !s.stopped && preview?.onFrame(f) == true) renderPreview() } }
    log("replay started run=$gen frames=${b.frames.size} real_ms=${b.realElapsedMs} shown_ms=${r.playMs} \"${r.banner}\"")
    open(); r.start(); s.ui.postDelayed({ clockLine(gen) }, 1000)
  }

  private fun stopRun() { replay?.stop(); replay = null; runNo++ }

  private fun onEvent(b: RecordedBuildBundle, e: JSONObject) {
    val tl = timeline ?: return
    tl.add(e)
    val kind = e.optString("kind")
    val arch = s.interaction.architecture
    if (arch.liveEvent(e) && kind == "plan") arch.open(b.projectId)   // the plan appears on the graph
    plan?.let { p ->
      p.add(e)
      val built = p.count("built")
      if (p.hasPlan && built != lastBuilt) { lastBuilt = built; log("parts built $built of ${p.parts.size}") }
    }
    when (kind) {
      "question" -> { question = e; pickNote = "" }
      "answered" -> if (question != null && pickNote.isEmpty()) pickNote = "The recorded run went on with its recorded answer: ${b.recordedAnswer}."
      "task_state" -> if (e.optString("task_id") == b.followUpTask && e.optString("state") == "queued")
        caption = "Spoken follow-up (recorded): “${b.followUpText}”"
      "finished" -> log("commit receipt ${e.optString("commit")} task=${e.optString("task_id")}")
    }
    if (kind == "task_state" && e.optString("state") == "running") question = null
    if (preview?.onEvent(e) == true) renderPreview()
    render()
  }

  private fun pick(optionId: String) {
    val q = question ?: return
    val b = bundle ?: return
    val label = q.optJSONArray("options")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
      ?.firstOrNull { it.optString("id") == optionId }?.optString("label") ?: return
    // The same answer path as a live card, with sending off: nothing leaves the headset, the recorded `answered` closes it.
    replay?.link?.answer(q.optString("decision_id"), optionId)
    pickNote = if (label == b.recordedAnswer) "You picked $label, as the recorded run did. It stays on this headset."
      else "You picked $label; it stays on this headset. The recorded run goes on with its recorded answer: ${b.recordedAnswer}."
    log("question pick \"$label\" recorded=\"${b.recordedAnswer}\"")
    render()
  }

  // ---------- project panel ----------
  private fun clock(): String {
    val r = replay ?: return ""
    val t = minOf(SystemClock.uptimeMillis() - began, r.playMs) / 1000; val all = r.playMs / 1000
    return "${r.banner} · %d:%02d of %d:%02d".format(t / 60, t % 60, all / 60, all % 60)
  }
  /** Once a second while it plays: only the banner line's text changes, no views are rebuilt. */
  private fun clockLine(gen: Int) {
    if (gen != runNo || s.stopped) return
    column?.findViewWithTag<TextView>("project-line-0")?.text = clock()
    if (previewShowing) taps()
    val r = replay ?: return
    if (SystemClock.uptimeMillis() - began <= r.playMs + 30_000) s.ui.postDelayed({ clockLine(gen) }, 1000)
  }

  fun screen(): ProjectScreen = RecordedBuildScreens.of(clock(), timeline?.name.orEmpty(), question, pickNote, caption,
    timeline?.rows?.map { it.text }.orEmpty(), plan?.hasPlan == true, replay?.let { it.fed >= (bundle?.frames?.size ?: 0) } ?: false)

  private fun render() { val c = column ?: return; if (showing) ProjectPanelView.render(c, screen()) { action(it) } }

  fun action(key: String) {
    log("action $key")
    when {
      key.startsWith("pick_") -> pick(key.removePrefix("pick_"))
      key == "replay" -> start()
      key == "graph" -> bundle?.let { s.interaction.architecture.open(it.projectId) }
      key == "close" -> { showing = false; s.commands.add { entity?.windowVisible(false,"RecordedBuildFeature_open_close") } }
    }
  }

  private fun open() {
    if (!registered) {
      registered = true
      val c = LinearLayout(s.activity).also { column = it }
      s.activity.registerPanel(ViewPanelRegistration(PANEL, { _, _ -> c },
        { Theme.panel(UIPanelSettings(shape = QuadShapeOptions(width = ProjectPanelView.W_M, height = ProjectPanelView.H_M),
          display = DpDisplayOptions(width = ProjectPanelView.W_DP, height = ProjectPanelView.H_DP))) }, { _, _, _ -> }))
    }
    if (!showing) {
      showing = true
      val pose = placement(35.0, ProjectPanelView.DIST_M)
      s.commands.add {
        val e = entity ?: Entity.create(listOf(Panel(PANEL))).also { it.windowPose(pose,"RecordedBuildFeature_create");it.windowVisible(false,"RecordedBuildFeature_create") }.also { entity = it }
        e.windowPose(pose,"RecordedBuildFeature_open_close"); e.windowVisible(true,"RecordedBuildFeature_open_close")
      }
    }
    render()
  }

  private fun placement(degrees: Double, d: Float): Pose {
    val h = s.head(); val f = h.q * Vector3(0f, 0f, 1f)
    val yaw = atan2(f.x, f.z) + Math.toRadians(degrees).toFloat()
    return Pose(Vector3(h.t.x + kotlin.math.sin(yaw) * d, h.t.y - 0.1f, h.t.z + kotlin.math.cos(yaw) * d),
      Quaternion.fromEuler(0f, Math.toDegrees(yaw.toDouble()).toFloat(), 0f))
  }

  // ---------- preview ----------
  private fun openPreview() {
    if (!previewRegistered) {
      previewRegistered = true
      val c = LinearLayout(s.activity)
      val web = PreviewPanelView.webView(s.activity) { msg -> previewParts?.status?.text = msg.take(160) }
      previewParts = PreviewPanelView.build(c, web)
      s.activity.registerPanel(ViewPanelRegistration(PREVIEW_PANEL, { _, _ -> c },
        { Theme.panel(UIPanelSettings(shape = QuadShapeOptions(width = PreviewPanelView.W_M, height = PreviewPanelView.H_M),
          display = DpDisplayOptions(width = PreviewPanelView.W_DP, height = PreviewPanelView.H_DP))) }, { _, _, _ -> }))
    }
    if (!previewShowing) {
      previewShowing = true
      val pose = placement(-30.0, PreviewPanelView.DIST_M)
      s.commands.add {
        val e = previewEntity ?: Entity.create(listOf(Panel(PREVIEW_PANEL))).also { it.windowPose(pose,"RecordedBuildFeature_create");it.windowVisible(false,"RecordedBuildFeature_create") }.also { previewEntity = it }
        e.windowPose(pose,"RecordedBuildFeature_open_close"); e.windowVisible(true,"RecordedBuildFeature_open_close")
      }
    }
  }

  /** On events only (a preview, a chunk): the WebView loads once per verified commit. */
  private fun renderPreview() {
    val m = preview ?: return
    openPreview()
    val p = previewParts ?: return
    val shown = m.shown
    if (shown != null && shown.sha256 != previewLoadedSha) {
      previewLoadedSha = shown.sha256
      (p.web as? WebView)?.let { PreviewPanelView.load(it, shown.html) }
      log("preview ready commit=${shown.commit} sha=${shown.sha256} loads=${m.loads}")
    }
    PreviewPanelView.show(p, m.state, if (m.receipt.isBlank()) "" else "Recorded · " + m.receipt, shown != null)
  }

  /** The taps the page itself received ([PreviewPanelView.TAP_COUNTER]), logged when the count changes. */
  private fun taps() {
    (previewParts?.web as? WebView)?.evaluateJavascript("(window.__afTaps||0)+'|'+(window.__afLastTap||'')") { r ->
      if (r != pageTaps) { pageTaps = r; log("page taps=$r") }
    }
  }

  override fun onDestroy() { stopRun() }

  override fun onDebugIntent(intent: Intent) {
    if (!BuildConfig.DEBUG) return
    if (intent.getBooleanExtra("bfn_start", false)) start()
    intent.getStringExtra("bfn_click")?.let { action(it) }
  }

  private fun log(m: String) = Log.i(TAG, "bfn $m")

  companion object {
    const val TAG = "Overhand"
    /** Outside the aapt range, like the other runtime panels. */
    const val PANEL = 0x1008_0001
    const val PREVIEW_PANEL = 0x1008_0002
  }
}
