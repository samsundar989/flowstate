package com.overhand.app.graph.site

import com.overhand.app.graph.Component
import com.overhand.app.graph.GraphLayout
import com.overhand.app.graph.GraphMotion
import com.overhand.app.graph.TaskReplay
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * #1003 build site: a second renderer of the same plan model (GraphLayout over each project's components) and the same
 * task states (TaskReplay over the recorded day, or a Live plans snapshot). Pure: no Android types.
 *
 * Ground is a 2 m x 2 m table top in metres, origin at its centre, y up, x right, z towards the wearer.
 * One plot per project; one block per plan part; a part's height is the share of its tasks done.
 */
object SiteMembership {
  /** (project, task id) -> part, assigned Nov 13 from each task title (source "title"), as GraphMotion did for the headset project. */
  val EXTRA: Map<Pair<String,Int>,String> = buildMap {
    val a="sc7e2275fab6a13cd";val g="s4a3f3b09ec1c0d92"
    put(a to 19,"serving");put(a to 20,"harness");put(a to 21,"submission");put(a to 25,"local-eval")
    put(a to 36,"agent-eval");put(a to 37,"local-eval");put(a to 38,"scaffold-experiments");put(a to 39,"local-eval")
    put(g to 26,"instrument");put(g to 27,"data");put(g to 32,"data");put(g to 66,"data")
    put(g to 30,"model");put(g to 33,"model");put(g to 65,"model");put(g to 71,"model");put(g to 34,"model")
    put(g to 35,"features-lidar");put(g to 69,"features-lidar");put(g to 60,"features-geophys")
    put(g to 70,"features-structure");put(g to 75,"features-structure");put(g to 72,"feature-stack")
  }
  fun part(project: String,task: Int,hasPlan: Boolean): String? =
    if(!hasPlan) "#$task" else GraphMotion.MEMBERSHIP[project to task]?.first ?: EXTRA[project to task]
}

data class SitePart(val project: String,val id: String,val x: Double,val z: Double)
data class SitePlot(val project: String,val name: String,val x0: Double,val z0: Double,val w: Double,val d: Double,
  val parts: List<SitePart>,val paths: List<Pair<String,String>>,val hasPlan: Boolean)
data class SiteLayout(val plots: List<SitePlot>) {
  fun part(project: String,id: String)=plots.firstOrNull { it.project==project }?.parts?.firstOrNull { it.id==id }
}
data class PartState(val done: Int,val total: Int,val state: String,val crew: Int=0) {
  val share get()=if(total==0) 0.0 else done.toDouble()/total
  val building get()=state=="building"
  val finished get()=total>0 && done==total
  /** Scaffolding shows real work: a worker at the part, or a part already rising with tasks still open. */
  val scaffold get()=building && total>0 && (crew>0 || done>0)
}
data class SiteAgent(val id: String,val name: String,val project: String,val task: Int,val part: String?,val verb: String,
  val now: String,val steps: List<String>,val pct: Int,
  /** #1035: the task's own title, and the host's task id (Live: where "talk" sends the sentence). */
  val title: String="",val taskId: String="") {
  val displayName get()=if(id.startsWith("layer:")) name else "$name #${taskId.ifBlank { task.toString() }}"
  val label get()="$displayName · $verb"
}
data class SiteState(val tMs: Long,val clock: String,val parts: Map<Pair<String,String>,PartState>,val agents: List<SiteAgent>) {
  fun part(project: String,id: String)=parts[project to id] ?: PartState(0,0,"planned")
  /** Comparable form: everything the drawing depends on except the clock text. */
  fun shape()=parts.toSortedMap(compareBy({ it.first },{ it.second })).map { (k,v) -> "${k.first}/${k.second}=${v.done}/${v.total}:${v.state}" } +
    agents.sortedBy { it.id }.map { "agent ${it.id} ${it.project}/${it.part} #${it.task}" }
}

