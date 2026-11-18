package com.overhand.app.graph.site

import com.overhand.app.graph.Component
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * #1029 the build site as the default view: every project in the manifest, built live.
 *
 * One event path for both modes. Recorded rows (assets/judge/real_day.jsonl, the demo days) and live host events
 * (docs/host/PROTOCOL.md section 5) become [SiteEvent]s; [SiteBoard] folds them into per-project plots; [SiteFeed]
 * batches them so the scene changes at most once a second; [SiteSelection] is the one (project, part) the site and the
 * graph share. Pure: no Android types, so all of it is tested on the JVM.
 */
enum class SiteKind { PROJECT, PLAN, QUEUED, RUNNING, PROGRESS, DONE, FAILED, AGENT_GONE, NOTE }

data class SiteEvent(val tMs: Long,val project: String,val kind: SiteKind,val task: String="",val part: String?=null,
  val agent: String="",val text: String="",val name: String="",val plan: List<Component>?=null)

object SiteEvents {
  private val taskRe=Regex("^#(\\d+) (\\w+)")
  private val progRe=Regex("^#(\\d+)\\s+(\\d+)%\\s*(.*)$")
  private val parkRe=Regex("^#(\\d+)\\s+(.*)$")

  /** One recorded row, or null. [part] maps (project, task) to its plan part when the row does not name one. */
  fun recorded(r: JSONObject,part: (String,Int) -> String?): SiteEvent? {
    val p=r.optString("project");if(p.isEmpty()) return null
    val t=r.optLong("t_ms");val title=r.optString("title");val d=r.optString("detail");val kind=r.optString("kind")
    val agent=r.optString("agent_id");val name=r.optString("project_name")
    val worker=r.optString("agent_name")=="Worker"
    if(kind in setOf("task_assigned","done","error")) taskRe.find(title)?.let { m ->
      val id=m.groupValues[1].toInt();val word=m.groupValues[2]
      val comp=r.optString("component").ifBlank { null } ?: part(p,id)
      val text=title.substringAfter(": ","").ifBlank { title }
      return when {
        kind=="done" && word=="success" -> SiteEvent(t,p,SiteKind.DONE,"$id",comp,agent,text,name)
        kind=="done" || kind=="error" || word=="failed" -> SiteEvent(t,p,SiteKind.FAILED,"$id",comp,agent,text,name)
        // The rebuilt demo days carry no worker progress rows: their assignment is the start of the work.
        r.has("component") -> SiteEvent(t,p,SiteKind.RUNNING,"$id",comp,agent,text,name)
        else -> SiteEvent(t,p,SiteKind.QUEUED,"$id",comp,agent,text,name)
      }
    }
    if(worker && title=="progress") progRe.find(d)?.let { m -> val id=m.groupValues[1].toInt()
      return SiteEvent(t,p,SiteKind.PROGRESS,"$id",part(p,id),agent,m.groupValues[3],name) }
    if(worker && title=="park") parkRe.find(d)?.let { m -> val id=m.groupValues[1].toInt()
      return SiteEvent(t,p,SiteKind.PROGRESS,"$id",part(p,id),agent,m.groupValues[2],name) }
    if(worker && (title=="spawn" || title=="handoff")) return SiteEvent(t,p,SiteKind.AGENT_GONE,agent=agent,text=d,name=name)
    if(kind=="metric" || (kind=="message" && title=="result")) return d.takeIf { it.isNotBlank() }?.let { SiteEvent(t,p,SiteKind.NOTE,text=it,name=name) }
    return null
  }

