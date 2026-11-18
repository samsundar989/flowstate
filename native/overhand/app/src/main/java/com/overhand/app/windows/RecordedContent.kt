package com.overhand.app.windows

import com.overhand.app.data.RecordedSource
import com.overhand.app.graph.*
import com.overhand.app.stations.StationState
import org.json.JSONArray
import org.json.JSONObject

/** Pure display projections shared by the native windows, graph and station adapters. */
class RecordedContent(val source: RecordedSource) {
  val taskReplay=TaskReplay(source.events,source.fixtureHash,{ source.clock(it,true) },holdsToEnd=source.dataset!=RecordedSource.DAY)
  val projects get()=rows(source.snapshot.getJSONArray("projects"))
  val defaultProject get()=projects.first().getString("id")
  val labels get()=ProjectContext.labels(projects)
  fun projectName(id: String)=ProjectContext.label(labels,id)
  fun plans()=JSONObject().also { out -> rows(source.architecture.getJSONArray("projects")).forEach { out.put(it.getString("id"),it) } }
  fun components(project: String)=rows(plans().optJSONObject(project)?.optJSONArray("components"))
  fun graph(project: String)=GraphLayout.build(components(project).map { c ->
    val a=c.optJSONArray("after")
    Component(c.getString("id"),(0 until (a?.length() ?: 0)).map { a!!.getString(it) })
  })
  fun componentDetail(project: String,c: JSONObject): String = buildString {
    appendLine(component(c))
    appendLine("\nRecorded code associations · ${source.planLabel}")
    val code=source.code(project)
    val association=rows(code.optJSONArray("components")).firstOrNull { it.getString("id")==c.getString("id") }
    val ids=association?.optJSONArray("code_ids")
    val linked=(0 until (ids?.length() ?: 0)).map { ids!!.getString(it) }.toSet()
    val modules=rows(code.optJSONArray("modules")).filter { m -> m.getString("id") in linked || rows(m.optJSONArray("sample")).any { it.getString("id") in linked } }
    // Demo plans list the tasks whose commits built each component (#759 membership from touched files).
    val tasks=rows(c.optJSONArray("tasks")).map { "#${it.optInt("id")} ${PolishRules.status(it.optString("status"))}" }
    if(tasks.isNotEmpty()) appendLine("Built by tasks: "+tasks.joinToString(", "))
    if(modules.isEmpty() && tasks.isEmpty()) appendLine("Not recorded for this component")
    modules.forEach { m ->
      appendLine("Module: "+moduleName(m,rows(code.optJSONArray("modules")).indexOfFirst { it.optString("id")==m.optString("id") }))
      m.optJSONObject("excerpt")?.let { excerpt(it) }
      rows(m.optJSONArray("sample")).filter { m.getString("id") in linked || it.getString("id") in linked }.map { it.getString("label") }.filter { shown(it) }.forEach { appendLine("File: $it") }
      rows(m.optJSONArray("top")).filter { m.getString("id") in linked || it.optString("file_id") in linked }.forEach { appendLine(it.getString("kind")+": "+it.getString("name")+" · "+it.optInt("used_by")+" incoming references") }
    }
  }
  fun stations(): List<StationState> {
    val decisions=rows(source.decisions())
    return projects.map { p ->
      val id=p.getString("id")
      StationState(id,labels.getValue(id),decisions.count { it.optString("project")==id && it.optString("status")=="open" },0,0,0)
    }.sortedBy { it.id }
  }
  fun window(view: String,project: String=defaultProject): String = when(view) {
    "fleet" -> buildString {
      appendLine("Recorded snapshot · ${source.dayLabel} · times ${source.zone}")
      projects.forEach { p ->
        appendLine("\n${projectName(p.getString("id"))}")
        val events=source.events.filter { it.optString("project")==p.getString("id") }
        events.filter { it.optString("kind")=="task_assigned" }.forEach { appendLine("${stamp(it)} · Recorded task: ${it.optString("title")}") }
        events.filter { it.optString("kind")=="metric" }.forEach { appendLine("${stamp(it)} · ${it.getString("name").ifBlank { "Recorded metric (name omitted)" }} = ${it.get("value")}") }
        if(events.isEmpty()) appendLine("Not recorded for this project")
      }
    }
    "blueprint" -> buildString {
      appendLine("Architecture snapshot · ${source.planLabel}")
      val cs=components(project)
      if(cs.isEmpty()) appendLine("Not recorded for this project")
      cs.forEach { appendLine("\n"+component(it)) }
    }
    "code" -> buildString {
      appendLine("Architecture snapshot · ${source.planLabel}")
      val code=source.code(project)
      val modules=rows(code.optJSONArray("modules"))
      if(modules.isEmpty()) appendLine("Not recorded for this project") else {
        if(code.optBoolean("excerpts")) appendLine("Recorded file contents · ${modules.size} excerpts from ${code.optInt("files")} files")
        else appendLine("Captured map · ${code.optInt("files")} files · ${code.optInt("symbols")} symbols")
        if(!code.optBoolean("indexed") && !code.optBoolean("excerpts")) appendLine("No symbol index for this project; module sizes follow.")
        modules.forEachIndexed { i,m ->
          appendLine("\nModule: ${moduleName(m,i)} · ${m.optInt("files")} files")
          // #1096: a hidden file name is left out, never shown as filler.
          val files=rows(m.optJSONArray("sample")).associate { it.getString("id") to it.getString("label") }.filterValues { shown(it) }
          files.values.forEach { appendLine("File: $it") }
          rows(m.optJSONArray("top")).forEach { s -> appendLine("${s.getString("kind")}: ${s.getString("name")} · ${files[s.optString("file_id")]?.let { "$it · " } ?: ""}${s.optInt("used_by")} incoming references") }
          m.optJSONObject("excerpt")?.let { excerpt(it) }
        }
      }
    }
    "build" -> buildString {
      val outcomes=source.events.filter { it.optString("kind") in listOf("done","error") }
      appendLine("Recorded snapshot · ${source.dayLabel} · times ${source.zone}")
      appendLine("${outcomes.count { it.getString("kind")=="done" }} done · ${outcomes.count { it.getString("kind")=="error" }} failed")
      outcomes.forEach { appendLine("\n${stamp(it)} · ${it.optString("project_name")} · ${it.optString("agent_name")} · ${if(it.getString("kind")=="done") "done" else "failed"}\n${PolishRules.rowTitle(it.optString("title"))}") }
    }
    "chat" -> buildString {
      appendLine("Recorded snapshot · ${source.dayLabel} · read only · times ${source.zone}")
      appendLine("Practice answers on this headset")
      val ds=rows(source.decisions())
      val receipts=ds.filter { it.has("practice_receipt") }
      if(receipts.isEmpty()) appendLine("No practice answers yet. Open Decisions to answer.")
      receipts.forEach { d -> val r=d.getJSONObject("practice_receipt");appendLine("${d.getString("question")}\nPractice: ${r.getString("option_label")} · device UTC ms ${r.getLong("device_utc_ms")}") }
      ds.forEach { d ->
        appendLine("\n${stamp(d)} · ${d.optString("project_name")} · ${d.getString("question")}")
        if(d.has("historical_answer")) appendLine("Recorded developer answer: ${d.getString("historical_answer")}")
        else appendLine(if(d.has("practice_receipt")) "Practice answer saved; recording continues with the original outcome" else "Open at recording cutoff")
      }
      appendLine("\nMessage timeline · edited for privacy; some details are omitted")
      source.events.filter { it.optString("kind")=="message" }.forEach { appendLine("\n${stamp(it)} · ${it.optString("project_name")} · ${it.optString("agent_name")}\n${it.optString("title")}: ${it.optString("detail")}") }
    }
    else -> error("Unknown recorded window: $view")
  }.let(PolishRules::rowTitle)
  /** Exact recorded question and option text, as the decision card receives it. */
  fun decisionText()=rows(source.decisions()).flatMap { d ->
    listOf(d.optString("question"),d.optJSONObject("practice_receipt")?.optString("option_label").orEmpty())+
      d.optJSONArray("options").let { a -> (0 until (a?.length() ?: 0)).map { i -> a!!.optJSONObject(i)?.optString("label") ?: a.optString(i) } }
  }
  /** The next open decision at the top of Chat. */
  fun chatLead(first: JSONObject)=buildString {
    appendLine("Recorded decision · ${source.dayLabel} · practice only")
    appendLine(first.optString("project_name"))
    appendLine(first.getString("question"))
    rows(first.getJSONArray("options")).forEach { appendLine(it.getString("label")) }
    appendLine("Open Decisions to choose.\n\nEarlier chat and answers")
  }
  /** Persistent cutoff summary, independent of the paged historical timeline. */
  fun currentWork(project: String,full: Boolean=false): String {
    val name=projectName(project)
    val task=rows(source.snapshot.optJSONArray("tasks")).firstOrNull { it.optString("project")==project }
    return if(task==null) "$name · current work not recorded" else
      "$name · Worker #${task.optInt("id")} · ${PolishRules.status(task.optString("status"))}\n${if(full) PolishRules.rowTitle(task.optString("title")) else RecordedText.title(task.optString("title"))}"
  }
  /** A module's recorded name, or (when the recording hid it) its position, language and size: never filler. */
  private fun moduleName(m: JSONObject,i: Int): String {
    val label=m.optString("label").trim()
    if(shown(label)) return label
    val lang=m.optString("lang").ifBlank { "mixed" }
    return "#${i+1} ($lang, ${(m.optLong("bytes")+1023)/1024} KB)"
  }
  private fun StringBuilder.excerpt(x: JSONObject) {
    appendLine("Recorded excerpt (some lines shortened), lines ${x.getInt("start_line")} to ${x.getInt("end_line")} at commit ${x.getString("sha").take(7)}:")
    x.getString("text").lines().forEach { appendLine("  "+it) }
  }
  companion object {
    /** Hidden or empty recorded names (the privacy marker, after [RecordedText.neutral]) are never shown. */
    val FILLER=Regex("""recorded detail|\[redacted]""",RegexOption.IGNORE_CASE)
    fun shown(label: String)=label.isNotBlank() && !FILLER.containsMatchIn(label)
    fun rows(a: JSONArray?)=(0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it) }
    private val REAL=java.time.format.DateTimeFormatter.ofPattern("MMM d HH:mm:ss 'UTC'",java.util.Locale.US).withZone(java.time.ZoneOffset.UTC)
    /** Row time: the Nov 3 clock, or a demo row's real date and time. */
    fun stamp(e: JSONObject): String {
      if(e.has("real_time")) return REAL.format(java.time.Instant.parse(e.getString("real_time")))
      val seconds=e.getLong("t_ms")/1000
      return "Nov 3 %02d:%02d:%02d CST".format(seconds/3600,seconds/60%60,seconds%60)
    }
    fun component(c: JSONObject)=buildString {
      appendLine(c.optString("name",c.getString("id"))+" · "+PolishRules.status(c.optString("shown",c.optString("status"))))
      listOf("desc" to "Description","why" to "Why","technique" to "Technique").forEach { (key,title) ->
        // Demo plans (#759, rows cite "sources") carry no why or technique: omit them, never a placeholder.
        if(c.optString(key).isNotBlank()) appendLine("$title: "+c.optString(key)) else if(!c.has("sources")) appendLine("$title: Not recorded") }
      append("After: "+(c.optJSONArray("after") ?: JSONArray()).toString())
    }
  }
}