object SiteGeometry {
  const val TABLE=2.0
  const val CELL=0.13
  const val MARGIN=0.07
  const val GAP=0.08
  /** Plots shelf-packed across the table, largest first; plan parts placed by GraphLayout's layers (x) and rows (z). */
  fun layout(names: Map<String,String>,plans: Map<String,List<Component>>,planless: Map<String,List<String>>): SiteLayout {
    data class Raw(val project: String,val cols: Int,val rows: Int,val parts: List<Triple<String,Double,Double>>,val paths: List<Pair<String,String>>,val plan: Boolean)
    val raws=names.keys.map { p ->
      val comps=plans[p].orEmpty()
      if(comps.isNotEmpty()) {
        val m=GraphLayout.build(comps)
        val layers=m.nodes.maxOf { it.layer }+1
        val rows=m.nodes.groupBy { it.layer }.values.maxOf { it.size }
        // GraphLayout spaces layers 0.54 apart and rows 0.36 apart: re-scale to one cell each.
        val minX=m.nodes.minOf { it.x };val maxY=m.nodes.maxOf { it.y }
        Raw(p,layers,rows,m.nodes.map { Triple(it.id,(it.x-minX)/0.54,(maxY-it.y)/0.36) },m.edges.map { it.from to it.to },true)
      } else {
        val ids=planless[p].orEmpty();val cols=maxOf(1,ceil(sqrt(ids.size.toDouble())).toInt())
        Raw(p,cols,maxOf(1,ceil(ids.size/cols.toDouble()).toInt()),ids.mapIndexed { i,id -> Triple(id,(i%cols).toDouble(),(i/cols).toDouble()) },emptyList(),false)
      }
    }.sortedWith(compareByDescending<Raw> { it.parts.size }.thenBy { it.project })
    val usable=TABLE-2*GAP
    val plots=mutableListOf<SitePlot>();var x=0.0;var z=0.0;var shelf=0.0
    for(r in raws) {
      val w=r.cols*CELL+2*MARGIN-CELL+0.09;val d=maxOf(r.rows,1)*CELL+2*MARGIN-CELL+0.09
      if(x>0 && x+w>usable) { x=0.0;z+=shelf+GAP;shelf=0.0 }
      val x0=-TABLE/2+GAP+x;val z0=-TABLE/2+GAP+z
      plots.add(SitePlot(r.project,names.getValue(r.project),x0,z0,w,d,
        r.parts.map { (id,cx,cz) -> SitePart(r.project,id,x0+MARGIN+0.045+cx*CELL,z0+MARGIN+0.045+cz*CELL) },r.paths,r.plan))
      x+=w+GAP;shelf=maxOf(shelf,d)
    }
    // Centre each shelf across the table, centre the shelves in depth, and mirror so the first (largest) shelf is
    // nearest the wearer: the busiest project stands in front.
    if(plots.isEmpty()) return SiteLayout(emptyList())
    val used=plots.maxOf { it.z0+it.d }-plots.minOf { it.z0 };val dz=(TABLE-2*GAP-used)/2
    val shelves=plots.groupBy { it.z0 }
    return SiteLayout(plots.map { p -> val row=shelves.getValue(p.z0)
      val dx=(TABLE-2*GAP-(row.maxOf { it.x0+it.w }-row.minOf { it.x0 }))/2
      val nz0=-(p.z0+dz+p.d)   // mirror in depth
      p.copy(x0=p.x0+dx,z0=nz0,parts=p.parts.map { it.copy(x=it.x+dx,z=nz0+(p.z0+p.d-it.z)) }) })
  }
}

