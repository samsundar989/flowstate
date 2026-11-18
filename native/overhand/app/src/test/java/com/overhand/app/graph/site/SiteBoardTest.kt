package com.overhand.app.graph.site

import com.overhand.app.graph.GraphMotion
import com.overhand.app.voice.FakeUi
import com.overhand.app.voice.SlotName
import com.overhand.app.voice.UiActions
import com.overhand.app.voice.UiCommands
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * #1029 the build site as the default view: plots from the manifest, live signs, 1 Hz batching, event-to-visible
 * latency on the recorded day at its real spacing, the put-on layout lint, triangles with four plots, the idle looks.
 */
class SiteBoardTest {
  private val dir=File("src/main/assets/judge")
  private fun rows(f: File)=f.readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
  private val dayRows=rows(dir.resolve("real_day.jsonl"))
  private val dayArch=JSONObject(dir.resolve("architecture.json").readText())
  private fun names(a: JSONObject): (String) -> String { val ps=a.getJSONArray("projects")
    val m=(0 until ps.length()).associate { ps.getJSONObject(it).let { p -> p.getString("id") to p.optString("name",p.getString("id")) } };return { m.getValue(it) } }
  private val day=SiteRecorded(dayArch,dayRows,names(dayArch),GraphMotion.MEMBERSHIP)
  private val out=File(System.getProperty("site.out") ?: System.getenv("SITE_OUT") ?: "build/site_t1029").apply { mkdirs() }

  /** The demo manifest's projects merged into one recording (what #1023 extends to four), [copies] times over. */
  private fun demos(copies: Int=1): SiteRecorded {
    val manifest=JSONObject(dir.resolve("demo/manifest.json").readText()).getJSONArray("projects")
    val projects=JSONArray();val all=ArrayList<JSONObject>()
    for(k in 0 until copies) for(i in 0 until manifest.length()) { val m=manifest.getJSONObject(i);val key=m.getString("key")
      val a=JSONObject(dir.resolve("demo/$key/architecture.json").readText()).getJSONArray("projects").getJSONObject(0)
      val id=a.getString("id")+(if(k==0) "" else "_$k")
      projects.put(JSONObject(a.toString()).put("id",id).put("name",a.optString("name")+(if(k==0) "" else " $k")))
      rows(dir.resolve("demo/$key/day.jsonl")).forEach { all.add(JSONObject(it.toString()).put("project",id)) } }
    val arch=JSONObject().put("projects",projects)
    return SiteRecorded(arch,all.sortedBy { it.getLong("t_ms") },names(arch))
  }

  /** Every state of a replay: (day time, board after applying every event up to it). */
  private fun replay(r: SiteRecorded,each: (Long,SiteBoard) -> Unit) { val b=r.board();r.events.forEach { e -> b.apply(e);each(e.tMs,b) } }

  @Test fun onePlotPerManifestProjectWithNoFixedCount() {
    val ps=dayArch.getJSONArray("projects")
    assertEquals((0 until ps.length()).map { ps.getJSONObject(it).getString("id") }.toSet(),day.board().layout().plots.map { it.project }.toSet())
    val n=JSONObject(dir.resolve("demo/manifest.json").readText()).getJSONArray("projects").length()
    assertEquals(n,demos().board().layout().plots.size)
    assertEquals(2*n,demos(2).board().layout().plots.size)
    // Live: plots appear as the computer reports projects.
    val live=SiteBoard()
    listOf("p_a" to "Weather app","p_b" to "Chess bot","p_c" to "Notes").forEachIndexed { i,(id,n) ->
      live.apply(SiteEvents.host(JSONObject().put("project_id",id).put("kind","project_created").put("name",n),1000L*i)!!)
      assertEquals(i+1,live.layout().plots.size) }
  }

  @Test fun signsSayNameProgressCrewAndLatestWithinTheirWordLimits() {
    var lit=0;var idle=0;var withCrew=0
    fun words(s: String)=s.removeSuffix("…").split(' ').count { it.isNotBlank() && it!="·" }
    for(r in listOf(day,demos())) replay(r) { t,b ->
      b.signs(t).forEach { s ->
        assertTrue(s.name.isNotBlank());assertTrue(s.figure,Regex("^(\\d+ of \\d+ done|No tasks yet)$").matches(s.figure))
        s.crew.forEach { c -> assertTrue("crew label '$c'",words(c) in 1..3) }
        assertTrue("latest '${s.latest}'",words(s.latest)<=6)
        assertEquals(s.lit,s.crew.isNotEmpty())
        if(s.lit) lit++ else idle++;if(s.crew.isNotEmpty()) withCrew++
      } }
    assertTrue("some plots lit ($lit) and some idle ($idle)",lit>50 && idle>50)
  }

