package com.overhand.app.hostlink

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Repo paths, found by walking up from the Gradle module directory. */
object Repo {
  val root: File by lazy {
    generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
      .firstOrNull { File(it,"host/package.json").isFile && File(it,"host/test/fixtures/protocol").isDirectory }
      ?: error("host/ not found above ${System.getProperty("user.dir")}")
  }
  val fixtures get()=File(root,"host/test/fixtures/protocol")
  val harness get()=File(root,"native/overhand/app/src/test/java/com/overhand/app/hostlink/host_harness.ts")
  /** Where measured numbers go: HOSTLINK_OUT (absolute, set by the queued run) or the module's build dir. */
  val out: File get()=(System.getenv("HOSTLINK_OUT")?.let(::File) ?: File(root,"native/overhand/app/build/hostlink")).apply { mkdirs() }
}

/**
 * The real host (`host/src/server.ts`) in a Node child process, on loopback, with a test clock, driven over
 * stdin by host_harness.ts. Every test gets a fresh one (fresh certificate, log and device list).
 */
class RealHost(private val harness: File=Repo.harness, private val keepDir: File?=null): AutoCloseable {
  private val dir=keepDir ?: Files.createTempDirectory("hostlink-").toFile()
  private val proc: Process
  private val lines=LinkedBlockingQueue<JSONObject>()
  val stderr=StringBuffer()
  lateinit var info: JSONObject
  init {
    val hostDir=File(Repo.root,"host")
    if(!File(hostDir,"node_modules/ws").isDirectory) {
      val npm=if(isWindows) listOf("cmd","/c","npm","ci","--no-audit","--no-fund") else listOf("npm","ci","--no-audit","--no-fund")
      check(ProcessBuilder(npm).directory(hostDir).inheritIO().start().waitFor()==0) { "npm ci failed in host/" }
    }
    proc=ProcessBuilder("node","--experimental-strip-types","--no-warnings",harness.absolutePath,Repo.root.absolutePath,dir.absolutePath)
      .directory(hostDir).start()
    Thread({ proc.inputStream.bufferedReader().forEachLine { l -> runCatching { lines.put(JSONObject(l)) } } },"host-out").apply { isDaemon=true }.start()
    Thread({ proc.errorStream.bufferedReader().forEachLine { stderr.append(it).append('\n') } },"host-err").apply { isDaemon=true }.start()
    info=next(30_000)
    check(info.optBoolean("ready")) { "host did not start: $info $stderr" }
  }
  private fun next(ms: Long): JSONObject=lines.poll(ms,TimeUnit.MILLISECONDS) ?: error("host harness silent; stderr: $stderr")
  @Synchronized fun cmd(c: JSONObject,ms: Long=30_000): JSONObject {
    proc.outputStream.write((c.toString()+"\n").toByteArray());proc.outputStream.flush()
    return next(ms).also { r -> check(!r.has("error")) { "harness: ${r.getString("error")}" };if(r.has("fp")) info=r }
  }
  fun cmd(name: String,vararg kv: Pair<String,Any>)=cmd(JSONObject().put("cmd",name).apply { kv.forEach { (k,v) -> put(k,v) } })
  val port get()=info.getInt("port")
  val fp get()=info.getString("fp")
  /** The address the host listens on: HOSTLINK_BIND (its Wi-Fi address) or loopback. */
  val address get()=info.optString("address","127.0.0.1")
  val endpoint get()=Endpoint(address,port,fp,info.getString("name"))
  val qr get()="flowstate://pair?v=1&h=$address&p=$port&fp=$fp&t=${info.getString("token")}&n=${info.getString("name")}"
  fun stats()=cmd("stats")
  override fun close() {
    runCatching { cmd(JSONObject().put("cmd","quit"),5_000) }
    proc.destroy();if(!proc.waitFor(5,TimeUnit.SECONDS)) proc.destroyForcibly()
    if(keepDir==null) dir.deleteRecursively()
  }
  companion object { val isWindows=System.getProperty("os.name").startsWith("Windows") }
}
