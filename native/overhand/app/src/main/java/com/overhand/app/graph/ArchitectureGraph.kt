package com.overhand.app.graph

import android.app.Activity
import android.graphics.*
import android.net.Uri
import android.os.*
import android.util.Log
import android.widget.*
import com.meta.spatial.core.*
import com.meta.spatial.toolkit.*
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.isdk.*
import com.meta.spatial.runtime.Scene
import com.meta.spatial.runtime.ControllerPose
import com.meta.spatial.runtime.ButtonBits
import org.json.*
import java.io.File
import java.net.*
import java.util.concurrent.Executors
import kotlin.math.*

/** Android UI/network owner; scene mutations are always submitted to the ECS queue. */
class ArchitectureGraph(private val activity: Activity,private val server: () -> String,
  private val enqueue: (() -> Unit) -> Unit,
  private val recorded: () -> com.overhand.app.windows.RecordedContent? = { null },
  /** #999: open one file's code (title, numbered lines) in the Code window. */
  private val openCode: (String,String) -> Unit = { _,_ -> },
  private val show: (Boolean) -> Unit) {
  private val ui=Handler(Looper.getMainLooper())
  private val io=Executors.newSingleThreadExecutor { r -> Thread(r,"graph-request").apply { isDaemon=true } }
  private var root: LinearLayout?=null
  private var plans=JSONObject()
  private var components=emptyList<JSONObject>()
  private var code=JSONObject()
  private var model=GraphModel(emptyList(),emptyList())
  private var page=0
  private var selected: String?=null
  /** #1032: the selected part, for the Smart answers screen summary (read only). */
  fun selectedPart(): String? = selected
  private var detail=HINT
  /** t775: the focused component's side card (description, why, tasks); null at rest. */
  private var card: JSONObject?=null
  private var cardTasks=emptyList<JSONObject>()
  private var busy=false
  private var token=0
  private var stopped=false
  private var taskPlaying=false
  private var taskBegan=0L
  private var taskText: TextView?=null
  private var taskBar: ProgressBar?=null
  private val taskTick=object : Runnable {
    override fun run() {
      if(stopped || !taskPlaying) return
      recorded()?.taskReplay?.let { r ->
        r.seek(TaskReplay.step(SystemClock.uptimeMillis()-taskBegan,r.count))
        updateTasks()
        if(r.step==r.count) taskPlaying=false
      }
      if(taskPlaying) ui.postDelayed(this,100)
    }
  }
  // GraphMotion (Recorded): the recorded day in fast-forward drives node states.
  private var motion: GraphMotion.Timeline?=null
  private var motionShown=0
  private var motionPlaying=false
  private var motionBegan=0L
  private var motionText: TextView?=null
  private var liveStates=emptyMap<String,String>()
  private val motionTick=object : Runnable {
    override fun run() {
      val m=motion
      if(stopped || !motionPlaying || m==null) return
      val wall=SystemClock.uptimeMillis()-motionBegan
      val day=m.dayAt(wall)
      recorded()?.taskReplay?.let { r -> r.seek(r.stepAt(day));updateTasks() }
      val n=m.shown(wall)
      if(n!=motionShown) {
        val newest=m.changes[n-1];motionShown=n
        rebuildColours()
        enqueue { sceneGraph.motionChange(newest.component,newest.label,newest.to) }
        writeMotion()
      }
      val clock=recorded()?.source?.clock(day) ?: GraphMotion.clock(day)
      enqueue { sceneGraph.motionClock(clock) }
      updateMotionText(clock)
      if(wall>=m.durationMs) { motionPlaying=false;render() } else ui.postDelayed(this,100)
    }
  }
  /** Start the day as soon as the recorded graph is installed (opening scene). */
  var autoplay=false
  val motionChanges get()=motionShown
  @Volatile var motionClockText="";private set
  private val motionOn get()=GraphMotion.ENABLED && recorded()!=null && motion!=null && !liveShown
  /** Blank until chosen: in host mode, the first project the host reports with a plan (#1096). */
  var project="";private set
  private val deny by lazy {
    val source=activity.assets.open("windows.js").bufferedReader().use { it.readText() }
    val shared=Regex("'([^']+)'").findAll(source.substringAfter("const deny = [").substringBefore("];"))
      .map { it.groupValues[1] }.toList()
    displayPattern(shared+EXTRA_DENY)
  }
  private fun clean(value: String)=deny.replace(value,"agent")
  private var displayed=mutableListOf<String>()
  private fun text(value: String,size: Float=30f)=TextView(activity).apply {
    val safe=clean(value);displayed.add(safe);text=safe;textSize=size;setTextColor(Color.WHITE);setPadding(14,14,14,14)
  }
  private fun button(value: String,action: () -> Unit)=Button(activity).apply {
    val safe=clean(value);displayed.add(safe);text=safe;textSize=28f;setOnClickListener { action() }
  }
  val sceneGraph=GraphScene { key,simulated -> ui.post { onTarget(key,simulated) } }
  // ---- #999 drill-down (UI thread): the selection lives in [drill], the scene only draws it ----------------------
  private var drill=GraphSelection<GraphDrill.Framing>("Project",{ emptyList() })
  private var drillFiles=emptyMap<String,List<GraphDrill.File>>()
  private var drillCaptions=emptyMap<String,String>()
  /** Every part's files: the recorded fixture (judge/drill/<dataset>.json), else the live code map. */
  private fun resetDrill() {
    val data=recorded()
    // #1380: the fixture holds one project's parts; every other project (and any part it lacks) uses its code map.
    val fixture=if(data!=null) runCatching { activity.assets.open("judge/drill/${data.source.dataset}.json").bufferedReader().use { it.readText() } }
      .getOrNull()?.let { GraphDrill.fixtureFor(it,project,::clean) } else null
    drillFiles=components.associate { it.getString("id") to GraphDrill.partFiles(fixture,it,code,::clean) }
    drillCaptions=components.associate { it.getString("id") to GraphDrill.caption(it,::clean) }
    val title=clean(data?.let { ProjectContext.label(ProjectContext.labels(it.projects),project) } ?: project)
    val infos=nodeInfo()
    drill=GraphSelection(title,{ id -> drillFiles[id].orEmpty() },{ id -> infos[id]?.label ?: id })
    enqueue { sceneGraph.revealCode(false) }
  }
  private fun dimmed()=if(drill.level==GraphDrill.Level.PROJECT) emptySet() else model.nodes.map { it.id }.filter { it!=drill.part }.toSet()
  /** A pinch on the graph: a node, a files-card row, the page row, a breadcrumb segment or empty space. */
  private fun onTarget(key: String,simulated: Boolean) {
    when {
      key.startsWith("node:") -> drillTo(key.removePrefix("node:"),simulated)
      key.startsWith("row:") -> openFile(key.removePrefix("row:").toIntOrNull() ?: return)
      key=="more" -> { drill.nextPage();drillOverlay(null);render() }
      key.startsWith("crumb:") -> backTo(key.removePrefix("crumb:").toIntOrNull() ?: return)
      key=="empty" -> back()
    }
  }
  private fun drillTo(id: String,simulated: Boolean) {
    val now=sceneGraph.framing ?: REST
    val from=drill.level
    if(drill.select(id,now)) {
      if(from==GraphDrill.Level.CODE) enqueue { sceneGraph.revealCode(false) }
      select(id,simulated)
      val draw=overlayOf();enqueue { val f=sceneGraph.partFraming(id);if(f!=null) { sceneGraph.frameTo(f);draw(f.zoom) } }
      recolourDim()
    }
    Log.i("OverhandGraph","drill level=${drill.level} part=${drill.part} files=${drill.partFiles().size} simulated=$simulated")
  }
  private fun openFile(row: Int) {
    val now=sceneGraph.framing ?: REST
    val f=drill.open(row,now) ?: return
    openCode(clean(drill.breadcrumb().joinToString(" > ")),GraphDrill.codeText(f))
    enqueue { sceneGraph.revealCode(true) };drillOverlay(null)
    render();dump()
    Log.i("OverhandGraph",com.overhand.app.PrivateLog.drillCode(drill.part,f.path,f.startLine,f.changed.size,f.text.lines().size))
  }
  /** One level up (pinch on empty space, the breadcrumb, Back, or the voice card): restores that level's framing. */
  fun back() { ui.post { val f=drill.back() ?: return@post;restore(f) } }
  /** Up to breadcrumb [index] (0 = the project). */
  private fun backTo(index: Int) { val f=drill.backTo(index) ?: return;restore(f) }
  /** The voice card's "show me <part>": resolve the name and go to that part's level. */
  fun zoomTo(partName: String): Boolean {
    val id=GraphDrill.resolve(partName,nodeInfo(),components.associate { it.getString("id") to clean(it.optString("name")) }) ?: return false
    ui.post { if(drill.level==GraphDrill.Level.CODE) drill.back()?.let { restore(it,quiet=true) };drillTo(id,false) }
    return true
  }
  private fun restore(f: GraphDrill.Framing,quiet: Boolean=false) {
    val level=drill.level
    if(level!=GraphDrill.Level.CODE) enqueue { sceneGraph.revealCode(false) }
    if(level==GraphDrill.Level.PROJECT) { selected=null;card=null;detail=HINT;enqueue { sceneGraph.clearFocus() } }
    val draw=overlayOf();enqueue { sceneGraph.frameTo(f);draw(f.zoom) }
    recolourDim()
    if(!quiet) { render();dump() }
    Log.i("OverhandGraph","drill back level=$level part=${drill.part ?: "-"} to=${"%.3f,%.3f,%.3f".format(f.t.x,f.t.y,f.t.z)} zoom=${"%.2f".format(f.zoom)}")
  }
  /** Snapshot (UI thread) of what the files card and breadcrumb show; the returned call draws it at a zoom (ECS thread). */
  private fun overlayOf(): (Double) -> Unit {
    val part=if(drill.level==GraphDrill.Level.PART) drill.part else null
    // #1380: never an empty card: a part with no files shows one caption line (its description), not pinchable to a file.
    val rows=if(part!=null) drill.rows().map { it.path.substringAfterLast('/') }.ifEmpty { listOf(drillCaptions[part] ?: "No files recorded for this part") } else emptyList()
    val paged=part!=null && drill.pages>1;val crumbs=drill.breadcrumb()
    return { z -> sceneGraph.overlay(part,rows,paged,crumbs,z,sceneGraph.viewDistance()) }
  }
  private fun drillOverlay(z: Double?) { val draw=overlayOf();enqueue { draw(z ?: sceneGraph.framing?.zoom ?: 1.0) } }
  private fun recolourDim() { /* Focus uses the stable highlight and detail entities, never a mesh swap. */ }
  /** The panel's view of the drill: breadcrumb buttons, then the files (7 a page) or the opened file's code. */
  private fun drillView(): LinearLayout? {
    if(drill.level==GraphDrill.Level.PROJECT) return null
    return LinearLayout(activity).apply {
      orientation=LinearLayout.VERTICAL
      val crumbs=LinearLayout(activity)
      drill.breadcrumb().forEachIndexed { i,c -> crumbs.addView(button((if(i>0) "> " else "")+GraphLabels.shorten(c,18)) { backTo(i) }) }
      addView(crumbs)
      if(drill.level==GraphDrill.Level.PART) {
        drill.rows().forEachIndexed { i,f -> addView(button(f.path.substringAfterLast('/')) { openFile(i) }) }
        if(drill.rows().isEmpty()) addView(text(drillCaptions[drill.part] ?: "No files recorded for this part",26f))
        if(drill.pages>1) addView(button("More files · ${drill.page+1}/${drill.pages}") { drill.nextPage();drillOverlay(null);render() })
      } else drill.file?.let { f ->
        addView(text(f.path+" · recorded excerpt, some lines shortened · lines ${f.startLine}-${f.endLine} · + changed",24f))
        addView(text(GraphDrill.codeText(f),20f).apply { typeface=Typeface.MONOSPACE;tag="graph-code" })
      }
      addView(button("Back") { back() })
    }
  }
  fun attach(column: LinearLayout) { root=column;render() }
  /** #1002: the parts on show (id to the label drawn), for "zoom into <part>". */
  /** #1029: set by the shell; null hides "Show on site". */
  var showOnSite: ((String,String) -> Unit)?=null
  /** #1029: the focused part (the shared selection's graph side). */
  val selectedPart get()=selected
  fun parts()=components.map { it.getString("id") to label(it) }
  /** #978: the live project's plan (host mode). Shown only when selected; the recorded day stays the default. */
  private var live: LivePlan?=null
  private val liveShown get()=live?.let { it.projectId==project && it.hasPlan } ?: false
  val liveProject get()=live?.takeIf { it.hasPlan }?.projectId
  private var liveRecorded=false
  private fun liveDetail(p: LivePlan)=p.detail().let { if(liveRecorded) it.replace("Live plan from your computer","Recorded run, replayed") else it }
  /** Follow [projectId]'s host events from now on, replaying the ones already logged (UI thread). */
  fun track(projectId: String,events: List<JSONObject>,fresh: Boolean=false,recorded: Boolean=false) {
    if(fresh || live?.projectId!=projectId) live=LivePlan(projectId)
    liveRecorded=recorded   // #1008: Recorded mode's build-from-nothing replay says so on the graph
    events.forEach { liveEvent(it) }
  }
  /** One host event (UI thread). Returns true if a part changed state; the graph is redrawn only if the plan is shown. */
  fun liveEvent(e: JSONObject): Boolean {
    val p=live ?: return false
    val planned=e.optString("kind")=="plan"
    val changed=p.add(e)
    if(changed.isEmpty()) return false
    if(stopped) return true
    if(!liveShown) { if(planned) render();return true }   // the picker gains the live project's chip
    if(planned) { showLive(p);return true }
    liveStates=p.states();detail=liveDetail(p)
    // Event-driven: one mesh rewrite and swap per change (no new entities, nothing per frame).
    val file=File(activity.cacheDir,"architecture-${System.nanoTime()}.glb")
    val infos=nodeInfo()
    GraphMesh.write(file,model,page,infos.mapValues { GraphLod.ringColour(it.value.state) },nodes=infos)
    val all=infos.mapValues { it.value.state };val (id,st)=p.newest ?: (changed.last() to liveStates.getValue(changed.last()))
    enqueue { sceneGraph.recolour(file);sceneGraph.states(all);sceneGraph.motionChange(id,LivePlan.word(st),st) }
    render();dump()
    return true
  }
  private fun showLive(p: LivePlan) {
    token++;motionPlaying=false;ui.removeCallbacks(motionTick);motion=null;motionShown=0
    taskPlaying=false;ui.removeCallbacks(taskTick)
    components=p.components();model=p.model();code=JSONObject();liveStates=p.states()
    page=0;selected=null;card=null;detail=liveDetail(p)
    rebuild();render();dump()
  }
  fun open(id: String=project) { if(busy) return;project=id.ifBlank { recorded()?.defaultProject ?: project };selected=null;show(true);refresh() }
  private fun get(base: String,path: String): JSONObject {
    val c=URL(base+path).openConnection() as HttpURLConnection
    c.connectTimeout=2500;c.readTimeout=15000
    return try { check(c.responseCode==200) { "HTTP ${c.responseCode}" };JSONObject(c.inputStream.bufferedReader().use { it.readText() }) } finally { c.disconnect() }
  }
  private fun arr(a: JSONArray?)=(0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it) }
  fun refresh() {
    if(busy || stopped) return
    live?.takeIf { it.projectId==project && it.hasPlan }?.let { showLive(it);return }
    recorded()?.let { data ->
      token++;plans=data.plans();components=data.components(project);model=data.graph(project)
      code=data.source.code(project);page=0;selected=null
      motionPlaying=false;ui.removeCallbacks(motionTick);motionShown=0
      motion=if(GraphMotion.ENABLED) GraphMotion.Timeline(data.source.events,project,components.map { it.getString("id") },data.source.membership).takeIf { it.changes.isNotEmpty() } else null
      card=null
      detail=if(model.nodes.isEmpty()) "Not recorded for this project" else "${model.nodes.size} components · ${model.edges.size} recorded dependencies\n$HINT"
      detail+=if(motion!=null && data.source.dataset!=com.overhand.app.data.RecordedSource.DAY) "\nPlay the day: the rebuilt history's tasks build its components at ${GraphMotion.SPEEDUP}x, ${data.source.dayLabel} in about a minute. Each task belongs to the component its commits' files touched."
        else if(motion!=null) "\nPlay the day: the recorded Nov 3 tasks build the Nov 5 components at ${GraphMotion.SPEEDUP}x. Task-to-component membership is assigned from task titles (3 from the plan); see pipeline_motion.json." else "\nNov 3 tasks have no recorded component membership. Architecture colours and dependencies are the static Nov 5 snapshot."
      resetDrill();rebuild();render();dump()
      if(autoplay) { autoplay=false;if(motion!=null) startDay() }
      return
    }
    busy=true;val generation=++token;val base=server();val asked=project;var pid=asked
    detail="Loading live plan…";render()
    io.execute {
      val result=runCatching {
        val snapshot=get(base,"/api/state");val nextPlans=snapshot.getJSONObject("plans")
        if(pid.isBlank()) pid=com.overhand.app.stations.StationRules.liveDefault(snapshot,activity.getSharedPreferences("windows-v1",0)
          .getString(com.overhand.app.stations.StationRules.LIVE_CHOICE,null)) ?: error("The host reports no project")
        val plan=nextPlans.optJSONObject(pid) ?: JSONObject()
        val rows=arr(plan.optJSONArray("components"))
        val graph=GraphLayout.build(rows.map { c -> Component(c.getString("id"),
          c.optJSONArray("after")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()) })
        val map=runCatching { get(base,"/api/code/"+enc(pid)) }.getOrElse { JSONObject().put("error","Code map unavailable") }
        Triple(nextPlans,rows,graph) to map
      }
      ui.post {
        busy=false
        if(stopped || generation!=token || server()!=base || project!=asked) return@post
        project=pid
        result.fold({ (data,map) ->
          val previous=liveStates
          plans=data.first;components=data.second;model=data.third;code=map;page=0;selected=null
          if(GraphMotion.ENABLED) liveStates=components.associate { it.getString("id") to GraphMotion.liveState(it) }
          card=null
          detail=if(model.nodes.isEmpty()) "This project has no plan components. Nothing is invented." else "${model.nodes.size} components · ${model.edges.size} directed dependencies\n$HINT"
          resetDrill();rebuild();render();dump()
          if(GraphMotion.ENABLED) GraphMotion.diff(previous,liveStates).entries.lastOrNull()?.let { (id,st) ->
            enqueue { sceneGraph.motionChange(id,GraphMotion.word(st),st) } }
        }, { detail="Unable to load live plan. Check server, then Refresh.";render() })
      }
    }
  }
  private fun label(c: JSONObject)=c.optString("short").ifBlank { c.getString("id").replace('_',' ') }
  /** t775: every node's short label, type, state, number and focus detail (module, file, code lines). */
  private fun nodeInfo(): Map<String,GraphLod.NodeInfo> {
    val states=if(motionOn) motionStates() else liveStates
    return GraphLod.infos(components,code,::clean,states)
  }
  private fun rebuild() {
    val infos=nodeInfo()
    val current=model;val currentPage=page
    val file=File(activity.cacheDir,"architecture-${System.nanoTime()}.glb")
    // Tiny bounded geometry, no model/data loader; CPU-only, a few thousand vertices for today's plan.
    if(motionOn) { writeMotionMesh(file,current,currentPage) } else GraphMesh.write(file,current,currentPage,infos.mapValues { GraphLod.ringColour(it.value.state) },nodes=infos,dim=dimmed())
    // Debug-only relative-view fixture: move the graph opposite a virtual head
    // translation. Production tracking/billboarding still runs unmodified.
    val fixture=File(activity.filesDir,"graph_viewpoint.json")
    val viewpoint=if(com.overhand.app.BuildConfig.DEBUG && fixture.isFile) runCatching {
      val v=JSONObject(fixture.readText())
      Vector3(v.optDouble("x").toFloat(),v.optDouble("y").toFloat(),v.optDouble("z").toFloat())
    }.getOrDefault(Vector3(0f)) else Vector3(0f)
    enqueue { sceneGraph.install(current,currentPage,infos,file,selected,viewpoint) }
  }
  private fun motionStates()=motion?.states(motionShown,components.map { it.getString("id") }) ?: emptyMap()
  private fun writeMotionMesh(file: File,current: GraphModel,currentPage: Int) {
    val states=motionStates()
    val infos=nodeInfo()
    GraphMesh.write(file,current,currentPage,states.mapValues { GraphLod.ringColour(it.value) },
      states.mapValues { if(it.value=="built") GraphMotion.EDGE_FILLED else GraphMotion.EDGE_EMPTY },infos,dimmed())
    enqueue { sceneGraph.states(infos.mapValues { it.value.state }) }
  }
  /** State change: rewrite the batched mesh colours and swap it in place (no new entities). */
  private fun rebuildColours() {
    val file=File(activity.cacheDir,"architecture-${System.nanoTime()}.glb")
    writeMotionMesh(file,model,page)
    enqueue { sceneGraph.recolour(file) }
  }
  private fun writeMotion() {
    val m=motion ?: return
    runCatching { File(activity.filesDir,"pipeline_motion.json").writeText(m.toJson(motionShown,recorded()?.source?.fixtureHash ?: "")
      .put("written_uptime_ms",SystemClock.uptimeMillis()).put("play_began_uptime_ms",motionBegan).toString(2)) }
  }
  private fun updateMotionText(clock: String) {
    val m=motion ?: return
    motionClockText=clock
    val newest=m.changes.getOrNull(motionShown-1)
    // The newest change is shown on the graph itself (pulse and word over its node); the panel keeps one line.
    motionText?.text=clean("Day clock $clock · ${motionShown}/${m.changes.size} changes"+(newest?.let { " · task #"+it.taskId } ?: ""))
  }
  private fun startDay() {
    val m=motion ?: return
    ui.removeCallbacks(motionTick);ui.removeCallbacks(taskTick);taskPlaying=false
    motionShown=0;motionBegan=SystemClock.uptimeMillis();motionPlaying=true
    rebuildColours();writeMotion();render();ui.post(motionTick)
  }
  private fun render() {
    val column=root ?: return
    displayed=mutableListOf();column.removeAllViews()
    // t775: the task lines and the detail or side card scroll together below the project chips, so nothing is clipped.
    val body=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
    val bar=LinearLayout(activity)
    val recordedLabels=recorded()?.let { ProjectContext.labels(it.projects) }
    val liveName=live?.displayName(liveRecorded)
    val title=if(liveShown) liveName!! else recordedLabels?.let { ProjectContext.label(it,project) } ?: "Architecture"
    bar.addView(text(title,36f).apply { if(recordedLabels!=null) setTypeface(typeface,Typeface.BOLD) },LinearLayout.LayoutParams(0,-2,1f).apply {
      // #911: LinearLayout baseline-aligns before adding top margins, so the title takes the buttons' margin (PanelPolish, near gap) to share their baseline.
      topMargin=Math.round(com.overhand.app.windows.PolishRules.NEAR_GAP_DP*activity.resources.displayMetrics.density/2) })
    bar.addView(button("Refresh") { refresh() })
    bar.addView(button("Close") { hide() })
    // #1029: the focused part back on the build site (its block and plot light up), through the shared selection.
    selected?.let { part -> showOnSite?.let { go -> bar.addView(button(SHOW_ON_SITE) { go(project,part) }.apply { tag="show-on-site" }) } }
    column.addView(bar)
    if(liveShown) column.addView(text(if(liveRecorded) "Recorded run · planned by your coding agent · replayed as its files were written" else "Live · planned by your coding agent · updates as files are written",24f))
    else if(recorded()!=null) {
      // t775: one provenance line (design §5: dates once per window), so the graph's side card keeps its room.
      column.addView(text("Snapshot · ${recorded()?.source?.planLabel ?: "Nov 5"} · replayed, read only",24f))
      // t775: one row of replay controls, so the side card keeps the panel's height.
      val controls=LinearLayout(activity)
      column.addView(controls)
      if(motionOn) {
        controls.addView(button(if(motionPlaying) "Restart day" else "Play the day") { startDay() })
        motionText=text("",26f).also { column.addView(it) }
        updateMotionText(motion!!.dayAt(if(motionPlaying) SystemClock.uptimeMillis()-motionBegan else 0L).let { recorded()?.source?.clock(it) ?: GraphMotion.clock(it) })
      }
      recorded()?.taskReplay?.let { r ->
        controls.addView(button(if(taskPlaying) "Pause tasks" else "Play tasks") {
          taskPlaying=!taskPlaying
          taskBegan=SystemClock.uptimeMillis()-r.step*TaskReplay.STEP_MS
          ui.removeCallbacks(taskTick);if(taskPlaying) ui.post(taskTick)
          render()
        })
        controls.addView(button("Restart tasks") {
          taskPlaying=false;ui.removeCallbacks(taskTick)
          val began=System.nanoTime()
          r.restart()
          val restarted=System.nanoTime()
          render()
          val rendered=System.nanoTime()
          // The graph model/text did not change. Capture UI pixels here; encode/write off-thread.
          if(com.overhand.app.BuildConfig.DEBUG) {
            val snapshot=r.snapshot(project).toString(2)
            val view=root
            val bitmap=if(view!=null && view.width>0 && view.height>0)
              Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) } else null
            io.execute {
              val dumpBegan=System.nanoTime()
              try {
                File(activity.filesDir,"task_replay_state.json").writeText(snapshot)
                bitmap?.let { b -> File(activity.filesDir,"graph_detail.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) } }
              } finally { bitmap?.recycle() }
              Log.i("OverhandGraph","event=restart_dump ms=${(System.nanoTime()-dumpBegan)/1e6}")
            }
          }
          Log.i("OverhandGraph","event=restart_tasks restart_ms=${(restarted-began)/1e6} render_ms=${(rendered-restarted)/1e6} dump_capture_ms=${(System.nanoTime()-rendered)/1e6}")
        })
        taskText=text("",24f).also { body.addView(it) }
        taskBar=ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal).also { body.addView(it,LinearLayout.LayoutParams(-1,16)) }
        updateTasks()
      }
    }
    val projects=(plans.keys().asSequence().sorted().toList()+listOfNotNull(liveProject)).distinct().ifEmpty { listOf(project).filter { it.isNotBlank() } }
    val choices=projects.map { key -> clean(if(key==liveProject) live!!.pickerName(liveRecorded) else recordedLabels?.let { ProjectContext.label(it,key) } ?: key) }
    displayed.addAll(choices)
    // In-panel chips, never a Spinner: its dropdown is a Dialog, which a panel's window context cannot host.
    val picker=com.overhand.app.windows.ChipRow(activity,choices,projects.indexOf(project).coerceAtLeast(0),perRow=2) { i ->
      if(project!=projects[i] && !busy) open(projects[i])
    }
    if(recordedLabels!=null) picker.view.contentDescription="Project: $title"
    body.addView(picker.view,0)
    if(model.nodes.size>GraphLayout.PAGE_SIZE) {
      column.addView(button("Page ${page+1}/${(model.nodes.size+31)/32} · Next") {
        page=(page+1)%((model.nodes.size+31)/32);selected=null;drill.reset();rebuild();render()
      })
      column.addView(text("Only edges between this page's nodes are drawn; the full model is retained.",24f))
    }
    drillView()?.let { body.addView(it) }
    body.addView(card?.let { sideCard(it) } ?: text(detail,32f))
    // Children of the scrolled body are reached by scrolling, not cut off (layout lint's marker for that).
    for(i in 0 until body.childCount) body.getChildAt(i).let { v -> if(v.tag==null) v.tag="lint-ok:clip scrolls" }
    val scroll=ScrollView(activity);scroll.addView(body);column.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
  }
  private fun updateTasks() {
    val data=recorded() ?: return
    val r=data.taskReplay
    val p=r.progress(project)
    val view=ProjectView.state(project,r.tMs,ProjectView.rowTimes(data.source.events),data.source.views)
    taskText?.text=TaskProgressView.styled(ProjectContext.graphLines(view,r.clock,r.step,r.count),::clean)
    taskText?.contentDescription=clean(ProjectContext.spoken(ProjectContext.label(ProjectContext.labels(data.projects),project),true,view,r.clock))
    taskBar?.let { TaskProgressView.update(it,p.fraction) }
  }
  fun debugReplay(step: Int) {
    val r=recorded()?.taskReplay ?: return
    taskPlaying=false;ui.removeCallbacks(taskTick);r.seek(step);updateTasks();dump()
  }
  fun select(id: String,simulated: Boolean=false) {
    val c=components.firstOrNull { it.getString("id")==id } ?: return
    selected=id;val pid=project;val generation=++token
    detail=componentText(c);card=c;cardTasks=arr(c.optJSONArray("tasks"));render();dump()
    enqueue { sceneGraph.highlight(id);sceneGraph.soundSource()?.let { com.overhand.app.audio.SpatialEarcons.of(activity).choose(it) } }
    Log.i("OverhandGraph","selected project=$pid component=$id simulated_pinch=$simulated detail_open=true")
    if(liveShown) return   // #978: a live part's card is its plan row; the host has no component endpoint for it
    recorded()?.let { data ->
      detail=data.componentDetail(pid,c)
      render();dump();return
    }
    io.execute {
      val extra=runCatching { get(server(),"/api/plan/${enc(pid)}/component/${enc(id)}") }.getOrNull()
      ui.post {
        if(stopped || generation!=token || selected!=id || project!=pid) return@post
        detail=componentText(c,extra)
        extra?.optJSONObject("tasks")?.let { groups -> cardTasks=groups.keys().asSequence().flatMap { arr(groups.optJSONArray(it)).asSequence() }.toList() }
        render();dump()
      }
    }
  }
  /**
   * The side card a focused node opens (design §6): full name, state chip, description, why and up to five tasks,
   * in the design tokens (tile radius, panel grey, white text, white 60 % secondary, state colour). Views, not
   * Compose: Compose has not run inside a panel yet (#811).
   */
  private fun sideCard(c: JSONObject): LinearLayout {
    val density=activity.resources.displayMetrics.density
    val state=GraphLod.state(if(motionOn) motionStates()[c.getString("id")] ?: c.optString("shown",c.optString("status")) else liveStates[c.getString("id")] ?: c.optString("shown",c.optString("status")))
    val ring=GraphLod.ringColour(state).let { Color.rgb((it[0]*255).toInt(),(it[1]*255).toInt(),(it[2]*255).toInt()) }
    return LinearLayout(activity).apply {
      orientation=LinearLayout.VERTICAL;setPadding((24*density).toInt(),(16*density).toInt(),(24*density).toInt(),(16*density).toInt())
      background=android.graphics.drawable.GradientDrawable().apply { cornerRadius=24*density;setColor(0xE0272727.toInt()) }
      addView(text(c.optString("name",label(c)),36f).apply { setTypeface(typeface,Typeface.BOLD);setTextColor(0xFFF1F4F7.toInt()) })
      addView(text(GraphLod.state(state).replaceFirstChar { it.uppercase() },26f).apply {
        setTextColor(ring);background=android.graphics.drawable.GradientDrawable().apply { cornerRadius=12*density;setColor(ring and 0x00FFFFFF or 0x29000000) } },
        LinearLayout.LayoutParams(-2,-2))
      fun section(title: String,body: String) { if(body.isBlank()) return
        addView(text(title,24f).apply { setTextColor(0x99FFFFFF.toInt()) });addView(text(body,30f).apply { setTextColor(0xFFF1F4F7.toInt()) }) }
      section("Description",c.optString("desc"))
      section("Why",c.optString("why"))
      section("Tasks",cardTasks.take(5).joinToString("\n") { "#${it.optInt("id")} · ${com.overhand.app.FirstMinutes.statusWord(it.optString("status"))} · ${com.overhand.app.windows.RecordedText.title(it.optString("title")).ifBlank { "untitled" }}" })
      addView(text(GraphDrill.FILE_HINT,24f).apply { setTextColor(0x99FFFFFF.toInt()) })
    }
  }
  private fun componentText(c: JSONObject,extra: JSONObject?=null): String {
    val paths=c.optJSONArray("paths")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
    val tasks=extra?.optJSONObject("tasks")?.let { groups -> groups.keys().asSequence().flatMap { arr(groups.optJSONArray(it)).asSequence() }.toList() }
      ?: arr(c.optJSONArray("tasks"))
    val symbols=arr(code.optJSONArray("modules")).flatMap { arr(it.optJSONArray("top")) }
      .filter { s -> paths.any { matchesPath(s.optString("file"),it) } }
      .distinctBy { it.optString("file")+":"+it.optString("name") }
      .sortedWith(compareByDescending<JSONObject> { it.optInt("used_by") }.thenBy { it.optString("file") }.thenBy { it.optString("name") })
    val topFiles=symbols.groupBy { it.optString("file") }.entries.sortedWith(
      compareByDescending<Map.Entry<String,List<JSONObject>>> { it.value.sumOf { s -> s.optInt("used_by") } }.thenBy { it.key }).take(5)
    return buildString {
      appendLine(label(c)+" · "+com.overhand.app.FirstMinutes.statusWord(c.optString("shown",c.optString("status"))))
      appendLine("Description\n"+c.optString("desc").ifBlank { "No description in plan." })
      appendLine("Why\n"+c.optString("why").ifBlank { "No reasoning in plan." })
      appendLine("Technique\n"+c.optString("technique").ifBlank { "No technique in plan." })
      appendLine("Paths\n"+paths.joinToString("\n").ifBlank { "No paths assigned." })
      appendLine("Tasks (${tasks.size})")
      tasks.forEach { appendLine("#${it.optInt("id")} · ${com.overhand.app.FirstMinutes.statusWord(it.optString("status"))} · ${it.optString("title")}") }
      appendLine("Code · top 5 files / 3 symbols each by incoming references in the code map")
      topFiles.forEach { (file,syms) -> appendLine(file);syms.take(3).forEach { appendLine("  ${it.optString("name")} · ${it.optString("kind")} · used by ${it.optInt("used_by")}") } }
      if(topFiles.isEmpty()) {
        appendLine(if(code.optBoolean("indexed")) "The code map returned no ranked symbols under these paths." else "No code index available for these paths.")
        val files=arr(code.optJSONArray("modules")).flatMap { module ->
          module.optJSONArray("sample")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        }.filter { f -> paths.any { matchesPath(f,it) } }.distinct().sorted().take(5)
        if(files.isNotEmpty()) { appendLine("Map file samples (no reference ranking available)");files.forEach { appendLine(it) } }
      }
      appendLine("Map coverage: ${code.optInt("files")} files · ${code.optInt("symbols")} symbols. Ranked map samples are bounded; this is not an exhaustive index.")
    }
  }
  private fun dump() {
    if(!com.overhand.app.BuildConfig.DEBUG) return
    recorded()?.taskReplay?.let { r -> File(activity.filesDir,"task_replay_state.json").writeText(r.snapshot(project).toString(2)) }
    val nodes=JSONArray(model.nodes.map { JSONObject().put("id",it.id).put("layer",it.layer).put("x",it.x).put("y",it.y).put("z",it.z) })
    val edges=JSONArray(model.edges.map { JSONObject().put("from",it.from).put("to",it.to) })
    File(activity.filesDir,"graph_model.json").writeText(JSONObject().put("project",project).put("live",liveShown)
      .put("states",JSONObject(liveStates as Map<*,*>))
      .put("project_label",recorded()?.let { ProjectContext.label(ProjectContext.labels(it.projects),project) } ?: JSONObject.NULL).put("nodes",nodes).put("edges",edges)
      .put("page",page).put("selected",selected ?: JSONObject.NULL).put("detail_open",selected!=null).toString(2))
    val allText=components.map { clean(label(it)) }+displayed
    File(activity.filesDir,"graph_text.txt").writeText(allText.joinToString("\n"))
    root?.let { view -> if(view.width>0 && view.height>0) {
      val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
      File(activity.filesDir,"graph_detail.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    } }
  }
  /** Debug and layout-gate hook (#999): drill to [part] (a component id) and, with [row], open that file. */
  fun debugDrill(part: String,row: Int?=null) { drillTo(part,true);row?.let { openFile(it) } }
  /** Debug intent "graph_back": the public back(). */
  fun debugBack() { back() }
  fun debugPinch(id: String) { enqueue { sceneGraph.simulatePinch(id) } }
  /** Smoke hook (t775): ease to [zoom] about the focused node. */
  fun debugZoom(zoom: Double) { enqueue { sceneGraph.debugZoom(zoom) } }
  fun hide() { taskPlaying=false;ui.removeCallbacks(taskTick);motionPlaying=false;ui.removeCallbacks(motionTick);token++;enqueue { sceneGraph.clear() };show(false) }
  fun close() { stopped=true;token++;io.shutdownNow();ui.removeCallbacksAndMessages(null) }
  companion object {
    /** The framing used before the scene has drawn a frame (off-device renders). */
    private val REST=GraphDrill.Framing(Vec(0.0,0.0,0.0),doubleArrayOf(1.0,0.0,0.0,0.0),1.0)
    const val HINT="Pinch a part to see its files. Pinch a file to read its contents.\nPinch empty space to go back."
    const val SHOW_ON_SITE="Show on site"
    fun displayPattern(words: List<String>)=GraphText.pattern(words)
    // Shared shell gate above, plus products/models actually present in portfolio plans/code.
    val EXTRA_DENY=listOf("unity","kaggle","nvidia","cuda","pytorch","torch","tensorflow","jax",
      "lightgbm","xgboost","catboost","rdkit","openrouter","runpod","vllm","qwen","qwen3","deepseek",
      "microsoft","amazon","apple","mongodb","postgresql","postgres","sqlite","wandb","mlflow",
      "unsloth","axolotl","e4b")
    private fun enc(s: String)=URLEncoder.encode(s,"UTF-8")
    fun matchesPath(file: String,path: String): Boolean {
      val p=path.replace('\\','/').trimEnd('/')
      if(!p.contains('*') && !p.contains('?') && !p.contains('[')) return file==p || file.startsWith("$p/")
      val pattern=buildString { append('^');p.forEach { ch -> append(when(ch) { '*' -> ".*"; '?' -> "."; else -> Regex.escape(ch.toString()) }) };append('$') }
      return Regex(pattern).matches(file)
    }
  }
}

/** t765: the graph is framed no farther than this (inside the 2.5 m comfort rule). */
const val MAX_FRAMING_M=2.4f

/** ECS-thread owner. One batched solid mesh, at most 32 nodes' labels, no extra panel per node. */
@OptIn(com.meta.spatial.core.SpatialSDKExperimentalAPI::class)
class GraphScene(private val select: (String,Boolean) -> Unit) {
  private val entities=GraphEntities<Entity>(
    hide={ it.setComponent(Visible(false)) }, destroy={ it.destroy() })
  private fun entity(key: String,make: () -> Entity)=entities.get(key,make)
  private val input=GraphDrillInput()
  private fun dispatch(key: String,simulated: Boolean) {
    if(frameEase!=null || !input.accept(android.os.SystemClock.uptimeMillis())) {
      Log.i("OverhandGraph","event=drill_ignored target=$key reason=moving");return
    }
    Log.i("OverhandGraph","event=drill_accept target=$key")
    select(key,simulated)
  }
  private var root: Entity?=null
  private val labels=mutableListOf<Entity>()
  private var model=GraphModel(emptyList(),emptyList())
  private var page=0
  private var held=mutableSetOf<Boolean>()
  private var pose=Pose()
  private var file: File?=null
  private var marker: Entity?=null
  private val labelText=mutableMapOf<Entity,String>()
  private val labelAnchors=mutableMapOf<Entity,Vector3>()
  private var needsFraming=false
  /** #1380: the page centre where the graph last stood (null before its first open); kept by [GraphPlace.choose]. */
  private var savedCentre: Vec?=null
  private var localMid=Vec(0.0,0.0,0.0)
  private var captureViewpoint=Vector3(0f)
  private val coveredPanels=mutableMapOf<Entity,Boolean>()
  // t775: flat labels on the tiles (no billboard), the focused node's detail columns, zoom and workers.
  private var infos=emptyMap<String,GraphLod.NodeInfo>()
  private var items=emptyList<GraphLod.Item>()
  private val itemEntities=mutableMapOf<String,Entity>()
  private val itemShown=mutableMapOf<String,Int>()
  private var focus: String?=null
  private var zoom=1.0
  private var zoomFrom=1.0;private var zoomTarget=1.0;private var zoomAt=0L;private var zoomAnchor: Vec?=null;private var zoomRoot=Vec(0.0,0.0,0.0)
  private val building=linkedSetOf<String>()
  private val halos=mutableMapOf<String,Entity>()
  private val workers=mutableMapOf<String,Entity>()
  private val flows=mutableListOf<Pair<Entity,List<Vec>>>()
  private var traveller: Entity?=null;private var travelPath: List<Vec>?=null;private var travelAt=0L;private var lastWorker: String?=null
  /** Current zoom and the deepest level drawn, for the smoke and the panel. */
  @Volatile var zoomState="1.00x system";private set
  // GraphMotion: one reused pulse bar, one change label, one clock label (rebuilt only when text changes).
  private var pulse: Entity?=null
  private var pulseAt=0L
  private var changeLabel: Entity?=null
  private var clockLabel: Entity?=null
  private var clockShown=""
  // #944/#960: per-frame writers never change a Material or Mesh unless the state changed (a Material write rebuilds a
  // mesh://box scene object, and the pointer ray can meet it mid-rebuild). The "building" halo is static: one Material
  // for its whole life; the moving data pulses and the worker marker carry the motion. Caption colours are prebuilt per
  // bucket and written through [itemMaterial]; label billboards skip sub-0.1 mm changes.
  private val itemMaterial=com.overhand.app.features.SetOnChange<Entity,Material>()
  private val itemMaterials=HashMap<Pair<Boolean,Int>,Material>()
  private val labelPoses=com.overhand.app.features.SetOnChange<Entity,Pose> { a,b ->
    com.overhand.app.features.SetOnChange.near(floatArrayOf(a.t.x,a.t.y,a.t.z,a.q.w,a.q.x,a.q.y,a.q.z),
      floatArrayOf(b.t.x,b.t.y,b.t.z,b.q.w,b.q.x,b.q.y,b.q.z),com.overhand.app.features.SetOnChange.POSE_EPS) }
  private fun motionLabel(old: Entity?,text: String,at: Vector3,size: Float,colour: Color4): Entity {
    old?.let { labels.remove(it);labelText.remove(it);labelAnchors.remove(it);labelPoses.forget(it);it.setComponent(Visible(false)) }
    val e=entity("motion:$text:$size") { Entity.create(listOf(Mesh(Text3D.create(text=text,fontSize=size,depth=0.0005f),MeshCollision.NoCollision),
      Material().apply { unlit=true;baseColor=colour },Transform(Pose(at,Quaternion())),TransformParent(root!!))) }
      .also { it.setComponent(Transform(Pose(at,Quaternion())));it.setComponent(Visible(true)) }
    labels.add(e);labelText[e]=text;labelAnchors[e]=at;return e
  }
  /** Swap the batched solid mesh in place: same root entity, labels and framing. */
  fun recolour(mesh: File) {
    val e=root ?: run { mesh.delete();return }
    val previous=file;file=mesh;e.setComponent(Mesh(Uri.fromFile(mesh),MeshCollision.NoCollision)) // rebuild-ok: event-driven (plan state change), not per frame
    previous?.let { old -> android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ old.delete() },2000) }
  }
  /** The eye is led to the newest change: a pulsing bar over the node and a two-word label. */
  fun motionChange(id: String,word: String,state: String) {
    val r=root ?: return
    val n=model.nodes.drop(page*GraphLayout.PAGE_SIZE).take(GraphLayout.PAGE_SIZE).firstOrNull { it.id==id } ?: return
    val c=GraphMotion.colour(state).let { Color4((it[0]*2.2f).coerceAtMost(1f),(it[1]*2.2f).coerceAtMost(1f),(it[2]*2.2f).coerceAtMost(1f),1f) }
    val at=Vector3(n.x.toFloat(),n.y.toFloat()+0.19f,n.z.toFloat()-0.03f)
    if(pulse==null) pulse=entity("pulse") { Entity.create(listOf(Mesh(Uri.parse("mesh://box"),MeshCollision.NoCollision),Box(Vector3(-0.23f,-0.012f,-0.008f),Vector3(0.23f,0.012f,0.008f)),
      Material().apply { unlit=true;baseColor=c },Transform(Pose(at)),Scale(Vector3(1f)),TransformParent(r))) }
    pulse?.setComponent(Material().apply { unlit=true;baseColor=c }) // rebuild-ok: event-driven (motionChange), not per frame
    pulse?.setComponent(Transform(Pose(at)));pulse?.setComponent(Visible(true))
    pulseAt=android.os.SystemClock.uptimeMillis()
    if(state=="building") { lastWorker?.takeIf { it!=id }?.let { from -> travel(from,id) };lastWorker=id }
    states(infos.mapValues { it.value.state }+(id to GraphLod.state(state)))
    changeLabel=motionLabel(changeLabel,word,Vector3(n.x.toFloat()-0.20f,n.y.toFloat()+0.30f,n.z.toFloat()-0.15f),0.075f,c)
  }
  fun motionClock(text: String) {
    if(root==null || text==clockShown) return
    clockShown=text
    val visible=model.nodes.drop(page*GraphLayout.PAGE_SIZE).take(GraphLayout.PAGE_SIZE)
    val x=(visible.minOfOrNull { it.x } ?: 0.0).toFloat()-0.20f;val y=(visible.maxOfOrNull { it.y } ?: 0.0).toFloat()+0.55f
    clockLabel=motionLabel(clockLabel,text,Vector3(x,y,-0.15f),0.11f,Color4(1f,.86f,.45f,1f))
  }
  private fun motionTick() {
    val p=pulse ?: return
    if(pulseAt==0L) return
    val t=android.os.SystemClock.uptimeMillis()-pulseAt
    if(t>1200) { if(pulseAt!=0L) { p.setComponent(Scale(Vector3(1f)));pulseAt=0L };return }
    val s=1f+0.35f*sin((t/1200f)*3f*PI.toFloat()).absoluteValue*(1f-t/1200f)
    p.setComponent(Scale(Vector3(s,1f+2f*(s-1f),1f)))
  }
  fun clear() {
    entities.retire();input.reset()
    coveredPanels.forEach { (panel,visible) -> panel.setComponent(Visible(visible)) };coveredPanels.clear()
    pulse?.setComponent(Visible(false));pulse=null;changeLabel=null;clockLabel=null;clockShown=""
    labels.forEach { it.setComponent(Visible(false)) };labels.clear();labelPoses.clear();marker?.setComponent(Visible(false));marker=null
    labelText.clear();labelAnchors.clear()
    itemEntities.values.forEach { it.setComponent(Visible(false)) };itemEntities.clear();itemShown.clear();items=emptyList()
    halos.values.forEach { it.setComponent(Visible(false)) };halos.clear()
    itemMaterial.clear()
    workers.values.forEach { it.setComponent(Visible(false)) };workers.clear()
    flows.forEach { it.first.setComponent(Visible(false)) };flows.clear();traveller?.setComponent(Visible(false));traveller=null;travelPath=null;building.clear()
    zoom=1.0;zoomAnchor=null;focus=null;frameEase=null
    overlay.forEach { it.setComponent(Visible(false)) };overlay.clear();rects=emptyList();hoverBar?.setComponent(Visible(false));hoverBar=null;hoverLabel?.setComponent(Visible(false));hoverLabel=null;hoverKey=null
    root?.setComponent(Visible(false));root=null;file?.delete();file=null;held.clear()
  }
  fun place(at: Pose) { pose=at;needsFraming=true;root?.setComponent(Transform(at)) }
  /** t799: follow the graph's text panel when it is dragged by its grab bar; no re-framing.
   *  #863: a drag during a t775 zoom ease shifts the ease's root and anchor too, so the ease does not snap it back. */
  fun moveBy(d: Vector3) { val e=root ?: return;val p=e.getComponent<Transform>().transform;pose=Pose(p.t+d,p.q);e.setComponent(Transform(pose))
    zoomAnchor?.let { a -> zoomAnchor=Vec(a.x+d.x,a.y+d.y,a.z+d.z);zoomRoot=Vec(zoomRoot.x+d.x,zoomRoot.y+d.y,zoomRoot.z+d.z) } }
  fun install(next: GraphModel,nextPage: Int,nodes: Map<String,GraphLod.NodeInfo>,mesh: File,selected: String?,viewpoint: Vector3=Vector3(0f)) {
    clear();file=mesh;captureViewpoint=viewpoint
    model=next;page=nextPage;held.clear();needsFraming=true;localMid=GraphPlace.localCentre(model,page)
    // The fitted graph extends behind side windows. Focus it while retaining Fleet
    // live; restore each prior visibility when closing or rebuilding the graph.
    val sideWindows=setOf(com.overhand.app.R.id.code_panel,com.overhand.app.R.id.blueprint_panel,
      com.overhand.app.R.id.yard_panel,com.overhand.app.R.id.chat_panel,com.overhand.app.R.id.settings_panel)
    Query.where { has(Panel.id) }.eval().filter { it.getComponent<Panel>().panelRegistrationId in sideWindows }.forEach {
      coveredPanels[it]=it.tryGetComponent<Visible>()?.isVisible ?: true
      it.setComponent(Visible(false))
    }
    // #999: no IsdkGrabbable on the root: a pinch on the graph is a selection; the graph moves by its panel's grab bar.
    root=entity("root") { Entity.create(listOf(Mesh(Uri.fromFile(mesh),MeshCollision.NoCollision),Transform(pose),Scale(Vector3(1f)))) }
    // One line per node, at most GraphLabels.MAX characters, laid flat on its tile; text appears by apparent size.
    infos=nodes;focus=selected;items=GraphLod.items(model,infos,page,focus)
    states(nodes.mapValues { it.value.state })
    selected?.let { highlight(it) }
    Log.i("OverhandGraph","rendered nodes=${next.nodes.size} edges=${next.edges.size} page=$page batched_solids=1 label_em=${GraphLod.LABEL_EM} items=${items.size}")
  }
  fun clearFocus() { marker?.setComponent(Visible(false));marker=null;setFocus(null) }
  fun soundSource()=marker
  fun rootPose()=root?.getComponent<Transform>()?.transform
  /** t998: the graph's own grab (the bar and frame ray grab, as for a window). ECS thread. */
  fun rootEntity()=root
  /** World centre x,y,z and width, height of the current page's tiles, at the current zoom; null before a render. */
  fun grabRect(): FloatArray? {
    val p=rootPose() ?: return null
    val c=GraphLayout.corners(model,page);if(c.isEmpty()) return null
    val x0=c.minOf { it.x };val x1=c.maxOf { it.x };val y0=c.minOf { it.y };val y1=c.maxOf { it.y }
    val mid=p*Vector3(((x0+x1)/2*zoom).toFloat(),((y0+y1)/2*zoom).toFloat(),0f)
    return floatArrayOf(mid.x,mid.y,mid.z,((x1-x0)*zoom).toFloat(),((y1-y0)*zoom).toFloat())
  }
  /** Every scene label now shown (node names, change word, clock). ECS thread. */
  fun shownText()=labelText.values.toList()+items.filter { (itemShown[it.key] ?: 0)>0 }.map { it.text }
  fun highlight(id: String) {
    if(focus!=id) setFocus(id)
    marker?.setComponent(Visible(false))
    val n=model.nodes.firstOrNull { it.id==id } ?: return
    marker=entity("selection") { Entity.create(listOf(Mesh(Uri.parse("mesh://box"),MeshCollision.NoCollision),Box(Vector3(-0.22f,-0.004f,-0.005f),Vector3(0.22f,0.004f,0.005f)),
      Material().apply { unlit=true;baseColor=Color4(1f,.78f,.23f,1f) },
      Transform(Pose(Vector3(n.x.toFloat(),n.y.toFloat()-0.14f,n.z.toFloat()-0.03f))),TransformParent(root!!))) }
      .also { it.setComponent(Transform(Pose(Vector3(n.x.toFloat(),n.y.toFloat()-0.14f,n.z.toFloat()-0.03f))));it.setComponent(Visible(true)) }
  }
  fun metrics(objects: SceneObjectSystem,viewer: Pose): JSONObject {
    val rp=root?.getComponent<Transform>()?.transform ?: return JSONObject().put("loaded",false)
    val entries=labels.mapNotNull { e ->
      val future=objects.getSceneObject(e) ?: return@mapNotNull null
      if(!future.isDone || future.isCompletedExceptionally) return@mapNotNull null
      val obj=future.getNow(null) ?: return@mapNotNull null
      val bounds=obj.mesh?.computeCombinedBounds() ?: return@mapNotNull null
      val height=bounds.size().y*zoom.toFloat()
      val local=e.getComponent<Transform>().transform
      val world=rp*(local*bounds.center())
      val worldUp=rp.q*(local.q*Vector3(0f,1f,0f))
      val worldFront=rp.q*(local.q*Vector3(0f,0f,-1f))
      val delta=world-viewer.t
      val distance=sqrt(delta.dot(delta))
      JSONObject().put("text",labelText[e])
        .put("up_dot",worldUp.dot(viewer.q*Vector3(0f,1f,0f)))
        .put("facing_dot",worldFront.dot(viewer.t-world)).put("height_m",height).put("width_m",bounds.size().x)
        .put("distance_m",distance).put("glyph_angle_deg",Math.toDegrees(2*atan(height/(2*distance)).toDouble()))
    }
    val shownItems=items.filter { (itemShown[it.key] ?: 0)>0 }.map { item ->
      val world=rp.t+rp.q*(Vector3(item.x.toFloat(),item.y.toFloat(),item.z.toFloat())*zoom.toFloat())
      val d=world-viewer.t
      JSONObject().put("text",item.text).put("level",item.level.name).put("em_m",item.em*zoom)
        .put("deg",GraphLod.degrees(item.em,zoom,sqrt(d.dot(d)).toDouble()))
    }
    val p=rp.t
    val solid=objects.getSceneObject(root!!)?.takeIf { it.isDone && !it.isCompletedExceptionally }?.getNow(null)?.mesh
    return JSONObject().put("loaded",true).put("solid_mesh_loaded",solid!=null)
      .put("solid_bounds",solid?.computeCombinedBounds()?.toString()).put("label_count",labels.size).put("loaded_labels",entries.size)
      .put("labels",JSONArray(entries)).put("grab_enabled",false).put("overlay",JSONArray(rects.map { it.key }))
      .put("zoom",zoom).put("zoom_state",zoomState).put("focus",focus ?: JSONObject.NULL).put("items",JSONArray(shownItems))
      .put("building",JSONArray(building.toList())).put("workers",workers.size).put("flows",flows.size)
      .put("root",JSONArray(listOf(p.x,p.y,p.z)))
      .put("viewer",JSONArray(listOf(viewer.t.x,viewer.t.y,viewer.t.z,viewer.q.w,viewer.q.x,viewer.q.y,viewer.q.z)))
      .put("synthetic_viewpoint",JSONArray(listOf(captureViewpoint.x,captureViewpoint.y,captureViewpoint.z)))
      .put("nodes",model.nodes.size).put("edges",model.edges.size)
  }
  fun simulatePinch(id: String) {
    val n=model.nodes.firstOrNull { it.id==id } ?: return
    // The debug hook uses the same inverse-root ray and AABB path as a tracked hand.
    val rootPose=root?.getComponent<Transform>()?.transform ?: return
    val worldOrigin=rootPose*Vector3(n.x.toFloat(),n.y.toFloat(),-1f)
    val worldEnd=rootPose*Vector3(n.x.toFloat(),n.y.toFloat(),1f)
    ray(rootPose,worldOrigin,worldEnd-worldOrigin,true)
  }
  private fun ray(rootPose: Pose,origin: Vector3,direction: Vector3,simulated: Boolean) {
    val key=targetAt(rootPose,origin,direction) ?: return
    Log.i("OverhandGraph","pinch target=$key simulated=$simulated")
    dispatch(key,simulated)
  }
  /** #999: what a world ray points at (GraphDrill.target): a card row, a crumb, a node, empty graph space, or null. */
  private fun targetAt(rootPose: Pose,origin: Vector3,direction: Vector3): String? {
    val inverse=rootPose.inverse();val o=(inverse*origin)*(1f/zoom.toFloat());val d=inverse.q*direction
    val eye=lastEye ?: origin;val dist=sqrt((rootPose.t-eye).dot(rootPose.t-eye)).toDouble().coerceAtLeast(0.3)
    return GraphDrill.target(model,page,rects,Vec(o.x.toDouble(),o.y.toDouble(),o.z.toDouble()),Vec(d.x.toDouble(),d.y.toDouble(),d.z.toDouble()),
      GraphDrill.minHalf(GraphDrill.NODE_MIN_DEG,dist,zoom))
  }
  private var lastEye: Vector3?=null
  private fun setFocus(id: String?) {
    focus=id
    val next=GraphLod.items(model,infos,page,focus);val keep=next.map { it.key }.toSet()
    itemEntities.keys.filter { it !in keep }.forEach { itemEntities[it]?.setComponent(Visible(false));itemShown.remove(it) }
    items=next
  }
  /** Ease the zoom to [target] over 250 ms about the world point [anchor]. */
  fun zoomTo(target: Double,anchor: Vec) {
    val r=root ?: return
    zoomFrom=zoom;zoomTarget=GraphLod.clampZoom(target);zoomAt=android.os.SystemClock.uptimeMillis();zoomAnchor=anchor
    zoomRoot=r.getComponent<Transform>().transform.t.let { Vec(it.x.toDouble(),it.y.toDouble(),it.z.toDouble()) }
  }
  /** Debug and smoke hook: zoom about the focused node's centre (else the root). */
  fun debugZoom(target: Double) {
    val r=root ?: return;val rp=r.getComponent<Transform>().transform
    val n=focus?.let { f -> model.nodes.firstOrNull { it.id==f } }
    val at=n?.let { rp.t+rp.q*(Vector3(it.x.toFloat(),it.y.toFloat(),it.z.toFloat())*zoom.toFloat()) } ?: rp.t
    zoomTo(target,Vec(at.x.toDouble(),at.y.toDouble(),at.z.toDouble()))
  }
  private fun applyZoom(r: Entity,s: Double,t: Vec) {
    zoom=s;val rp=r.getComponent<Transform>().transform
    r.setComponent(Transform(Pose(Vector3(t.x.toFloat(),t.y.toFloat(),t.z.toFloat()),rp.q)));r.setComponent(Scale(Vector3(s.toFloat())))
    pose=r.getComponent<Transform>().transform
  }
  /** Nodes now being built: a breathing halo, a worker marker, and data pulses along their incoming edges. */
  fun states(next: Map<String,String>) {
    infos=infos.mapValues { (id,i) -> next[id]?.let { i.copy(state=it) } ?: i }
    val r=root ?: return
    val now=next.filterValues { it=="building" }.keys
    (building-now).forEach { id -> halos[id]?.setComponent(Visible(false));workers[id]?.setComponent(Visible(false)) }
    building.clear();building.addAll(now)
    val visible=model.nodes.drop(page*GraphLayout.PAGE_SIZE).take(GraphLayout.PAGE_SIZE).associateBy { it.id }
    now.forEach { id -> val n=visible[id] ?: return@forEach
      val hw=(GraphLayout.WIDTH/2+0.02).toFloat();val hh=(GraphLayout.HEIGHT/2+0.02).toFloat()
      halos[id]=entity("halo:$id") { Entity.create(listOf(Mesh(Uri.parse("mesh://box"),MeshCollision.NoCollision),Box(Vector3(-hw,-hh,-0.004f),Vector3(hw,hh,0.004f)),
        Material().apply { unlit=true;baseColor=Color4(.28f,.65f,.98f,1f) },Transform(Pose(Vector3(n.x.toFloat(),n.y.toFloat(),n.z.toFloat()+0.03f))),TransformParent(r))) }
      .also { it.setComponent(Visible(true)) }
      if(id !in workers) workers[id]=workerMarker(r,Vector3(n.x.toFloat()+0.17f,n.y.toFloat()-0.115f,n.z.toFloat()-0.04f),"worker:$id")
      workers[id]?.setComponent(Visible(true))
    }
    flows.forEach { it.first.setComponent(Visible(false)) };flows.clear()
    model.edges.forEachIndexed { i,e -> if(e.to in now && flows.size<12) {
      val a=visible[e.from];val b=visible[e.to]
      if(a!=null && b!=null) flows.add(entity("flow:$i") { Entity.create(listOf(Mesh(Uri.parse("mesh://box"),MeshCollision.NoCollision),Box(Vector3(-0.012f),Vector3(0.012f)),
        Material().apply { unlit=true;baseColor=Color4(.75f,.9f,1f,1f) },Transform(Pose(Vector3(0f))),TransformParent(r))) }
      .also { it.setComponent(Visible(true)) } to GraphLod.edgePath(a,b,i))
    } }
  }
  private fun workerMarker(r: Entity,at: Vector3,key: String="traveller")=entity(key) { Entity.create(listOf(Mesh(Uri.parse("mesh://box"),MeshCollision.NoCollision),Box(Vector3(-0.022f),Vector3(0.022f)),
    Material().apply { unlit=true;baseColor=Color4(1f,1f,1f,1f) },Transform(Pose(at)),TransformParent(r))) }
      .also { it.setComponent(Transform(Pose(at)));it.setComponent(Visible(true)) }
  /** A worker leaves [from] for [to]: a marker travels the joining edge (or a straight hop) over 900 ms. */
  private fun travel(from: String,to: String) {
    val r=root ?: return
    val path=GraphLod.workerPath(model,from,to) ?: return
    traveller?.setComponent(Visible(false));traveller=workerMarker(r,path.first().let { Vector3(it.x.toFloat(),it.y.toFloat(),it.z.toFloat()) })
    travelPath=path;travelAt=android.os.SystemClock.uptimeMillis()
  }
  private fun animate(r: Entity,eye: Vector3) {
    val now=android.os.SystemClock.uptimeMillis()
    zoomAnchor?.let { anchor ->
      val t=(now-zoomAt).toDouble()/GraphLod.EASE_MS
      val s=zoomFrom+(zoomTarget-zoomFrom)*GraphLod.ease(t)
      applyZoom(r,s,GraphLod.about(zoomRoot,anchor,zoomFrom,s))
      if(t>=1.0) zoomAnchor=null
    }
    val phase=(now%1600)/1600.0
    flows.forEach { (e,path) -> val p=GraphLod.along(path,phase);e.setComponent(Transform(Pose(Vector3(p.x.toFloat(),p.y.toFloat(),p.z.toFloat())))) }
    travelPath?.let { path ->
      val t=(now-travelAt)/900.0
      val p=GraphLod.along(path,GraphLod.ease(t))
      traveller?.setComponent(Transform(Pose(Vector3(p.x.toFloat(),p.y.toFloat(),p.z.toFloat()))))
      if(t>=1.0) { traveller?.setComponent(Visible(false));traveller=null;travelPath=null }
    }
    // Text by apparent size: drawn only at or above the caption floor, faded in over the next 10 %.
    val rp=r.getComponent<Transform>().transform
    val local=(rp.inverse()*eye)*(1f/zoom.toFloat())
    items.forEach { item ->
      val dx=item.x+item.width/2-local.x;val dy=item.y-local.y;val dz=item.z-local.z
      val a=GraphLod.alpha(item.em,zoom,sqrt(dx*dx+dy*dy+dz*dz)*zoom)
      val bucket=(a*4).roundToInt().let { if(a>0 && it==0) 1 else it }
      if(bucket==(itemShown[item.key] ?: 0)) return@forEach
      itemShown[item.key]=bucket
      val e=itemEntities[item.key]
      if(bucket==0) { e?.setComponent(Visible(false));return@forEach }
      val major=item.level==GraphLod.Level.SYSTEM || item.level==GraphLod.Level.MODULE
      val material=itemMaterials.getOrPut(major to bucket) { Material().apply { unlit=true
        baseColor=if(major) Color4(.945f,.957f,.969f,bucket/4f) else Color4(.80f,.84f,.88f,bucket/4f) } }
      if(e==null) itemEntities[item.key]=entity("detail:${item.key}") { Entity.create( // create-once: only while the key has no entity
        listOf(Mesh(Text3D.create(text=item.text,fontSize=item.em.toFloat(),depth=0.0005f),MeshCollision.NoCollision),
        material,
        Transform(Pose(Vector3(item.anchorX.toFloat(),item.anchorY.toFloat(),item.z.toFloat()),Quaternion())),TransformParent(r))) }
      .also { it.setComponent(Visible(true)) }
      else { itemMaterial.set(e,material) { e.setComponent(it) };e.setComponent(Visible(true)) }
    }
    val dist=sqrt((rp.t-eye).dot(rp.t-eye)).toDouble()
    zoomState="%.2fx %s".format(zoom,GraphLod.level(zoom,dist).name.lowercase())
  }
  // ---- #999 drill-down: framing, files card, breadcrumb, hover ---------------------------------------------------
  /** Card rows, the page button and breadcrumb segments now drawn, in graph-local metres (hit before nodes). */
  private var rects=emptyList<GraphDrill.Rect>()
  private val overlay=mutableListOf<Entity>()
  private val pointer=GraphPointer()
  private var hoverKey: String?=null
  private var hoverBar: Entity?=null
  private var hoverLabel: Entity?=null
  private var frameEase: Triple<GraphDrill.Framing,GraphDrill.Framing,Long>?=null
  /** The root's framing, refreshed every frame on the ECS thread (read from the UI thread). */
  @Volatile var framing: GraphDrill.Framing?=null;private set
  private fun Vector3.vec()=Vec(x.toDouble(),y.toDouble(),z.toDouble())
  private fun Vec.v3()=Vector3(x.toFloat(),y.toFloat(),z.toFloat())
  private fun currentFraming(): GraphDrill.Framing? { val rp=root?.getComponent<Transform>()?.transform ?: return null
    return GraphDrill.Framing(rp.t.vec(),doubleArrayOf(rp.q.w.toDouble(),rp.q.x.toDouble(),rp.q.y.toDouble(),rp.q.z.toDouble()),zoom) }
  /** Ease the root to [f] (pose and zoom) over 400 ms; back() restores exactly the framing it left. */
  fun frameTo(f: GraphDrill.Framing) {
    if(frameEase!=null) return
    val now=currentFraming() ?: return;zoomAnchor=null
    val at=android.os.SystemClock.uptimeMillis();input.block(at)
    frameEase=Triple(now,f,at)
    Log.i("OverhandGraph","event=drill_move duration_ms=${GraphDrillInput.MOVE_MS}")
  }
  /** The part level's framing: node [id] spans [GraphDrill.PART_DEG] and sits left of centre so its files card fits. */
  fun partFraming(id: String): GraphDrill.Framing? {
    val r=root ?: return null
    val rp=r.getComponent<Transform>().transform;val eye=lastEye ?: return null
    // #1380: framed by angle (GraphFrame): the box and its children 25 to 40 degrees wide, nothing nearer than 0.9 m.
    val f=GraphFrame.frame(model,page,id,eye.vec(),doubleArrayOf(rp.q.w.toDouble(),rp.q.x.toDouble(),rp.q.y.toDouble(),rp.q.z.toDouble())) ?: return null
    settleNote="dist=${"%.2f".format(f.dist)} width_deg=${"%.1f".format(f.widthDeg)} nearest=${"%.2f".format(f.nearest)}"
    return f.framing
  }
  private var settleNote=""
  /** Distance from the eye to the root, for sizing the overlay. */
  fun viewDistance(): Double { val rp=root?.getComponent<Transform>()?.transform ?: return 2.4;val e=lastEye ?: return 2.4
    return sqrt((rp.t-e).dot(rp.t-e)).toDouble().coerceIn(0.8,MAX_FRAMING_M.toDouble()) }
  private fun easeFrame(r: Entity) {
    val (a,b,at)=frameEase ?: return
    val t=(android.os.SystemClock.uptimeMillis()-at).toDouble()/GraphDrillInput.MOVE_MS
    val step=GraphDrillMove.sample(a,b,t);val p=step.t;zoom=step.zoom
    val q=Quaternion(step.q[0].toFloat(),step.q[1].toFloat(),step.q[2].toFloat(),step.q[3].toFloat())
    r.setComponent(Transform(Pose(p.v3(),q)));r.setComponent(Scale(Vector3(zoom.toFloat())))
    pose=r.getComponent<Transform>().transform
    if(t>=1.0) { frameEase=null;Log.i("OverhandGraph","event=drill_settled zoom=${"%.2f".format(zoom)} $settleNote");settleNote="" }
  }
  /**
   * The files card beside [id] ([rows] labels, a "More files" row when [paged]) and the breadcrumb above it, sized
   * for zoom [z] seen from [distance]. Null [id]: the breadcrumb alone, above the graph. Labels are already gated.
   */
  fun overlay(id: String?,rows: List<String>,paged: Boolean,crumbs: List<String>,z: Double,distance: Double) {
    val r=root ?: return
    overlay.forEach { it.setComponent(Visible(false)) };overlay.clear()
    val n=id?.let { i -> model.nodes.firstOrNull { it.id==i } }
    val card=if(n!=null) GraphDrill.cardRects(n,rows.size,paged,z,distance) else emptyList()
    val visible=model.nodes.drop(page*GraphLayout.PAGE_SIZE).take(GraphLayout.PAGE_SIZE)
    val left=n?.let { it.x-GraphLayout.WIDTH/2 } ?: ((visible.minOfOrNull { it.x } ?: 0.0)-GraphLayout.WIDTH/2)
    val rowH=2*GraphDrill.minHalf(GraphDrill.ROW_DEG,distance,z)*1.1
    val top=(n?.let { it.y+GraphLayout.HEIGHT/2 } ?: ((visible.maxOfOrNull { it.y } ?: 0.0)+GraphLayout.HEIGHT/2))+rowH*1.6
    val shown=crumbs.map { GraphLabels.shorten(it,24) }
    val crumbs3d=GraphDrill.crumbRects(shown,left,top,(n?.z ?: 0.0)-0.05/z,z,distance)
    rects=card+crumbs3d
    fun plate(rc: GraphDrill.Rect,colour: Color4)=entity("plate:${rc.key}") { Entity.create(listOf(Mesh(Uri.parse("mesh://box"),MeshCollision.NoCollision),
      Box(Vector3(-1f),Vector3(1f)),Material().apply { unlit=true;baseColor=colour },Transform(Pose()),TransformParent(r))) }
      .also {
      it.setComponent(Transform(Pose(Vector3(rc.x.toFloat(),rc.y.toFloat(),rc.z.toFloat()+.004f/z.toFloat()))))
      it.setComponent(Scale(Vector3(rc.halfW.toFloat(),rc.halfH.toFloat(),.002f/z.toFloat())));it.setComponent(Visible(true)) }
    fun words(rc: GraphDrill.Rect,text: String) {
      val em=rc.halfH*0.9;val x=rc.x-rc.halfW+0.4*em;val y=rc.y+em*0.45
      overlay.add(entity("overlay-text:${rc.key}:$text") { Entity.create(listOf(Mesh(Text3D.create(text=text,fontSize=.05f,depth=0.0005f),MeshCollision.NoCollision),
        Material().apply { unlit=true;baseColor=Color4(.945f,.957f,.969f,1f) },
        Transform(Pose(Vector3((x-GraphLod.START_EM*em).toFloat(),(y-GraphLod.TOP_EM*em).toFloat(),rc.z.toFloat()),Quaternion())),TransformParent(r))) }
      .also {
        it.setComponent(Transform(Pose(Vector3((x-GraphLod.START_EM*em).toFloat(),(y-GraphLod.TOP_EM*em).toFloat(),rc.z.toFloat()))))
        it.setComponent(Scale(Vector3((em/.05).toFloat())));it.setComponent(Visible(true)) })
    }
    card.forEachIndexed { i,rc -> overlay.add(plate(rc,Color4(.15f,.17f,.20f,1f)))
      val label=if(rc.key=="more") "More files" else rows.getOrNull(i).orEmpty()
      words(rc,GraphLabels.shorten(label,(2*rc.halfW/(0.43*rc.halfH*0.9)).toInt().coerceIn(8,40))) }
    crumbs3d.forEachIndexed { i,rc -> overlay.add(plate(rc,if(i==crumbs3d.lastIndex) Color4(.20f,.30f,.40f,1f) else Color4(.12f,.14f,.17f,1f)))
      words(rc,(if(i>0) "> " else "")+shown[i]) }
    hoverKey="";hover(null)
    Log.i("OverhandGraph","drill overlay part=${id ?: "-"} rows=${rows.size} paged=$paged crumbs=${shown.joinToString(">")} zoom=${"%.2f".format(z)}")
  }
  /** Hover: a bright bar under the node (or under a row or crumb) and, for a node, its name above it. */
  private fun hover(key: String?) {
    if(key==hoverKey) return
    hoverKey=key;hoverBar?.setComponent(Visible(false));hoverLabel?.setComponent(Visible(false))
    val r=root ?: return
    if(key==null || key=="empty") return
    val rc=rects.firstOrNull { it.key==key }
    val n=model.nodes.firstOrNull { "node:${it.id}"==key }
    if(rc==null && n==null) return
    val c=Color4(.55f,.85f,1f,1f)
    hoverBar=entity("hover") { Entity.create(listOf(Mesh(Uri.parse("mesh://box"),MeshCollision.NoCollision),
      Box(Vector3(-1f),Vector3(1f)),Material().apply { unlit=true;baseColor=c },Transform(Pose()),TransformParent(r))) }
    val at=if(rc!=null) Vector3(rc.x.toFloat(),(rc.y-rc.halfH*.9).toFloat(),(rc.z-.003).toFloat())
      else Vector3(n!!.x.toFloat(),n.y.toFloat()-.165f,n.z.toFloat()-.03f)
    hoverBar?.setComponent(Transform(Pose(at)))
    hoverBar?.setComponent(Scale(if(rc!=null) Vector3(rc.halfW.toFloat(),(rc.halfH*.08).toFloat(),.001f) else Vector3(.22f,.006f,.005f)))
    hoverBar?.setComponent(Visible(true))
    if(n!=null) {
      val name=infos[n.id]?.label ?: n.id
      hoverLabel=entity("hover-name:${n.id}") { Entity.create(listOf(Mesh(Text3D.create(text=name,fontSize=.05f,depth=.0005f),MeshCollision.NoCollision),
        Material().apply { unlit=true;baseColor=c },Transform(Pose(Vector3((n.x-.2).toFloat(),(n.y+.17).toFloat(),(n.z-.06).toFloat()))),TransformParent(r))) }
      hoverLabel?.setComponent(Visible(true))
    }
  }
  /** #999: the Code window shows the opened file; it is hidden again when the drill leaves the code level. */
  fun revealCode(show: Boolean) {
    coveredPanels.keys.firstOrNull { it.getComponent<Panel>().panelRegistrationId==com.overhand.app.R.id.code_panel }?.setComponent(Visible(show))
  }
  fun tick(scene: Scene,paused: Boolean,head: Pose?=null,hands: Map<String,com.overhand.app.hands.HandJoints?> =emptyMap()) {
    entities.tick()
    val e=root ?: return
    if(paused) { held.clear();return }
    val viewer=head ?: scene.getViewerPose()
    var rp=e.getComponent<Transform>().transform
    if(needsFraming) {
      // #1380: placed by the head, yaw only (GraphPlace), not by the Code window's pose and tilt; a saved place
      // wins only inside the Yard's rule.
      val hq=doubleArrayOf(viewer.q.w.toDouble(),viewer.q.x.toDouble(),viewer.q.y.toDouble(),viewer.q.z.toDouble())
      val at=GraphPlace.choose(savedCentre,viewer.t.vec(),hq)
      val local=localMid
      // clear() resets zoom on every install; fit the overview even when reusing a saved centre.
      zoom=GraphPlace.fitZoom(model,page,at.dist)
      val q=Quaternion(at.q[0].toFloat(),at.q[1].toFloat(),at.q[2].toFloat(),at.q[3].toFloat())
      val t=GraphPlace.rootT(at.centre,at.q,local,zoom).v3()-viewer.q*captureViewpoint
      rp=Pose(t,q);e.setComponent(Transform(rp));e.setComponent(Scale(Vector3(zoom.toFloat())));pose=rp;needsFraming=false
      Log.i("OverhandGraph","event=place why=${at.why} dist=${"%.2f".format(at.dist)} yaw_off=${"%.1f".format(at.yawOffDeg)} zoom=${"%.2f".format(zoom)}")
    }
    motionTick()
    lastEye=viewer.t
    easeFrame(e)
    animate(e,viewer.t);rp=e.getComponent<Transform>().transform
    framing=currentFraming()
    savedCentre=(rp.t+rp.q*(localMid.v3()*zoom.toFloat())).vec()
    val inverse=rp.inverse()
    val eye=inverse*viewer.t
    val cameraUp=inverse.q*(viewer.q*Vector3(0f,1f,0f))
    labels.forEach { label ->
      val anchor=labelAnchors.getValue(label)
      val frame=GraphLayout.labelFrame(Vec(anchor.x.toDouble(),anchor.y.toDouble(),anchor.z.toDouble()),
        Vec(eye.x.toDouble(),eye.y.toDouble(),eye.z.toDouble()),
        Vec(cameraUp.x.toDouble(),cameraUp.y.toDouble(),cameraUp.z.toDouble()))
      val back=frame.back;val up=frame.up;val right=frame.right
      // Pivot the whole line about its node, keeping both ends ahead of the solid.
      val t=anchor-Vector3(right.x.toFloat(),right.y.toFloat(),right.z.toFloat())*0.20f
      labelPoses.set(label,Pose(t,Quaternion.fromDirection(
        Vector3(back.x.toFloat(),back.y.toFloat(),back.z.toFloat()),
        Vector3(up.x.toFloat(),up.y.toFloat(),up.z.toFloat())))) { label.setComponent(Transform(it)) }
    }
    // #999: ray and pinch from the tracked joints (GraphPointer), not the controller pose at time 0 and its pinch bit.
    val frameRoot=rp
    pointer.update(android.os.SystemClock.elapsedRealtime(),com.overhand.app.features.V3(viewer.t.x.toDouble(),viewer.t.y.toDouble(),viewer.t.z.toDouble()),hands) { o,d ->
      targetAt(frameRoot,Vector3(o.x.toFloat(),o.y.toFloat(),o.z.toFloat()),Vector3(d.x.toFloat(),d.y.toFloat(),d.z.toFloat())) }.forEach { ev ->
      when(ev) {
        is GraphPointer.Event.Hover -> { hover(ev.target);Log.i("OverhandGraph","hover hand=${ev.hand} target=${ev.target ?: "-"}") }
        is GraphPointer.Event.Select -> {
          Log.i("OverhandGraph","pinch hand=${ev.hand} target=${ev.target ?: "-"} onset_hit=${ev.onsetHit ?: "-"} simulated=false")
          ev.target?.let { dispatch(it,false) }
        }
      }
    }
    // Pinches select modules. Hand separation must not become a second transform writer after a drill.
  }
}
