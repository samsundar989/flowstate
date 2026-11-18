package com.overhand.app.stations
import com.overhand.app.features.windowPose

import com.overhand.app.features.windowPose

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.util.Log
import android.view.View
import android.widget.*
import com.meta.spatial.core.*
import com.meta.spatial.isdk.*
import com.meta.spatial.toolkit.*
import com.overhand.app.R
import com.overhand.app.graph.ProjectContext
import com.overhand.app.graph.ProjectView
import com.overhand.app.graph.TaskProgressView
import org.json.*
import java.net.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.math.*

/** Labels are small native mesh panels, never WebViews. One shared window cluster follows focus. */
@OptIn(SpatialSDKExperimentalAPI::class)
class Stations(private val activity: Activity, private val server: () -> String,
  private val commands: ConcurrentLinkedQueue<() -> Unit>,
  private val recorded: () -> com.overhand.app.windows.RecordedContent? = { null },
  private val stage: () -> Pose? = { null },
  private val focus: (String, Pose) -> Unit) {
  private val prefs=activity.getSharedPreferences(if(recorded()!=null) "stations-recorded-v1" else "windows-v1",Activity.MODE_PRIVATE)
  private val ui=Handler(Looper.getMainLooper())
  private val io=Executors.newSingleThreadExecutor { r -> Thread(r,"station-poll").apply { isDaemon=true } }
  private val ids=List(32) { View.generateViewId() }
  private val roots=mutableMapOf<Int,TextView>() // UI thread
  private val progressBars=mutableMapOf<Int,ProgressBar>()
  private val entries=linkedMapOf<String,Entry>() // ECS thread
  private var states=listOf<StationState>() // UI thread
  @Volatile private var selected="" // written on the ECS thread, painted on the UI thread
  private var paintedSelected=""
  /** Opening: 0 glow only, 1 one name, 2 full text (see Opening.stationMode). */
  @Volatile var mode=2
  private var paintedMode=2
  private val labels by lazy { recorded()?.let { ProjectContext.labels(it.projects) } ?: emptyMap() }
  private val rowTime by lazy { ProjectView.rowTimes(recorded()?.source?.events ?: emptyList()) }
  private val selectedOutline by lazy { GradientDrawable().apply { setStroke(4,Color.WHITE);setColor(Color.argb(40,255,255,255)) } }
  @Volatile private var hostFirst: String?=null // #1122: StationRules.liveDefault
  private var candidate=""
  private var since=0L
  private var lastFocus=""
  @Volatile private var closed=false
  @Volatile private var busy=false
  private var origin: Pose?=null
  private var attentionUpdated=0L
  private var paintedStep=-1
  private val deny=StationRules.brandPattern(activity.assets.open("windows.js").bufferedReader().use { it.readText() })
  private data class Entry(val slot: Int,val entity: Entity,var saved: String="",var held: Boolean=false)
  fun registrations(): List<PanelRegistration> = ids.mapIndexed { slot,id ->
    LayoutXMLPanelRegistration(id,layoutIdCreator={ R.layout.web_panel },settingsCreator={
      com.overhand.app.windows.Theme.panel(UIPanelSettings(shape=QuadShapeOptions(width=0.60f,height=if(recorded()!=null) 0.40f else 0.22f),
        display=DpDisplayOptions(width=384f,height=if(recorded()!=null) 256f else 140f),
        rendering=UIPanelRenderOptions(PanelRenderMode.Mesh())))
    },panelSetupWithRootView={ root,_,_ ->
      val column=root.findViewById<LinearLayout>(R.id.panel_root)
      val text=com.overhand.app.features.Shell.inset(TextView(activity)).apply { textSize=25f
        setOnClickListener { commands.add { entries.entries.firstOrNull { it.value.slot==slot }?.let { choose(it.key) } } } }
      if(recorded()!=null) {
        column.addView(text,LinearLayout.LayoutParams(-1,0,1f))
        val bar=ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal)
        column.addView(bar,LinearLayout.LayoutParams(-1,8));progressBars[slot]=bar
      } else column.addView(text,LinearLayout.LayoutParams(-1,-1))
      roots[slot]=text;paint(false)
    })
  }
  fun start() { ui.post(poll) }
  private val poll=object: Runnable {
    override fun run() {
      if(closed) return
      recorded()?.let { data ->
        val now=SystemClock.uptimeMillis()
        val attentionDue=states.isEmpty() || now-attentionUpdated>=5000
        if(attentionDue) {
          val rows=data.stations();states=rows;attentionUpdated=now
          commands.add { reconcile(rows) }
        }
        if(attentionDue || paintedStep!=data.taskReplay.step || paintedSelected!=selected || paintedMode!=mode) { paintedStep=data.taskReplay.step;paint(false) }
        ui.postDelayed(this,100);return
      }
      if(!busy) {
        busy=true;val base=server()
        io.execute {
          val result=runCatching {
            val c=URL("$base/api/state").openConnection() as HttpURLConnection
            c.connectTimeout=2000;c.readTimeout=3000
            try { check(c.responseCode==200);JSONObject(c.inputStream.bufferedReader().use { it.readText() }).also { com.overhand.app.audio.SpatialEarcons.of(activity).live(it) }
              .let { hostFirst=StationRules.liveDefault(it,prefs.getString(StationRules.LIVE_CHOICE,null));StationRules.evaluate(it) } }
            finally { c.disconnect() }
          }
          ui.post {
            busy=false
            if(!closed && server()==base) {
              result.onSuccess { rows ->
                states=rows;paint(false)
                commands.add { reconcile(rows) }
                Log.i("Overhand","stations state "+rows.joinToString { "${it.id}=${it.needsYou}" })
              }.onFailure { paint(true) }
            }
          }
        }
      }
      ui.postDelayed(this,5000)
    }
  }
  private fun paint(stale: Boolean) {
    paintedSelected=selected;paintedMode=mode
    states.forEachIndexed { slot,s -> roots[slot]?.apply {
      val status=if(stale) "○ Status unavailable" else if(s.needsYou) "● ${s.detail}" else "● Clear"
      val replay=recorded()?.taskReplay
      if(replay!=null) {
        val p=replay.progress(s.id);val name=ProjectContext.label(labels,s.id);val focused=s.id==selected
        if(mode<2) {
          // Opening: a glowing plinth (status colour), then its one name.
          val value=if(mode==0) "" else deny.replace(name,"agent")
          if(text.toString()!=value) text=value
          textSize=if(mode==0) 25f else 34f;gravity=android.view.Gravity.CENTER
          contentDescription=deny.replace(name,"agent")
          background=GradientDrawable().apply { cornerRadius=28f
            setColor(if(s.needsYou) Color.argb(110,255,170,60) else Color.argb(110,70,200,160))
            setStroke(if(focused) 6 else 3,if(s.needsYou) Color.rgb(255,190,90) else Color.rgb(110,225,190)) }
          progressBars[slot]?.let { TaskProgressView.update(it,p.fraction) }
          setTextColor(Color.WHITE);return@apply
        }
        textSize=GLANCE_SP;gravity=android.view.Gravity.START or android.view.Gravity.TOP
        val view=ProjectView.state(s.id,replay.tMs,rowTime,recorded()?.source?.views ?: ProjectView.PROJECTS)
        // #816: at the arc's 3.3 m a 0.6 m panel holds about four lines at the 1 degree floor: the bold lines
        // (selected, name, what needs the wearer) and "Not anchored". What it is, Now and the replay clock stay in
        // the spoken description and the graph panel; the windows' badge says the day is replayed.
        val glance=ProjectContext.stationLines(name,focused,view,replay.clock,com.overhand.app.mr.MrStations.notAnchored)
          .filter { it.bold || it.text.startsWith("○") }.map { it.copy(scale=1f) }
        val value=TaskProgressView.styled(glance) { deny.replace(it,"agent") }
        if(text.toString()!=value.toString()) text=value
        contentDescription=deny.replace(ProjectContext.spoken(name,focused,view,replay.clock),"agent")
        background=if(focused) selectedOutline else null
        progressBars[slot]?.let { TaskProgressView.update(it,p.fraction) }
      } else {
        val value=deny.replace(s.name,"agent")+"\n"+status+
          (if(com.overhand.app.mr.MrStations.notAnchored) "\n○ Not anchored · saved position" else "")
        if(text.toString()!=value) text=value
        contentDescription=text
      }
      setTextColor(if(stale) Color.LTGRAY else if(s.needsYou) Color.rgb(255,181,71) else Color.rgb(103,212,181))
    } }
  }
  private fun reconcile(rows: List<StationState>) {
    if(origin==null) return
    entries.keys.filter { key -> rows.none { it.id==key } }.toList().forEach { key -> entries.remove(key)?.entity?.destroy() }
    // Slots follow current sorted rows; saved places follow project IDs, never list indices.
    rows.take(ids.size).forEachIndexed { slot,s ->
      val old=entries[s.id]
      if(old!=null && old.slot!=slot) { old.entity.destroy();entries.remove(s.id) }
      if(!entries.containsKey(s.id)) {
        val a=StationRules.arc(slot,rows.size)
        val default=stage()?.let { com.overhand.app.features.StageWindows.project(it,slot,rows.size) } ?: (origin!!*Pose(Vector3(a[0],a[1],a[2]),Quaternion.fromEuler(0f,a[3],0f)))
        val restored=decode(prefs.getString((if(stage()!=null) "stage/" else "")+"station/${s.id}",null))
        val pose=restored ?: default
        val e=Entity.create(listOf(Panel(ids[slot]))).also { it.windowPose(pose,"Stations_create") }
        entries[s.id]=Entry(slot,e)
        Log.i("Overhand","station ${s.id} ${if(restored==null) "created" else "restored"} ${encode(pose)}")
      }
    }
    rows.forEach { s -> entries[s.id]?.let { com.overhand.app.audio.SpatialEarcons.of(activity).station(s.id,it.entity,s.needsYou) } }
    if(selected !in entries) choose(StationRules.defaultStation(entries.keys,recorded()?.defaultProject,hostFirst),wearer=false)
  }
  fun frame(viewer: Pose,now: Long) {
    if(origin==null) {
      // Level yaw from the view direction (same as the windows); robust to a rolled head at launch.
      val flat=com.overhand.app.features.WindowPose.upright(viewer)
      origin=Pose(Vector3(flat.t.x,0f,flat.t.z),flat.q)
      ui.post { val rows=states;commands.add { reconcile(rows) } }
    }
    var aimed="";var grabbedProject="";var best=cos(Math.toRadians(9.0)).toFloat()
    entries.forEach { (id,e) ->
      com.overhand.app.mr.MrStations.place(e.entity,anchorKey(id),e.slot,entries.size,viewer)
      val p=e.entity.getComponent<Transform>().transform
      val normal=p.forward();val toEye=viewer.t-p.t
      // #1341: Visible has one owner, WindowsFeature's lifetime loop (ControlDock.panelVisible); writing it here too
      // re-showed every label each frame for lifetime() to hide again (his nov17a: 9 show/hide cycles a second).
      val grabbed=e.entity.tryGetComponent<IsdkGrabbable>()?.grabState==IsdkGrabState.Grabbed
      val encoded=encode(p)
      if(!grabbed && (encoded!=e.saved || e.held)) {
        prefs.edit().putString((if(stage()!=null) "stage/" else "")+"station/$id",encoded).apply();e.saved=encoded
        com.overhand.app.mr.MrStations.persist(anchorKey(id),p)
        Log.i("Overhand","station saved $id $encoded")
      }
      e.held=grabbed
      val dot=(p.t-viewer.t).normalize().dot(viewer.forward())
      if(dot>best) { aimed=id;best=dot }
      if(grabbed) grabbedProject=id
    }
    if(grabbedProject.isNotEmpty()) aimed=grabbedProject
    if(aimed!=candidate) { candidate=aimed;since=now }
    if(aimed.isNotEmpty() && aimed!=selected && (entries[aimed]?.held==true || now-since>650)) choose(aimed)
    entries[selected]?.let { e ->
      val p=e.entity.getComponent<Transform>().transform
      val value=encode(p)
      if(value!=lastFocus) { lastFocus=value;focus(selected,p) }
    }
  }
  /** Also #1002 "open project X" (ECS thread). */
  fun choose(id: String,wearer: Boolean=true) {
    if(id !in entries) return
    if(wearer && recorded()==null) prefs.edit().putString(StationRules.LIVE_CHOICE,id).apply() // #1122: Live opens here next time
    com.overhand.app.audio.SpatialEarcons.of(activity).choose(entries.getValue(id).entity)
    selected=id;lastFocus=""
    Log.i("Overhand","station focused $id")
  }
  /** t765 centerAll: the arc in front of [viewer] (level), saved as the new places; the window cluster is not re-origined. */
  fun recenter(viewer: Pose) {
    val flat=com.overhand.app.features.WindowPose.upright(viewer)
    origin=Pose(Vector3(flat.t.x,0f,flat.t.z),flat.q)
    entries.values.forEach { e ->
      val a=StationRules.arc(e.slot,entries.size)
      e.entity.windowPose(stage()?.let { com.overhand.app.features.StageWindows.project(it,e.slot,entries.size) } ?: (origin!!*Pose(Vector3(a[0],a[1],a[2]),Quaternion.fromEuler(0f,a[3],0f))),"Stations_recenter")
    }
    entries[selected]?.let { lastFocus=encode(it.entity.getComponent<Transform>().transform) }
  }
  /** ECS thread: current station poses (debug snapshot). */
  fun poses(): List<Pair<String,Pose>> = entries.map { (id,e) -> id to e.entity.getComponent<Transform>().transform }
  fun debugMove() { entries[selected]?.entity?.let { e ->
    val p=e.getComponent<Transform>().transform
    e.windowPose(Pose(p.t+Vector3(0.12f,0.05f,0f),p.q),"Stations_debugMove")
  } }
  fun snapshot() {
    if(!com.overhand.app.BuildConfig.DEBUG) return
    val data=JSONObject().put("recorded",recorded()!=null).put("date",if(recorded()!=null) "Nov 3, 2026" else JSONObject.NULL)
      .put("selected",selected)
      .put("stations",JSONArray(states.map { s -> JSONObject().put("id",s.id).put("name",s.name).put("decisions",s.decisions)
        .put("label",if(recorded()!=null) ProjectContext.label(labels,s.id) else s.name)
        .put("needs_you",s.needsYou).put("text",roots[states.indexOf(s)]?.text?.toString())
        .put("task_replay",recorded()?.taskReplay?.snapshot(s.id))
        .put("text_height",roots[states.indexOf(s)]?.layout?.height)
        .put("available_height",roots[states.indexOf(s)]?.let { it.height-it.paddingTop-it.paddingBottom }) }))
    java.io.File(activity.filesDir,"stations_state.json").writeText(data.toString(2))
    states.forEachIndexed { slot,s -> roots[slot]?.let { view ->
      if(view.width>0 && view.height>0) {
        val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        java.io.File(activity.filesDir,"station_${s.id}.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
      }
    } }
  }
  fun close() { closed=true;ui.removeCallbacksAndMessages(null);io.shutdownNow() }
  private fun anchorKey(id: String)=(if(recorded()!=null) "recorded/" else "")+"station/$id"
  private fun encode(p: Pose)=JSONArray(listOf(p.t.x,p.t.y,p.t.z,p.q.w,p.q.x,p.q.y,p.q.z)).toString()
  private fun decode(value: String?): Pose? = StationRules.readPlace(value)?.let { v ->
    Pose(Vector3(v[0],v[1],v[2]),Quaternion(v[3],v[4],v[5],v[6]))
  }
  companion object {
    /** Station text: the caption step (1.05 deg) at the arc's distance, from the degree tokens (39 sp). */
    val GLANCE_SP=kotlin.math.ceil(com.overhand.app.design.FlowstateTokens.STATION.dpForDegrees(com.overhand.app.design.FlowstateTokens.TYPE_CAPTION_DEG))
  }
}