  /** One live host event (PROTOCOL.md section 5), or null. [tMs] is when it arrived. */
  fun host(e: JSONObject,tMs: Long): SiteEvent? {
    val p=e.optString("project_id");if(p.isEmpty()) return null
    val task=e.optString("task_id");val title=e.optString("title")
    return when(e.optString("kind")) {
      "project_created" -> SiteEvent(tMs,p,SiteKind.PROJECT,name=e.optString("name"))
      "plan" -> e.optJSONArray("parts")?.let { a -> SiteEvent(tMs,p,SiteKind.PLAN,task,
        plan=(0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotBlank() } }.distinct().map { Component(it,emptyList()) }) }
      "task_state" -> when(e.optString("state")) {
        "queued" -> SiteEvent(tMs,p,SiteKind.QUEUED,task,text=title)
        "running","needs_answer" -> SiteEvent(tMs,p,SiteKind.RUNNING,task,agent="host:$p",text=title)
        "done" -> SiteEvent(tMs,p,SiteKind.DONE,task,text=title)
        "failed","cancelled","canceled" -> SiteEvent(tMs,p,SiteKind.FAILED,task,text=title)
        else -> null }
      "started" -> SiteEvent(tMs,p,SiteKind.RUNNING,task,agent="host:$p",text=title)
      "file_written","command_run" -> SiteEvent(tMs,p,SiteKind.PROGRESS,task,e.optString("part_id").ifBlank { null },"host:$p",title)
      "finished" -> SiteEvent(tMs,p,if(e.optBoolean("ok")) SiteKind.DONE else SiteKind.FAILED,task,text=title)
      "message" -> SiteEvent(tMs,p,SiteKind.NOTE,text=title.ifBlank { e.optString("detail") })
      else -> null
    }
  }
}

/** What each plot's sign says: name, a progress figure, agents at work (three words each at most), latest (six words at most). */
data class SiteSign(val project: String,val name: String,val figure: String,val crew: List<String>,val latest: String,val lit: Boolean)

enum class SiteMode { RECORDED, LIVE, NO_COMPUTER, DAY_ENDED }

/**
 * Folds [SiteEvent]s into the site, incrementally. [strict]: only the projects it was seeded with get plots (Recorded:
 * the manifest); otherwise any project an event names gets one (Live). No fixed project count anywhere.
 */
class SiteBoard(private val strict: Boolean=false) {
  class Task(val id: String,var part: String?,var status: String,var title: String)
  class Crew(val agent: String,val task: String,var lastT: Long,var text: String) {
    /** #1035: its last three steps, oldest first (what "talk" answers from in Recorded). */
    val steps=ArrayDeque<String>()
    fun step(s: String) { val w=BuildSite.words(s,8);if(w.isBlank() || steps.lastOrNull()==w) return;steps.addLast(w);while(steps.size>3) steps.removeFirst() }
  }
  class Proj(val id: String,var name: String) {
    var plan: List<Component>?=null
    val tasks=LinkedHashMap<String,Task>()
    val crews=LinkedHashMap<String,Crew>()
    var latest=""
    var latestT=0L
  }
  val projects=LinkedHashMap<String,Proj>()
  private val agentNames=HashMap<String,String>()
  private var layoutKey=""
  private var cached: SiteLayout?=null
  var lastEventT=0L;private set
  /** Bumped by every change the drawing can see. */
  var version=0;private set

  fun seed(id: String,name: String,plan: List<Component>?) { val p=projects.getOrPut(id) { Proj(id,name) };p.name=name;if(!plan.isNullOrEmpty()) p.plan=plan }

  /** Recorded: every task of the day is known up front (pending), so heights are shares of the whole day, as in #1003. */
  fun prime(events: List<SiteEvent>) {
    for(e in events) { val p=proj(e) ?: continue
      if(e.task.isNotEmpty() && e.kind in setOf(SiteKind.QUEUED,SiteKind.RUNNING,SiteKind.DONE,SiteKind.FAILED))
        p.tasks.getOrPut(e.task) { Task(e.task,partOf(p,e),"pending",e.text) } }
  }

  private fun proj(e: SiteEvent): Proj? = projects[e.project] ?: if(strict) null else Proj(e.project,e.name.ifBlank { "New project" }).also { projects[e.project]=it }
  private fun partOf(p: Proj,e: SiteEvent): String? = if(p.plan.isNullOrEmpty()) "#${e.task}" else e.part?.takeIf { id -> p.plan!!.any { it.id==id } }

