package com.overhand.app.hostlink.project

import com.overhand.app.hostlink.Repo
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * #977: the Preview window serves the whole committed app. The commit is #972's 3-file replay build (index.html,
 * style.css, app.js from the `file_change` items of host/test/fixtures/streams/codex_web_plan.resume.jsonl) committed into
 * a fresh git repo; the bytes the fake host serves are `git show <commit>:<path>`, as the real host reads them.
 */
class PreviewFilesTest {
  companion object {
    lateinit var commit: String
    val committed=mutableMapOf<Pair<String,String>,ByteArray>()
    private lateinit var repo: File
    /** Every answer the tests gave, for the report's served-file table. */
    private val table=JSONArray()

    @BeforeClass @JvmStatic fun commitTheFixture() {
      val fixture="host/test/fixtures/streams/codex_web_plan.resume.jsonl"
      val written=File(Repo.root,fixture).readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
        .mapNotNull { it.optJSONObject("item")?.takeIf { i -> i.optString("type")=="file_change" }?.optJSONArray("changes") }
        .flatMap { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        .associate { it.getString("path").replace('\\','/').substringAfterLast('/') to it.getString("_content").toByteArray(Charsets.UTF_8) }
      assertEquals(setOf("index.html","style.css","app.js"),written.keys)
      repo=Files.createTempDirectory("preview-files-repo").toFile()
      written.forEach { (n,b) -> File(repo,n).writeBytes(b) }
      git("init","-q");git("add",".");git("commit","-q","-m","Build the tap counter")
      commit=String(git("rev-parse","HEAD")).trim()
      written.keys.forEach { committed[commit to it]=git("show","$commit:$it") }
    }
    private fun git(vararg a: String): ByteArray {
      val p=ProcessBuilder(listOf("git","-c","user.name=t","-c","user.email=t@t","-c","core.autocrlf=false")+a).directory(repo).start()
      val out=p.inputStream.readBytes();check(p.waitFor()==0) { "git ${a.toList()} failed: "+p.errorStream.bufferedReader().readText() }
      return out
    }
    @AfterClass @JvmStatic fun writeTable() {
      File(Repo.out,"preview_files_served.json").writeText(JSONObject().put("commit",commit).put("served",table).toString(1))
      repo.deleteRecursively()
    }
  }

  /** The fake host answers each get_file at once, on the caller's thread (as a fast link would, just sooner). */
  private fun files(link: PreviewFetchTest.FakeLink=PreviewFetchTest.FakeLink(committed.toMutableMap()),timeoutMs: Long=5000): Pair<PreviewFiles,PreviewFetchTest.FakeLink> {
    lateinit var pf: PreviewFiles
    pf=PreviewFiles("p_web",{ f -> link.request(f);while(link.replies.isNotEmpty()) pf.onFrame(link.replies.removeFirst()) },timeoutMs=timeoutMs)
    return pf to link
  }
  private fun record(test: String,pf: PreviewFiles) { pf.served.forEach { table.put(JSONObject(it.toString()).put("test",test)) } }

  @Test fun styleAndScriptComeBackWithTheirExactBytesAndType() {
    val (pf,link)=files()
    pf.show(commit,emptyMap())
    val css=pf.serve(PreviewFiles.ORIGIN+"style.css");val js=pf.serve(PreviewFiles.ORIGIN+"app.js");val page=pf.serve(PreviewFiles.ORIGIN+"index.html")
    assertEquals(200,css.status);assertEquals("text/css",css.mime);assertTrue(committed[commit to "style.css"]!!.contentEquals(css.bytes))
    assertEquals(200,js.status);assertEquals("text/javascript",js.mime);assertTrue(committed[commit to "app.js"]!!.contentEquals(js.bytes))
    assertEquals(200,page.status);assertEquals("text/html",page.mime)
    assertTrue(link.asked.all { it.getString("commit")==commit && it.getString("project_id")=="p_web" })
    // a second ask is answered from the commit's cache, not the link
    val n=link.asked.size;assertEquals(200,pf.serve(PreviewFiles.ORIGIN+"style.css").status);assertEquals(n,link.asked.size)
    record("exact bytes",pf)
  }

  @Test fun otherOriginsDotDotAndPathsNotAtTheCommitAre403() {
    val (pf,link)=files()
    pf.show(commit,emptyMap())
    for(u in listOf("https://example.com/app.js","http://preview.invalid/app.js","https://preview.invalid:444/app.js","https://u@preview.invalid/app.js",
        "file:///sdcard/app.js","data:text/javascript,1","https://preview.invalid/../x","https://preview.invalid/a/../app.js",
        "https://preview.invalid/%2e%2e/x","https://preview.invalid/app.js?v=1","https://preview.invalid/app.js#x","https://preview.invalid/",
        "https://preview.invalid//etc/passwd"))
      assertEquals(u,403,pf.serve(u).status)
    assertEquals("nothing above reached the link",0,link.asked.size)
    val missing=pf.serve(PreviewFiles.ORIGIN+"x.png")
    assertEquals(403,missing.status);assertTrue(missing.why,missing.why.contains("not_found"))
    record("refusals",pf)
  }

  @Test fun aShaMismatchIsRefused() {
    val (pf,link)=files()
    link.corrupt={ b -> b.copyOf().also { it[0]=(it[0]+1).toByte() } }
    pf.show(commit,emptyMap())
    val r=pf.serve(PreviewFiles.ORIGIN+"style.css")
    assertEquals(403,r.status);assertTrue(r.why,r.why.startsWith("the check did not match"))
    assertTrue("a refused file is not cached",pf.cached().isEmpty())
    record("sha mismatch",pf)
  }

  @Test fun aNewCommitClearsTheCache() {
    val c2="ab".repeat(20)
    val v2="body{color:red}".toByteArray()
    val (pf,link)=files(PreviewFetchTest.FakeLink((committed+((c2 to "style.css") to v2)).toMutableMap()))
    pf.show(commit,emptyMap())
    assertTrue(committed[commit to "style.css"]!!.contentEquals(pf.serve(PreviewFiles.ORIGIN+"style.css").bytes))
    pf.show(c2,emptyMap())
    assertEquals(emptySet<String>(),pf.cached())
    val r=pf.serve(PreviewFiles.ORIGIN+"style.css")
    assertEquals(200,r.status);assertTrue(v2.contentEquals(r.bytes))
    assertEquals(c2,link.asked.last().getString("commit"))
    assertEquals("app.js is not at the new commit",403,pf.serve(PreviewFiles.ORIGIN+"app.js").status)
    record("new commit",pf)
  }

  /** The WebView asks on its own thread and the link answers on another; no answer within the timeout is a 403. */
  @Test fun theWaitIsOnTheCallersThreadAndBounded() {
    val link=PreviewFetchTest.FakeLink(committed.toMutableMap())
    val pool=Executors.newSingleThreadExecutor()
    lateinit var pf: PreviewFiles
    pf=PreviewFiles("p_web",{ f -> synchronized(link) { link.request(f) }
      pool.submit { Thread.sleep(20);while(true) { val r=synchronized(link) { link.replies.removeFirstOrNull() } ?: break;pf.onFrame(r) } } })
    pf.show(commit,emptyMap())
    val r=pf.serve(PreviewFiles.ORIGIN+"app.js")
    assertEquals(200,r.status);assertTrue(committed[commit to "app.js"]!!.contentEquals(r.bytes))
    pool.shutdown();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS))
    // not `.apply { show(commit, ...) }`: inside apply, `commit` is PreviewFiles.commit (still "")
    val silent=PreviewFiles("p_web",{ },timeoutMs=300);silent.show(commit,emptyMap())
    val t0=System.nanoTime();val s=silent.serve(PreviewFiles.ORIGIN+"style.css");val ms=(System.nanoTime()-t0)/1_000_000
    assertEquals(403,s.status);assertTrue(s.why,s.why.startsWith("no answer"));assertTrue("$ms ms",ms in 250..3000)
  }

  /** The preview prefetches the page's own files and the plan's, so the first render has them all checked. */
  @Test fun thePreviewPrefetchesThePlansFilesForTheFirstRender() {
    val link=PreviewFetchTest.FakeLink(committed.toMutableMap())
    val m=PreviewModel("p_web",link::request)
    m.onEvent(JSONObject().put("kind","plan").put("project_id","p_web").put("parts",JSONArray()
      .put(JSONObject().put("id","ui").put("files",JSONArray().put("index.html").put("style.css")))
      .put(JSONObject().put("id","logic").put("files",JSONArray().put("app.js").put("../bad").put("notes.md")))))
    assertEquals(listOf("index.html","style.css","app.js","notes.md"),m.planFiles)
    val index=committed[commit to "index.html"]!!
    m.onEvent(JSONObject().put("kind","preview").put("project_id","p_web").put("commit",commit).put("path","index.html")
      .put("bytes",index.size).put("sha256",PreviewFetch.sha256Hex(index)))
    while(link.replies.isNotEmpty()) m.onFrame(link.replies.removeFirst())
    val ready=m.state as PreviewModel.State.Ready
    assertEquals(setOf("index.html","style.css","app.js"),ready.files.keys)
    ready.files.forEach { (p,b) -> assertTrue(p,committed[commit to p]!!.contentEquals(b)) }
    // seeded into the server: the first render needs no fetch at all
    val pf=PreviewFiles("p_web",{ error("prefetched: nothing is fetched") }).apply { show(ready.commit,ready.files) }
    for(p in listOf("index.html","style.css","app.js")) assertEquals(p,200,pf.serve(PreviewFiles.ORIGIN+p).status)
    assertNull(ready.files["notes.md"])
    record("prefetched",pf)
  }
}
