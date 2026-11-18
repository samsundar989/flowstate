package com.overhand.app.hostlink.project

import org.json.JSONObject
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * #977: the committed app's files as the Preview window's WebView asks for them. The page runs at the fake origin
 * [ORIGIN]; a request for `ORIGIN + <plain relative path>` is answered with that file **at the shown commit**, fetched
 * with `get_file` and checked against the sha256 its `file_chunk` names ([PreviewFetch]), then kept per commit. Anything
 * else (another origin, `file:`, `data:`, a query, `..`, a path the host refuses, a wrong sha, a 5 s wait) is a 403.
 * No Android in it. [serve] blocks (the WebView calls it on a background thread); [onFrame] runs on the link's thread.
 */
class PreviewFiles(val projectId: String,private val request: (JSONObject) -> Unit,
  private val reqIds: () -> String={ "pf_"+java.util.UUID.randomUUID().toString().replace("-","").take(12) },
  private val timeoutMs: Long=5000) {
  class Served(val status: Int,val mime: String,val bytes: ByteArray,val why: String="")
  private class Pending(val commit: String,val fetch: PreviewFetch,val done: CompletableFuture<PreviewFetch.Result>)

  private val lock=Any()
  var commit=""; private set
  private val cache=mutableMapOf<String,ByteArray>()
  private val pendingByPath=mutableMapOf<String,Pending>()
  private val pendingByReq=mutableMapOf<String,Pending>()
  /** Every answer given, oldest first: path, status, bytes, sha256 (or why), for the report's served-file table. */
  val served=java.util.concurrent.CopyOnWriteArrayList<JSONObject>()

  /** The commit now on screen and the files already checked for it. A different commit empties the cache. */
  fun show(commit: String,files: Map<String,ByteArray>) = synchronized(lock) {
    if(commit!=this.commit) {
      this.commit=commit;cache.clear()
      pendingByPath.values.forEach { it.done.complete(PreviewFetch.Result.Refused("a newer commit is shown")) }
      pendingByPath.clear();pendingByReq.clear()
    }
    cache.putAll(files)
  }
  fun cached(): Set<String> = synchronized(lock) { cache.keys.toSet() }

  /** A `file_chunk` / `error` frame. Returns true if it was one of ours. */
  fun onFrame(f: JSONObject): Boolean {
    val (p,r)=synchronized(lock) {
      val p=pendingByReq[f.optString("req_id")] ?: return false
      val r=p.fetch.onFrame(f) ?: return true
      pendingByReq.remove(p.fetch.reqId);pendingByPath.remove(p.fetch.path)
      if(r is PreviewFetch.Result.Ok && p.commit==commit) cache[p.fetch.path]=r.bytes
      p to r
    }
    p.done.complete(r);return true
  }

  /** The answer for [url] (blocks up to the timeout when the file has to be fetched). */
  fun serve(url: String): Served {
    val path=pathOf(url) ?: return refuse(url,"not a plain path at the preview origin")
    val p: Pending
    synchronized(lock) {
      if(commit.isEmpty()) return refuse(path,"no commit shown")
      cache[path]?.let { return ok(path,it) }
      p=pendingByPath[path] ?: Pending(commit,PreviewFetch(projectId,commit,path,"",-1,reqIds(),request),CompletableFuture()).also {
        pendingByPath[path]=it;pendingByReq[it.fetch.reqId]=it;it.fetch.start()
      }
    }
    val r=try { p.done.get(timeoutMs,TimeUnit.MILLISECONDS) } catch(e: Exception) {
      synchronized(lock) { if(pendingByPath[path]===p) { pendingByPath.remove(path);pendingByReq.remove(p.fetch.reqId) } }
      return refuse(path,"no answer in ${timeoutMs/1000} s")
    }
    return when(r) {
      is PreviewFetch.Result.Ok -> if(p.commit==synchronized(lock) { commit }) ok(path,r.bytes) else refuse(path,"a newer commit is shown")
      is PreviewFetch.Result.Refused -> refuse(path,r.reason)
    }
  }
  private fun ok(path: String,b: ByteArray)=Served(200,mimeOf(path),b).also {
    served.add(JSONObject().put("path",path).put("status",200).put("bytes",b.size).put("sha256",PreviewFetch.sha256Hex(b)).put("mime",it.mime))
  }
  private fun refuse(what: String,why: String)=Served(403,"text/plain",ByteArray(0),why).also {
    served.add(JSONObject().put("path",what).put("status",403).put("why",why))
  }

  companion object {
    /** The page's origin: `.invalid` never resolves, and every request to it is answered here. */
    const val ORIGIN="https://preview.invalid/"
    /** The project-relative path [url] names at [ORIGIN], or null for anything else. */
    fun pathOf(url: String): String? {
      if(!url.startsWith(ORIGIN) || url.contains('?') || url.contains('#')) return null
      val raw=url.substring(ORIGIN.length)
      if(raw.split('/').any { it=="." || it==".." || it.equals("%2e",true) || it.equals("%2e%2e",true) || it.equals(".%2e",true) || it.equals("%2e.",true) }) return null
      val u=try { URI(url) } catch(e: Exception) { return null }
      if(u.scheme!="https" || u.host!="preview.invalid" || u.port!=-1 || u.userInfo!=null) return null
      val p=(u.path ?: return null).removePrefix("/")
      return p.takeIf { PreviewModel.isLocal(it) }
    }
    private val MIME=mapOf("html" to "text/html","htm" to "text/html","css" to "text/css","js" to "text/javascript","mjs" to "text/javascript",
      "json" to "application/json","svg" to "image/svg+xml","png" to "image/png","jpg" to "image/jpeg","jpeg" to "image/jpeg",
      "gif" to "image/gif","webp" to "image/webp","ico" to "image/x-icon","txt" to "text/plain","wasm" to "application/wasm",
      "woff" to "font/woff","woff2" to "font/woff2","ttf" to "font/ttf","mp3" to "audio/mpeg","wav" to "audio/wav","ogg" to "audio/ogg")
    fun mimeOf(path: String)=MIME[path.substringAfterLast('/').substringAfterLast('.',"").lowercase()] ?: "application/octet-stream"
    fun isText(mime: String)=mime.startsWith("text/") || mime=="application/json" || mime=="image/svg+xml"
  }
}
