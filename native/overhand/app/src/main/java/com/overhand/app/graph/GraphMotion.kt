package com.overhand.app.graph

import org.json.JSONArray
import org.json.JSONObject

/**
 * The architecture graph builds itself: one event-to-visual mapping for Recorded and Live.
 *
 * Mapping (both modes): a task queued/ready on a component -> node "building" (blue, pulse,
 * "now building"); its latest outcome success -> "built" (green, edges to dependants fill,
 * "now built"); failed with nothing else open or built -> "blocked" (amber, "now blocked");
 * partial only -> stays "building". Cancelled tasks drop out. No tasks -> "planned" (grey).
 *
 * The Nov 3 rows carry no component field. Membership comes from [MEMBERSHIP]: three tasks
 * from the live plan's own task lists (source "plan"), the rest assigned Nov 12 from the
 * task title (source "title"). Every visual change names that source and its event row.
 */
object GraphMotion {
  /** false gives the graph exactly as before. */
  const val ENABLED=true
  /** One day-minute = 0.5 s wall: the recorded day's 38 component changes play in about 52 s. */
  const val SPEEDUP=120L
  /** Changes that land together are staggered so each one is seen. */
  const val SPACING_MS=400L
  /** Quiet hours between busy spells are shown as at most 3 s wall (the clock still jumps truthfully). */
  const val GAP_CAP_MS=3_000L
  /** Wall lead-in before the first event so the wearer sees the empty graph first. */
  const val LEAD_MS=1_500L
  const val HEADSET="s74617e7e4f3c9e64"

  /** (project, task id) -> (component, source). */
  val MEMBERSHIP: Map<Pair<String,Int>,Pair<String,String>> = buildMap {
    fun t(id: Int,c: String)=put(HEADSET to id,c to "title")
    fun p(id: Int,c: String)=put(HEADSET to id,c to "plan")
    t(1,"schema");t(52,"schema");p(73,"schema")
    t(2,"eval");t(41,"eval");t(48,"eval");t(54,"eval");t(67,"eval")
    t(15,"neutral_runtime");t(44,"neutral_runtime");p(61,"neutral_runtime");t(43,"neutral_runtime");t(45,"neutral_runtime")
    t(16,"web_runtime");t(53,"web_runtime");t(74,"web_runtime")
    t(17,"panels_tray");t(47,"panels_tray");t(40,"panels_tray");t(46,"panels_tray");t(62,"panels_tray")
    t(49,"graph");t(55,"graph");t(63,"graph")
    t(50,"gestures_sound");t(64,"gestures_sound");t(56,"gestures_sound");p(68,"gestures_sound")
    t(57,"assets")
    t(42,"package");t(58,"package")
  }

  data class Change(val wallMs: Long,val dayMs: Long,val component: String,val from: String,val to: String,
    val label: String,val row: Int,val taskId: Int,val title: String,val source: String)

  fun word(state: String)=when(state) { "building" -> "now building";"built" -> "now built";"blocked" -> "now blocked";else -> "now planned" }

  /** Component state from the latest status of each of its tasks (same rule used for Live task lists). */
  fun state(statuses: Collection<String>): String {
    val live=statuses.filter { it!="cancelled" }
    return when {
      live.any { it=="active" || it=="claimed" || it=="ready" || it=="queued" || it=="parked" } -> "building"
      live.any { it=="success" || it=="done" } -> "built"
      live.any { it=="partial" } -> "building"
      live.any { it=="failed" } -> "blocked"
      else -> "planned"
    }
  }

  /** Live: the server already resolves each component; show its own word, falling back to its tasks. */
  fun liveState(c: JSONObject): String {
    val shown=c.optString("shown",c.optString("status"))
    return when(shown) { "verified","built" -> "built";"building" -> "building";"blocked","at_risk" -> "blocked";"dropped" -> "planned"
      else -> c.optJSONArray("tasks")?.let { a -> state((0 until a.length()).map { a.getJSONObject(it).optString("status") }) } ?: "planned" }
  }

  /** Diff two state maps into the changes a Live refresh shows (same words, same pulse). */
  fun diff(before: Map<String,String>,after: Map<String,String>)=after.filter { (id,s) -> before[id]!=null && before[id]!=s }