  @Test fun blocksAgreeWithTheFirstPassAtTheEndOfTheDay() {
    val first=BuildSite(dayRows,dayArch);val b=day.board();day.events.forEach { b.apply(it) }
    val mine=b.state(day.end);val theirs=first.at(first.end)
    first.layout.plots.forEach { p -> p.parts.forEach { part ->
      assertEquals("${p.project}/${part.id} done",theirs.part(p.project,part.id).done,mine.part(p.project,part.id).done)
      assertEquals("${p.project}/${part.id} total",theirs.part(p.project,part.id).total,mine.part(p.project,part.id).total) } }
  }

  @Test fun anIdlePlotIsDimmedNeverEmpty() {
    val b=day.board();day.events.takeWhile { it.tMs<day.start+(day.end-day.start)/2 }.forEach { b.apply(it) }
    val t=day.start+(day.end-day.start)/2;val l=b.layout();val st=b.state(t)
    val idle=b.signs(t).first { !it.lit }.project;val plot=l.plots.first { it.project==idle }
    val litMesh=SiteMesh.build(l,st,null,null);val dimMesh=SiteMesh.build(l,st,null,emptySet())
    assertEquals(litMesh.triangles,dimMesh.triangles)   // same geometry: dimmed, not removed
    fun brightness(m: SiteMesh.Built): Double { var sum=0.0;var n=0
      for(v in 0 until m.positions.size/3) { val x=m.positions[3*v];val z=m.positions[3*v+2]
        if(x>=plot.x0 && x<=plot.x0+plot.w && z>=plot.z0 && z<=plot.z0+plot.d) { sum+=m.colours[4*v]+m.colours[4*v+1]+m.colours[4*v+2];n++ } }
      return sum/n }
    assertEquals(SiteMesh.DIM.toDouble(),brightness(dimMesh)/brightness(litMesh),0.02)
  }

  @Test fun idleStatesHaveOneLineEach() {
    val b=day.board()
    val lines=mapOf(SiteMode.NO_COMPUTER to SiteText.header(SiteMode.NO_COMPUTER,b,0),SiteMode.DAY_ENDED to SiteText.header(SiteMode.DAY_ENDED,b,0),
      SiteMode.LIVE to SiteText.header(SiteMode.LIVE,SiteBoard(),0))
    lines.values.forEach { assertFalse(it.contains('\n'));assertTrue(it.length<=60) }
    assertEquals("No computer connected: pair one in Settings",lines[SiteMode.NO_COMPUTER])
    assertTrue(lines.getValue(SiteMode.DAY_ENDED).startsWith("The day has ended:"))
    assertEquals("Nothing building right now: 0 projects waiting",lines[SiteMode.LIVE])
    File(out,"idle_lines.json").writeText(JSONObject(lines.mapKeys { it.key.name } as Map<*,*>).put("idle_plot",SiteText.IDLE_PLOT).toString(1))
  }

  /** Live host events drive the same board: a part lights while a task runs, the plot dims when it ends. */
  @Test fun liveHostEventsDriveTheSameBoard() {
    val b=SiteBoard();var t=0L
    fun e(kind: String,vararg kv: Pair<String,Any>)=b.apply(SiteEvents.host(JSONObject().put("project_id","p1").put("kind",kind).apply { kv.forEach { (k,v) -> put(k,v) } },t++)!!)
    e("project_created","name" to "Weather app")
    e("plan","task_id" to "t1","parts" to JSONArray().put(JSONObject().put("id","ui")).put(JSONObject().put("id","api")))
    e("task_state","task_id" to "t1","state" to "running","title" to "Build the forecast page")
    e("file_written","task_id" to "t1","part_id" to "ui","title" to "Wrote the forecast page","path" to "ui/index.html")
    assertTrue(b.signs(t).single().lit);assertEquals("building",b.state(t).part("p1","ui").state)
    assertEquals(listOf("Agent Building"),b.signs(t).single().crew)
    e("finished","task_id" to "t1","ok" to true,"title" to "Forecast page done")
    assertFalse(b.signs(t).single().lit);assertEquals("1 of 1 done",b.signs(t).single().figure)
    assertEquals("built",b.state(t).part("p1","ui").state)
  }

