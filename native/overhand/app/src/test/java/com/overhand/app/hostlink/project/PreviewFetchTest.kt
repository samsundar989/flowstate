package com.overhand.app.hostlink.project

import com.overhand.app.hostlink.Repo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

/**
 * #973: the preview's committed file comes over the link in `get_file` / `file_chunk` steps and is shown only when its
 * sha256 matches the one the `preview` event announced. [FakeLink] answers like the host (PROTOCOL: data is base64 of
 * at most 6000 chars, offsets in bytes, `total` the file's size, `sha256` the whole file's).
 */
class PreviewFetchTest {
  /** The host's side of get_file, in memory: (commit, path) -> bytes. Records every request it was sent. */
  class FakeLink(val files: MutableMap<Pair<String,String>,ByteArray> =mutableMapOf(),var chunkChars: Int=6000) {
    val asked=mutableListOf<JSONObject>()
    val replies=ArrayDeque<JSONObject>()
    /** Overrides the sha256 the host sends (a file that changed under it). */
    var shaOverride: String?=null
    var corrupt: ((ByteArray) -> ByteArray)?=null
    fun request(f: JSONObject) {
      asked.add(f)
      require(f.getString("type")=="get_file")
      val b=files[f.getString("commit") to f.getString("path")]
        ?: return replies.addLast(JSONObject().put("type","error").put("req_id",f.getString("req_id")).put("code","not_found"))
      val off=f.getLong("offset").toInt();val n=minOf(chunkChars/4*3,b.size-off)
      var part=b.copyOfRange(off,off+n);corrupt?.let { part=it(part) }
      replies.addLast(JSONObject().put("type","file_chunk").put("req_id",f.getString("req_id")).put("path",f.getString("path"))
        .put("sha256",shaOverride ?: PreviewFetch.sha256Hex(b)).put("offset",off).put("total",b.size).put("data",Base64.getEncoder().encodeToString(part)))
    }
    /** Delivers queued replies to [m] until none are left (each may ask for the next chunk). */
    fun pump(m: PreviewModel): Int { var n=0;while(replies.isNotEmpty()) { m.onFrame(replies.removeFirst());n++ };return n }
  }
  private var seq=0L
  private fun preview(commit: String,b: ByteArray,message: String="Add a counter",sha: String=PreviewFetch.sha256Hex(b))=
    JSONObject().put("seq",++seq).put("kind","preview").put("project_id","p_web").put("commit",commit).put("path","index.html")
      .put("bytes",b.size).put("sha256",sha).put("title",message)

  private val page=javaClass.getResourceAsStream("/preview/index.html")!!.readBytes()

  @Test fun chunksFromTheLinkGiveTheExactPageAndItsSha() {
    val link=FakeLink(mutableMapOf(("c1abcdef99" to "index.html") to page))
    val m2=PreviewModel("p_web",link::request) { "f2" }
    assertFalse("not a preview",m2.onEvent(JSONObject().put("kind","file_written").put("project_id","p_web")))
    assertTrue(m2.onEvent(preview("c1abcdef99",page,"Add a counter\n\nlonger body")))
    assertTrue(m2.state is PreviewModel.State.Loading)
    val frames=link.pump(m2)
    val ready=m2.state as PreviewModel.State.Ready
    assertEquals(String(page,Charsets.UTF_8),ready.html)
    assertEquals(PreviewFetch.sha256Hex(page),ready.sha256)
    assertEquals("15 KB in 4500-byte pieces",(page.size+4499)/4500,frames)
    assertEquals(listOf(0L,4500L,9000L,13500L),link.asked.map { it.getLong("offset") })
    assertTrue(link.asked.all { it.getString("project_id")=="p_web" && it.getString("commit")=="c1abcdef99" && it.getString("req_id")=="f2" })
    assertEquals("Commit c1abcde: Add a counter",m2.receipt)
    assertEquals(1,m2.loads)
    assertEquals("Pinch to play",PreviewModel.status(m2.state))
  }

