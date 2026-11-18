package com.overhand.app.hands

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Debug builds: one JSON line per hand frame whenever a hand is tracked or the decision card is open (joints,
 * recognizer verdict, aim, tracked count, card gate). A thumbs-up that failed on real hands can then be replayed
 * offline through [ThumbsRecognizer.evaluate] with [parse]. Rotates at [maxBytes] to keep one older file.
 * #800 cost: about 1020 lines and 1.1 MiB a minute at 20 Hz (worn2b), so 32 MiB holds about 28 minutes and the
 * two files together never pass 64 MiB. The JSON is built on the UI thread (HandFrameLogCostTest), written off it.
 */
class HandFrameLog(private val file: File,private val maxBytes: Long=32L shl 20) {
  private val io=Executors.newSingleThreadExecutor { r -> Thread(r,"hand-frames").apply { isDaemon=true } }
  fun write(line: String) { io.execute { runCatching {
    if(file.length()>maxBytes) { val old=File(file.path+".1");old.delete();file.renameTo(old) }
    file.appendText(line+"\n")
  } } }
  fun close() { io.shutdown() }
  companion object {
    private fun point(p: Point)=JSONArray().put(round(p.x)).put(round(p.y)).put(round(p.z))
    private fun round(v: Double)=Math.round(v*10000.0)/10000.0
    /** Joint order matches SdkHandSource: wrist, thumb (4), then index, middle, ring, little (4 each). */
    fun joints(h: HandJoints)=JSONArray().apply {
      put(point(h.wrist));(listOf(h.thumb)+h.fingers).forEach { f -> listOf(f.base,f.pip,f.dip,f.tip).forEach { put(point(it)) } }
    }
    fun record(t: Long,hands: Map<String,Pair<HandJoints?,String>>,aimed: Boolean,aimDot: Double,tracked: Int,gate: String): String {
      val h=JSONObject()
      hands.forEach { (side,v) -> h.put(side,JSONObject().put("why",v.second).put("joints",v.first?.let { joints(it) } ?: JSONObject.NULL)) }
      return JSONObject().put("t",t).put("aimed",aimed).put("aim_dot",round(aimDot)).put("tracked",tracked).put("gate",gate).put("hands",h).toString()
    }
    /** Replay: the joints of each hand in a record line, null where the hand was not tracked. */
    fun parse(line: String): Map<String,HandJoints?> {
      val hands=JSONObject(line).getJSONObject("hands")
      return hands.keys().asSequence().associateWith { side ->
        val a=hands.getJSONObject(side).optJSONArray("joints") ?: return@associateWith null
        val p=(0 until a.length()).map { i -> a.getJSONArray(i).let { Point(it.getDouble(0),it.getDouble(1),it.getDouble(2)) } }
        if(p.size!=21) null else {
          fun finger(i: Int)=Finger(p[i],p[i+1],p[i+2],p[i+3])
          HandJoints(p[0],finger(1),listOf(5,9,13,17).map(::finger))
        }
      }
    }
  }
}
