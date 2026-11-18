package com.overhand.app.windows

import org.json.JSONArray
import org.json.JSONObject

/**
 * #1371: what the agents changed, as file rows with their real unified diff. Recorded rows come from
 * `assets/judge/diffs.json` (`tools/code_diffs/build.py`: `git show` of each recorded commit, never hand-written);
 * live rows from the host's `file_written` event (PROTOCOL.md 7a), which carries a diff only when the host sends one.
 */
object CodeDiffs {
  /** #1385: how many times diffs.json has been parsed (the open-path budget test reads it). */
  val parses=java.util.concurrent.atomic.AtomicInteger()
  enum class Kind { ADD, DEL, CTX, HUNK }
  data class Line(val kind: Kind,val text: String)
  data class Row(val project: String,val path: String,val sha: String,val subject: String,val timeMs: Long,
    val added: Int,val removed: Int,val lines: List<Line>,val worker: Int?=null,val part: String="") {
    val name get()=path.substringAfterLast('/')
  }

  /** One unified-diff line; the leading '+', '-' or ' ' is the kind, and is not part of [Line.text]. */
  fun line(raw: String): Line = when {
    raw.startsWith("@@") -> Line(Kind.HUNK,raw)
    raw.startsWith("+") -> Line(Kind.ADD,raw.substring(1))
    raw.startsWith("-") -> Line(Kind.DEL,raw.substring(1))
    else -> Line(Kind.CTX,raw.removePrefix(" "))
  }
  /** The hunks of a unified diff ("diff --git" and file headers dropped). */
  fun parse(unified: String): List<Line> =
    unified.lines().dropWhile { !it.startsWith("@@") }.filter { it.isNotEmpty() && !it.startsWith("\\") }.map(::line)

  /** Rows of one project from diffs.json, oldest first; [clean] is the displayed-text gate. */
  fun recorded(json: String,project: String,clean: (String)->String={ it }): List<Row> {
    parses.incrementAndGet()
    val a=runCatching { JSONObject(json).optJSONArray(project) }.getOrNull() ?: return emptyList()
    return rows(a,project,clean)
  }
  /** #1385: every project's rows from one parse of diffs.json, gated once; projects without rows are left out. */
  fun recordedAll(json: String,clean: (String)->String={ it }): Map<String,List<Row>> {
    parses.incrementAndGet()
    val o=runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
    return o.keys().asSequence().toList().mapNotNull { p -> o.optJSONArray(p)?.takeIf { it.length()>0 }?.let { p to rows(it,p,clean) } }.toMap()
  }
  private fun rows(a: JSONArray,project: String,clean: (String)->String): List<Row> =
    (0 until a.length()).map { a.getJSONObject(it) }.map { r ->
      val ls=r.getJSONArray("lines").let { l -> (0 until l.length()).map { line(clean(l.getString(it))) } }
      Row(project,clean(r.getString("path")),r.getString("sha"),clean(r.optString("subject")),
        runCatching { java.time.OffsetDateTime.parse(r.getString("time")).toInstant().toEpochMilli() }.getOrDefault(0L),
        r.optInt("added"),r.optInt("removed"),ls,part=r.optString("part"))
    }.sortedBy { it.timeMs }
  /** Projects in diffs.json that have at least one row. */
  fun projects(json: String): List<String> = runCatching { JSONObject(json).let { o -> o.keys().asSequence()
    .filter { (o.optJSONArray(it)?.length() ?: 0)>0 }.toList() } }.getOrDefault(emptyList())

  /**
   * A live host event (PROTOCOL.md 5 / 7a): `file_written {path, part_id?}`. The host does not send a diff today;
   * when it carries `diff` (unified text) the row has it, otherwise the row has the file name and no lines.
   */
  fun host(e: JSONObject,tMs: Long,clean: (String)->String={ it }): Row? {
    val data=e.optJSONObject("data") ?: e
    if((e.optString("type").ifBlank { e.optString("kind") })!="file_written") return null
    val path=data.optString("path").ifBlank { return null }
    val lines=data.optString("diff").let { if(it.isBlank()) emptyList() else parse(clean(it)) }
    return Row(e.optString("project_id",data.optString("project_id")),clean(path),data.optString("commit"),
      clean(data.optString("title")),tMs,lines.count { it.kind==Kind.ADD },lines.count { it.kind==Kind.DEL },lines,
      data.optInt("slot",0).takeIf { it>0 },data.optString("part_id"))
  }

  /** Newest first, one row per file (its latest change); [module] keeps files under that folder or part only. */
  fun files(rows: List<Row>,module: String?=null): List<Row> =
    rows.filter { module.isNullOrBlank() || it.path.startsWith(module.trimEnd('/')+"/") || it.path==module || it.part==module }
      .sortedByDescending { it.timeMs }.distinctBy { it.path }

  /** Header text: "Writing now" only for a file a worker is writing at this moment, else the change's short subject. */
  fun header(row: Row,writing: Boolean): String =
    if(writing && row.worker!=null) "Writing now" else row.subject.replace(Regex("^\\[t\\d+\\]\\s*"),"").let { s ->
      if(s.length<=48) s else s.take(47).substringBeforeLast(' ')+"…" }

  fun toJson(rows: List<Row>)=JSONArray(rows.map { JSONObject().put("path",it.path).put("sha",it.sha).put("added",it.added).put("removed",it.removed) })
}
