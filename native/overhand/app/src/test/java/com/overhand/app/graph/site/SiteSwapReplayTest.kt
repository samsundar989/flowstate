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

/** Shares the production planner, placement, swap owner and label yaw; importer/render oracle is simulated. */
class SiteSwapReplayTest {
  private val out=File(System.getenv("SITE_SWAP_OUT") ?: "../../../runs/site/t1272").apply { mkdirs() }
  private data class Eye(val t: Long,val x: Double,val y: Double,val z: Double)
  private class Model(val id: Int,val due: Int) { var loaded=false;var cull=false;var visible=false }
  private fun recording(): SiteRecorded {
    val dir=File("src/main/assets/judge")
    return SiteRecorded(JSONObject(dir.resolve("architecture.json").readText()),
      dir.resolve("real_day.jsonl").readLines().filter { it.isNotBlank() }.map { JSONObject(it) },{ it },GraphMotion.MEMBERSHIP)
  }
  private fun logged(): List<Eye> {
    val a=JSONArray(File(out,"heads.json").readText())
    return (0 until a.length()).map { val o=a.getJSONObject(it);val h=o.getJSONArray("head")
      Eye(o.getLong("t_ms"),h.getDouble(0),h.getDouble(1),h.getDouble(2)) }
  }
  private fun walk(h: Double)=(0..2800).map { i ->
    val a=(i-700)*2*PI/2100
    if(i<=700) Eye(i*14L,0.0,h,-2+1.7*i/700)
    else Eye(i*14L,.8*sin(a),h,-.8*cos(a))
  }
  private fun interpolate(path: List<Eye>): List<Eye> {
    var k=0
    return (0L..path.last().t step 14).map { t ->
      while(k+1<path.lastIndex && path[k+1].t<t) k++
      val a=path[k];val b=path[min(k+1,path.lastIndex)]
      val f=if(a.t==b.t) 0.0 else (t-a.t).toDouble()/(b.t-a.t)
      Eye(t,a.x+(b.x-a.x)*f,a.y+(b.y-a.y)*f,a.z+(b.z-a.z)*f)
    }
  }
  private data class Result(val reloads: Int,val vanish: Int,val back: Int,val changes: List<String>)
  private fun replay(path: List<Eye>,floor: Boolean,delay: Int,single: Boolean=false,fault: Int=0): Result {
    val pump=SitePump(recording(),null,0);val draw=SiteDraw(deduplicate=!single)
    val placement=SitePlacement()
    val ground=if(floor) FloorPlacement.place(FloorPlacement.Head(0.0,path.first().y,-2.0,0.0,1.0)) else null
    placement.open(Pose(Vector3(0f,.4f,0f)),"put_on",ground,path.first().y)
    var now=0L;var tick=0;var next=0L;var loads=0;var vanish=0;var back=0;var started=false
    val objects=ArrayList<Model>();val reasons=ArrayList<String>();var texts=emptyList<SiteLabelFacing>()
    val swaps=SiteSwap<Model>({ now },{ m,on ->
      if(on) { assertTrue("visible before own load",m.loaded);assertTrue("no-cull must be on the new object",m.cull) }
      m.visible=on
    },{ it.visible=false },{})
    for(eye in path) {
      now=eye.t
      for(m in objects) if(tick>=m.due) m.loaded=true
      val pending=swaps.pending
      if(pending!=null) {
        if(fault==1) { pending.visible=true;assertTrue("planted early swap",pending.loaded) }
        if(pending.loaded) swaps.poll(pending,true) { if(fault==2 && swaps.active!=null) swaps.active?.cull=true else pending.cull=true }
        else swaps.poll(swaps.active,false) {}
      }
      if(eye.t>=next) {
        val s=pump.step(eye.t,eye.t,true)!!
        val f=SiteFrame.of(pump.board,s.day,pump.mode,GraphMotion.clock(s.day),null,null)
        val p=draw.plan(f,true)
        texts=f.text.map { val n=SiteMesh.native(it.x,it.y,it.z);SiteLabelFacing(n[0].toFloat(),n[1].toFloat(),n[2].toFloat()) }
        if(p?.mesh!=null) {
          assertTrue(FloorDrawing.site(p.mesh,floor).indices.isNotEmpty())
          loads++;reasons.add("t=${eye.t} reason=${draw.meshReason} triangles=${p.mesh.triangles} key=${f.meshKey.hashCode()}")
          val model=Model(loads,tick+delay);objects.add(model)
          if(single) objects.forEach { it.visible=false }
          swaps.begin(model,draw.meshReason)
        }
        next=eye.t+1000
      }
      if(objects.any { it.visible }) started=true
      if(started && objects.none { it.visible }) vanish++
      assertTrue("never two visible sites across a frame",objects.count { it.visible }<=1)
      // Same local conversion as Scene, using the SDK inverse here as an independent oracle.
      val local=placement.pose!!.inverse()*Vector3(eye.x.toFloat(),eye.y.toFloat(),eye.z.toFloat())
      val hx=local.x/placement.scale;val hz=local.z/placement.scale
      for(l in texts) {
        if(fault!=3) l.face(hx,hz)
        val p=l.pose;val dx=hx-p.t.x;val dz=hz-p.t.z
        // Rotate the known Text3D front (-Z) by the actual quaternion, then dot with the eye vector.
        val nx=-2*p.q.w*p.q.y;val nz=2*p.q.y*p.q.y-1
        if(dx*dx+dz*dz>1e-10 && nx*dx+nz*dz<=0) back++
        assertEquals(0f,p.q.x,0f);assertEquals(0f,p.q.z,0f)
      }
      tick++
    }
    assertTrue("no initial visible sample",started)
    return Result(loads,vanish,back,reasons)
  }
  @Test fun delayedLoadsNeverBlankAndLabelsFaceTheWholeCircle() {
    val logged=logged();assertEquals(390,logged.size)
    val table=StringBuilder("path\tfloor\tdelay\tbefore_reloads\treloads\tbefore_vanish\tafter_vanish\tbackside\n")
    val paths=linkedMapOf("nov15g" to interpolate(logged),"seated_walk_circle" to walk(1.05),"standing_walk_circle" to walk(1.7))
    for((name,path) in paths) for(floor in listOf(false,true)) for(delay in listOf(1,2,5,10)) {
      val before=replay(path,floor,delay,single=true);val after=replay(path,floor,delay)
      table.append("$name\t$floor\t$delay\t${before.reloads}\t${after.reloads}\t${before.vanish}\t${after.vanish}\t${after.back}\n")
      assertTrue("control must blank at every delay",before.vanish>0)
      assertEquals(0,after.vanish);assertEquals(0,after.back)
      if(name=="nov15g" && !floor && delay==1) {
        File(out,"reload_reasons.txt").writeText(after.changes.joinToString("\n"))
        val kept=after.changes.associateBy { it.substringBefore(" ") }
        File(out,"paired_loads.tsv").writeText("control_request\tresult\treason\n"+before.changes.joinToString("\n") {
          "$it\t${if(it.substringBefore(" ") in kept) "imported" else "coalesced_identical_geometry"}\t${kept[it.substringBefore(" ")] ?: "byte-identical mesh key; labels still update"}"
        }+"\n")
      }
    }
    File(out,"swap_replay.tsv").writeText(table.toString());println(table)
  }
  @Test fun allThreePlantedFaultsAreRejected() {
    fun rejected(block: () -> Unit): Boolean = try { block();false } catch(e: AssertionError) { true }
    assertTrue(rejected { replay(walk(1.05),false,5,fault=1) })
    assertTrue(rejected { replay(walk(1.05),false,5,fault=2) })
    assertTrue(rejected { assertEquals(0,replay(walk(1.05),false,5,fault=3).back) })
    File(out,"faults.json").writeText("{\"site_faults_caught\":3,\"total\":3}")
  }
  @Test fun timeoutFailureStaleObjectAndSupersessionKeepTheOldModel() {
    var now=0L;val visible=HashSet<Int>();val destroyed=HashSet<Int>();val logs=ArrayList<String>()
    val swap=SiteSwap<Int>({ now },{ n,on -> if(on) visible.add(n) else visible.remove(n);Unit },{ destroyed.add(it);Unit },{ logs.add(it) })
    swap.begin(1,"initial");swap.poll(1,true) {};assertEquals(setOf(1),visible)
    swap.begin(2,"changed");swap.poll(1,true) { fail("must not cull old object") };assertEquals(2,swap.pending)
    swap.begin(3,"latest");assertFalse(2 in destroyed);assertEquals(1,swap.queuedCount)
    now=10_000;swap.poll(2,false) {};assertEquals(setOf(1),visible);assertTrue(2 in destroyed)
    swap.poll(2,true) { fail("stale timed-out owner") };assertEquals(3,swap.pending)
    swap.fail("queued import");assertTrue(3 in destroyed)
    swap.begin(4,"error");swap.fail("import");assertEquals(setOf(1),visible)
    swap.begin(5,"ok");swap.poll(5,true) {};assertEquals(setOf(5),visible)
    assertTrue(logs.last().contains("gap_ms=0"));swap.clear()
  }
  @Test fun identicalGeometryWithDifferentCountersDoesNotReload() {
    val pump=SitePump(recording(),null,0);val step=pump.step(0,0,true)!!
    val f=SiteFrame.of(pump.board,step.day,pump.mode,"00:00",null,null)
    val draw=SiteDraw();assertNotNull(draw.plan(f,true)!!.mesh)
    val changed=SiteFrame(f.layout,f.state.copy(clock="00:01"),f.signs,f.lit,
      f.text.map { it.copy(text=it.text+"!") },f.target,f.pick,f.mode)
    draw.invalidate();assertNull(draw.plan(changed,false)!!.mesh)
    draw.retryMesh();assertNotNull("a failed identical import is retryable",draw.plan(changed,true)!!.mesh)
  }
}