  class Timeline(rows: List<JSONObject>,val project: String,components: Collection<String>,
    private val membership: Map<Pair<String,Int>,Pair<String,String>> =MEMBERSHIP) {
    private val task=Regex("^#(\\d+) (\\w+)")
    val start: Long
    val changes: List<Change>
    val unmapped: List<Int>
    private val wallStarts: LongArray
    private val dayStarts: LongArray
    init {
      data class E(val row: Int,val t: Long,val id: Int,val status: String,val title: String)
      val events=rows.mapIndexedNotNull { i,e ->
        val m=task.find(e.optString("title"))
        if(e.optString("kind") !in setOf("task_assigned","done","error") || m==null || e.optString("project")!=project) null else {
          val verb=m.groupValues[2];val kind=e.optString("kind")
          val status=when { kind=="error" || verb=="failed" -> "failed"; kind=="done" && verb=="success" -> "success"
            kind=="done" -> "partial"; verb=="cancelled" -> "cancelled"; else -> "active" }
          E(i+1,e.getLong("t_ms"),m.groupValues[1].toInt(),status,e.optString("title"))
        }
      }.sortedWith(compareBy<E> { it.t }.thenBy { it.row })
      start=events.firstOrNull()?.t ?: 0L
      unmapped=events.map { it.id }.distinct().filter { (project to it) !in membership }
      // Piecewise clock: SPEEDUP everywhere, quiet gaps capped at GAP_CAP_MS wall.
      val ws=ArrayList<Long>();val ds=ArrayList<Long>()
      var wall=LEAD_MS;var prev=start
      ws.add(0L);ds.add(start-LEAD_MS*SPEEDUP)
      val wallOf=HashMap<Long,Long>()
      for(e in events) {
        if(e.t!=prev) {
          val dw=(e.t-prev)/SPEEDUP
          if(dw>GAP_CAP_MS) { ws.add(wall);ds.add(prev);wall+=GAP_CAP_MS;ws.add(wall);ds.add(e.t) } else wall+=dw
          prev=e.t
        }
        wallOf[e.t]=wall
      }
      ws.add(wall);ds.add(prev)
      wallStarts=ws.toLongArray();dayStarts=ds.toLongArray()
      val tasks=HashMap<String,HashMap<Int,String>>()
      val current=components.associateWith { "planned" }.toMutableMap()
      val out=ArrayList<Change>();var last=-SPACING_MS
      for(e in events) {
        val (component,source)=membership[project to e.id] ?: continue
        if(component !in current) continue
        tasks.getOrPut(component) { HashMap() }[e.id]=e.status
        val next=state(tasks.getValue(component).values)
        val before=current.getValue(component)
        if(next!=before) {
          current[component]=next
          val w=maxOf(wallOf.getValue(e.t),last+SPACING_MS);last=w
          out.add(Change(w,e.t,component,before,next,word(next),e.row,e.id,e.title,source))
        }
      }
      changes=out
    }
    val durationMs get()=maxOf(wallStarts.last(),changes.lastOrNull()?.wallMs ?: 0L)+2_000L
    /** Real day time shown at a wall offset since Play. */
    fun dayAt(wallMs: Long): Long {
      if(wallMs<=0) return dayStarts[0]
      for(i in 1 until wallStarts.size) if(wallMs<=wallStarts[i]) {
        val w0=wallStarts[i-1];val w1=wallStarts[i];val d0=dayStarts[i-1];val d1=dayStarts[i]
        return if(w1==w0) d1 else d0+(d1-d0)*(wallMs-w0)/(w1-w0)
      }
      return dayStarts.last()
    }
    /** Number of changes visible at a wall offset (changes are sorted by wall time). */
    fun shown(wallMs: Long): Int { var n=0;while(n<changes.size && changes[n].wallMs<=wallMs) n++;return n }
    fun states(n: Int,components: Collection<String>): Map<String,String> {
      val s=components.associateWith { "planned" }.toMutableMap()
      changes.take(n).forEach { s[it.component]=it.to };return s
    }
    fun toJson(n: Int=changes.size,fixtureHash: String=""): JSONObject=JSONObject().put("project",project)
      .put("fixture_sha256",fixtureHash).put("speedup",SPEEDUP).put("gap_cap_ms",GAP_CAP_MS).put("lead_ms",LEAD_MS)
      .put("shown",n).put("total",changes.size).put("first60",shown(60_000)).put("unmapped_task_ids",JSONArray(unmapped))
      .put("changes",JSONArray(changes.take(n).map { c -> JSONObject().put("wall_ms",c.wallMs).put("day_ms",c.dayMs)
        .put("clock",clock(c.dayMs)).put("component",c.component).put("from",c.from).put("to",c.to).put("label",c.label)
        .put("event_row",c.row).put("task_id",c.taskId).put("title",c.title).put("membership",c.source) }))
  }
  fun clock(dayMs: Long): String { val t=dayMs.coerceAtLeast(0);return "Nov 3 %02d:%02d".format(t/3_600_000,t/60_000%60) }
  /** Node colours, same palette as the static graph. */
  fun colour(state: String)=when(state) {
    "built" -> floatArrayOf(.07f,.34f,.25f,1f)
    "building" -> floatArrayOf(.12f,.29f,.48f,1f)
    "blocked" -> floatArrayOf(.48f,.20f,.08f,1f)
    else -> floatArrayOf(.22f,.29f,.37f,1f)
  }
  val EDGE_FILLED=floatArrayOf(.30f,.85f,.55f,1f)
  val EDGE_EMPTY=floatArrayOf(.16f,.24f,.30f,1f)
}
