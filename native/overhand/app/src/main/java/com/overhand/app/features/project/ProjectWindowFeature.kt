package com.overhand.app.features.project
import com.overhand.app.features.windowPose
import com.overhand.app.features.windowVisible

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.webkit.WebView
import android.widget.LinearLayout
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
import com.overhand.app.decisions.LiveSafety
import com.overhand.app.features.Feature
import com.overhand.app.features.Shell
import com.overhand.app.hostlink.LinkState
import com.overhand.app.hostlink.project.FollowUpFlow
import com.overhand.app.hostlink.project.NewProjectFlow
import com.overhand.app.hostlink.project.PreviewFiles
import com.overhand.app.hostlink.project.PreviewModel
import com.overhand.app.hostlink.project.ProjectPanelView
import com.overhand.app.hostlink.project.ProjectScreens
import com.overhand.app.hostlink.project.ProjectTimeline
import com.overhand.app.windows.PolishEvidence
import com.overhand.app.windows.PreviewPanelView
import com.overhand.app.windows.Theme
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.atan2

/**
 * "New project" by voice and its window (#815), in host mode only. The voice panel hands every transcript here
 * ([heard]); [NewProjectFlow] decides. A confirmed chip becomes `create_project` over the host link; the project's
 * events then fill this window. A question goes to the existing decision card (HostSource -> DecisionCards, answered
 * through LiveSafety.answer, the card's own path); a spoken yes/no answers it through that same function.
 * Evidence for device runs: files/project_trace.json and one PNG per screen in files/project_frames/.
 *
 * #973: each `preview` event fetches the committed app over the link ([PreviewModel]) and runs it in the Preview
 * window ([PreviewPanelView]), which reloads on every new commit and shows the commit's receipt under it. While the
 * project is open, "change/make/add/now ..." shows a [FollowUpFlow] chip; only its Send (or a spoken yes) sends `prompt`.
 */
class ProjectWindowFeature(private val s: Shell) : Feature {
  override val name = "project"
  val flow = NewProjectFlow()
  val followUp = FollowUpFlow()
  private var preview: PreviewModel? = null
  /** #977: the shown commit's files for the WebView, answered off the UI thread. */
  @Volatile private var previewFiles: PreviewFiles? = null
  private var previewParts: PreviewPanelView.Parts? = null
  private var previewEntity: Entity? = null
  private var previewRegistered = false
  private var previewShowing = false
  private var previewLoadedSha = ""
  private var previewKey = ""
  private var timeline: ProjectTimeline? = null
  private var column: LinearLayout? = null
  private var entity: Entity? = null
  private var registered = false
  private var showing = false
  private var lastKey = ""
  private var frameNo = 0
  private var listening = false
  private var cardShotFor = ""
  private val heardLog = JSONArray()
  private val marks = JSONObject()
  private val hostMode get() = s.prefs.getString("session-mode", "recorded") == "host"
  val active get() = hostMode

  override fun onSceneReady() { if (hostMode) listen() }

  // ---------- voice ----------
  /** One transcript from the voice panel (UI thread). Returns true: in host mode every transcript is ours. */
  fun heard(text: String): Boolean {
    val q = timeline?.question
    val tracking = flow.state as? NewProjectFlow.State.Tracking
    if (tracking != null && showing && (followUp.state is FollowUpFlow.State.Chip || q == null) && followUp.wants(text)) {
      val effect = followUp.heard(text, projectName(), tracking.projectId)
      synchronized(heardLog) { heardLog.put(JSONObject().put("wall_ms", System.currentTimeMillis()).put("text", text).put("effect", "followup_" + effect.name).put("state", followUpName())) }
      log("heard \"$text\" -> followup $effect ${followUpName()}")
      if (effect == FollowUpFlow.Effect.SEND) sendFollowUp()
      render(); dump()
      return true
    }
    val effect = flow.heard(text, q != null)
    synchronized(heardLog) { heardLog.put(JSONObject().put("wall_ms", System.currentTimeMillis()).put("text", text).put("effect", effect.name).put("state", stateName())) }
    log("heard \"$text\" -> $effect ${stateName()}")
    if (effect == NewProjectFlow.Effect.SHOW && flow.state is NewProjectFlow.State.Chip) marks.put("chip_wall_ms", System.currentTimeMillis())
    when (effect) {
      NewProjectFlow.Effect.SEND -> send()
      NewProjectFlow.Effect.ANSWER_YES -> answerByVoice(true)
      NewProjectFlow.Effect.ANSWER_NO -> answerByVoice(false)
      else -> {}
    }
    open(); dump()
    return true
  }

