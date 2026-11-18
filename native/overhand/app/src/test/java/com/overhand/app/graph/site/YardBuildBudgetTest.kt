package com.overhand.app.graph.site

import com.overhand.app.graph.GraphMotion
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Y1: recorded-day creation cost, measured at the same pure-data seam as SiteLookTest. */
class YardBuildBudgetTest {
  private val dir=File("src/main/assets/judge")
  private val arch=JSONObject(dir.resolve("architecture.json").readText())
  private val ps=arch.getJSONArray("projects")
  private val names=(0 until ps.length()).associate { ps.getJSONObject(it).let { p -> p.getString("id") to p.getString("name") } }
  private val day=SiteRecorded(arch,dir.resolve("real_day.jsonl").readLines().filter { it.isNotBlank() }.map(::JSONObject),{ names.getValue(it) },GraphMotion.MEMBERSHIP)
  private val info=SiteLayers.catalog(arch) { p -> dir.resolve("code/$p.json").takeIf { it.exists() }?.readText()?.let(::JSONObject) ?: JSONObject() }
  private val board=day.board().also { b -> day.events.forEach { b.apply(it) } }
  private val layout=board.layout()
  private val state=board.state(day.end)
  private val layers=SiteLayers.boxes(layout,state,.7,info)
  private fun frame(hover: SiteLayers.Id?=null,card: SiteLayers.Id?=null)=SiteLook.frame(layers,layout,state,.7,hover,card)
  /** Counting sink on a fake clock: 1 ms a box, 20 ms a text. */
  private class Count: SiteLookApply.Sink {
    var now=0.0
    val made=linkedMapOf<String,SiteLook.Spec>()
    val times=HashMap<String,Int>()
    var frameVisibility=0;var frameBoxes=0;var frameTexts=0;var lastStart=0.0
    override fun create(slot: String,s: SiteLook.Spec) {
      made[slot]=s;times[slot]=(times[slot] ?: 0)+1;lastStart=now
      if(s.shape==SiteLook.Shape.BOX) { frameBoxes++;now+=1.0 } else { frameTexts++;now+=20.0 }
    }
    override fun place(slot: String,s: SiteLook.Spec) { made[slot]=s }
    override fun colour(slot: String,s: SiteLook.Spec) { made[slot]=s }
    override fun visible(slot: String,on: Boolean) { frameVisibility++;made[slot]=made.getValue(slot).copy(on=on) }
    override fun text(slot: String,s: SiteLook.Spec) { made[slot]=s }
    override fun destroy(slot: String) {}
  }
  private fun rig(): Pair<Count,SiteLookApply> { val c=Count();return c to SiteLookApply(c,SiteLookApply.Budget(clockMs={ c.now })) }
  private val out=File(System.getenv("YARD_BUILD_OUT") ?: "build/yard_build").apply { mkdirs() }
  /** One frame: drain under the caps and check them (b). Returns how many were made. */
  private fun frameStep(c: Count,a: SiteLookApply): Int {
    c.frameVisibility=0;c.frameBoxes=0;c.frameTexts=0;val t0=c.now
    val n=a.drain()
    assertTrue("box cap: ${c.frameBoxes}",c.frameBoxes<=8)
    assertTrue("text cap: ${c.frameTexts}",c.frameTexts<=1)
    assertFalse("a text frame makes nothing else",c.frameTexts>0 && c.frameBoxes>0)
    if(n>1) assertTrue("time cap: last create started at ${c.lastStart-t0} ms",c.lastStart-t0<4.0)
    return n+c.frameVisibility
  }
  private fun build(c: Count,a: SiteLookApply,log: SiteSlotLog?=null): Int {
    var frames=0
    while(a.waiting>0) { val n=frameStep(c,a);frames++;assertTrue("stalled queue",n>0);c.now+=13.9
      log?.frame(c.now,n,0.0,a.waiting,a.slots,a.notMade) }
    return frames
  }