  @Test fun feedAppliesAtMostOncePerSecond() {
    val b=SiteBoard();val f=SiteFeed(b)
    (0 until 50).forEach { f.offer(SiteEvent(it.toLong(),"p",SiteKind.PROJECT,name="P$it"),it*10L) }
    assertNotNull(f.flush(1000));assertNull(f.flush(1500))   // nothing pending, and within the second
    f.offer(SiteEvent(0,"p",SiteKind.NOTE,text="one more thing"),1600);assertNull(f.flush(1700));assertNotNull(f.flush(2000))
  }

  /**
   * `site_event_to_visible_p95_ms`: the recorded day replayed at its real event spacing on a virtual clock. Each event
   * arrives at its own time; the 1 Hz pump flushes and the site is rebuilt (mesh, GLB, signs: measured here, in real
   * milliseconds). Visible = tick time + rebuild time. The device's mesh swap is not in this number.
   */
  @Test fun eventToVisibleOnTheRecordedDayAtRealSpacing() {
    val b=day.board();val f=SiteFeed(b);val waits=ArrayList<Long>();var builds=0;var maxBuild=0L
    val ev=day.events;var i=0;var tick=ev.first().tMs-(ev.first().tMs%1000)
    val glb=File.createTempFile("site",".glb")
    while(i<ev.size || f.pendingCount()>0) {
      while(i<ev.size && ev[i].tMs<=tick) f.offer(ev[i],ev[i++].tMs)
      f.flush(tick)?.let { arrivals ->
        val t0=System.nanoTime()
        val l=b.layout();val st=b.state(tick);val sg=b.signs(tick)
        val m=SiteMesh.build(l,st,null,sg.filter { it.lit }.map { it.project }.toSet());SiteMesh.write(glb,m)
        SiteMesh.signLabels(l,st,sg,SiteText.header(SiteMode.RECORDED,b,tick),SiteText.subline(SiteMode.RECORDED,GraphMotion.clock(tick)),SiteMode.RECORDED)
        val build=(System.nanoTime()-t0)/1_000_000;builds++;maxBuild=maxOf(maxBuild,build)
        arrivals.forEach { a -> waits.add(tick-a+build) } }
      // Jump quiet stretches straight to the next whole second with an event (the pump idles in between).
      tick=if(f.pendingCount()==0 && i<ev.size && ev[i].tMs>tick+1000) ev[i].tMs-(ev[i].tMs%1000)+1000 else tick+1000
    }
    glb.delete()
    waits.sort();val p95=waits[((waits.size-1)*0.95).toInt()];val p50=waits[waits.size/2]
    File(out,"latency.json").writeText(JSONObject().put("site_event_to_visible_p95_ms",p95).put("p50_ms",p50).put("max_ms",waits.last())
      .put("events_visible",waits.size).put("events_total",ev.size).put("rebuilds",builds).put("max_rebuild_ms",maxBuild)
      .put("day_span_ms",ev.last().tMs-ev.first().tMs).put("tick_ms",1000).toString(1))
    assertTrue("p95 $p95 ms",p95<=2000)
  }

  @Test fun putOnLayoutLintIsClean() {
    val l=SitePutOn.lint()
    File(out,"put_on_lint.json").writeText(JSONObject().put("faults",l.faults).put("nearest_m",l.nearestM).put("overlap_deg2",l.overlapDeg2)
      .put("site_deg",JSONArray(l.siteDeg.toList())).put("main_deg",JSONArray(l.mainDeg.toList())).toString(1))
    assertEquals("nearest ${l.nearestM} m, overlap ${l.overlapDeg2} deg2",0,l.faults)
  }

  /** `site_triangles` with four plots: the busiest moment of the four-project day; and the six-project recorded day. */
  @Test fun trianglesWithFourPlots() {
    fun peak(r: SiteRecorded): Int { var max=0
      replay(r) { t,b -> val pick=b.layout().plots.first().let { SiteSelection.Pick(it.project,it.parts.first().id) }
        max=maxOf(max,SiteMesh.build(b.layout(),b.state(t),null,b.signs(t).filter { it.lit }.map { it.project }.toSet(),pick).triangles) }
      return max }
    val four=peak(demos(2));val six=peak(day)
    File(out,"triangles.json").writeText(JSONObject().put("site_triangles",four).put("plots",4).put("recorded_day_six_plots",six).toString(1))
    assertTrue("four plots: $four",four<=SiteMesh.MAX_TRIANGLES);assertTrue("six plots: $six",six<=SiteMesh.MAX_TRIANGLES)
  }

