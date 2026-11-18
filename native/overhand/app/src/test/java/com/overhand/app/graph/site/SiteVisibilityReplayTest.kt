package com.overhand.app.graph.site

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import com.overhand.app.graph.GraphMotion
import com.overhand.app.graph.site.floor.FloorDrawing
import com.overhand.app.graph.site.floor.FloorPlacement
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.*

/** Diagnostic, not pixel evidence. The fake importer takes ONE tick to load, deliberately.
 * Runs real Pump/Frame/Draw/Placement; it does not instantiate Android SiteScene or RecenterFeature.
 * The reference buffer policy is a control, not an implemented fix. No device gap is inferred here.
 */
class SiteVisibilityReplayTest {
  private val out=File(System.getenv("SITE_VIS_OUT") ?: "../../../runs/site/t1263/replay").apply { mkdirs() }
  private data class Eye(val t: Long,val x: Double,val y: Double,val z: Double)
  private data class Counts(val ticks: Int,val swaps: Int,val vanish: Int,val moves: Int,val hides: Int,val back: Int)
  private enum class Fault { SINGLE_BUFFER, REFERENCE, RECENTER, ONE_SIDED_WALL }

  private fun logged(): List<Eye> {
    val a=JSONArray(File(out.parentFile,"heads.json").readText())
    return (0 until a.length()).map { val o=a.getJSONObject(it);val h=o.getJSONArray("head")
      Eye(o.getLong("t_ms"),h.getDouble(0),h.getDouble(1),h.getDouble(2)) }
  }
  private fun walk(height: Double)= (0..800).map { i ->
    val t=i*50L
    if(i<=200) Eye(t,0.0,height,-(2.0-1.7*i/200))
    else { val a=(i-200)*2*PI/600;Eye(t,.8*sin(a),height,-.8*cos(a)) }
  }
  private fun recording(): SiteRecorded {
    val dir=File("src/main/assets/judge")
    val arch=JSONObject(dir.resolve("architecture.json").readText())
    val events=dir.resolve("real_day.jsonl").readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
    return SiteRecorded(arch,events,{ it },GraphMotion.MEMBERSHIP)
  }
  private fun run(path: List<Eye>,floor: Boolean,fault: Fault): Counts {
    val pump=SitePump(recording(),null,0L);val draw=SiteDraw(false)
    var moves=0;var hides=0;var armed=false
    val placement=SitePlacement { if(armed && "event=pose_set" in it) moves++
      if(armed && "event=visible_set value=false" in it) hides++ }
    val at=Pose(Vector3(0f,.4f,0f))
    val ground=if(floor) FloorPlacement.place(FloorPlacement.Head(0.0,path.first().y, -2.0,0.0,1.0)) else null
    placement.open(at,"put_on",ground,path.first().y);armed=true
    var loaded=false;var pending=false;var nextPump=0L;var vanish=0;var swaps=0;var back=0
    for(eye in path) {
      if(pending) { loaded=true;pending=false }
      // The real placement rejects these ordinary centring triggers as the head moves.
      placement.recenter(Pose(Vector3(eye.x.toFloat(),eye.y.toFloat(),eye.z.toFloat())),
        if(fault==Fault.RECENTER) SitePlacement.SYSTEM_RECENTER else "first60_steady")
      if(eye.t>=nextPump) {
        val s=pump.step(eye.t,eye.t,true)!!
        val f=SiteFrame.of(pump.board,s.day,pump.mode,GraphMotion.clock(s.day),null,null)
        val p=draw.plan(f,true)
        if(p?.mesh!=null) {
          val m=FloorDrawing.site(p.mesh,floor)
          assertTrue("site geometry exists",m.indices.isNotEmpty())
          swaps++;pending=true
          if(fault==Fault.SINGLE_BUFFER) loaded=false
        }
        nextPump=eye.t+1000
      }
      // Exclude initial import from steady-state continuity for ALL policies.
      if(swaps>1 && !loaded) vanish++
      // Known test wall at origin, facing -z, with eye looking toward it. An actual
      // one-sided wall can be invisible on +z; current glTF is double-sided.
      if(fault==Fault.ONE_SIDED_WALL && eye.z>0) back++
    }
    return Counts(path.size,swaps,vanish,moves,hides,back)
  }
  @Test fun replayExposesSingleBufferRiskAndDetectsAllThreeControls() {
    val paths=linkedMapOf("nov15g" to logged(),"seated_walk" to walk(1.05),"standing_walk" to walk(1.7))
    assertEquals(390,paths.getValue("nov15g").size)
    val table=StringBuilder("path\tfloor\tpolicy\tticks\tswaps\tmodel_vanish_ticks\troot_writes\thides\tone_sided_wall_back_ticks\n")
    for((name,path) in paths) for(floor in listOf(false,true)) for(fault in Fault.values()) {
      val r=run(path,floor,fault)
      table.append("$name\t$floor\t$fault\t${r.ticks}\t${r.swaps}\t${r.vanish}\t${r.moves}\t${r.hides}\t${r.back}\n")
      assertTrue("nontrivial reload coverage",r.swaps>2)
      when(fault) {
        Fault.SINGLE_BUFFER -> assertTrue("single-buffer load must expose empty ticks",r.vanish>0)
        Fault.REFERENCE -> { assertEquals(0,r.vanish);assertEquals(0,r.moves);assertEquals(0,r.hides);assertEquals(0,r.back) }
        Fault.RECENTER -> assertTrue("unwanted system recenter must count",r.moves>0)
        Fault.ONE_SIDED_WALL -> if(name!="nov15g") assertTrue("far-side wall must count",r.back>0)
      }
    }
    File(out,"diagnostic.tsv").writeText(table.toString());println(table)
  }
  @Test fun generatedSiteMaterialIsDoubleSidedAndFloorBlendIsExplicit() {
    val pump=SitePump(recording(),null,0L);val s=pump.step(1000,1000,true)!!
    val mesh=SiteFrame.of(pump.board,s.day,pump.mode,GraphMotion.clock(s.day),null,null).mesh()
    for(blend in listOf(false,true)) {
      val file=File(out,"material_$blend.glb");SiteMesh.write(file,mesh,blend)
      val bytes=file.readBytes();val b=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
      val n=b.getInt(12);val json=JSONObject(String(bytes,20,n,Charsets.UTF_8))
      val material=json.getJSONArray("materials").getJSONObject(0)
      assertTrue(material.getBoolean("doubleSided"))
      assertEquals(if(blend) "BLEND" else "OPAQUE",material.getString("alphaMode"))
      file.delete() // only the textual assertion output is retained
    }
  }
}