  /** Applies one event; true when the drawing changed. */
  fun apply(e: SiteEvent): Boolean {
    val p=proj(e) ?: return false
    lastEventT=maxOf(lastEventT,e.tMs)
    if(e.kind==SiteKind.AGENT_GONE) {
      SPAWN.find(e.text)?.let { agentNames.putIfAbsent(e.agent,it.groupValues[1].uppercase()) }
      val gone=p.crews.values.filter { it.agent==e.agent }.map { it.task }
      projects.values.forEach { q -> q.crews.values.removeIf { it.agent==e.agent } }
      return bump(gone.isNotEmpty())
    }
    if(e.kind==SiteKind.PROJECT) { val changed=e.name.isNotBlank() && e.name!=p.name;if(e.name.isNotBlank()) p.name=e.name;return bump(changed) }
    if(e.kind==SiteKind.PLAN) { p.plan=e.plan;p.tasks.values.forEach { it.part=null };return bump(true) }
    if(e.kind==SiteKind.NOTE) return note(p,e.text,e.tMs)
    val t=p.tasks.getOrPut(e.task) { Task(e.task,partOf(p,e),"pending",e.text) }
    if(t.part==null) t.part=partOf(p,e)
    if(e.text.isNotBlank() && e.kind!=SiteKind.PROGRESS) t.title=e.text
    when(e.kind) {
      SiteKind.QUEUED -> { if(t.status=="pending") t.status="queued";note(p,"Queued: ${t.title}",e.tMs) }
      SiteKind.RUNNING,SiteKind.PROGRESS -> {
        t.status="running"
        val c=p.crews.getOrPut(e.task) { Crew(e.agent,e.task,e.tMs,e.text) };c.lastT=e.tMs;if(e.text.isNotBlank()) c.text=e.text
        if(e.kind==SiteKind.PROGRESS) c.step(e.text)
        note(p,if(e.kind==SiteKind.PROGRESS && e.text.isNotBlank()) e.text else "Started: ${t.title}",e.tMs)
      }
      SiteKind.DONE -> { t.status="done";p.crews.remove(e.task);note(p,"Finished: ${t.title}",e.tMs) }
      SiteKind.FAILED -> { t.status="failed";p.crews.remove(e.task);note(p,"Stopped: ${t.title}",e.tMs) }
      else -> {}
    }
    return bump(true)
  }
  private fun note(p: Proj,text: String,t: Long): Boolean { val w=BuildSite.words(text,6);if(w.isBlank()) return false
    val changed=w!=p.latest;p.latest=w;p.latestT=t;return bump(changed) }
  private fun bump(changed: Boolean): Boolean { if(changed) version++;return changed }

  /** One plot per project: plan parts where a plan exists, else one block per task. Re-laid only when the structure changes. */
  fun layout(): SiteLayout {
    val key=projects.values.joinToString("|") { p -> p.id+":"+(p.plan?.joinToString(",") { it.id } ?: p.tasks.keys.joinToString(",")) }
    cached?.takeIf { key==layoutKey }?.let { return it }
    layoutKey=key
    return SiteGeometry.layout(projects.mapValues { it.value.name },projects.mapValues { it.value.plan.orEmpty() },
      projects.filterValues { it.plan.isNullOrEmpty() }.mapValues { (_,p) -> p.tasks.keys.sortedWith(compareBy({ it.toIntOrNull() ?: Int.MAX_VALUE },{ it })).map { "#$it" } })
      .also { cached=it }
  }

  /** Agents still at work at [nowMs] (a crew silent for [SILENT_MS] has gone home). */
  private fun crews(p: Proj,nowMs: Long)=p.crews.values.filter { nowMs-it.lastT<=SILENT_MS && p.tasks[it.task]?.status=="running" }

  fun state(nowMs: Long,clock: String=""): SiteState {
    val l=layout();val parts=HashMap<Pair<String,String>,PartState>();val agents=ArrayList<SiteAgent>()
    for(plot in l.plots) { val p=projects[plot.project] ?: continue
      val byPart=p.tasks.values.groupBy { it.part }
      val working=crews(p,nowMs)
      plot.parts.forEach { part -> val mine=byPart[part.id].orEmpty()
        val st=when { mine.any { it.status=="running" } -> "building";mine.any { it.status=="queued" } -> "building"
          mine.isNotEmpty() && mine.all { it.status=="done" } -> "built";mine.any { it.status=="failed" } -> "blocked";else -> "planned" }
        parts[plot.project to part.id]=PartState(mine.count { it.status=="done" },mine.size,st) }
      working.take(MAX_CRANES).forEach { c -> val t=p.tasks.getValue(c.task)
        agents.add(SiteAgent("${p.id}/${c.task}",name(c.agent),p.id,c.task.toIntOrNull() ?: 0,t.part,BuildSite.verb(t.title),BuildSite.words(c.text,12),
          c.steps.toList(),0,t.title,c.task)) }
    }
    return SiteState(nowMs,clock,BuildSite.withCrew(parts,agents),agents)
  }
  private fun name(agent: String)=agentNames[agent] ?: if(agent.startsWith("host:")) "Agent" else "Worker"

