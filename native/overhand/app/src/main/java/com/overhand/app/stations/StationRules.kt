package com.overhand.app.stations

import org.json.JSONObject
import org.json.JSONArray
import kotlin.math.*

data class StationState(val id: String, val name: String, val decisions: Int,
  val failed: Int, val reviews: Int, val stall: Int) {
  val needsYou: Boolean get() = decisions > 0 || failed > 0 || reviews > 0 || stall > 0
  val detail: String get() = if (!needsYou) "Clear" else
    listOfNotNull(if(decisions>0) "$decisions ${if(decisions==1) "decision" else "decisions"}" else null,
      if(failed>0) "$failed failed" else null, if(reviews>0) "$reviews ${if(reviews==1) "review" else "reviews"}" else null,
      if(stall>0) "Stalled $stall" else null).joinToString(" · ")
}

/** Pure projection of /api/state. No clock heuristic: parked/queued is not a stall.
 * A failed result only needs attention while reviewed=0, matching the Head digest.
 * Union recent tasks with the per-project build history (both bounded); do not alert on
 * aggregate historical failure counters, which include already-reviewed failures.
 */
object StationRules {
  fun evaluate(state: JSONObject): List<StationState> {
    val projects=state.getJSONArray("projects")
    val tasks=state.getJSONArray("tasks")
    val decisions=state.getJSONArray("decisions")
    return (0 until projects.length()).map { i ->
      val p=projects.getJSONObject(i);val id=p.getString("id")
      val history=state.optJSONObject("build")?.optJSONArray(id) ?: JSONArray()
      val rows=((0 until tasks.length()).map { tasks.getJSONObject(it) }.filter { it.optString("project")==id }+
        (0 until history.length()).map { history.getJSONObject(it) })
        .distinctBy { if(it.has("id")) it.get("id") else it }
      val failed=rows.count { it.optString("status")=="failed" && it.optInt("reviewed")==0 }
      val reviews=rows.count { it.optString("status")=="done" && it.optInt("reviewed")==0 }
      val open=(0 until decisions.length()).map { decisions.getJSONObject(it) }
        .count { it.optString("project")==id && it.optString("status")=="open" }
      StationState(id,p.optString("name",id),open,failed,reviews,p.optInt("stall").coerceAtLeast(0))
    }.sortedBy { it.id }
  }

  /** #1096: the first project the host reports (its /api/state order), or null. */
  fun firstProject(state: JSONObject,having: (String) -> Boolean={ true }): String? {
    return projectIds(state).firstOrNull(having)
  }
  private fun projectIds(state: JSONObject): List<String> { val projects=state.optJSONArray("projects") ?: return emptyList()
    return (0 until projects.length()).mapNotNull { projects.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotBlank() } } }
  /** #1127 (R354, supersedes R350): the project Live mode opens on, for the stations and the graph alike: the wearer's last
   * choice if still listed, else the most agents working now (tie: the latest update), else the project whose agents were
   * updated most recently in any state, else the first with a plan, else the first listed. */
  fun liveDefault(state: JSONObject,saved: String?): String? = liveDefaultStep(state,saved).first
  /** [liveDefault] with the step that decided: saved, working, recent, plan, first, or none. */
  fun liveDefaultStep(state: JSONObject,saved: String?): Pair<String?,String> {
    val ids=projectIds(state)
    saved?.takeIf { it in ids }?.let { return it to "saved" }
    val all=state.optJSONArray("agents").let { a -> (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it) } }.filter { it.optString("project") in ids }
    fun pick(agents: List<JSONObject>)=ids.map { id -> agents.filter { it.optString("project")==id }.let { w -> Triple(id,w.size,w.maxOfOrNull { it.optDouble("updated_at",0.0) } ?: 0.0) } }
      .filter { it.second>0 }
    pick(all.filter { it.optString("state")=="working" }).maxWithOrNull(compareBy<Triple<String,Int,Double>>({ it.second },{ it.third }))?.let { return it.first to "working" }
    pick(all).filter { it.third>0 }.maxByOrNull { it.third }?.let { return it.first to "recent" }
    firstProject(state) { state.optJSONObject("plans")?.has(it)==true }?.let { return it to "plan" }
    return ids.firstOrNull().let { it to (if (it==null) "none" else "first") }
  }
  const val LIVE_CHOICE="live/chosen-project"
  /** #1096: the station chosen when none is: the recording's default, else the host's first, else the first shown. No project id ships as a literal. */
  fun defaultStation(present: Collection<String>,recorded: String?,hostFirst: String?): String =
    recorded?.takeIf { it in present } ?: hostFirst?.takeIf { it in present } ?: present.firstOrNull() ?: ""

  /** Seven labels span 72 degrees; places are saved on first allocation, never reflowed. */
  fun arc(index: Int, count: Int): List<Float> {
    require(count>0 && index in 0 until count)
    val degrees=(index-(count-1)/2f)*min(12f,72f/max(1,count-1))
    val angle=Math.toRadians(degrees.toDouble())
    return listOf((sin(angle)*3.2).toFloat(),0.85f,(cos(angle)*3.2).toFloat(),degrees)
  }

  /** Same local-floor pose encoding as the window layout store; reject corrupt saved data. */
  fun readPlace(value: String?): List<Float>? = runCatching {
    val a=JSONArray(value ?: "[]");require(a.length()==7)
    val v=(0..6).map { a.getDouble(it).toFloat() }
    require(v.all { it.isFinite() } && v.take(3).all { abs(it)<20 } &&
      sqrt(v.drop(3).sumOf { (it*it).toDouble() }) in 0.9..1.1)
    v
  }.getOrNull()

  fun brandPattern(script: String): Regex {
    val part=script.substringAfter("const deny = [").substringBefore("];")
    val words=Regex("'([^']+)'").findAll(part).map { Regex.escape(it.groupValues[1]) }.toList()
    require(words.isNotEmpty())
    return Regex("\\b(?:"+words.sortedByDescending { it.length }.joinToString("|")+")\\b",RegexOption.IGNORE_CASE)
  }
}
