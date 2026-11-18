package com.overhand.app.graph

import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos

data class RecordedTask(val project: String,val id: Int,val title: String,val status: String,val row: Int,val tMs: Long)
data class ProjectProgress(val tasks: List<RecordedTask>) {
  val total get()=tasks.size
  fun count(status: String)=tasks.count { it.status==status }
  val success get()=count("success")
  val fraction get()=if(total==0) 0.0 else success.toDouble()/total
  val summary get()="$success/$total recorded tasks successful · ${count("partial")} partial · ${count("failed")} failed · ${count("cancelled")} cancelled · ${count("active")} active · ${count("pending")} not yet seen"
}

/** Nov 3 task history only. Identity is scoped to this fixture/day AND project AND task ID.
 * No plan is accepted here: Nov 5 text/tags cannot change Nov 3 component state.
 * Denominator includes every distinct task observed in this partial recording, including
 * cancelled/failed/partial tasks and tasks whose first event is later than the cursor.
 */
/** [holdsToEnd]: the last step shows the end of the recording, so rows after the last task (a demo's final result) are reached. */
class TaskReplay(rows: List<JSONObject>,val fixtureHash: String,private val clockOf: ((Long)->String)?=null,private val holdsToEnd: Boolean=false) {
  private data class Event(val task: RecordedTask)
  private val events=rows.mapIndexedNotNull { index,e ->
    val m=TASK.find(e.optString("title"))
    if(e.optString("kind") !in KINDS || m==null || e.optString("project").isBlank()) null else {
      val verb=m.groupValues[2]
      val status=when {
        e.optString("kind")=="error" || verb=="failed" -> "failed"
        e.optString("kind")=="done" && verb=="success" -> "success"
        e.optString("kind")=="done" -> "partial"
        verb=="cancelled" -> "cancelled"
        else -> "active"
      }
      Event(RecordedTask(e.getString("project"),m.groupValues[1].toInt(),e.optString("title"),status,index+1,e.getLong("t_ms")))
    }
  }.sortedWith(compareBy<Event> { it.task.tMs }.thenBy { it.task.row })
  val count get()=events.size
  val start=rows.minOfOrNull { it.getLong("t_ms") } ?: 0L
  val end=rows.maxOfOrNull { it.getLong("t_ms") } ?: 0L
  var step=0; private set
  val tMs get()=if(step==0) start else if(holdsToEnd && step==count) end else events[step-1].task.tMs
  val clock get()=clockOf?.invoke(tMs) ?: "Nov 3 %02d:%02d:%02d CST".format(tMs/3_600_000,tMs/60_000%60,tMs/1000%60)
  fun seek(n: Int) { step=n.coerceIn(0,count) }
  fun restart()=seek(0)
  fun progress(project: String)=afterSteps(project,step)
  fun afterSteps(project: String,n: Int): ProjectProgress {
    val mine=events.filter { it.task.project==project }
    val latest=events.take(n.coerceIn(0,count)).filter { it.task.project==project }
      .associate { it.task.id to it.task }
    return ProjectProgress(mine.distinctBy { it.task.id }.map { e ->
      latest[e.task.id] ?: e.task.copy(status="pending",row=0,tMs=start,title="#${e.task.id} · not yet seen")
    })
  }
  /** Steps whose recorded time is at or before tMs (GraphMotion's day clock drives the task panel). */
  fun stepAt(tMs: Long)=events.count { it.task.tMs<=tMs }
  fun at(project: String,tMs: Long)=afterSteps(project,events.count { it.task.tMs<=tMs })
  fun snapshot(project: String): JSONObject {
    val p=progress(project)
    return JSONObject().put("project",project).put("fixture_sha256",fixtureHash)
      .put("step",step).put("count",count).put("t_ms",tMs).put("clock",clock)
      .put("denominator","distinct task IDs in this project's partial Nov 3 recording")
      .put("total",p.total).put("success",p.success).put("fraction",p.fraction)
      .put("summary",p.summary).put("component_event_links",org.json.JSONArray())
      .put("architecture_date","Nov 5, 2026").put("architecture_animated",false)
      .put("tasks",org.json.JSONArray(p.tasks.map { t -> JSONObject().put("id",t.id)
        .put("status",t.status).put("event_row",t.row).put("t_ms",t.tMs).put("title",t.title) }))
  }
  companion object {
    private val TASK=Regex("^#(\\d+) (\\w+)")
    private val KINDS=setOf("task_assigned","done","error")
    const val STEP_MS=1200L
    const val DURATION=200L
    // Reused from t361 GraphReplay: 200 ms cosine ease and event-paced wall clock.
    fun ease(from: Float,to: Float,startedMs: Long,nowMs: Long): Float {
      val t=((nowMs-startedMs).toFloat()/DURATION).coerceIn(0f,1f)
      return from+(to-from)*((1-cos(t*PI)).toFloat()/2)
    }
    fun step(elapsedMs: Long,count: Int)=(elapsedMs/STEP_MS).coerceIn(0,count.toLong()).toInt()
  }
}