  fun signs(nowMs: Long): List<SiteSign> = layout().plots.mapNotNull { plot -> val p=projects[plot.project] ?: return@mapNotNull null
    val working=crews(p,nowMs)
    val done=p.tasks.values.count { it.status=="done" };val total=p.tasks.size
    val crew=working.take(MAX_CRANES).map { c -> label(name(c.agent),BuildSite.verb(p.tasks[c.task]?.title.orEmpty())) }
    SiteSign(p.id,p.name,if(total==0) "No tasks yet" else "$done of $total done",crew,p.latest,working.isNotEmpty()) }

  /** Projects with an agent at work now. */
  fun litCount(nowMs: Long)=projects.values.count { crews(it,nowMs).isNotEmpty() }
  fun doneCount()=projects.values.sumOf { p -> p.tasks.values.count { it.status=="done" } }
  fun taskCount()=projects.values.sumOf { it.tasks.size }

  companion object {
    const val SILENT_MS=90L*60_000
    const val MAX_CRANES=3
    private val SPAWN=Regex(":(w\\d+|vs\\d*) started")
    /** An agent's label: its name and the task's verb, three words at most. */
    fun label(name: String,verb: String)=("$name $verb").split(' ').filter { it.isNotBlank() }.take(3).joinToString(" ")
  }
}

/** The site's one-line header and the idle states' looks (designed before the geometry: #1029 method 4). */
object SiteText {
  fun header(mode: SiteMode,board: SiteBoard,nowMs: Long): String {
    val n=board.projects.size;val lit=board.litCount(nowMs)
    return when(mode) {
      SiteMode.NO_COMPUTER -> "No computer connected: pair one in Settings"
      SiteMode.DAY_ENDED -> "The day has ended: ${board.doneCount()} of ${board.taskCount()} tasks done"
      else -> if(lit==0) "Nothing building right now: $n ${plural(n,"project")} waiting" else "Building now: $lit of $n ${plural(n,"project")}"
    }
  }
  /** Under the header: where the site comes from, and when. */
  fun subline(mode: SiteMode,clock: String)=when(mode) {
    SiteMode.RECORDED -> "Recorded day · $clock · replay"
    SiteMode.LIVE -> "Live from your computer${if(clock.isBlank()) "" else " · $clock"}"
    SiteMode.NO_COMPUTER -> "Showing the last known site"
    SiteMode.DAY_ENDED -> "Replay starts again shortly"
  }
  const val IDLE_PLOT="Idle: no agent at work"
  fun plural(n: Int,w: String)=if(n==1) w else w+"s"
  /** Plots are dimmed when idle, and all of them when no computer is connected or the day has ended. */
  fun lit(mode: SiteMode,sign: SiteSign)=sign.lit && (mode==SiteMode.RECORDED || mode==SiteMode.LIVE)
}

/**
 * At most one visible change per [periodMs]: events wait here and are applied together on the 1 Hz tick, so the scene
 * is never edited per frame and a burst of events costs one rebuild. [offer] is safe from any thread.
 */
class SiteFeed(private val board: SiteBoard,val periodMs: Long=1000L) {
  private val pending=ArrayList<Pair<SiteEvent,Long>>()
  private var last=Long.MIN_VALUE/2
  @Synchronized fun offer(e: SiteEvent,arrivedMs: Long) { pending.add(e to arrivedMs) }
  @Synchronized fun pendingCount()=pending.size
  /** The 1 Hz tick: applies what waited; returns the arrival times of the events that changed the drawing, or null. */
  @Synchronized fun flush(nowMs: Long): LongArray? {
    if(pending.isEmpty() || nowMs-last<periodMs) return null
    last=nowMs
    val changed=pending.filter { (e,_) -> board.apply(e) }.map { it.second }.toLongArray()
    pending.clear()
    return changed.takeIf { it.isNotEmpty() }
  }
}

