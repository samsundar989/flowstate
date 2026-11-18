package com.overhand.app.graph.site

import com.overhand.app.graph.GraphMotion
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Counts real sink calls, including Visible supplied on creation, on the bundled recorded day. */
class YardVisibilityBudgetTest {
  private class Count: SiteLookApply.Sink {
    val actual=linkedMapOf<String,SiteLook.Spec>()
    var writes=0
    var creates=0
    override fun create(slot: String,s: SiteLook.Spec) { actual[slot]=s;writes++;creates++ }
    override fun place(slot: String,s: SiteLook.Spec) { actual[slot]=s.copy(on=actual.getValue(slot).on) }
    override fun colour(slot: String,s: SiteLook.Spec) { actual[slot]=s.copy(on=actual.getValue(slot).on) }
    override fun text(slot: String,s: SiteLook.Spec) { actual[slot]=s.copy(on=actual.getValue(slot).on) }
    override fun visible(slot: String,on: Boolean) {
      check(actual.getValue(slot).on!=on) { "redundant visibility: $slot" }
      actual[slot]=actual.getValue(slot).copy(on=on);writes++
    }
    override fun destroy(slot: String) { actual.remove(slot) }
  }
  @Test fun hideShowPinchAndLeaveNeverExceedOneFrameBudget() {
    val dir=File("src/main/assets/judge")
    val arch=JSONObject(dir.resolve("architecture.json").readText())
    val ps=arch.getJSONArray("projects")
    val names=(0 until ps.length()).associate { ps.getJSONObject(it).let { p -> p.getString("id") to p.getString("name") } }
    val day=SiteRecorded(arch,dir.resolve("real_day.jsonl").readLines().filter { it.isNotBlank() }.map(::JSONObject),{ names.getValue(it) },GraphMotion.MEMBERSHIP)
    val info=SiteLayers.catalog(arch) { p -> dir.resolve("code/$p.json").takeIf { it.exists() }?.readText()?.let(::JSONObject) ?: JSONObject() }
    val board=day.board().also { b -> day.events.forEach { b.apply(it) } }
    val layout=board.layout();val state=board.state(day.end)
    val layers=SiteLayers.boxes(layout,state,.7,info);val id=layers.first().id
    fun frame(card: Boolean)=SiteLook.frame(layers,layout,state,.7,if(card) id else null,if(card) id else null)
    val base=frame(false);val card=frame(true)
    val c=Count();val a=SiteLookApply(c,SiteLookApply.Budget(clockMs={ 0.0 }))
    fun settled(f: Map<String,SiteLook.Spec>)=f.filterValues { it.on }.keys==c.actual.filterValues { it.on }.keys
    fun settle(f: Map<String,SiteLook.Spec>) { repeat(10000) { if(settled(f)) return;a.drain() };error("queue did not settle") }
    a.apply(base);settle(base)
    // Warm the retained card, as in the repeated pinch/reopen flow, then close it.
    a.apply(card);settle(card);a.apply(base);settle(base)
    val rows=mutableListOf("operation\twrites\tframes\tmax_frame\tcreates")
    var largest=0
    fun operation(name: String,target: Map<String,SiteLook.Spec>,start: () -> Unit) {
      c.writes=0;c.creates=0
      start();a.drain()
      var total=c.writes;var peak=c.writes;var frames=1
      while(!settled(target)) {
        c.writes=0;a.drain();total+=c.writes;peak=maxOf(peak,c.writes);frames++
        check(frames<10000)
      }
      largest=maxOf(largest,peak)
      rows+="$name\t$total\t$frames\t$peak\t${c.creates}"
      target.filterValues { it.on }.forEach { (k,s) -> assertEquals("settled look $k",s,c.actual[k]) }
    }
    operation("hide",base.mapValues { it.value.copy(on=false) }) { a.hideAll() }
    operation("show",base) { a.apply(base) }
    operation("focus",card) { a.apply(card) }
    operation("leave_focus",base) { a.apply(base) }
    val out=File(System.getenv("YARD_VIS_OUT") ?: "build/yard_visibility").apply { mkdirs() }
    val phase=System.getenv("YARD_VIS_PHASE") ?: "after"
    out.resolve("$phase.tsv").writeText(rows.joinToString("\n")+"\n")
    println(rows.joinToString("\n"))
    assertTrue("maximum visibility writes in a frame: $largest > 8",largest<=8)
  }
}