  @Test fun aShaMismatchIsRefusedAndNothingIsShown() {
    val link=FakeLink(mutableMapOf(("c1" to "index.html") to page))
    // the host sends exactly what the event announced, but the bytes were changed on the way
    link.corrupt={ p -> p.copyOf().also { if(it.isNotEmpty()) it[0]=(it[0]+1).toByte() } }
    val m=PreviewModel("p_web",link::request) { "f1" }
    m.onEvent(preview("c1",page));link.pump(m)
    val r=m.state as PreviewModel.State.Refused
    assertTrue(r.reason,r.reason.startsWith("the check did not match"))
    assertEquals(null,m.shown);assertEquals(0,m.loads)
    assertTrue(PreviewModel.status(m.state).startsWith("Not shown: "))
  }

  @Test fun otherRefusals() {
    fun refused(setup: (FakeLink) -> Unit,event: JSONObject=preview("c1",page)): String {
      val link=FakeLink(mutableMapOf(("c1" to "index.html") to page));setup(link)
      val m=PreviewModel("p_web",link::request) { "f1" };m.onEvent(event);link.pump(m)
      return (m.state as PreviewModel.State.Refused).reason
    }
    assertEquals("the file changed on the computer",refused({ it.shaOverride="00".repeat(32) }))
    assertTrue(refused({ it.files.clear() }).startsWith("the computer refused it (not_found)"))
    assertEquals("the size does not match the commit",refused({},preview("c1",page).put("bytes",10)))
    assertEquals("a piece was too large",refused({ it.chunkChars=8000 }))
    val big=ByteArray((PreviewFetch.MAX_BYTES+1).toInt()) { 'a'.code.toByte() }
    assertTrue(refused({ it.files[("c1" to "index.html")]=big },preview("c1",big)).startsWith("the file is larger than 512 KB"))
    // a chunk at the wrong offset (a replay of the first piece)
    val link=FakeLink(mutableMapOf(("c1" to "index.html") to page))
    val m=PreviewModel("p_web",link::request) { "f1" };m.onEvent(preview("c1",page))
    val first=link.replies.removeFirst();m.onFrame(first);link.replies.clear();m.onFrame(first)
    assertEquals("a piece arrived out of order",(m.state as PreviewModel.State.Refused).reason)
  }

  @Test fun eachNewCommitReloadsAndTheSameCommitDoesNot() {
    val v2=String(page,Charsets.UTF_8).replace("<title>Counter</title>","<title>Counter two</title>").toByteArray()
    val link=FakeLink(mutableMapOf(("c1" to "index.html") to page,("c2" to "index.html") to v2))
    var n=0
    val m=PreviewModel("p_web",link::request) { "f"+(++n) }
    m.onEvent(preview("c1",page));link.pump(m)
    assertEquals("c1",m.shown!!.commit);assertEquals(1,m.loads)
    assertFalse("the same commit again is not fetched again",m.onEvent(preview("c1",page)))
    assertEquals(4,link.asked.size)
    assertFalse("another project's preview is not ours",m.onEvent(preview("c9",page).put("project_id","p_other")))
    assertTrue(m.onEvent(preview("c2",v2,"Rename the page")))
    // while the new one loads, the old app stays on screen
    assertTrue(m.state is PreviewModel.State.Loading);assertEquals("c1",m.shown!!.commit)
    link.pump(m)
    assertEquals("c2",m.shown!!.commit);assertEquals(2,m.loads)
    assertTrue((m.state as PreviewModel.State.Ready).html.contains("Counter two"))
    assertEquals("Commit c2: Rename the page",m.receipt)
    // a refused commit leaves the last good app showing
    m.onEvent(preview("c3",page,sha="11".repeat(32)));link.pump(m)
    assertTrue(m.state is PreviewModel.State.Refused);assertEquals("c2",m.shown!!.commit);assertEquals(2,m.loads)
  }

