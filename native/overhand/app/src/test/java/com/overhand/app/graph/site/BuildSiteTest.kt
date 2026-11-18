package com.overhand.app.graph.site

import com.overhand.app.graph.GraphMotion
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** #1003: the build site on the recorded day (assets/judge/real_day.jsonl + architecture.json). */
class BuildSiteTest {
  private val dir=File("src/main/assets/judge")
  private val rows=dir.resolve("real_day.jsonl").readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
  private val site=BuildSite(rows,JSONObject(dir.resolve("architecture.json").readText()))
  private val HEADSET="s74617e7e4f3c9e64";private val AGENT="sc7e2275fab6a13cd";private val GEO="s4a3f3b09ec1c0d92"

  /** Independent recount straight from the raw rows: latest status per task id up to tMs, grouped by part. */
  private fun recount(project: String,tMs: Long): Map<String,Pair<Int,Int>> {
    val re=Regex("^#(\\d+) (\\w+)");val all=HashMap<Int,String>();val latest=HashMap<Int,String>()
    rows.forEach { r -> val m=re.find(r.optString("title")) ?: return@forEach
      val k=r.optString("kind");if(k !in setOf("task_assigned","done","error") || r.optString("project")!=project) return@forEach
      val id=m.groupValues[1].toInt();all[id]="pending"
      if(r.getLong("t_ms")<=tMs) latest[id]=if(k=="done" && m.groupValues[2]=="success") "success" else "other" }
    return all.keys.mapNotNull { id -> SiteMembership.part(project,id,true)?.let { it to id } }.groupBy({ it.first },{ it.second })
      .mapValues { (_,ids) -> ids.count { latest[it]=="success" } to ids.size }
  }

  @Test fun blockHeightFollowsTaskCountsForThreeProjects() {
    var checked=0;var nonZero=0
    for(p in listOf(HEADSET,AGENT,GEO)) for(f in listOf(0.0,0.2,0.4,0.5,0.6,0.8,1.0)) {
      val t=site.start+((site.end-site.start)*f).toLong();val s=site.at(t);val want=recount(p,t)
      site.layout.plots.first { it.project==p }.parts.forEach { part ->
        val st=s.part(p,part.id);val (done,total)=want[part.id] ?: (0 to 0)
        assertEquals("$p/${part.id} done at $f",done,st.done);assertEquals("$p/${part.id} total",total,st.total)
        val h=SiteMesh.BLOCK_H*st.share
        assertEquals(if(total==0) 0.0 else SiteMesh.BLOCK_H*done/total,h,1e-9)
        checked++;if(done>0) nonZero++ } }
    assertTrue("parts checked $checked",checked>=3*7*9)
    assertTrue("some blocks rise ($nonZero)",nonZero>10)
    // The end of the day: the headset project's schema part has finished work.
    assertTrue(site.at(site.end).part(HEADSET,"schema").done>0)
    assertEquals(0,site.at(site.start).parts.values.sumOf { it.done })
  }

  @Test fun agentsStandAtTheRightPartForTenSampledEvents() {
    val prog=Regex("^#(\\d+)\\s+(\\d+)%")
    val events=rows.filter { it.optString("agent_name")=="Worker" && it.optString("title")=="progress" && prog.containsMatchIn(it.optString("detail")) }
      .filter { val id=prog.find(it.getString("detail"))!!.groupValues[1].toInt();SiteMembership.part(it.getString("project"),id,site.hasPlan(it.getString("project")))!=null }
    assertTrue(events.size>=10)
    val sample=(0 until 10).map { events[it*(events.size-1)/9] }
    sample.forEach { e ->
      val id=prog.find(e.getString("detail"))!!.groupValues[1].toInt();val p=e.getString("project")
      val s=site.at(e.getLong("t_ms"))
      val a=s.agents.firstOrNull { it.id==e.getString("agent_id") }
      assertNotNull("agent ${e.getString("agent_id")} shown at ${e.getLong("t_ms")}",a)
      assertEquals(id,a!!.task);assertEquals(p,a.project)
      assertEquals(SiteMembership.part(p,id,site.hasPlan(p)),a.part)
      val crane=SiteMesh.cranes(site.layout,s).first { it.agent.id==a.id }
      val part=site.layout.part(p,a.part!!)!!
      assertEquals(part.x,crane.hookX,1e-9);assertEquals(part.z,crane.hookZ,1e-9)
      // #1312: the task number tells two same-named workers apart; it is not a word.
      assertTrue("label at most three words: ${a.label}",a.label.split(' ').filter { it!="·" && !it.startsWith("#") }.size<=3)
      assertTrue("now at most 12 words",a.now.removeSuffix("…").split(' ').size<=12)
      assertTrue(a.steps.size<=3)
    }
  }

  @Test fun replayEndsInTheSameStateAsTheLiveSnapshot() {
    val end=site.at(site.end)
    val live=BuildSite.fromLive(site.layout,JSONObject(site.liveSnapshot().toString()))
    assertEquals(end.shape(),live.shape())
    assertTrue(end.parts.values.any { it.done>0 })
    // Live words map back to the recorded ones the mesh uses.
    assertEquals(GraphMotion.state(listOf("success")),GraphMotion.state(listOf(BuildSite.recordedWord("done"))))
  }