  private fun send() {
    val st = flow.state as? NewProjectFlow.State.Sending ?: return
    marks.put("confirm_wall_ms", System.currentTimeMillis()).put("req_id", st.reqId).put("description", st.description)
    log("send req=${st.reqId}")
    s.hostLink.createProject(NewProjectFlow.prompt(st.description), st.reqId).thenAccept { made ->
      s.ui.post {
        if (s.stopped) return@post
        val pid = made.optString("project_id"); val tid = made.optString("task_id")
        flow.created(st.reqId, pid, tid)
        marks.put("project_id", pid).put("task_id", tid).put("created_wall_ms", System.currentTimeMillis())
        timeline = ProjectTimeline(pid).also { tl -> s.hostLink.events.forEach { tl.add(it) } }
        startPreview(pid)
        s.interaction.architecture.track(pid, s.hostLink.events.toList())   // #978: the plan's parts on the graph
        // Events already in the log when the reply lands count too (the listener only sees later ones).
        timeline?.let { tl -> if (tl.firstEventWallMs != 0L) marks.put("first_event_wall_ms", tl.firstEventWallMs)
          if (tl.started && !marks.has("started_wall_ms")) marks.put("started_wall_ms", System.currentTimeMillis()) }
        log("created $pid $tid"); render(); dump()
      }
    }
  }

  private fun projectName() = timeline?.name?.takeIf { it.isNotBlank() } ?: (flow.state as? NewProjectFlow.State.Tracking)?.description.orEmpty()
  private fun followUpName() = followUp.state.let { it::class.simpleName ?: "?" }

  /** #973: the wearer confirmed the change chip; only now does `prompt` go to the computer. */
  private fun sendFollowUp() {
    val st = followUp.state as? FollowUpFlow.State.Sending ?: return
    marks.put("followup_confirm_wall_ms", System.currentTimeMillis()).put("followup_req_id", st.reqId).put("followup_text", st.text)
    log("followup send req=${st.reqId}")
    s.hostLink.followUp(st.projectId, st.text, st.reqId).thenAccept { made ->
      s.ui.post {
        if (s.stopped) return@post
        followUp.sent(st.reqId)
        marks.put("followup_task_id", made.optString("task_id")).put("followup_queued_wall_ms", System.currentTimeMillis())
        render(); dump()
      }
    }
  }

