package com.overhand.app.graph

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ProjectContextTest {
  private val dir=listOf(File("src/main/assets/judge"),File("app/src/main/assets/judge")).first { it.isDirectory }
  private fun row(id: String,name: String?)=JSONObject().put("id",id).also { if(name!=null) it.put("name",name) }
  private fun snapshot()=JSONObject(File(dir,"snapshot.json").readText()).getJSONArray("projects").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
  private fun replay()=File(dir,"real_day.jsonl").readLines().filter { it.isNotBlank() }.map(::JSONObject).let { TaskReplay(it,"f") }
  private val fixture=mapOf("s74617e7e4f3c9e64" to "Headset competition","sc7e2275fab6a13cd" to "Agent challenge",
    "s4a3f3b09ec1c0d92" to "Geothermal faults","s04f0600b6c43c5ad" to "Molecule discovery",
    "s523af42b36935d2d" to "Circuit compilation","s1f2fd986ca050085" to "Sports tracking")

  @Test fun allSixFixtureProjectsShowRecordedNamesKeyedByOriginalIds() {
    assertEquals(fixture,ProjectContext.labels(snapshot()))
  }
  @Test fun missingNameFallsBackHonestlyAndNeverShowsTheKey() {
    val l=ProjectContext.labels(listOf(row("s1",null),row("s2","  "),row("s3","Real")))
    assertEquals("Unnamed recorded project 1",l["s1"]);assertEquals("Unnamed recorded project 2",l["s2"]);assertEquals("Real",l["s3"])
    assertEquals(ProjectContext.FALLBACK,ProjectContext.label(l,"not-in-recording"))
  }
  @Test fun duplicateNamesStayDistinctAndKeepTheirOwnKeys() {
    val l=ProjectContext.labels(listOf(row("a","Twin"),row("b","Other"),row("c","Twin")))
    assertEquals("Twin (recorded entry 1)",l["a"]);assertEquals("Other",l["b"]);assertEquals("Twin (recorded entry 3)",l["c"])
    assertEquals(3,l.values.toSet().size)
  }
  private val rawRows by lazy { File(dir,"real_day.jsonl").readLines().filter { it.isNotBlank() }.map(::JSONObject) }
  private fun view(key: String,r: TaskReplay)=ProjectView.state(key,r.tMs,ProjectView.rowTimes(rawRows))
  @Test fun defaultFinalSaysWhatItIsAndWhatNeedsYouInsteadOfCounts() {
    val r=replay();r.seek(r.count)
    val lines=ProjectContext.graphLines(view("s74617e7e4f3c9e64",r),r.clock,r.step,r.count).map { it.text }
    assertTrue(lines[0],lines[0].startsWith("What it is: A hands-first room"))
    assertTrue(lines.any { it.startsWith("Now: ") && it.contains("game engine") })
    assertTrue(lines.any { it.startsWith("Done (11): ") })
    assertTrue(lines.any { it.startsWith("Stuck: ") && it.contains("direction") })
    assertTrue(lines.any { it.startsWith("Needs you: Choose the direction for the Nov 18 entry") })
    assertTrue(lines.none { it.contains("recorded tasks successful") })
    val spoken=ProjectContext.spoken("Headset competition",true,view("s74617e7e4f3c9e64",r),r.clock)
    listOf("selected project","What it is:","Now:","Done (11):","Stuck:","Needs you:").forEach { assertTrue(it,spoken.contains(it)) }
  }
  @Test fun labelsAndViewSurviveRewindAndRestart() {
    val r=replay();val labels=ProjectContext.labels(snapshot());val key="s74617e7e4f3c9e64"
    r.seek(r.count);val end=view(key,r)
    r.seek(r.count/2);assertNotEquals(end,view(key,r))
    r.restart()
    val start=view(key,r)!!
    assertEquals(ProjectView.State(end!!.goal,emptyList(),emptyList(),emptyList(),emptyList()),start)
    assertEquals("Done: nothing finished yet.",ProjectView.doneLine(start,3))
    r.seek(r.count);assertEquals(end,view(key,r))
    assertEquals("Headset competition",ProjectContext.label(labels,key))
  }
  @Test fun onlyTheSelectedStationCarriesTheMarkerAndNoLineShowsAKey() {
    val r=replay();r.seek(r.count);val labels=ProjectContext.labels(snapshot())
    fixture.keys.forEach { key ->
      val lines=ProjectContext.stationLines(ProjectContext.label(labels,key),key=="s74617e7e4f3c9e64",view(key,r),r.clock,true)
      assertEquals(key=="s74617e7e4f3c9e64",lines.first().text=="Selected project")
      assertTrue(lines.none { it.text.contains(key) })
      assertTrue(lines.any { it.text.contains("Nov 3") })
      assertTrue(lines.any { it.text.startsWith("What it is: ") })
      assertTrue(lines.any { it.text.startsWith("Needs you: ") })
    }
  }
  @Test fun aProjectWithNoWrittenSummaryIsSaidPlainly() {
    val lines=ProjectContext.graphLines(null,"Nov 3 00:00:00 CST",0,0).map { it.text }
    assertEquals("No plain-words summary is recorded for this project.",lines.first())
  }
}
