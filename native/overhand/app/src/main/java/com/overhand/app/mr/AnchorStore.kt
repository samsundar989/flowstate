package com.overhand.app.mr

import com.meta.spatial.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A Horizon OS scene anchor (Space Setup persists it; its UUID survives app restart and recentre). */
data class AnchorRef(val uuid: String, val pose: Pose)

/**
 * Lifecycle file the worn session pulls (`files/anchor_log.jsonl`, plus the tail inside mr_state.json).
 * Events: created, persisted, loaded, relocalized, failed. [sink] mirrors lines to logcat.
 */
class AnchorLog(private val file: File?, private val clock: () -> Long = System::currentTimeMillis,
  private val sink: (String) -> Unit = {}) {
  private val recent=ArrayDeque<JSONObject>()
  fun event(event: String, key: String, uuid: String?=null, detail: String="") {
    val line=JSONObject().put("t",clock()).put("event",event).put("key",key)
      .put("uuid",uuid ?: JSONObject.NULL).put("detail",detail)
    synchronized(recent) { recent.addLast(line);while(recent.size>64) recent.removeFirst() }
    runCatching { file?.appendText(line.toString()+"\n") }
    sink("anchor_lifecycle $line")
  }
  fun tail()=synchronized(recent) { JSONArray(recent.toList()) }
}

/**
 * Desk and station placements bound to a persisted scene anchor: the anchor UUID and the
 * anchor-relative pose are kept in app storage, with the last world pose as the fallback.
 * Pure JSON + pose math; callers own threading (ECS thread only).
 */
class AnchorStore(private val file: File, val log: AnchorLog) {
  data class Record(val anchor: String?, val local: List<Float>?, val world: List<Float>)
  data class Resolved(val pose: Pose, val anchored: Boolean, val reason: String)
  private val records=linkedMapOf<String,Record>()
  private val announced=mutableMapOf<String,String>() // key -> last resolve outcome, to log transitions once

  init {
    if(file.exists()) runCatching {
      val root=JSONObject(file.readText())
      root.keys().forEach { k -> val o=root.getJSONObject(k)
        val world=floats(o.optJSONArray("world")) ?: return@forEach
        records[k]=Record(o.optString("anchor").ifEmpty { null },floats(o.optJSONArray("local")),world)
      }
      records.forEach { (k,r) -> log.event("loaded",k,r.anchor,if(r.anchor==null) "saved transform only" else "anchor-relative pose") }
    }.onFailure { log.event("failed","store",null,"unreadable ${file.name}: ${it.javaClass.simpleName}") }
    log.event("loaded","store",null,"launch; ${records.size} saved placements in ${file.name}")
  }

  fun record(key: String)=records[key]
  fun keys()=records.keys.toList()

  /** Save [world]; when [anchor] is known, bind it as an anchor-relative pose. */
  fun persist(key: String, world: Pose, anchor: AnchorRef?) {
    val old=records[key]
    val next=if(anchor!=null) Record(anchor.uuid,encode(anchor.pose.inverse()*world),encode(world))
      else Record(old?.anchor,old?.local,encode(world)) // keep a prior binding; only the fallback moves
    // Tracking jitter must not rewrite storage every frame: 1 mm / ~0.1 degree tolerance.
    if(old!=null && old.anchor==next.anchor && close(old.local,next.local) && (anchor!=null || close(old.world,next.world))) return
    if(anchor!=null && old?.anchor!=anchor.uuid) log.event("created",key,anchor.uuid,"bound to scene anchor")
    records[key]=next
    val written=runCatching { write() }
    if(written.isSuccess) { if(anchor!=null) log.event("persisted",key,anchor.uuid,"local=${next.local}") }
    else log.event("failed",key,anchor?.uuid,"write ${written.exceptionOrNull()?.javaClass?.simpleName}")
  }

  /** Anchored pose when [anchor] is the saved one, else the saved transform (fallback, not anchored). */
  fun resolve(key: String, anchor: AnchorRef?): Resolved? {
    val r=records[key] ?: return null
    val local=r.local?.let(::decode)
    val result=when {
      r.anchor!=null && local!=null && anchor?.uuid==r.anchor -> Resolved(anchor.pose*local,true,
        "relocalized; saved-world drift ${"%.3f".format(java.util.Locale.ROOT,((anchor.pose*local).t-decode(r.world).t).length())} m")
      r.anchor==null -> Resolved(decode(r.world),false,"never anchored")
      anchor==null -> Resolved(decode(r.world),false,"anchor ${r.anchor} not loaded")
      else -> Resolved(decode(r.world),false,"anchor ${r.anchor} not in room (found ${anchor.uuid})")
    }
    if(announced[key]!=result.reason) {
      announced[key]=result.reason
      log.event(if(result.anchored) "relocalized" else "failed",key,r.anchor,
        if(result.anchored) "pose=${encode(result.pose)}" else "fallback to saved transform: ${result.reason}")
    }
    return result
  }

  private fun write() {
    val root=JSONObject()
    records.forEach { (k,r) -> root.put(k,JSONObject().put("anchor",r.anchor ?: JSONObject.NULL)
      .put("local",r.local?.let { JSONArray(it) } ?: JSONObject.NULL).put("world",JSONArray(r.world))) }
    val tmp=File(file.path+".tmp");tmp.writeText(root.toString(2))
    if(!tmp.renameTo(file)) { file.delete();check(tmp.renameTo(file)) }
  }

  companion object {
    fun encode(p: Pose)=listOf(p.t.x,p.t.y,p.t.z,p.q.w,p.q.x,p.q.y,p.q.z)
    fun decode(v: List<Float>)=Pose(Vector3(v[0],v[1],v[2]),Quaternion(v[3],v[4],v[5],v[6]))
    private fun close(a: List<Float>?, b: List<Float>?)=
      if(a==null || b==null) a==b else a.indices.all { kotlin.math.abs(a[it]-b[it])<1e-3f }
    private fun floats(a: JSONArray?): List<Float>? = runCatching {
      requireNotNull(a);require(a.length()==7)
      (0..6).map { a.getDouble(it).toFloat() }.also { v -> require(v.all { it.isFinite() }) }
    }.getOrNull()
  }
}