  @Test fun noModelOrVendorNamesInAnySiteText() {
    val deny=Regex("(?i)\\b(claude|anthropic|openai|gpt|gemini|codex|llama|mistral|copilot|cursor)\\b")
    val seen=HashSet<String>()
    for(r in listOf(day,demos())) replay(r) { t,b -> b.signs(t).forEach { s -> seen.add(s.name);seen.add(s.figure);seen.addAll(s.crew);seen.add(s.latest) } }
    seen.addAll(listOf(SiteText.IDLE_PLOT)+SiteMode.values().map { SiteText.subline(it,"") })
    val hits=seen.filter { deny.containsMatchIn(it) }
    File(out,"brand_scan.json").writeText(JSONObject().put("texts",seen.size).put("hits",JSONArray(hits)).toString(1))
    assertEquals(emptyList<String>(),hits)
  }

  @Test fun voiceShowsTheSiteAndTheGraph() {
    val fake=FakeUi()
    val ui=object : UiActions by fake { override fun windows()=fake.windows()+SlotName("site","Build site",listOf("site","build site","building site","construction site")) }
    val c=UiCommands(ui)
    assertEquals("open_window" to "site",c.handle("show the site").let { it.action to it.slot })
    assertEquals("show_architecture",c.handle("show the graph").action)
    assertEquals("close_window" to "site",c.handle("close the build site").let { it.action to it.slot })
  }

  /** Stills for the cold read: GLB + label JSON at put-on moments and in each idle state; tools/site_stills/render.py draws them. */
  @Test fun exportPutOnStills() {
    val dst=System.getenv("SITE_STILLS_OUT")?.let { File(it) } ?: File(out,"stills")
    dst.mkdirs()
    fun dump(name: String,b: SiteBoard,t: Long,mode: SiteMode,clock: String) {
      val l=b.layout();val st=b.state(t,clock);val sg=b.signs(t);val lit=sg.filter { SiteText.lit(mode,it) }.map { it.project }.toSet()
      val m=SiteMesh.build(l,st,null,lit);SiteMesh.write(File(dst,"$name.glb"),m)
      val labels=SiteMesh.signLabels(l,st,sg,SiteText.header(mode,b,t),SiteText.subline(mode,clock),mode)
      File(dst,"$name.json").writeText(JSONObject().put("clock",clock).put("mode",mode.name).put("triangles",m.triangles).put("signs",sg.toJson())
        .put("put_on",JSONObject().put("distance",SitePutOn.DISTANCE).put("drop",SitePutOn.DROP).put("scale",SitePutOn.SCALE)
          .put("main",JSONArray(SitePutOn.mainCentre().toList())).put("main_w",SitePutOn.MAIN_W).put("main_h",SitePutOn.MAIN_H))
        .put("labels",JSONArray(labels.map { JSONObject().put("text",it.text).put("x",it.x).put("y",it.y).put("z",it.z).put("kind",it.kind).put("size",it.size) })).toString(1))
      // #1029 no two projects' sign lines, and no crane tag and sign, overlap in the wearer's view at put-on.
      val boxes=labels.filter { it.kind.startsWith("sign") || it.kind=="agent" }.map { L -> Triple(L.project ?: L.agent,SiteMesh.putOnBox(L),L.text) }
      for(i in boxes.indices) for(j in i+1 until boxes.size) { val (pa,a,ta)=boxes[i];val (pb,b,tb)=boxes[j]
        if(pa==pb) continue
        assertTrue("$name: labels overlap at put-on: '$ta' / '$tb'",!SiteMesh.overlaps(a,b)) }
    }
    // Put-on moments: 15 s, 30 s and 45 s into the Recorded play (event-paced, as the headset plays it).
    for(s in listOf(15,30,45)) { val t=day.dayAt(s*1000L);val b=day.board();day.events.takeWhile { it.tMs<=t }.forEach { b.apply(it) }
      dump("puton_${s}s",b,t,SiteMode.RECORDED,GraphMotion.clock(t)) }
    val end=day.board();day.events.forEach { end.apply(it) };dump("idle_day_ended",end,day.end,SiteMode.DAY_ENDED,GraphMotion.clock(day.end))
    val t=day.dayAt(30_000L);val mid=day.board();day.events.takeWhile { it.tMs<=t }.forEach { mid.apply(it) }
    dump("idle_no_computer",mid,t,SiteMode.NO_COMPUTER,"")
    dump("idle_empty",SiteBoard(),0,SiteMode.NO_COMPUTER,"")
    assertTrue(File(dst,"puton_30s.glb").length()>0)
  }
}
