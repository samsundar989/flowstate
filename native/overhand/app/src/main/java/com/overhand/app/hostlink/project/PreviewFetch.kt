package com.overhand.app.hostlink.project

import com.overhand.app.hostlink.Frames
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

/**
 * #973: fetches one committed file (the preview's `index.html`) over the host link in `get_file` / `file_chunk` steps
 * and checks it against the sha256 the `preview` event announced. No Android in it. [request] sends a C2H frame.
 * A chunk that does not continue where the last one ended, a total over [MAX_BYTES] or different from the
 * announced size, an `error` for this request, or a final sha256 that differs: the file is refused, never shown.
 */
class PreviewFetch(val projectId: String,val commit: String,val path: String,val sha256: String,val bytes: Long,
  val reqId: String,private val request: (JSONObject) -> Unit) {
  sealed interface Result {
    data class Ok(val text: String,val sha256: String,val bytes: ByteArray=text.toByteArray(Charsets.UTF_8)): Result
    data class Refused(val reason: String): Result
  }
  private val buf=java.io.ByteArrayOutputStream()
  /** The whole file's sha256 to check against: [sha256], or for an asset ("" given) the one its first chunk names. */
  private var expected=sha256
  var result: Result?=null; private set
  val received get()=buf.size().toLong()

  fun start() { request(Frames.getFile(reqId,projectId,commit,path,0)) }

  /** A `file_chunk` or `error` frame for [reqId]. Returns the result once there is one, else null (next chunk asked). */
  fun onFrame(f: JSONObject): Result? {
    if(result!=null || f.optString("req_id")!=reqId) return result
    if(f.optString("type")=="error") return refuse("the computer refused it (${f.optString("code").ifBlank { "error" }})")
    if(f.optString("type")!="file_chunk") return null
    val total=f.optLong("total",-1);val offset=f.optLong("offset",-1)
    if(f.optString("path")!=path) return refuse("a different file came back")
    if(expected.isEmpty()) expected=f.optString("sha256")
    if(expected.isEmpty() || !f.optString("sha256").equals(expected,true)) return refuse("the file changed on the computer")
    if(total<0 || total>MAX_BYTES) return refuse("the file is larger than ${MAX_BYTES/1024} KB")
    if(bytes>=0 && total!=bytes) return refuse("the size does not match the commit")
    if(offset!=received) return refuse("a piece arrived out of order")
    val data=f.optString("data")
    if(data.length>MAX_CHUNK_CHARS) return refuse("a piece was too large")
    val part=try { Base64.getDecoder().decode(data) } catch(e: IllegalArgumentException) { return refuse("a piece was damaged") }
    if(part.isEmpty() && received<total) return refuse("a piece was empty")
    buf.write(part)
    if(received>total) return refuse("more arrived than the file holds")
    if(received<total) { request(Frames.getFile(reqId,projectId,commit,path,received));return null }
    val all=buf.toByteArray();val got=sha256Hex(all)
    if(!got.equals(expected,true)) return refuse("the check did not match (got ${got.take(8)})")
    return Result.Ok(String(all,Charsets.UTF_8),got,all).also { result=it }
  }
  private fun refuse(why: String)=Result.Refused(why).also { result=it }

  companion object {
    const val MAX_BYTES=512L*1024
    const val MAX_CHUNK_CHARS=6000
    fun sha256Hex(b: ByteArray)=MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
  }
}

/**
 * #973: what the Preview window shows. A `preview` event for a new commit starts a fetch; the window keeps showing
 * the last good app while the next one loads, and swaps it in (a reload) only once its sha256 checks out.
 */