/** The recorded day as a site: the same TaskReplay the task panel uses, plus the workers' own progress rows. */
class BuildSite(rows: List<JSONObject>,architecture: JSONObject,fixtureHash: String="real_day") {
  val replay=TaskReplay(rows,fixtureHash)
  val names: Map<String,String>
  val plans: Map<String,List<Component>>
  val layout: SiteLayout
  val start: Long
  val end: Long
  /** Every row's time, sorted: the play is paced by events, so quiet hours pass quickly while the clock stays true. */
  private val times: LongArray
  private data class Note(val t: Long,val row: Int,val agent: String,val project: String,val kind: String,val task: Int?,val pct: Int?,val text: String)
  private val notes: List<Note>
  private val agentNames=HashMap<String,String>()
  private val titles=HashMap<Pair<String,Int>,String>()
  init {
    val projects=architecture.getJSONArray("projects")
    val n=LinkedHashMap<String,String>();val pl=HashMap<String,List<Component>>()
    (0 until projects.length()).map { projects.getJSONObject(it) }.forEach { p -> val k=p.getString("id");n[k]=p.optString("name",k)
      val cs=p.optJSONArray("components") ?: JSONArray()
      pl[k]=(0 until cs.length()).map { i -> val c=cs.getJSONObject(i);val a=c.optJSONArray("after") ?: JSONArray()
        Component(c.getString("id"),(0 until a.length()).map { a.getString(it) }) } }
    names=n;plans=pl
    val taskRe=Regex("^#(\\d+) (\\w+)")
    val planless=HashMap<String,MutableSet<Int>>()
    rows.forEach { r -> taskRe.find(r.optString("title"))?.let { m -> val p=r.optString("project");val id=m.groupValues[1].toInt()
      if(r.optString("kind") in setOf("task_assigned","done","error") && p in names) {
        if(pl[p].isNullOrEmpty()) planless.getOrPut(p) { mutableSetOf() }.add(id)
        if(m.groupValues[2]=="queued") titles.putIfAbsent(p to id,r.optString("title").substringAfter(": ").trim())
      } } }
    layout=SiteGeometry.layout(names,plans,planless.mapValues { it.value.sorted().map { id -> "#$id" } })
    start=rows.minOfOrNull { it.getLong("t_ms") } ?: 0L
    end=rows.maxOfOrNull { it.getLong("t_ms") } ?: 0L
    times=rows.map { it.getLong("t_ms") }.sorted().toLongArray()
    val progRe=Regex("^#(\\d+)\\s+(\\d+)%\\s*(.*)$");val parkRe=Regex("^#(\\d+)\\s+(.*)$")
    val spawnRe=Regex(":(w\\d+|vs\\d*) started")
    notes=rows.mapIndexedNotNull { i,r ->
      if(r.optString("agent_name")!="Worker") return@mapIndexedNotNull null
      val a=r.optString("agent_id");val d=r.optString("detail");val title=r.optString("title")
      spawnRe.find(d)?.let { agentNames.putIfAbsent(a,it.groupValues[1].uppercase()) }
      when {
        title=="progress" -> progRe.find(d)?.let { m -> Note(r.getLong("t_ms"),i+1,a,r.optString("project"),"progress",m.groupValues[1].toInt(),m.groupValues[2].toInt(),m.groupValues[3]) }
        title=="park" -> parkRe.find(d)?.let { m -> Note(r.getLong("t_ms"),i+1,a,r.optString("project"),"park",m.groupValues[1].toInt(),null,m.groupValues[2]) }
        title=="handoff" || title=="spawn" -> Note(r.getLong("t_ms"),i+1,a,r.optString("project"),title,null,null,d)
        r.optString("kind") in setOf("done","error") -> taskRe.find(title)?.let { m -> Note(r.getLong("t_ms"),i+1,a,r.optString("project"),"end",m.groupValues[1].toInt(),null,title) }
        else -> null
      }
    }.sortedWith(compareBy<Note> { it.t }.thenBy { it.row })
  }

  fun hasPlan(project: String)=!plans[project].isNullOrEmpty()