  @Test fun recordedDayFirstFrameMakesNoHiddenSlotAndStaysInBudget() {
    val f=frame();val (c,a)=rig()
    a.apply(f)
    assertEquals("apply makes nothing; the queue does",0,c.made.size)
    val first=frameStep(c,a)
    // (a) the first frame creates 0 hidden slots.
    assertEquals("hidden slots must wait until first shown",0,c.made.values.count { !it.on })
    assertTrue(first in 1..8)
    // The site assembles upward, building by building: the first slots made are the first building's lowest.
    val rows=ArrayList<String>();val logs=ArrayList<String>()
    val frames=1+build(c,a,SiteSlotLog({ logs+=it }))
    // (c) when the queue is empty every on slot exists and equals frame().
    val on=f.filterValues { it.on }
    assertEquals(on.keys,c.made.keys)
    on.forEach { (k,s) -> assertEquals(k,s,c.made[k]) }
    assertTrue(c.times.values.all { it==1 })
    // The log: one slots_done row, and at most four slots rows a second of (fake) build time.
    assertEquals(1,logs.count { it.startsWith("site event=slots_done ") })
    assertTrue(logs.last().startsWith("site event=slots_done total=${on.size} not_made=${f.size-on.size} "))
    // The throttle runs on the clock, which also counts create time, not only 13.9 ms a frame.
    assertTrue("log rows ${logs.size} for ${c.now} ms",logs.size-1<=c.now/1000*4+2)
    rows+="shape	on	slots"
    for(shape in SiteLook.Shape.values()) for(o in listOf(false,true)) rows+="$shape	$o	${f.values.count { it.shape==shape && it.on==o }}"
    rows+="total	all	${f.size}"
    rows+="yard_creates_first_frame	all	$first"
    rows+="yard_slots_total	all	${f.size}"
    rows+="yard_slots_not_made_at_launch	false	${a.notMade}"
    rows+="frames_to_build	all	$frames"
    rows+="fake_build_ms	all	${"%.0f".format(java.util.Locale.ROOT,c.now)}"
    out.resolve("${System.getenv("YARD_BUILD_PHASE") ?: "after"}.tsv").writeText(rows.joinToString("\n")+"\n")
    out.resolve("log_sample.txt").writeText(logs.joinToString("\n")+"\n")
  }

  @Test fun slotsTurnedOnMidBuildGoFirstAndLaterOnesAreMadeNextFrame() {
    val (c,a)=rig();a.apply(frame());frameStep(c,a)
    // (d) mid-build: pointing at the top layer of the last building puts its tag at the front.
    val target=layers.last().id;val tagKeys=frame(target).filter { (k,s) -> s.on && k !in c.made && !frame()[k]!!.on }.keys
    assertTrue(tagKeys.isNotEmpty())
    a.apply(frame(target));val before=c.made.keys.toSet();frameStep(c,a)
    val next=c.made.keys-before
    assertTrue("next frame made $next",next.isNotEmpty() && next.all { it in tagKeys })
    a.apply(frame());build(c,a)
    // After the queue is empty a slot that first turns on is made in the very next frame.
    val card=layers.first().id;val cardKeys=frame(card,card).filter { (k,s) -> s.on && k !in c.made }.keys
    a.apply(frame(card,card));val b2=c.made.keys.toSet();frameStep(c,a)
    if(c.made.keys==b2) frameStep(c,a)
    assertTrue((c.made.keys-b2).isNotEmpty() && (c.made.keys-b2).all { it in cardKeys })
    build(c,a);assertTrue(cardKeys.all { it in c.made })
  }

  @Test fun hideThenShowMakesNothing() {
    val (c,a)=rig();a.apply(frame());build(c,a);val n=c.made.size
    // (e) hide then show creates 0.
    a.hideAll();a.apply(frame());build(c,a);frameStep(c,a)
    assertEquals(n,c.made.size);assertTrue(c.times.values.all { it==1 })
  }

  @Test fun pointingAtFiftyLayersMakesOnlyTheirTagsOnce() {
    val (c,a)=rig();a.apply(frame());build(c,a);val base=c.made.keys.toSet()
    // (f) pointing at 50 layers in turn creates only their tags, once each.
    val tags=HashSet<String>()
    for(i in 0 until 50) { val id=layers[(i*7)%layers.size].id
      tags+=frame(id).filter { (k,s) -> s.on && k !in base }.keys
      a.apply(frame(id));build(c,a);frameStep(c,a);a.apply(frame());frameStep(c,a) }
    val made=c.made.keys-base
    assertEquals(tags,made)
    assertTrue(made.all { "#tag" in it || it.endsWith("#leader") })
    assertTrue(c.times.values.all { it==1 })
  }
}
