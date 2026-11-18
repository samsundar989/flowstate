package com.overhand.app.data

import com.overhand.app.graph.GraphMotion
import com.overhand.app.graph.ProjectView
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

object RecordedReducer {
  fun reduce(jsonl: String): JSONArray {
    val decisions=linkedMapOf<String,JSONObject>()
    jsonl.lineSequence().filter { it.isNotBlank() }.forEach { line ->
      val e=JSONObject(line)
      val id=e.optString("decision_id")
      if(e.optString("kind")=="decision") {
        decisions[id]=JSONObject(e.toString()).put("id",id).put("question",e.optString("title")).put("status","open")
      } else if(e.optString("kind")=="status" && e.optString("status")=="WORKING" && e.has("answer")) {
        decisions[id]?.put("historical_answer",e.getString("answer"))?.put("status","historical")
      }
    }
    return JSONArray(decisions.values.toList())
  }
}

/**
 * One bundled recording a judge can choose in Recorded mode: the Nov 3 recorded day ([DAY]) or one of the
 * demo histories rebuilt from two finished repos (#759, assets/judge/demo/manifest.json). One recording is
 * loaded per activity; choosing another restarts the recorded session on it (Settings).
 */
class RecordedSource(private val readAsset: (String)->String,private val receipts: LocalReceiptStore,
  @Suppress("UNUSED_PARAMETER") http: HttpClient?=null,private val receiptChanged: ()->Unit={},
  private val display: (String)->String=com.overhand.app.windows.RecordedText::neutral,
  requested: String=DAY): SessionSource {
  override val recorded=true
  private val demo: JSONObject?=if(requested==DAY) null else demos(readAsset).firstOrNull { it.getString("key")==requested }
  /** The recording actually loaded: an unknown key falls back to the recorded day. */
  val dataset: String=demo?.getString("key") ?: DAY
  private val dir=if(demo==null) "judge/" else "judge/demo/$dataset/"
  private val fixture=readAsset(if(demo==null) "judge/real_day.jsonl" else dir+"day.jsonl")
  val fixtureHash=MessageDigest.getInstance("SHA-256").digest(fixture.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
  private val frozen=RecordedReducer.reduce(display(fixture))
  val architecture=JSONObject(display(readAsset(dir+"architecture.json")))
  val events: List<JSONObject> = display(fixture).lineSequence().filter { it.isNotBlank() }.map(::JSONObject).toList()
  private val times=events.filter { it.has("real_time") }.map { it.getLong("t_ms") to Instant.parse(it.getString("real_time")).toEpochMilli() }
  /** Dates as a judge reads them: the recording's own day, or the real span of the rebuilt history. */
  val dayLabel: String=if(demo==null) "Nov 3, 2026" else
    "${DATE.format(Instant.ofEpochMilli(times.first().second))} to ${DATE.format(Instant.ofEpochMilli(times.last().second))}, ${YEAR.format(Instant.ofEpochMilli(times.last().second))}"
  val zone=if(demo==null) "CST" else "UTC"
  /** When the architecture plan was captured. */
  val planLabel: String=if(demo==null) "Nov 5" else DATE.format(LocalDate.parse(architecture.getString("date")).atStartOfDay(ZoneOffset.UTC))
  val badge: String=if(demo==null) BADGE else "Rebuilt from a finished project's real history · $dayLabel · practice answers stay on this headset"
  val name: String=demo?.getString("name") ?: DAY_NAME
  val snapshot: JSONObject=if(demo==null) JSONObject(display(readAsset("judge/snapshot.json"))) else demoSnapshot(demo)
  private val demoCode: JSONObject?=demo?.let { JSONObject(display(readAsset(dir+"code.json"))) }
  /** (project, task id) -> (component, source) for the graph's build-up motion. */
  val membership: Map<Pair<String,Int>,Pair<String,String>> = if(demo==null) GraphMotion.MEMBERSHIP else
    JSONObject(readAsset(dir+"membership.json")).let { m -> rows(m.getJSONArray("tasks")).associate {
      (m.getString("project") to it.getInt("task_id")) to (it.getString("component") to it.optString("source","files")) } }
  /** Plain-words project view, by project id. */
  val views: Map<String,ProjectView.Project> = if(demo==null) ProjectView.PROJECTS else
    JSONObject(display(readAsset(dir+"view.json"))).let { mapOf(it.getString("project") to ProjectView.fromJson(it)) }

  /** Real time at a replay offset: the Nov 3 clock, or the demo's real dates interpolated between its rows. */
  fun clock(tMs: Long,seconds: Boolean=false): String {
    if(demo==null) { val t=tMs.coerceAtLeast(0)
      return if(seconds) "Nov 3 %02d:%02d:%02d CST".format(t/3_600_000,t/60_000%60,t/1000%60) else "Nov 3 %02d:%02d".format(t/3_600_000,t/60_000%60) }
    val i=times.indexOfFirst { it.first>tMs }
    val real=when { i==0 -> times.first().second;i<0 -> times.last().second
      else -> { val (t0,r0)=times[i-1];val (t1,r1)=times[i];if(t1==t0) r1 else r0+(r1-r0)*(tMs-t0)/(t1-t0) } }
    return (if(seconds) STAMP else MINUTE).format(Instant.ofEpochMilli(real))+" UTC"
  }
  fun code(project: String): JSONObject = when {
    demoCode!=null -> if(demoCode.optString("project")==project) demoCodeMap(demoCode) else JSONObject()
    (0 until snapshot.getJSONArray("projects").length()).any { snapshot.getJSONArray("projects").getJSONObject(it).getString("id")==project } ->
      JSONObject(display(readAsset("judge/code/$project.json")))
    else -> JSONObject()
  }
  /** #1371: real diffs of this recording's commits (assets/judge/diffs.json, tools/code_diffs/build.py); "{}" when absent. */
  val diffs: String by lazy { runCatching { display(readAsset("judge/diffs.json")) }.getOrDefault("{}") }
  /** One project row, and the cutoff's current work: the task still open, else the last one to finish. */
  private fun demoSnapshot(d: JSONObject): JSONObject {
    val task=Regex("^#(\\d+) (\\w+): (.*)$")
    val last=linkedMapOf<Int,Pair<String,String>>()
    events.forEach { e -> task.find(e.optString("title"))?.let { m -> val id=m.groupValues[1].toInt()
      val status=when { e.optString("kind")=="error" -> "failed";e.optString("kind")=="done" -> m.groupValues[2];else -> "claimed" }
      last.remove(id);last[id]=status to m.groupValues[3] } }
    val (id,current)=(last.entries.lastOrNull { it.value.first=="claimed" } ?: last.entries.last()).toPair()
    return JSONObject().put("date",dayLabel).put("projects",JSONArray().put(JSONObject().put("id",d.getString("id")).put("name",d.getString("name"))))
      .put("tasks",JSONArray().put(JSONObject().put("id",id).put("title",current.second)
        .put("status",if(current.first=="claimed") "claimed" else "done").put("result_status",current.first).put("project",d.getString("id"))))
  }
  /** code.json's verbatim excerpts, in the shape the Code window and the component details read. */
  private fun demoCodeMap(c: JSONObject): JSONObject {
    val names=rows(architecture.getJSONArray("projects")).flatMap { rows(it.optJSONArray("components")) }.associate { it.getString("id") to it.optString("name") }
    val excerpts=rows(c.getJSONArray("excerpts"))
    val modules=JSONArray();val components=JSONArray()
    excerpts.forEachIndexed { i,x ->
      val id="code-${x.getString("component")}-$i"
      modules.put(JSONObject().put("id",id).put("label",names[x.getString("component")] ?: x.getString("component")).put("files",1)
        .put("top",JSONArray()).put("sample",JSONArray().put(JSONObject().put("id","$id-file").put("label",x.getString("path"))))
        .put("excerpt",JSONObject().put("path",x.getString("path")).put("sha",x.getString("sha"))
          .put("start_line",x.getInt("start_line")).put("end_line",x.getInt("end_line")).put("text",x.getString("text"))))
      components.put(JSONObject().put("id",x.getString("component")).put("code_ids",JSONArray().put(id)))
    }
    return JSONObject().put("excerpts",true).put("indexed",false).put("files",excerpts.map { it.getString("path") }.distinct().size)
      .put("symbols",0).put("modules",modules).put("components",components)
  }
  override fun decisions(): JSONArray {
    val copy=JSONArray(frozen.toString())
    for(i in 0 until copy.length()) {
      val d=copy.getJSONObject(i)
      receipts.find(fixtureHash,d.getString("id"))?.let { d.put("practice_receipt",it);if(d.optString("status")=="open") d.put("status","answered") }
    }
    return JSONArray(display(copy.toString()))
  }
  override fun answer(decision: JSONObject,label: String,input: String): String {
    val original=(0 until frozen.length()).map { frozen.getJSONObject(it) }.first { it.getString("id")==decision.getString("id") }
    check(original.getString("status")=="open") { "Historical answer is read only" }
    val options=original.getJSONArray("options")
    val option=(0 until options.length()).map { options.getJSONObject(it) }.first { it.getString("label")==label }
    val saved=receipts.save(fixtureHash,original.getString("id"),option.getString("id"),label,input,original.getLong("t_ms"))
    // Persistence is authoritative; a display observer cannot undo a durable receipt.
    runCatching { receiptChanged() }
    return "Practice answer saved on this headset · ${saved.getString("option_label")}. No live agent was contacted."
  }
  fun restart()=receipts.clear()
  companion object {
    const val DAY="day"
    const val DAY_NAME="Recorded day"
    private val DATE=DateTimeFormatter.ofPattern("MMM d",Locale.US).withZone(ZoneOffset.UTC)
    private val YEAR=DateTimeFormatter.ofPattern("yyyy",Locale.US).withZone(ZoneOffset.UTC)
    private val MINUTE=DateTimeFormatter.ofPattern("MMM d HH:mm",Locale.US).withZone(ZoneOffset.UTC)
    private val STAMP=DateTimeFormatter.ofPattern("MMM d HH:mm:ss",Locale.US).withZone(ZoneOffset.UTC)
    private fun rows(a: JSONArray?)=(0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it) }
    /** The demo manifest's projects; an APK without demos simply lists none. */
    fun demos(readAsset: (String)->String): List<JSONObject> =
      runCatching { rows(JSONObject(readAsset("judge/demo/manifest.json")).getJSONArray("projects")) }.getOrDefault(emptyList())
    /** (key, name) for every recording a judge can choose, the recorded day first. */
    fun datasets(readAsset: (String)->String)=listOf(DAY to DAY_NAME)+demos(readAsset).map { it.getString("key") to it.getString("name") }
    const val BADGE="Replayed from real coding-agent runs · Nov 3, 2026 · practice answers stay on this headset"
  }
}