  /** The site at a recorded day time: parts from TaskReplay's counts, agents from the workers' own rows. */
  fun at(tMs: Long): SiteState {
    val step=replay.stepAt(tMs)
    val parts=HashMap<Pair<String,String>,PartState>()
    for(plot in layout.plots) {
      val tasks=replay.afterSteps(plot.project,step).tasks
      val byPart=tasks.groupBy { SiteMembership.part(plot.project,it.id,plot.hasPlan) }
      plot.parts.forEach { part -> val mine=byPart[part.id].orEmpty()
        parts[plot.project to part.id]=PartState(mine.count { it.status=="success" },mine.size,
          GraphMotion.state(mine.map { it.status }.filter { it!="pending" })) }
    }
    // An agent stands at its task's part from its latest progress/park row until the task ends, it hands off, or it falls silent.
    val on=LinkedHashMap<String,Note>();val history=HashMap<String,MutableList<String>>();val last=HashMap<String,Long>()
    val ended=HashSet<Pair<String,Int>>()
    for(n in notes) { if(n.t>tMs) break
      last[n.agent]=n.t
      when(n.kind) {
        "progress","park" -> { on[n.agent]=n;ended.remove(n.project to n.task!!);history.getOrPut(n.agent) { mutableListOf() }.add(n.text) }
        "handoff","spawn" -> on.remove(n.agent)
        "end" -> { ended.add(n.project to n.task!!);on.entries.removeIf { it.value.project==n.project && it.value.task==n.task } }
      }
    }
    val agents=on.values.filter { tMs-(last[it.agent] ?: 0L)<=SILENT_MS && (it.project to it.task!!) !in ended && it.project in names }.map { n ->
      val steps=history[n.agent].orEmpty()
      SiteAgent(n.agent,agentNames[n.agent] ?: "Worker",n.project,n.task!!,SiteMembership.part(n.project,n.task,hasPlan(n.project)),
        verb(titles[n.project to n.task].orEmpty()),words(n.text,12),steps.dropLast(1).takeLast(3).reversed().map { words(it,8) },n.pct ?: 0,
        titles[n.project to n.task].orEmpty(),"${n.task}")
    }
    return SiteState(tMs,GraphMotion.clock(tMs),withCrew(parts,agents),agents)
  }
  /** Day time at a fraction of the play, 0..1 (stills at 0, .25, .5, .75 and 1): event-paced, empty ground at 0, the end of the day at 1. */
  fun timeAt(f: Double): Long { if(times.isEmpty()) return start;if(f<=0.0) return start;if(f>=1.0) return end
    return times[(f*(times.size-1)).roundToLong().toInt()] }
  fun atFraction(f: Double)=at(timeAt(f))
  /** Recorded play: empty ground to the end state in [PLAY_MS] of wall time. */
  fun dayAt(wallMs: Long)=timeAt(wallMs.coerceIn(0L,PLAY_MS).toDouble()/PLAY_MS)

  /** The end of the day in the Live host's shape (plans with components carrying their tasks; agents with their task). */
  fun liveSnapshot(): JSONObject {
    val s=at(end);val out=JSONObject()
    val plansOut=JSONObject()
    layout.plots.forEach { plot ->
      val tasks=replay.afterSteps(plot.project,replay.count).tasks
      val comps=JSONArray()
      plot.parts.forEach { part -> val ts=JSONArray()
        tasks.filter { SiteMembership.part(plot.project,it.id,plot.hasPlan)==part.id }.forEach { ts.put(JSONObject().put("id",it.id).put("status",liveWord(it.status))) }
        comps.put(JSONObject().put("id",part.id).put("tasks",ts)) }
      plansOut.put(plot.project,JSONObject().put("components",comps))
    }
    val agentsOut=JSONArray()
    s.agents.forEach { a -> agentsOut.put(JSONObject().put("id",a.id).put("name",a.name).put("project",a.project).put("task",a.task)
      .put("component",a.part ?: JSONObject.NULL).put("verb",a.verb).put("summary",a.now).put("steps",JSONArray(a.steps)).put("progress_pct",a.pct)) }
    return out.put("plans",plansOut).put("agents",agentsOut).put("t_ms",end)
  }