  /**
   * #973 acceptance: a real committed index.html through the chunk path. The page is the one #972's replay agent writes
   * (the `file_change` for index.html in host/test/fixtures/streams/codex_web_plan.resume.jsonl), committed into a fresh
   * git repo as the host does; the bytes are `git show <commit>:index.html` and the sha must match.
   */
  @Test fun aRealCommittedIndexHtmlComesThroughTheChunkPathWithItsSha() {
    val fixture="host/test/fixtures/streams/codex_web_plan.resume.jsonl"
    val written=File(Repo.root,fixture).readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
      .mapNotNull { it.optJSONObject("item")?.takeIf { i -> i.optString("type")=="file_change" }?.optJSONArray("changes") }
      .flatMap { a -> (0 until a.length()).map { a.getJSONObject(it) } }
      .associate { it.getString("path").replace('\\','/').substringAfterLast('/') to it.getString("_content").toByteArray(Charsets.UTF_8) }
    assertEquals(setOf("index.html","style.css","app.js"),written.keys)
    val repo=Files.createTempDirectory("preview-repo").toFile()
    fun git(vararg a: String): ByteArray {
      val p=ProcessBuilder(listOf("git","-c","user.name=t","-c","user.email=t@t","-c","core.autocrlf=false")+a).directory(repo).redirectErrorStream(false).start()
      val out=p.inputStream.readBytes();check(p.waitFor()==0) { "git ${a.toList()} failed: "+p.errorStream.bufferedReader().readText() }
      return out
    }
    git("init","-q");written.forEach { (n,b) -> File(repo,n).writeBytes(b) };git("add",".");git("commit","-q","-m","Build the tap counter")
    val commit=String(git("rev-parse","HEAD")).trim()
    val committed=written.keys.associate { (commit to it) to git("show","$commit:$it") }
    val index=committed[commit to "index.html"]!!
    val link=FakeLink(committed.toMutableMap())
    var n=0
    val m=PreviewModel("p_web",link::request) { "f"+(++n) }
    m.onEvent(JSONObject().put("kind","message").put("project_id","p_web").put("task_id","t1").put("title","Built a tap counter: index.html, style.css and app.js."))
    m.onEvent(preview(commit,index,"Preview ready").put("task_id","t1"));link.pump(m)
    val ready=m.state as PreviewModel.State.Ready
    assertEquals("the sha is index.html's as committed",PreviewFetch.sha256Hex(index),ready.sha256)
    assertEquals(listOf("index.html","style.css","app.js"),link.asked.map { it.getString("path") })
    assertTrue(link.asked.all { it.getString("commit")==commit })
    // the page's own files are inlined (it loads with no base URL and no network), the rest of it is as committed
    val css=String(committed[commit to "style.css"]!!,Charsets.UTF_8);val js=String(committed[commit to "app.js"]!!,Charsets.UTF_8)
    assertTrue(ready.html.contains(css) && ready.html.contains(js))
    assertFalse(ready.html.contains("href=\"style.css\"") || ready.html.contains("src=\"app.js\""))
    assertTrue(ready.html.contains("<button id=\"tap\" type=\"button\">Tap</button>"))
    assertEquals("Commit ${commit.take(7)}: Built a tap counter: index.html, style.css and app.js.",m.receipt)
    // evidence for the report: which file, its sha, and how many pieces
    File(Repo.out,"preview_fixture.json").writeText(JSONObject().put("source","$fixture (file_change items)")
      .put("commit",commit).put("bytes",index.size).put("sha256",ready.sha256).put("requests",link.asked.size).put("inlined_html_chars",ready.html.length).toString(1))
    repo.deleteRecursively()
  }

  @Test fun onlyPlainRelativeAssetsAreFetched() {
    val html="""<link rel="stylesheet" href="a.css"><link rel=stylesheet href='https://x.example/b.css'><link rel="icon" href="i.png">
      <script src="js/app.js"></script><script src="../up.js"></script><script src="/abs.js"></script><script>var x=1</script>"""
    assertEquals(listOf("a.css","js/app.js"),PreviewModel.localAssets(html))
    val out=PreviewModel.inline(html,mapOf("a.css" to "b{}","js/app.js" to "s='</script>'"))
    assertTrue(out.contains("<style>\nb{}\n</style>"));assertTrue(out.contains("s='<\\/script>'"))
    assertTrue("unfetched references stay as they are (WebGuard blocks them)",out.contains("../up.js"))
  }

  companion object {
    /** The Preview window's states for the layout lint: state and receipt line. */
    fun states(): List<Pair<String,Pair<PreviewModel.State,String>>> = listOf(
      "loading" to (PreviewModel.State.Loading("a1b2c3d",9000,15029) to "Commit a1b2c3d: Add a counter with a reset button"),
      "ready" to (PreviewModel.State.Ready("a1b2c3d","<html></html>","00") to "Commit a1b2c3d: Add a counter with a reset button"),
      "refused" to (PreviewModel.State.Refused("e4f5a6b","the check did not match (got 3f9a1c2e)") to "Commit e4f5a6b: Make the buttons larger and add a history line"))
  }
}
