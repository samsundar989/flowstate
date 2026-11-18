package com.overhand.app.graph.site

import com.overhand.app.graph.GraphMotion
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.*

class SiteLayersTest {
  private val dir=File("src/main/assets/judge")
  private val arch=JSONObject(dir.resolve("architecture.json").readText())
  private val ps=arch.getJSONArray("projects")
  private val names=(0 until ps.length()).associate { ps.getJSONObject(it).let { p -> p.getString("id") to p.getString("name") } }
  private val rows=dir.resolve("real_day.jsonl").readLines().filter { it.isNotBlank() }.map(::JSONObject)
  private val day=SiteRecorded(arch,rows,{ names.getValue(it) },GraphMotion.MEMBERSHIP)
  private val info=SiteLayers.catalog(arch) { p -> dir.resolve("code/$p.json").takeIf { it.exists() }?.readText()?.let(::JSONObject) ?: JSONObject() }
  private val board=day.board().also { b -> day.events.forEach { b.apply(it) } }
  private val layout=board.layout()
  private val state=board.state(day.end)
  private val out=File(System.getenv("SITE_LAYERS_OUT") ?: "build/site_layers").apply { mkdirs() }

  @Test fun everyRecordedBuildingLayerIsPickableAtViewingDistance() {
    val layers=SiteLayers.boxes(layout,state,.7,info)
    var resolved=0
    for(l in layers) {
      // Cast from 1.5 physical metres, from eight sides. Other buildings remain in the scene.
      val hits=(0 until 8).count { i -> val a=i*PI/4;val dx=cos(a);val dz=sin(a);val r=1.5/.7
        SiteLayers.pick(layers,doubleArrayOf(l.box.x+dx*r,l.box.y,l.box.z+dz*r),doubleArrayOf(-dx,0.0,-dz))==l.id }
      assertTrue("No reachable face: ${l.id}",hits>0);resolved++
    }
    assertEquals(6,layers.map { it.id.project }.distinct().size)
    val expected=layout.plots.sumOf { if(it.hasPlan) it.parts.size else 1 }
    assertEquals(expected,layers.size)
    val min=layers.minOf { (it.box.y1-it.box.y0)*.7 }
    out.resolve("pickable.txt").writeText("yard_layers_pickable=${resolved.toDouble()/layers.size}\nresolved=$resolved total=${layers.size}\nsmallest_metres=$min smallest_degrees=${SiteLayers.angularDegrees(min)}\n"+
      layout.plots.joinToString("\n") { p -> "${p.name}: ${layers.count { it.id.project==p.project }} layers (${if(p.hasPlan) "modules" else "Tasks fallback"})" })
  }
  @Test fun dependencyOrderSizeAndFloorHoldAcrossPlacementScales() {
    for(scale in listOf(.3,.7,.8)) {
      val layers=SiteLayers.boxes(layout,state,scale,info)
      assertTrue(layers.all { SiteLayers.angularDegrees((it.box.y1-it.box.y0)*scale)>=2.0 })
      layout.plots.filter { it.hasPlan }.forEach { p ->
        val byId=layers.filter { it.id.project==p.project }.associateBy { it.id.module }
        p.paths.forEach { (from,to) -> assertTrue("$from below $to",byId.getValue(from).box.y1<byId.getValue(to).box.y0) }
        val sized=byId.values.filter { (info[it.id]?.files ?: 0)>0 }
        sized.forEach { a -> sized.filter { (info[it.id]?.files ?: 0)>(info[a.id]?.files ?: 0) }.forEach { b ->
          assertTrue(b.box.y1-b.box.y0>=a.box.y1-a.box.y0-1e-9) } }
      }
    }
  }
  @Test fun raySlabsHandleParallelBehindInsideNearestAndInvalid() {
    val id=SiteLayers.Id("p","a");val a=SiteLayers.Layer(id,"A",SiteLayers.Box(-1.0,0.0,0.0,1.0,1.0,1.0))
    val b=a.copy(id=id.copy(module="b"),box=a.box.copy(z0=-2.0,z1=-1.0))
    assertEquals(id,SiteLayers.pick(listOf(b,a),doubleArrayOf(0.0,.5,2.0),doubleArrayOf(0.0,0.0,-1.0)))
    assertEquals(id,SiteLayers.pick(listOf(a),doubleArrayOf(0.0,.5,.5),doubleArrayOf(1.0,0.0,0.0)))
    for(d in listOf(doubleArrayOf(0.0,0.0,1.0),doubleArrayOf(0.0,0.0,0.0),doubleArrayOf(Double.NaN,0.0,1.0)))
      assertNull(SiteLayers.pick(listOf(a),doubleArrayOf(0.0,.5,2.0),d))
    assertNull(SiteLayers.pick(listOf(a),doubleArrayOf(2.0,.5,2.0),doubleArrayOf(0.0,0.0,-1.0)))
  }
  @Test fun hoverPinchCodeAskMoveAndCloseKeepExactlyOneSubject() {
    val logs=mutableListOf<String>();val i=SiteLayerInteraction(logs::add)
    val a=SiteLayers.Id("p","a");val b=a.copy(module="b")
    i.aim(a,false,0);assertEquals(a,i.hover);assertNull(i.card)
    i.pinch(a);i.pinch(a);assertEquals(a,i.card)
    var opened: Pair<String,String>?=null;i.code { p,m -> opened=p to m };assertEquals("p" to "a",opened)
    var asked: SiteLayers.Id?=null;i.ask { asked=it };assertEquals(a,asked)
    i.pinch(b);assertEquals(b,i.card)
    i.aim(null,false,100);i.aim(null,false,3099);assertEquals(b,i.card)
    i.aim(null,true,3100);i.aim(null,false,4000);i.aim(null,false,6999);assertEquals(b,i.card)
    i.aim(null,false,7000);assertNull(i.card)
    i.pinch(a);i.pinch(null);assertNull(i.card)
    for(action in listOf("hover","pinch","code","ask")) assertTrue(logs.any { it=="site event=layer_$action project=p module=a" })
  }
  @Test fun cardButtonsUseTheSameMathAndAreInFrontOfTheBuilding() {
    val l=SiteLayers.boxes(layout,state,.7,info).first();val b=SiteLayers.cardBox(l,.7)
    assertTrue(b.y0>0)
    val d=doubleArrayOf(0.0,0.0,-1.0)
    assertEquals("Code",SiteLayers.cardHit(l,.7,doubleArrayOf(b.x0+.03/.7,b.y0+.025/.7,b.z1+1.5/.7),d)?.second)
    assertEquals("Ask",SiteLayers.cardHit(l,.7,doubleArrayOf(b.x1-.03/.7,b.y0+.025/.7,b.z1+1.5/.7),d)?.second)
    assertEquals("Card",SiteLayers.cardHit(l,.7,doubleArrayOf(b.x,b.y1-.02/.7,b.z1+1.5/.7),d)?.second)
    assertNull(SiteLayers.cardHit(l,.7,doubleArrayOf(b.x1+1,b.y,b.z1+1.5/.7),d))
  }
  @Test fun askAnIdleModuleLocksItThroughTheExistingTalkFlow() {
    var now=0L;val t=SiteTalk { now }
    val a=SiteAgent("layer:p/m","Module", "p",0,"m","Module","Untouched",emptyList(),0,"Module: untouched")
    t.subject(a);t.aim(null);now=1000;t.aim(null)
    assertEquals(a,t.lock());assertTrue(SiteTalk.recordedAnswer(t.locked!!).lowercase().contains("untouched"))
    assertFalse(SiteTalk.caption(a).contains("#0"))
    t.end();now+=2000;assertNull(t.target())
  }
  @Test fun wholeRecordedLoopHasNoGroundSwapsAfterInitialLoad() {
    val pump=SitePump(day,null,0);val old=SiteDraw();val ground=SiteDraw()
    var oldSwaps=0;var newSwaps=0
    for(now in 0L..120_000L step 1000) {
      val s=pump.step(now,now,true)!!;val f=SiteFrame.of(pump.board,s.day,pump.mode,"clock",null,null)
      if(old.plan(f,true)?.mesh!=null) oldSwaps++
      if(ground.plan(SiteLayers.ground(f,.7),true)?.mesh!=null) newSwaps++
    }
    assertTrue(oldSwaps>1);assertEquals(1,newSwaps)
    out.resolve("swaps.txt").writeText("same_120s_replay baseline_loads=$oldSwaps layer_ground_loads=$newSwaps\nbaseline_swaps_per_min=${(oldSwaps-1)/2.0} layer_swaps_per_min=${(newSwaps-1)/2.0}\nworn_q2=81 swaps in about 2 minutes (40.5/min); after is PC replay only\n")
  }
  @Test fun exportRecordedBuildingProfilesAndCard() {
    val layers=SiteLayers.boxes(layout,state,.7,info)
    val projects=org.json.JSONArray()
    layout.plots.forEach { p ->
      val ls=org.json.JSONArray()
      layers.filter { it.id.project==p.project }.forEach { l -> val b=l.box
        ls.put(JSONObject().put("module",l.id.module).put("name",BuildSite.words(l.name,2))
          .put("y0",b.y0*.7).put("y1",b.y1*.7).put("status",SiteLayers.status(SiteLayers.state(l,layout,state)))
          .put("line",SiteLayers.line(l,layout,state)))
      }
      val old=org.json.JSONArray()
      p.parts.forEach { old.put(state.part(p.project,it.id).share*SiteMesh.BLOCK_H*.7) }
      projects.put(JSONObject().put("name",p.name).put("layers",ls).put("before_heights",old))
    }
    out.resolve("recorded-layer-boxes.json").writeText(JSONObject().put("projects",projects).toString(2))
    assertEquals(6,projects.length())
  }
}