/** The one shared selection (project, part): a pinch on the site and "Show on site" in the graph both go through here. */
class SiteSelection {
  data class Pick(val project: String,val part: String)
  @Volatile var current: Pick?=null;private set
  val listeners=CopyOnWriteArrayList<(Pick,String) -> Unit>()
  /** [from] is "site" or "graph": each side reacts to the other's picks. */
  fun select(p: Pick,from: String) { current=p;listeners.forEach { runCatching { it(p,from) } } }
}

/**
 * Where the default view puts things at put-on, relative to the level head (x right, y up, z ahead, metres).
 * The site is a table top in front at table height; the main window floats above it, straight ahead. Pure, so the
 * put-on layout lint ([SitePutOn.lint]) runs on the JVM.
 */
object SitePutOn {
  const val DISTANCE=2.0
  const val DROP=0.65
  const val SCALE=0.7
  /** Highest thing on the table (header text top), in table metres. */
  const val TOP=0.78
  /** The main window over the site: bearing, elevation (degrees), distance (m); the main reading panel is 0.84 x 0.70 m. */
  const val MAIN_BEARING=0.0
  const val MAIN_ELEVATION=13.0
  const val MAIN_DISTANCE=1.5
  const val MAIN_W=0.84
  const val MAIN_H=0.7

  data class Box(val x0: Double,val y0: Double,val z0: Double,val x1: Double,val y1: Double,val z1: Double)
  /** The site's bounding box, head frame (labels included). */
  fun siteBox(): Box { val h=SiteGeometry.TABLE/2*SCALE+0.02
    return Box(-h,-DROP-0.03*SCALE,DISTANCE-h,h,-DROP+TOP*SCALE,DISTANCE+h) }
  fun mainCentre(): DoubleArray { val b=Math.toRadians(MAIN_BEARING);val e=Math.toRadians(MAIN_ELEVATION)
    return doubleArrayOf(MAIN_DISTANCE*Math.cos(e)*Math.sin(b),MAIN_DISTANCE*Math.sin(e),MAIN_DISTANCE*Math.cos(e)*Math.cos(b)) }

  data class Lint(val nearestM: Double,val overlapDeg2: Double,val siteDeg: DoubleArray,val mainDeg: DoubleArray) {
    val faults get()=(if(nearestM<1.0) 1 else 0)+(if(overlapDeg2>0.0) 1 else 0)
  }
  /** Angular rectangle (bearing min/max, elevation min/max) of a set of head-frame points. */
  private fun extent(ps: List<DoubleArray>): DoubleArray {
    val b=ps.map { Math.toDegrees(Math.atan2(it[0],it[2])) };val e=ps.map { Math.toDegrees(Math.atan2(it[1],Math.hypot(it[0],it[2]))) }
    return doubleArrayOf(b.min(),b.max(),e.min(),e.max())
  }
  /** Put-on layout lint: nothing nearer than 1.0 m, and the site and the main window do not overlap as the wearer sees them. */
  fun lint(): Lint {
    val s=siteBox()
    val corners=listOf(s.x0,s.x1).flatMap { x -> listOf(s.y0,s.y1).flatMap { y -> listOf(s.z0,s.z1).map { z -> doubleArrayOf(x,y,z) } } }
    // Nearest point of the box to the eye (the box is all ahead of it).
    val nx=0.0.coerceIn(s.x0,s.x1);val ny=0.0.coerceIn(s.y0,s.y1);val nz=0.0.coerceIn(s.z0,s.z1)
    val c=mainCentre();val yaw=Math.atan2(c[0],c[2])
    val rx=Math.cos(yaw)*MAIN_W/2;val rz=-Math.sin(yaw)*MAIN_W/2
    val main=listOf(-1,1).flatMap { i -> listOf(-1,1).map { j -> doubleArrayOf(c[0]+i*rx,c[1]+j*MAIN_H/2,c[2]+i*rz) } }
    val nearest=minOf(Math.sqrt(nx*nx+ny*ny+nz*nz),main.minOf { Math.sqrt(it[0]*it[0]+it[1]*it[1]+it[2]*it[2]) },MAIN_DISTANCE)
    val a=extent(corners);val m=extent(main)
    val ob=maxOf(0.0,minOf(a[1],m[1])-maxOf(a[0],m[0]));val oe=maxOf(0.0,minOf(a[3],m[3])-maxOf(a[2],m[2]))
    return Lint(nearest,ob*oe,a,m)
  }
}

