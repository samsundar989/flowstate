package com.overhand.app.graph.site

import com.overhand.app.graph.GraphMotion
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Diagnostic reconstruction; sparse shown lines do not uniquely identify all intermediate pump states. */
class SiteSessionReplayTest {
  private val out=File(System.getenv("SITE_SWAP_OUT") ?: "../../../runs/site/t1272").apply { mkdirs() }
  @Test fun capturedFeedBatchesExposeMissingTelemetry() {
    val dir=File("src/main/assets/judge")
    val r=SiteRecorded(JSONObject(dir.resolve("architecture.json").readText()),
      dir.resolve("real_day.jsonl").readLines().filter { it.isNotBlank() }.map { JSONObject(it) },{ it },GraphMotion.MEMBERSHIP)
    val session=JSONObject(File(out,"session.json").readText())
    val trace=session.getJSONArray("shown")
    var loopStart=session.getLong("put_on_ms")
    val source=r.board();var changed=0
    data class Snapshot(val event: Int,val changed: Int,val day: Long,val frame: SiteFrame,val triangles: Int)
    val snapshots=ArrayList<Snapshot>()
    for((i,e) in r.events.withIndex()) {
      if(source.apply(e)) changed++
      if(i+1<r.events.size && r.events[i+1].tMs==e.tMs) continue
      val frame=SiteFrame.of(source,e.tMs,SiteMode.RECORDED,GraphMotion.clock(e.tMs),null,null)
      snapshots.add(Snapshot(i+1,changed,e.tMs,frame,frame.mesh().triangles))
    }
    var replayPump=SitePump(null,r.board(),0)
    var consumed=0;var previousMode=SiteMode.RECORDED;var lastChanged=0
    var inferredExtra=0
    val before=SiteDraw(deduplicate=false);val after=SiteDraw()
    var old=0;var fixed=0;var logged=0;var mismatches=0
    val table=StringBuilder("t_ms\tmode\tevents\tprefix\tbefore\tafter\tlogged_reload\ttriangles\tlogged_triangles\tagents\tlit\n")
    val changes=ArrayList<String>()
    var last: SiteFrame?=null
    for(i in 0 until trace.length()) {
      val row=trace.getJSONObject(i);val kv=Regex("(\\w+)=([^\\s]+)").findAll(row.getString("msg")).associate { it.groupValues[1] to it.groupValues[2] }
      val mode=SiteMode.valueOf(kv.getValue("mode").uppercase())
      if(mode==SiteMode.RECORDED && previousMode==SiteMode.DAY_ENDED) {replayPump=SitePump(null,r.board(),0);consumed=0;lastChanged=0;loopStart=row.getLong("t_ms")-kv.getValue("build_ms").toLong()}
      previousMode=mode
      val n=kv.getValue("events").toInt()
      // Pumps whose complete frame key was unchanged were not logged by show(). They can still
      // consume feed events. Select the earliest monotonic prefix matching the RAW state summaries;
      // neither the historical reload decision nor the candidate's count participates in selection.
      val match=snapshots.firstOrNull { it.event>=consumed && it.changed>=lastChanged+n &&
        it.triangles==kv.getValue("triangles").toInt() && it.frame.state.agents.size==kv.getValue("agents").toInt() &&
        (if(mode==SiteMode.DAY_ENDED) 0 else it.frame.lit.size)==kv.getValue("lit").toInt() }
        ?: error("no matching recording prefix for trace row $i after event=$consumed changed=$lastChanged n=$n")
      inferredExtra+=match.changed-lastChanged-n
      for(index in consumed until match.event) replayPump.feed.offer(r.events[index],row.getLong("t_ms"))
      replayPump.step(row.getLong("t_ms"),match.day,true)
      consumed=match.event;lastChanged=match.changed
      val frame=SiteFrame.of(replayPump.board,match.day,mode,GraphMotion.clock(match.day),null,null)
      val b=before.plan(frame,true)?.mesh;val a=after.plan(frame,true)?.mesh
      if(b!=null) old++
      if(a!=null) {
        fixed++
        val reasons=ArrayList<String>()
        val p=last
        if(p==null) reasons.add("initial") else {
          if(p.layout!=frame.layout) reasons.add("layout")
          if(p.state.parts!=frame.state.parts) reasons.add("parts_or_crew")
          if(p.state.agents.map { Triple(it.id,it.project,it.part) }!=frame.state.agents.map { Triple(it.id,it.project,it.part) }) reasons.add("cranes")
          if(p.lit!=frame.lit) reasons.add("lit")
        }
        changes.add("t_ms=${row.getLong("t_ms")} reason=${reasons.joinToString("+")} triangles=${a.triangles}")
      }
      val count=kv.getValue("mesh_reload").toInt();logged+=count
      val triangles=frame.mesh().triangles
      if(triangles!=kv.getValue("triangles").toInt() || frame.state.agents.size!=kv.getValue("agents").toInt() || frame.lit.size!=kv.getValue("lit").toInt()) mismatches++
      table.append("${row.getLong("t_ms")}\t$mode\t$n\t${match.event}\t${if(b!=null) 1 else 0}\t${if(a!=null) 1 else 0}\t$count\t$triangles\t${kv.getValue("triangles")}\t${frame.state.agents.size}\t${frame.lit.size}\n")
      last=frame
    }
    File(out,"session_replay.tsv").writeText(table.toString())
    File(out,"session_reload_reasons.txt").writeText(changes.joinToString("\n"))
    File(out,"session_metrics.json").writeText(JSONObject().put("site_reloads_replay_before",old).put("site_reloads_replay",fixed)
      .put("logged_reloads",logged).put("state_summary_mismatches",mismatches).put("events_per_loop",r.events.size).put("unshown_changed_events_inferred",inferredExtra).toString(2))
    println("session reloads logged=$logged real_before=$old after=$fixed summary_mismatches=$mismatches")
    assertEquals(115,logged)
    // The raw log changes its remembered triangle count on a no-reload line after a 2 s gap.
    // Its missing intermediate pump is not an exact replay input. Never claim 115 reconstructed.
    assertTrue("historical count alone is not a complete pump trace",inferredExtra>0)
    File(out,"exact_replay_limit.txt").writeText("Historical logged reloads=$logged; earliest-prefix reconstruction=$old; candidate=$fixed. Exact before=115 acceptance is unproven: omitted pump events and nonunique state summaries.\n")
    assertEquals("reconstructed states must match logged geometry/crew/lit counts",0,mismatches)
  }
}
