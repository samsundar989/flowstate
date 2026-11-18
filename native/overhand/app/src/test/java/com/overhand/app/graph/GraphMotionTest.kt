package com.overhand.app.graph

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GraphMotionTest {
  private val dir=listOf(File("src/main/assets/judge"),File("app/src/main/assets/judge")).first { it.isDirectory }
  private val rows=File(dir,"real_day.jsonl").readLines().filter { it.isNotBlank() }.map(::JSONObject)
  private val components=JSONObject(File(dir,"architecture.json").readText()).getJSONArray("projects").let { a ->
    (0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("id")==GraphMotion.HEADSET }
  }.getJSONArray("components").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }
  private val timeline=GraphMotion.Timeline(rows,GraphMotion.HEADSET,components)

  @Test fun firstMinuteShowsAtLeastTenChangesEachTracedToARecordedRow() {
    val first60=timeline.changes.filter { it.wallMs<=60_000 }
    val mismatches=first60.filter { c ->
      val row=rows[c.row-1]
      row.getString("project")!=GraphMotion.HEADSET || !row.getString("title").startsWith("#${c.taskId} ") ||
        row.getLong("t_ms")!=c.dayMs || GraphMotion.MEMBERSHIP[GraphMotion.HEADSET to c.taskId]?.first!=c.component
    }
    File("build").mkdirs()
    File("build/pipeline_motion_test.json").writeText(timeline.toJson(fixtureHash="unit").toString(2))
    println("pipeline_changes_first60=${first60.size} total=${timeline.changes.size} mismatches=${mismatches.size} unmapped=${timeline.unmapped} end_wall_ms=${timeline.changes.last().wallMs}")
    assertTrue("first60=${first60.size}",first60.size>=10)
    assertEquals(emptyList<GraphMotion.Change>(),mismatches)
    assertEquals(first60.size,timeline.shown(60_000))
  }

  @Test fun replayAgreesWithAnIndependentRecountOfTheRawRows() {
    // Independent recount: walk raw rows in time order, keep the latest status per task, derive each
    // component's state, and count transitions. Must equal the timeline's change list exactly.
    val latest=HashMap<String,HashMap<Int,String>>();val state=components.associateWith { "planned" }.toMutableMap()
    val expected=ArrayList<Triple<Int,String,String>>()
    rows.withIndex().sortedWith(compareBy({ it.value.getLong("t_ms") },{ it.index })).forEach { (i,r) ->
      val title=r.optString("title");val kind=r.optString("kind")
      if(r.optString("project")!=GraphMotion.HEADSET || kind !in setOf("task_assigned","done","error") || !title.startsWith("#")) return@forEach
      val id=title.drop(1).substringBefore(' ').toIntOrNull() ?: return@forEach
      val verb=title.substringAfter(' ').substringBefore(':').substringBefore(' ')
      val c=GraphMotion.MEMBERSHIP[GraphMotion.HEADSET to id]?.first ?: return@forEach
      latest.getOrPut(c) { HashMap() }[id]=when { kind=="error" || verb=="failed" -> "failed"; kind=="done" && verb=="success" -> "success"
        kind=="done" -> "partial"; verb=="cancelled" -> "cancelled"; else -> "active" }
      val next=GraphMotion.state(latest.getValue(c).values)
      if(next!=state[c]) { state[c]=next;expected.add(Triple(i+1,c,next)) }
    }
    assertEquals(expected,timeline.changes.map { Triple(it.row,it.component,it.to) })
  }

  @Test fun clockRunsForwardThroughTheRealDayAndChangesAreSeenOneAtATime() {
    var last=Long.MIN_VALUE
    for(w in 0L..timeline.durationMs step 100) { val d=timeline.dayAt(w);assertTrue(d>=last);last=d }
    assertEquals(rows.filter { it.optString("project")==GraphMotion.HEADSET && it.optString("title").startsWith("#") &&
      it.optString("kind") in setOf("task_assigned","done","error") }.maxOf { it.getLong("t_ms") },timeline.dayAt(timeline.durationMs))
    timeline.changes.zipWithNext().forEach { (a,b) -> assertTrue(b.wallMs-a.wallMs>=GraphMotion.SPACING_MS) }
    assertEquals(0,timeline.shown(0))
    assertEquals("Nov 3 01:36",GraphMotion.clock(timeline.changes.first().dayMs))
    assertTrue(timeline.durationMs in 30_000L..90_000L)
  }

  @Test fun everyMappedComponentExistsAndOnlyOneRecordedTaskIsUnmapped() {
    GraphMotion.MEMBERSHIP.values.forEach { (c,_) -> assertTrue(c,c in components) }
    assertEquals(listOf(59),timeline.unmapped)
  }

  @Test fun oneMappingForBothModes() {
    assertEquals("building",GraphMotion.state(listOf("success","active")))
    assertEquals("built",GraphMotion.state(listOf("success","cancelled")))
    assertEquals("blocked",GraphMotion.state(listOf("failed")))
    assertEquals("planned",GraphMotion.state(listOf("cancelled")))
    val live=JSONObject().put("id","x").put("shown","planned").put("tasks",org.json.JSONArray().put(JSONObject().put("status","claimed")))
    assertEquals("building",GraphMotion.liveState(live))
    assertEquals("built",GraphMotion.liveState(JSONObject().put("shown","verified")))
    assertEquals(mapOf("a" to "built"),GraphMotion.diff(mapOf("a" to "building","b" to "built"),mapOf("a" to "built","b" to "built","c" to "building")))
    assertEquals("now building",GraphMotion.word("building"))
  }
}
