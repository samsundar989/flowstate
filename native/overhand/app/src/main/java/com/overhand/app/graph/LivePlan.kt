package com.overhand.app.graph

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

/**
 * #978: a live project's plan (PROTOCOL.md section 7) as graph parts. `plan` gives the parts, each "planned" (the dashed
 * ring); `file_written` lights EVERY part whose plan files hold the written path (the host tags only the first, so its
 * `part_id` is a hint, not the whole answer) as "building"; the task's commit (`finished` ok, or `task_state done`) settles
 * the parts that task was building as "built", a failure as "blocked". A follow-up that writes into a built part flips it
 * back to building until its own commit. Event-driven only: [add] says which parts changed, and the graph rewrites its
 * mesh once per change, never per frame. Pure: no Android types.
 */
class LivePlan(val projectId: String) {
  data class Part(val id: String,val name: String,val files: List<String>)
  var parts=emptyList<Part>();private set
  var name="";private set
  private val state=LinkedHashMap<String,String>()
  /** Parts each task has written into and not yet settled. */
  private val open=HashMap<String,LinkedHashSet<String>>()
  private var lastSeq=0L
  /** The part that changed last and its new state, for the pulse and the change word. */
  var newest: Pair<String,String>?=null;private set
  val hasPlan get()=parts.isNotEmpty()

  /** Display wording only: the same plan model also plays recorded runs. */
  fun displayName(recorded: Boolean)=name.ifBlank { if(recorded) "Recorded project" else "Live project" }
  fun pickerName(recorded: Boolean)=(if(recorded) "Recorded: " else "Live: ")+displayName(recorded)

  fun states(): Map<String,String> =LinkedHashMap(state)
  fun count(s: String)=state.values.count { it==s }

  /** Applies one host event; returns the ids of parts whose state changed (empty if none or not ours). */
  fun add(e: JSONObject): List<String> {
    if(e.optString("project_id")!=projectId) return emptyList()
    val seq=e.optLong("seq",0L)
    if(seq>0) { if(seq<=lastSeq) return emptyList();lastSeq=seq }
    val task=e.optString("task_id")
    return when(e.optString("kind")) {
      "project_created" -> { name=e.optString("name").ifBlank { name };emptyList() }
      "plan" -> plan(e)
      "file_written" -> wrote(task,norm(e.optString("path")),e.optString("part_id"))
      "finished" -> settle(task,if(e.optBoolean("ok")) "built" else "blocked")
      "task_state" -> when(e.optString("state")) {
        "done" -> settle(task,"built");"failed","cancelled","canceled" -> settle(task,"blocked");else -> emptyList() }
      else -> emptyList()
    }
  }

  private fun plan(e: JSONObject): List<String> {
    val a=e.optJSONArray("parts") ?: return emptyList()
    val seen=HashSet<String>()
    parts=(0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { p ->
      val id=p.optString("id").takeIf { it.isNotBlank() && seen.add(it) } ?: return@mapNotNull null
      val f=p.optJSONArray("files")
      Part(id,p.optString("name").ifBlank { id },(0 until (f?.length() ?: 0)).map { norm(f!!.optString(it)) }.filter { it.isNotEmpty() })
    }
    state.clear();open.clear();parts.forEach { state[it.id]="planned" }
    newest=null
    return parts.map { it.id }
  }

  private fun wrote(task: String,path: String,hint: String): List<String> {
    val hit=parts.filter { path.isNotEmpty() && path in it.files }.map { it.id }.toMutableList()
    if(hint in state && hint !in hit) hit.add(hint)
    val changed=hit.filter { state[it]!="building" }
    hit.forEach { open.getOrPut(task) { LinkedHashSet() }.add(it) }
    changed.forEach { state[it]="building";newest=it to "building" }
    return changed
  }

  private fun settle(task: String,to: String): List<String> {
    val ids=open.remove(task) ?: return emptyList()
    val changed=ids.filter { state[it]=="building" && open.values.none { o -> it in o } }
    changed.forEach { state[it]=to;newest=it to to }
    return changed
  }

  /** The parts as the graph's component rows (GraphLod.infos reads id, short, name and paths). */
  fun components(): List<JSONObject> =parts.map { p ->
    JSONObject().put("id",p.id).put("name",p.name).put("short",p.name).put("status",state[p.id] ?: "planned")
      .put("paths",JSONArray(p.files)).put("desc","Planned part of your new project.")
  }

  /** Laid out on the recorded graph's tile pitch (0.54 m across, 0.36 m down) in plan order, at most two rows, so a plan
   * of 3 to 6 parts reads left to right like the recorded graph's layers. No edges: the plan states no dependencies. */
  fun model(): GraphModel {
    val n=parts.size
    val cols=if(n<=3) n else ceil(n/2.0).toInt()
    val rows=if(n==0) 0 else ceil(n/cols.toDouble()).toInt()
    val nodes=parts.mapIndexed { i,p ->
      val row=i/cols;val col=i%cols
      GraphNode(p.id,col,(col-(cols-1)/2.0)*0.54,((rows-1)/2.0-row)*0.36,-0.025*(col%2))
    }
    return GraphModel(nodes,emptyList())
  }

  /** The panel's detail line: counts only, no invented content. */
  fun detail()=if(!hasPlan) "Waiting for the plan from your computer." else
    "${parts.size} planned parts · ${count("building")} building · ${count("built")} built"+
      (if(count("blocked")>0) " · ${count("blocked")} stopped" else "")+
      "\nLive plan from your computer: a part lights up while its files are written and turns green at the commit."

  companion object {
    fun norm(p: String)=p.replace('\\','/').trim().removePrefix("./")
    /** The word over a changed part. */
    fun word(state: String)=when(state) { "building" -> "now building";"built" -> "now built";"blocked" -> "stopped";else -> "planned" }
  }
}