  companion object {
    const val PLAY_MS=40_000L
    /** A worker silent this long is no longer shown at its part (sessions end without a row). */
    const val SILENT_MS=90L*60_000
    private fun Double.roundToLong()=Math.round(this)
    fun withCrew(parts: Map<Pair<String,String>,PartState>,agents: List<SiteAgent>): Map<Pair<String,String>,PartState> {
      val crew=agents.filter { it.part!=null }.groupingBy { it.project to it.part!! }.eachCount()
      return parts.mapValues { (k,v) -> v.copy(crew=crew[k] ?: 0) } }
    /** Card text for a person: no redaction marks, job numbers or bracketed asides from the raw progress rows. */
    fun plain(s: String)=s.replace(Regex("\\[[^\\]]*]|\\([^)]*\\)"),"").replace(Regex("(?i)\\bworkerq job \\d+"),"a queued job")
      .replace(Regex("\\s+([,.;:])"),"$1")
    fun words(s: String,n: Int): String { val w=plain(s).replace(Regex("\\s+")," ").trim().split(' ').filter { it.isNotEmpty() }
      return if(w.size<=n) w.joinToString(" ") else w.take(n).joinToString(" ")+"…" }
    /** Live words for the recorded statuses (Live hosts report done/claimed/failed...). */
    fun liveWord(s: String)=when(s) { "success" -> "done";"active" -> "claimed";else -> s }
    fun recordedWord(s: String)=when(s) { "done" -> "success";"claimed","ready","queued","parked" -> "active";else -> s }
    private val VERBS=listOf("evaluat" to "Evaluating","score" to "Scoring","bench" to "Benchmarking","baseline" to "Benchmarking",
      "install" to "Installing","download" to "Fetching data","data card" to "Checking data","snapshot" to "Collecting",
      "research" to "Researching","serve" to "Serving","sideload" to "Deploying","deploy" to "Deploying","script" to "Writing",
      "feature" to "Building","harness" to "Building","build" to "Building","sound" to "Making sounds","room" to "Modelling",
      "spec" to "Designing","design" to "Designing","fixture" to "Recording","reproduce" to "Reproducing","analysis" to "Analysing")
    /** The task's verb, at most two words, never a model or vendor name. */
    fun verb(title: String): String { val t=title.lowercase();return VERBS.firstOrNull { t.contains(it.first) }?.second ?: "Working" }

    /** Live: the same SiteState from a host plans snapshot (components carrying task statuses) and its agents. */
    fun fromLive(layout: SiteLayout,snapshot: JSONObject,clock: String=""): SiteState {
      val plans=snapshot.optJSONObject("plans") ?: JSONObject()
      val parts=HashMap<Pair<String,String>,PartState>()
      layout.plots.forEach { plot -> val comps=plans.optJSONObject(plot.project)?.optJSONArray("components") ?: JSONArray()
        val byId=(0 until comps.length()).map { comps.getJSONObject(it) }.associateBy { it.getString("id") }
        plot.parts.forEach { part -> val ts=byId[part.id]?.optJSONArray("tasks") ?: JSONArray()
          val st=(0 until ts.length()).map { recordedWord(ts.getJSONObject(it).optString("status")) }
          parts[plot.project to part.id]=PartState(st.count { it=="success" },st.size,GraphMotion.state(st.filter { it!="pending" })) } }
      val a=snapshot.optJSONArray("agents") ?: JSONArray()
      val agents=(0 until a.length()).map { a.getJSONObject(it) }.map { j -> val steps=j.optJSONArray("steps") ?: JSONArray()
        SiteAgent(j.getString("id"),j.optString("name","Worker"),j.getString("project"),j.optInt("task"),
          if(j.isNull("component")) null else j.optString("component"),j.optString("verb","Working"),words(j.optString("summary"),12),
          (0 until steps.length()).map { steps.getString(it) },j.optInt("progress_pct")) }
      return SiteState(snapshot.optLong("t_ms"),clock,withCrew(parts,agents),agents)
    }
  }
}