/** Ray against each block's column (table metres): the part a point-and-pinch lands on. Pure, so the pick is tested on the JVM. */
object SiteBlockHit {
  fun block(layout: SiteLayout,o: DoubleArray,d: DoubleArray): SiteSelection.Pick? =
    layout.plots.flatMap { it.parts }.mapNotNull { p ->
      val f=SiteMesh.FOOT/2+0.01
      val lo=doubleArrayOf(p.x-f,0.0,p.z-f);val hi=doubleArrayOf(p.x+f,SiteMesh.BLOCK_H+0.01,p.z+f)
      var t0=0.0;var t1=1e9
      for(k in 0..2) {
        if(kotlin.math.abs(d[k])<1e-12) { if(o[k]<lo[k] || o[k]>hi[k]) return@mapNotNull null;continue }
        var a=(lo[k]-o[k])/d[k];var b=(hi[k]-o[k])/d[k];if(a>b) { val t=a;a=b;b=t }
        t0=maxOf(t0,a);t1=minOf(t1,b);if(t0>t1) return@mapNotNull null
      }
      t0 to SiteSelection.Pick(p.project,p.id)
    }.minByOrNull { it.first }?.second
}

/** Sign text as JSON (stills, logs and the cold-read pack read the same words the scene shows). */
fun List<SiteSign>.toJson()=JSONArray(map { s -> JSONObject().put("project",s.project).put("name",s.name).put("figure",s.figure)
  .put("crew",JSONArray(s.crew)).put("latest",s.latest).put("lit",s.lit) })

/**
 * The Recorded site: one plot per project in the loaded recording's manifest (its architecture.json), every row of its
 * day as a [SiteEvent], paced by events over [PLAY_MS] so quiet hours pass quickly while the clock stays true.
 */
class SiteRecorded(architecture: JSONObject,rows: List<JSONObject>,name: (String) -> String,
  membership: Map<Pair<String,Int>,Pair<String,String>> = emptyMap()) {
  val manifest: List<Pair<String,List<Component>>>
  val events: List<SiteEvent>
  val names: Map<String,String>
  init {
    val ps=architecture.getJSONArray("projects")
    manifest=(0 until ps.length()).map { ps.getJSONObject(it) }.map { p -> val cs=p.optJSONArray("components") ?: JSONArray()
      p.getString("id") to (0 until cs.length()).map { i -> val c=cs.getJSONObject(i);val a=c.optJSONArray("after") ?: JSONArray()
        Component(c.getString("id"),(0 until a.length()).map { a.getString(it) }) } }
    val names=manifest.associate { (id,_) -> id to name(id) }
    val planned=manifest.filter { it.second.isNotEmpty() }.map { it.first }.toSet()
    fun part(p: String,task: Int): String? = if(p !in planned) "#$task" else membership[p to task]?.first ?: SiteMembership.part(p,task,true)
    events=rows.mapNotNull { SiteEvents.recorded(it,::part) }.filter { it.project in names }.sortedBy { it.tMs }
    this.names=names
  }
  val start get()=events.firstOrNull()?.tMs ?: 0L
  val end get()=events.lastOrNull()?.tMs ?: 0L
  /** A fresh board: the manifest's plots, every task of the day known (pending), nothing applied yet. */
  fun board()=SiteBoard(strict=true).also { b -> manifest.forEach { (id,plan) -> b.seed(id,names.getValue(id),plan) };b.prime(events) }
  /** Day time after [wallMs] of the play (event-paced). */
  fun dayAt(wallMs: Long): Long { if(events.isEmpty()) return 0L;val f=(wallMs.toDouble()/PLAY_MS).coerceIn(0.0,1.0)
    return events[Math.round(f*(events.size-1)).toInt()].tMs }
  companion object {
    const val PLAY_MS=75_000L
    /** The ended day stays on show this long, then the replay starts again. */
    const val ENDED_MS=12_000L
  }
}