class PreviewModel(val projectId: String,private val request: (JSONObject) -> Unit,
  private val reqIds: () -> String={ "f_"+java.util.UUID.randomUUID().toString().replace("-","").take(12) }) {
  sealed interface State {
    val key: String
    object Idle: State { override val key="idle" }
    data class Loading(val commit: String,val received: Long,val total: Long): State { override val key="loading" }
    data class Ready(val commit: String,val html: String,val sha256: String,
      /** #977: every file fetched and checked for this commit (the page, its own files, the plan's), for [PreviewFiles]. */
      val files: Map<String,ByteArray> = emptyMap()): State { override val key="ready" }
    data class Refused(val commit: String,val reason: String): State { override val key="refused" }
  }
  var state: State=State.Idle; private set
  /** The app on screen (the last one that checked out), and how many times it was (re)loaded. */
  var shown: State.Ready?=null; private set
  var loads=0; private set
  /** "Commit abc1234: Add a reset button": the receipt line under the panel. */
  var receipt=""; private set
  private var fetch: PreviewFetch?=null
  /** While the page's own files (style.css, app.js) are fetched at the same commit: the page, and what is left. */
  private var page: PreviewFetch.Result.Ok?=null
  private val assets=ArrayDeque<String>()
  private val got=mutableMapOf<String,String>()
  private val gotBytes=mutableMapOf<String,ByteArray>()
  /** #977: the files the project's plan names (`plan.parts[].files`), prefetched with each commit so the first render does not wait. */
  var planFiles: List<String> = emptyList(); private set
  /** The last agent message per task: the receipt line, since `preview`'s own title is only "Preview ready". */
  private val lastMessage=mutableMapOf<String,String>()

  /** A host event. Returns true if what the window shows changed. */
  fun onEvent(e: JSONObject): Boolean {
    if(e.optString("project_id")!=projectId) return false
    if(e.optString("kind")=="message") { e.optString("title").takeIf { it.isNotBlank() }?.let { lastMessage[e.optString("task_id")]=it };return false }
    if(e.optString("kind")=="plan") {
      val parts=e.optJSONArray("parts")
      planFiles=(0 until (parts?.length() ?: 0)).flatMap { i -> parts!!.optJSONObject(i)?.optJSONArray("files")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty() }
        .filter(::isLocal).distinct()
      return false
    }
    if(e.optString("kind")!="preview") return false
    val commit=e.optString("commit");if(commit.isBlank()) return false
    if(commit==shown?.commit || commit==fetch?.commit) return false
    receipt=receiptFor(commit,e.optString("message").ifBlank { e.optString("summary") }.ifBlank { lastMessage[e.optString("task_id")].orEmpty() }
      .ifBlank { e.optString("title") })
    page=null;assets.clear();got.clear();gotBytes.clear()
    val f=PreviewFetch(projectId,commit,e.optString("path").ifBlank { "index.html" },e.optString("sha256"),e.optLong("bytes",-1),reqIds(),request)
    fetch=f;state=State.Loading(commit,0,f.bytes);f.start();return true
  }
  /** A non-event frame from the host (`file_chunk`, `error`). Returns true if what the window shows changed. */
  fun onFrame(f: JSONObject): Boolean {
    val cur=fetch ?: return false
    if(f.optString("req_id")!=cur.reqId) return false
    when(val r=cur.onFrame(f)) {
      null -> { if(page==null) state=State.Loading(cur.commit,cur.received,cur.bytes);return page==null }
      is PreviewFetch.Result.Ok -> {
        if(page==null) { page=r;gotBytes[cur.path]=r.bytes;assets.addAll((localAssets(r.text)+planFiles).distinct()-cur.path) } else { got[cur.path]=r.text;gotBytes[cur.path]=r.bytes }
        next(cur.commit)
      }
      is PreviewFetch.Result.Refused -> {
        // a missing asset only leaves the page without it; anything else about a file refuses the version
        if(page!=null && r.reason.startsWith("the computer refused it")) next(cur.commit)
        else { state=State.Refused(cur.commit,r.reason);fetch=null;page=null;assets.clear() }
      }
    }
    return true
  }
  private fun next(commit: String) {
    val p=page!!
    val a=assets.removeFirstOrNull()
    if(a!=null) { fetch=PreviewFetch(projectId,commit,a,"",-1,reqIds(),request).also { it.start() };return }
    val ok=State.Ready(commit,inline(p.text,got),p.sha256,gotBytes.toMap());shown=ok;state=ok;loads++;fetch=null;page=null;got.clear();gotBytes.clear()
  }

  companion object {
    private val LINK=Regex("""<link\b[^>]*\brel\s*=\s*["']?stylesheet["']?[^>]*>""",RegexOption.IGNORE_CASE)
    private val SCRIPT=Regex("""<script\b([^>]*)\bsrc\s*=\s*["']([^"'>\s]+)["']([^>]*)>\s*</script>""",RegexOption.IGNORE_CASE)
    private val HREF=Regex("""\bhref\s*=\s*["']([^"'>\s]+)["']""",RegexOption.IGNORE_CASE)
    private val CLOSE=Regex("""</(script|style)""",RegexOption.IGNORE_CASE)
    /** A plain relative path inside the project (what get_file serves): no scheme, no "..", no leading "/". */
    fun isLocal(p: String)=p.isNotBlank() && p.length<=200 && !p.contains(':') && !p.contains('\\') && !p.contains('?') &&
      !p.contains('#') && p.split('/').none { it=="" || it=="." || it==".." }
    /** The page's own stylesheets and scripts, in order, once each. */
    fun localAssets(html: String): List<String> =
      (LINK.findAll(html).mapNotNull { HREF.find(it.value)?.groupValues?.get(1) } + SCRIPT.findAll(html).map { it.groupValues[2] })
        .filter(::isLocal).distinct().toList()
    /**
     * The page with its fetched files inlined, since it loads with no base URL and no network: a stylesheet link becomes
     * a <style>, a script src a <script> with the code. A closing script/style tag inside them is escaped.
     */
    fun inline(html: String,files: Map<String,String>): String {
      fun esc(t: String)=CLOSE.replace(t) { "<\\/"+it.groupValues[1] }
      val out=LINK.replace(html) { m -> HREF.find(m.value)?.groupValues?.get(1)?.let(files::get)?.let { "<style>\n"+esc(it)+"\n</style>" } ?: m.value }
      return SCRIPT.replace(out) { m -> files[m.groupValues[2]]?.let { "<script"+m.groupValues[1]+m.groupValues[3]+">\n"+esc(it)+"\n</script>" } ?: m.value }
    }
    fun receiptFor(commit: String,message: String): String {
      val m=ProjectTimeline.short(message.lineSequence().firstOrNull().orEmpty(),72)
      return "Commit ${commit.take(7)}"+(if(m.isNotBlank()) ": $m" else "")
    }
    /** The status line under the panel for [s]. */
    fun status(s: State)=when(s) {
      State.Idle -> "Waiting for the first commit"
      is State.Loading -> if(s.total>0) "Loading the new version… ${s.received*100/s.total}%" else "Loading the new version…"
      is State.Ready -> "Pinch to play"
      is State.Refused -> "Not shown: ${s.reason}"
    }
  }
}