  private fun answerByVoice(yes: Boolean) {
    val q = timeline?.question ?: return
    val id = q.optString("decision_id")
    val labels = q.optJSONArray("options")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("label") } }.orEmpty()
    val label = labels.firstOrNull { it.trim().trimEnd('.', '!').equals(if (yes) "yes" else "no", true) }
    if (label == null) { marks.put("voice_answer_$id", "no yes/no option; pinch the card"); return }
    Thread({
      val r = runCatching {
        val card = s.sessionSource.decisions().let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.first { it.optString("id") == id }
        LiveSafety.answer(s.sessionSource, LiveSafety.sendReal, card, label, "voice")
      }
      log("voice answer $id $label -> ${r.getOrElse { it.message }}")
      marks.put("voice_answer_$id", r.getOrElse { "failed: ${it.message}" })
      s.ui.post { if (!s.stopped) { s.interaction.decisions.refresh(); dump() } }
    }, "project-voice-answer").apply { isDaemon = true; start() }
  }

  // ---------- host events ----------
  private fun listen() {
    if (listening) return
    listening = true
    // #973: file_chunk / error replies for the preview fetch, handled on the UI thread like the events.
    s.hostLink.frameListeners.add { f ->
      if (f.optString("type") != "file_chunk" && f.optString("type") != "error") return@add
      if (previewFiles?.onFrame(f) == true) return@add          // #977: the WebView's thread waits on these, not the UI
      s.ui.post { if (!s.stopped && preview?.onFrame(f) == true) renderPreview() }
    }
    s.hostLink.eventListeners.add { e ->
      if (e.optString("kind") == "preview" || e.optString("kind") == "message" || e.optString("kind") == "plan") s.ui.post { if (!s.stopped && preview?.onEvent(e) == true) renderPreview() }
      val tl = timeline ?: return@add
      if (e.optString("project_id") != tl.projectId) return@add
      s.ui.post {
        if (s.stopped) return@post
        val firstBefore = tl.firstEventWallMs
        // #978: the graph's live plan folds the same event; a new plan adds this window's "Show plan on graph".
        val planChanged = s.interaction.architecture.liveEvent(e) && e.optString("kind") == "plan"
        if (tl.add(e) || planChanged) {
          if (firstBefore == 0L) marks.put("first_event_wall_ms", tl.firstEventWallMs)
          if (e.optString("kind") == "started" && !marks.has("started_wall_ms")) marks.put("started_wall_ms", System.currentTimeMillis())
          // The card polls on its own; ask it now so the question opens without waiting for the next poll.
          if (e.optString("kind") == "question") { marks.put("question_wall_ms", System.currentTimeMillis()); s.interaction.decisions.refresh() }
          render(); dump()
        }
      }
    }
    s.hostLink.statusListeners.add { s.ui.post { if (!s.stopped) render() } }
  }

  // ---------- panel ----------
  fun open() {
    if (!registered) {
      registered = true
      val c = LinearLayout(s.activity).also { column = it }
      s.activity.registerPanel(ViewPanelRegistration(PANEL, { _, _ -> c },
        { Theme.panel(UIPanelSettings(shape = QuadShapeOptions(width = ProjectPanelView.W_M, height = ProjectPanelView.H_M),
          display = DpDisplayOptions(width = ProjectPanelView.W_DP, height = ProjectPanelView.H_DP))) }, { _, _, _ -> }))
    }
    if (!showing) {
      showing = true
      val pose = placement()
      s.commands.add {
        val e = entity ?: Entity.create(listOf(Panel(PANEL))).also { it.windowPose(pose,"ProjectWindowFeature_create");it.windowVisible(false,"ProjectWindowFeature_create") }.also { entity = it }
        e.windowPose(pose,"ProjectWindowFeature_open_close"); e.windowVisible(true,"ProjectWindowFeature_open_close")
      }
    }
    render()
  }
  private fun close() { showing = false; s.commands.add { entity?.windowVisible(false,"ProjectWindowFeature_open_close") } }

  /** 1 m away, 35 degrees to the left of the gaze, so the decision card can sit straight ahead. */
  private fun placement(degrees: Double = 35.0, d: Float = ProjectPanelView.DIST_M): Pose {
    val h = s.head(); val f = h.q * Vector3(0f, 0f, 1f)
    val yaw = atan2(f.x, f.z) + Math.toRadians(degrees).toFloat()
    return Pose(Vector3(h.t.x + kotlin.math.sin(yaw) * d, h.t.y - 0.1f, h.t.z + kotlin.math.cos(yaw) * d),
      Quaternion.fromEuler(0f, Math.toDegrees(yaw.toDouble()).toFloat(), 0f))
  }

  private fun screen() = FollowUpFlow.screen(followUp.state) ?: ProjectScreens.of(flow.state, timeline, LiveSafety.sendReal,
    s.hostLink.status.state == LinkState.CONNECTED, if (flow.state == NewProjectFlow.State.Idle) flow.lastHeard else "",
    timeline?.projectId?.let { it == s.interaction.architecture.liveProject } ?: false)

  private fun render() {
    val c = column ?: return
    if (!showing) return
    val sc = screen()
    ProjectPanelView.render(c, sc) { action(it) }
    val key = sc.key + if (sc.key == "chip" && !LiveSafety.sendReal) "_sends_off" else ""
    if (key != lastKey) { lastKey = key; c.postDelayed({ frame(c, key) }, 400) }
    if (sc.key == "question") timeline?.question?.optString("decision_id")?.let { id -> if (id != cardShotFor) { cardShotFor = id; c.postDelayed({ cardFrame(id) }, 2500) } }
  }

  // ---------- preview (#973) ----------
  private fun startPreview(pid: String) {
    val m = PreviewModel(pid, { f -> s.hostLink.request(f) })
    preview = m
    previewFiles = PreviewFiles(pid, { f -> s.hostLink.request(f) })
    // A preview already in the log when the project's reply landed counts too.
    // Its task's messages first: the last one is the receipt line.
    val log = s.hostLink.events.filter { it.optString("project_id") == pid }
    val last = log.lastOrNull { it.optString("kind") == "preview" }
    log.lastOrNull { it.optString("kind") == "plan" }?.let { m.onEvent(it) }
    if (last != null) { log.filter { it.optString("kind") == "message" }.forEach { m.onEvent(it) }; m.onEvent(last) }
    if (m.state != PreviewModel.State.Idle) renderPreview()
  }

  private fun openPreview() {
    if (!previewRegistered) {
      previewRegistered = true
      val c = LinearLayout(s.activity)
      val web = PreviewPanelView.webView(s.activity, { previewFiles }) { msg -> previewParts?.status?.text = msg.take(160) }
      previewParts = PreviewPanelView.build(c, web)
      s.activity.registerPanel(ViewPanelRegistration(PREVIEW_PANEL, { _, _ -> c },
        { Theme.panel(UIPanelSettings(shape = QuadShapeOptions(width = PreviewPanelView.W_M, height = PreviewPanelView.H_M),
          display = DpDisplayOptions(width = PreviewPanelView.W_DP, height = PreviewPanelView.H_DP))) }, { _, _, _ -> }))
    }
    if (!previewShowing) {
      previewShowing = true
      val pose = placement(-30.0, PreviewPanelView.DIST_M)
      s.commands.add {
        val e = previewEntity ?: Entity.create(listOf(Panel(PREVIEW_PANEL))).also { it.windowPose(pose,"ProjectWindowFeature_create");it.windowVisible(false,"ProjectWindowFeature_create") }.also { previewEntity = it }
        e.windowPose(pose,"ProjectWindowFeature_open_close"); e.windowVisible(true,"ProjectWindowFeature_open_close")
      }
    }
  }

  /** Runs on events only (a preview, a chunk, a refusal): the WebView is loaded once per verified commit. */
  private fun renderPreview() {
    val m = preview ?: return
    openPreview()
    val p = previewParts ?: return
    val shown = m.shown
    if (shown != null && shown.sha256 != previewLoadedSha) {
      previewLoadedSha = shown.sha256
      previewFiles?.show(shown.commit, shown.files)
      (p.web as? WebView)?.let { PreviewPanelView.load(it, shown) }
      marks.put("preview_loaded_wall_ms", System.currentTimeMillis()).put("preview_commit", shown.commit).put("preview_sha", shown.sha256).put("preview_loads", m.loads)
      log("preview loaded commit=${shown.commit} sha=${shown.sha256.take(12)} loads=${m.loads}")
    }
    PreviewPanelView.show(p, m.state, m.receipt, shown != null)
    val key = m.state.key
    if (key != previewKey) {
      previewKey = key
      (m.state as? PreviewModel.State.Refused)?.let { log("preview refused: ${it.reason}") }
      val col = p.receipt.parent as? View
      col?.postDelayed({ frame(col, "preview_$key") }, if (key == "ready") 1500 else 400)
    }
    dump()
  }

  /** A pinch or ray click as the panel receives it: DOWN then UP at web-area dp ([x],[y]) through the panel's root. */
  private fun previewTap(x: Float, y: Float) {
    val p = previewParts ?: run { log("preview_tap: no preview panel"); return }
    val root = p.receipt.parent as? View ?: return
    val d = root.resources.displayMetrics.density
    val t = SystemClock.uptimeMillis()
    for ((dt, action) in listOf(0L to MotionEvent.ACTION_DOWN, 80L to MotionEvent.ACTION_UP))
      MotionEvent.obtain(t, t + dt, action, x * d, y * d, 0).also { root.dispatchTouchEvent(it); it.recycle() }
    root.postDelayed({
      (p.web as? WebView)?.evaluateJavascript("(window.__afTaps||0)+'|'+(window.__afLastTap||'')") { r ->
        log("preview_tap $x,$y page_taps=$r"); marks.put("preview_tap_page", r); dump()
      }
    }, 500)
  }

  fun action(key: String) {
    log("action $key")
    when (key) {
      "followup_send" -> if (followUp.confirm() == FollowUpFlow.Effect.SEND) sendFollowUp()
      "followup_discard" -> followUp.discard()
      "start" -> if (flow.confirm() == NewProjectFlow.Effect.SEND) send()
      "discard" -> flow.discard()
      "card" -> s.interaction.decisions.open()
      "graph" -> timeline?.projectId?.let { s.interaction.architecture.open(it) }   // #978: switch the graph to this plan
      "new" -> { flow.reset(); timeline = null; preview = null; previewFiles = null; previewLoadedSha = ""; followUp.discard(); marks.keys().asSequence().toList().forEach { marks.remove(it) } }
      "close" -> { if (timeline?.done == true) { flow.reset(); timeline = null }; close(); dump(); return }
    }
    render(); dump()
  }

  // ---------- evidence ----------
  private fun frame(v: View, key: String) {
    if (v.width <= 0 || v.height <= 0) return
    runCatching {
      val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
      val dir = File(s.activity.filesDir, "project_frames").apply { mkdirs() }
      File(dir, "%02d_%s.png".format(++frameNo, key)).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
      log("frame n=$frameNo key=$key")
    }
  }
  /** The decision card as the wearer sees it with the agent's question on it. */
  private fun cardFrame(id: String) {
    val v = PolishEvidence.registered["decision"] ?: return
    if (!s.interaction.decisionShowing) s.interaction.decisions.open()
    v.postDelayed({ frame(v, "card_$id") }, 800)
  }
  private fun stateName() = flow.state.let { it::class.simpleName ?: "?" }
  fun dump() {
    runCatching {
      val tl = timeline
      val o = JSONObject().put("written_wall_ms", System.currentTimeMillis()).put("mode", s.prefs.getString("session-mode", "recorded"))
        .put("link", s.hostLink.status.state.name).put("send_real", LiveSafety.sendReal).put("flow", stateName())
        .put("chip", (flow.state as? NewProjectFlow.State.Chip)?.let { NewProjectFlow.chip(it.description) } ?: JSONObject.NULL)
        .put("marks", JSONObject(marks.toString())).put("heard", synchronized(heardLog) { JSONArray(heardLog.toString()) })
        .put("creating", s.hostLink.creatingCount).put("create_frames_sent", s.hostLink.createFramesSent)
        .put("followup", followUpName())
        .put("preview", preview?.let { m -> JSONObject().put("state", m.state.key).put("receipt", m.receipt).put("loads", m.loads)
          .put("commit", m.shown?.commit ?: JSONObject.NULL).put("sha256", m.shown?.sha256 ?: JSONObject.NULL)
          .put("status", PreviewModel.status(m.state)) } ?: JSONObject.NULL)
        .put("screen", screen().let { sc -> JSONObject().put("key", sc.key).put("title", sc.title).put("lines", JSONArray(sc.lines)) })
      if (tl != null) {
        val seqs = s.hostLink.events.map { it.optLong("seq") }.sorted()
        val missing = if (seqs.isEmpty()) 0 else (seqs.first()..seqs.last()).count { java.util.Collections.binarySearch(seqs, it) < 0 }
        o.put("project_id", tl.projectId).put("rows", JSONArray(tl.rows.map { JSONObject().put("seq", it.seq).put("kind", it.kind).put("text", it.text) }))
          .put("question", tl.question ?: JSONObject.NULL).put("finished", tl.finished ?: JSONObject.NULL)
          .put("link_max_seq", seqs.lastOrNull() ?: 0).put("link_events", seqs.size).put("link_missing", missing)
          .put("project_seqs", JSONArray(s.hostLink.events.filter { it.optString("project_id") == tl.projectId }.map { it.optLong("seq") }))
      }
      val f = File(s.activity.filesDir, "project_trace.json"); val tmp = File(f.path + ".tmp")
      tmp.writeText(o.toString()); tmp.renameTo(f)
    }
  }
  private fun log(m: String) = Log.i(TAG, "project $m")

  override fun onDebugIntent(intent: Intent) {
    if (!BuildConfig.DEBUG || !hostMode) return
    if (intent.getBooleanExtra("project_send_real", false)) { LiveSafety.sendReal = true; log("send real on (debug)"); render() }
    intent.getStringExtra("project_say")?.let { heard(it) }            // a transcript without audio (layout checks only)
    intent.getStringExtra("followup_text")?.let { heard(it) }          // #973: a spoken change without audio
    intent.getStringExtra("preview_tap")?.let { xy ->                  // #973: "x,y" in the web area's dp
      val v = xy.split(',').mapNotNull { it.trim().toFloatOrNull() }
      if (v.size == 2) previewTap(v[0], v[1]) else log("preview_tap: want x,y, got \"$xy\"")
    }
    intent.getStringExtra("project_click")?.let { k -> column?.findViewWithTag<View>("project-$k")?.performClick() ?: log("no button $k") }
    // A pinch on the decision card: the option's own button, then Confirm (the same onClick a pinch fires).
    intent.getStringExtra("project_card_pick")?.let { label ->
      val root = PolishEvidence.registered["decision"]
      fun buttons(v: View): List<Button> = when (v) { is Button -> listOf(v); is ViewGroup -> (0 until v.childCount).flatMap { buttons(v.getChildAt(it)) }; else -> emptyList() }
      val all = root?.let(::buttons).orEmpty()
      val opt = all.firstOrNull { it.text.toString().trim().equals(label, true) } ?: all.firstOrNull { it.text.toString().contains(label, true) && it.text != "Confirm selected option" }
      log("card pick \"$label\" found=${opt != null} buttons=${all.map { it.text }}")
      opt?.performClick()
      s.ui.postDelayed({ all.firstOrNull { it.text == "Confirm selected option" }?.performClick(); log("card confirm clicked") }, 800)
    }
    if (intent.getBooleanExtra("project_dump", false)) dump()
  }

  companion object {
    const val TAG = "Overhand"
    /** Outside the aapt range, like the other runtime panels. */
    const val PANEL = 0x0815_0001
    const val PREVIEW_PANEL = 0x0973_0001
  }
}
