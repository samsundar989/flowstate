package com.overhand.app.graph.site

import org.junit.Assert.*
import org.junit.Test

class YardVisibilityQueueTest {
  private class Count: SiteLookApply.Sink {
    val actual=linkedMapOf<String,SiteLook.Spec>()
    val calls=mutableListOf<String>()
    var now=0.0
    var cost=0.0
    override fun create(slot: String,s: SiteLook.Spec) { actual[slot]=s;calls+="create:$slot";now+=cost }
    override fun place(slot: String,s: SiteLook.Spec) {}
    override fun colour(slot: String,s: SiteLook.Spec) {}
    override fun text(slot: String,s: SiteLook.Spec) {}
    override fun visible(slot: String,on: Boolean) {
      assertNotEquals(actual.getValue(slot).on,on)
      actual[slot]=actual.getValue(slot).copy(on=on);calls+="vis:$slot";now+=cost
    }
    override fun destroy(slot: String) {}
  }
  private fun spec(size: Double=1.0)=SiteLook.Spec(SiteLook.Shape.BOX,0.0,0.0,0.0,size,size,size,argb=SiteLook.CYAN,on=true)
  @Test fun visibilityAndCreatesShareUnitsAndTimeAndHideLargestFirst() {
    val c=Count();val logs=mutableListOf<String>()
    val a=SiteLookApply(c,SiteLookApply.Budget(clockMs={ c.now }),{ logs+=it })
    a.apply((0..15).associate { "$it" to spec(it+1.0) });repeat(2) { a.drain() }
    c.calls.clear();logs.clear();a.hideAll();a.drain()
    assertEquals((15 downTo 8).map { "vis:$it" },c.calls)
    c.calls.clear();c.cost=1.5;a.drain()
    assertEquals("three calls reach 4.5 ms; no fourth starts",3,c.calls.size)
    c.cost=0.0;a.drain()
    assertEquals(1,logs.size)
    assertTrue(logs.single(),logs.single().startsWith("site event=vis op=hide writes=16 frames=3 ms="))
    c.calls.clear()
    a.apply((0..16).associate { "$it" to spec(it+1.0) },"show")
    a.drain();assertEquals(8,c.calls.size);assertTrue(c.calls.all { it.startsWith("vis:") })
    c.calls.clear();a.drain();assertEquals(8,c.calls.size)
    c.calls.clear();a.drain();assertEquals(listOf("create:16"),c.calls)
    assertTrue(logs.last(),logs.last().startsWith("site event=vis op=show writes=17 frames=3 ms="))
    val out=java.io.File(System.getenv("YARD_VIS_OUT") ?: "build/yard_visibility").apply { mkdirs() }
    out.resolve("vis_log_sample.txt").writeText(logs.joinToString("\n")+"\n")
  }
  @Test fun interruptedShowRetainsOriginalLegacyChildVisibility() {
    val c=Count();val a=SiteLookApply(c,SiteLookApply.Budget(clockMs={ c.now }))
    val states=(0..19).associateWith { it%3!=0 }.toMutableMap()
    val original=states.toMap();val restore=SiteVisibilityRestore<Int>()
    fun write(k: Int,on: Boolean) { a.visibility(k,states.getValue(k),on) { states[k]=it } }
    fun hide() { restore.hide(states.keys,{ states.getValue(it) },::write) }
    hide();repeat(3) { a.drain() };assertTrue(states.values.none { it })
    restore.resume(::write);a.drain() // restored only eight of the thirteen visible children
    hide();repeat(3) { a.drain() };assertTrue(states.values.none { it })
    restore.resume(::write);repeat(3) { a.drain() };assertEquals(original,states)
    restore.settled()
    // A later completed session can legitimately change a child's visibility.
    states[1]=false;val changed=states.toMap()
    hide();repeat(3) { a.drain() };restore.resume(::write);repeat(3) { a.drain() }
    assertEquals(changed,states)
  }
  @Test fun rapidReversalRemovalAndExternalChildrenUseLatestIntent() {
    val c=Count();val a=SiteLookApply(c,SiteLookApply.Budget(clockMs={ c.now }))
    val f=(0..19).associate { "$it" to spec() }
    a.apply(f);repeat(3) { a.drain() }
    a.hideAll();a.drain()
    a.apply(f,"show");repeat(3) { a.drain() }
    assertTrue(c.actual.values.all { it.on })
    c.calls.clear();a.apply(f);a.drain();assertTrue(c.calls.isEmpty())
    a.apply(emptyMap());repeat(3) { a.drain() };assertTrue(c.actual.values.none { it.on })
    // External entity state is read afresh: import activation may have changed it between operations.
    val key=Any();var visible=true;var writes=0
    a.visibility(key,visible,false) { visible=it;writes++ }
    a.visibility(key,visible,true) { visible=it;writes++ };a.drain()
    assertEquals(0,writes)
    a.visibility(key,visible,false) { visible=it;writes++ };a.drain();assertFalse(visible)
    visible=true
    a.visibility(key,visible,false) { visible=it;writes++ };a.drain();assertFalse(visible);assertEquals(2,writes)
  }
}