  @Test fun pointingAtACraneSelectsItsAgentAndTheCardHasNowStepsAndTalk() {
    val s=(0..100).map { site.atFraction(it/100.0) }.maxBy { it.agents.size }
    assertTrue(s.agents.size>=2)
    SiteMesh.cranes(site.layout,s).forEach { c ->
      // A ray from the wearer's eye towards the middle of the mast.
      val eye=doubleArrayOf(0.0,0.6,1.6);val at=doubleArrayOf(c.baseX,SiteMesh.CRANE_H*0.8,c.baseZ)
      val hit=SiteHit.crane(site.layout,s,eye,doubleArrayOf(at[0]-eye[0],at[1]-eye[1],at[2]-eye[2]))
      assertNotNull(hit)
      val card=SiteMesh.card(hit!!)
      assertTrue(card.getString("now").removeSuffix("…").split(' ').size<=12)
      assertTrue(card.getJSONArray("steps").length()<=3)
      assertEquals("Talk",card.getJSONArray("actions").getString(0))
    }
    assertNull(SiteHit.crane(site.layout,s,doubleArrayOf(0.0,0.6,1.6),doubleArrayOf(0.0,1.0,0.0)))
  }

  @Test fun cardTextIsForAPersonNotTheLog() {
    assertEquals("parked on a queued job: data downloaded to main repo",
      BuildSite.plain("parked on workerq job 3927: data downloaded to main repo [redacted] (v0.2.11)").replace(Regex("\\s+")," ").trim())
    (0..100).flatMap { site.atFraction(it/100.0).agents }.forEach { a ->
      (listOf(a.now)+a.steps).forEach { t -> assertFalse(t,Regex("\\[|\\(|workerq job \\d").containsMatchIn(t)) } }
  }

  @Test fun theQuarterStillsShowWorkersAtWork() {
    val n=listOf(0.25,0.5,0.75).map { site.atFraction(it).agents.size }
    assertTrue("agents at 25/50/75%: $n",n.count { it>0 }>=2)
  }

  @Test fun recordedPlayGoesFromEmptyGroundToTheEndIn40Seconds() {
    assertEquals(site.start,site.dayAt(0));assertEquals(site.end,site.dayAt(BuildSite.PLAY_MS));assertEquals(site.end,site.dayAt(99_000))
    assertTrue(site.at(site.dayAt(0)).agents.isEmpty())
  }

  @Test fun budgetAndStillsExport() {
    val out=File(System.getenv("SITE_STILLS_OUT") ?: "build/site_stills").apply { mkdirs() }
    var maxTri=0;var states=0;var last: List<String>?=null
    for(k in 0..40) {   // every second of the 40 s play
      val s=site.at(site.dayAt(k*1000L))
      if(s.shape()==last) continue;last=s.shape();states++
      maxTri=maxOf(maxTri,SiteMesh.build(site.layout,s,s.agents.firstOrNull()?.id).triangles)
    }
    assertTrue("site_triangles $maxTri",maxTri<=SiteMesh.MAX_TRIANGLES)
    val manifest=JSONArray()
    for(pct in listOf(0,25,50,75,100)) {
      val s=site.atFraction(pct/100.0);val m=SiteMesh.build(site.layout,s)
      SiteMesh.write(out.resolve("site_$pct.glb"),m)
      out.resolve("site_$pct.json").writeText(JSONObject().put("pct",pct).put("clock",s.clock).put("triangles",m.triangles)
        .put("labels",JSONArray(SiteMesh.labels(site.layout,s).map { JSONObject().put("text",it.text).put("x",it.x).put("y",it.y).put("z",it.z).put("kind",it.kind) }))
        .put("agents",s.agents.size).put("parts_done",s.parts.values.sumOf { it.done }).put("tasks",s.parts.values.sumOf { it.total }).toString(1))
      manifest.put(JSONObject().put("pct",pct).put("clock",s.clock).put("triangles",m.triangles).put("agents",s.agents.size))
    }
    // Card close-up: the busiest moment's first agent, selected.
    val t=(0..100).map { site.atFraction(it/100.0) }.maxBy { it.agents.size }
    val a=t.agents.first { it.part!=null };val m=SiteMesh.build(site.layout,t,a.id)
    SiteMesh.write(out.resolve("site_card.glb"),m)
    val crane=SiteMesh.cranes(site.layout,t).first { it.agent.id==a.id }
    out.resolve("site_card.json").writeText(JSONObject().put("clock",t.clock).put("triangles",m.triangles).put("card",SiteMesh.card(a))
      .put("focus",JSONArray(listOf(crane.baseX,SiteMesh.CRANE_H,crane.baseZ)))
      .put("labels",JSONArray(SiteMesh.labels(site.layout,t).map { JSONObject().put("text",it.text).put("x",it.x).put("y",it.y).put("z",it.z).put("kind",it.kind).put("agent",it.agent ?: "") })).toString(1))
    out.resolve("site_metrics.json").writeText(JSONObject().put("site_triangles",maxTri).put("distinct_states_in_play",states)
      .put("stills",manifest).toString(1))
  }
}
