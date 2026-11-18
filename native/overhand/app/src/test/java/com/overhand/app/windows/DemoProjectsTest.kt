package com.overhand.app.windows

import com.overhand.app.data.*
import com.overhand.app.graph.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * #774: the #759 demo histories (four since #1023) load in Recorded mode, and every window fills from that project's files.
 * Expected counts are recounted here from the raw asset files, not taken from the code under test.
 */
class DemoProjectsTest {
  private val read={ path: String -> File("src/main/assets/$path").readText() }
  private fun source(key: String)=RecordedSource(read,LocalReceiptStore(Files.createTempDirectory("demo").resolve("r.json").toFile()),requested=key)
  private val keys=RecordedSource.demos(read).map { it.getString("key") }
  private fun raw(key: String)=File("src/main/assets/judge/demo/$key/day.jsonl").readLines().filter { it.isNotBlank() }.map(::JSONObject)
  private fun json(key: String,f: String)=JSONObject(File("src/main/assets/judge/demo/$key/$f").readText())
  private val words=File("src/main/assets/windows.js").readText().let { shell ->
    Regex("'([^']+)'").findAll(shell.substringAfter("const deny = [").substringBefore("];")).map { it.groupValues[1] }.toList() }+ArchitectureGraph.EXTRA_DENY
  /** Exactly what RecordedWindows puts on the page. */
  private fun shown(c: RecordedContent,value: String)=RecordedText.display(value,c.decisionText(),GraphText.pattern(words),RecordedText.decisionGate(words))
  private val placeholder=Regex("""not recorded|\[name hidden]|\[redacted]|recorded detail|\bNov [35]\b|placeholder|lorem|TODO""",RegexOption.IGNORE_CASE)

  @Test fun recordedModeListsTheDayAndBothDemoProjects() {
    val sets=RecordedSource.datasets(read)
    assertEquals(listOf("day","cards","cells","farm","grid"),sets.map { it.first })
    assertEquals(listOf("Recorded day","Card battle AI","Embryo cell tracking","Farm economy AI","Puzzle game agent"),sets.map { it.second })
    println("demo_projects_in_app=${sets.size-1}");println("demo_projects_recorded=${sets.size-1}")
    // Unknown or missing preference: the recorded day, unchanged.
    val day=source("no-such-recording")
    assertEquals(RecordedSource.DAY,day.dataset);assertEquals(714,day.events.size);assertEquals("Nov 3, 2026",day.dayLabel)
    assertEquals(source(RecordedSource.DAY).fixtureHash,day.fixtureHash)
  }

  @Test fun everyWindowFillsFromEachDemoProjectsOwnFiles() {
    assertEquals(4,keys.size)
    val report=StringBuilder()
    for(key in keys) {
      val s=source(key);val c=RecordedContent(s);val rows=raw(key)
      val pid=json(key,"view.json").getString("project")
      fun kind(k: String)=rows.filter { it.getString("kind")==k }
      assertEquals(key,s.dataset);assertEquals(rows.size,s.events.size)
      assertEquals(listOf(pid),c.projects.map { it.getString("id") })
      assertEquals(pid,c.defaultProject)
      // Fleet: one line per recorded task and metric.
      val fleet=shown(c,c.window("fleet",pid))
      assertEquals(kind("task_assigned").size,fleet.lines().count { "Recorded task: " in it })
      assertEquals(kind("metric").size,fleet.lines().count { Regex(" · \\w+ = ").containsMatchIn(it) })
      // Blueprint: every planned component, with its description.
      val comps=c.components(pid);val planned=json(key,"architecture.json").getJSONArray("projects").getJSONObject(0).getJSONArray("components")
      assertEquals(planned.length(),comps.size);assertTrue(comps.isNotEmpty())
      val blueprint=shown(c,c.window("blueprint",pid))
      comps.forEach { assertTrue(it.getString("name"),it.getString("name") in blueprint);assertTrue(it.getString("desc").isNotBlank()) }
      // Architecture: the graph model and the build-up replay through the real Timeline with the files membership.
      val graph=c.graph(pid);assertEquals(planned.length(),graph.nodes.size)
      val edges=(0 until planned.length()).sumOf { planned.getJSONObject(it).getJSONArray("after").length() }
      assertEquals(edges,graph.edges.size)
      val membership=json(key,"membership.json").getJSONArray("tasks")
      assertEquals(membership.length(),s.membership.size)
      val motion=GraphMotion.Timeline(s.events,pid,comps.map { it.getString("id") },s.membership)
      assertEquals("every task maps to a component",emptyList<Int>(),motion.unmapped)
      assertTrue("${motion.changes.size} changes",motion.changes.size>=10);assertTrue(motion.shown(60_000)>=10)
      comps.forEach { comp -> assertFalse(comp.getString("id"),placeholder.containsMatchIn(c.componentDetail(pid,comp))) }
      // Decisions: each recorded question, answered as recorded.
      val ds=RecordedContent.rows(s.decisions())
      assertEquals(kind("decision").size,ds.size);assertTrue(ds.isNotEmpty())
      assertEquals(kind("status").size,ds.count { it.has("historical_answer") })
      val chat=shown(c,c.window("chat",pid))
      ds.forEach { assertTrue(it.getString("question") in chat);assertTrue(it.getString("historical_answer") in chat) }
      assertEquals(kind("message").size,chat.lines().count { Regex("^\\w{3} \\d+ \\d\\d:\\d\\d:\\d\\d UTC · ").containsMatchIn(it) }-ds.size)
      assertEquals(kind("decision").sumOf { it.getJSONArray("options").length() },c.decisionText().count { Regex("^[A-D]: ").containsMatchIn(it) })
      // Replay: the task panel and the plain-words view at the cutoff.
      val tasks=rows.mapNotNull { Regex("^#(\\d+) ").find(it.optString("title"))?.groupValues?.get(1) }.toSet()
      val r=c.taskReplay;assertEquals(kind("task_assigned").size+kind("done").size+kind("error").size,r.count)
      val end=r.afterSteps(pid,r.count);assertEquals(tasks.size,end.total);assertEquals(0,end.count("pending"))
      r.seek(r.count);assertEquals("the last step reaches the final row",rows.last().getLong("t_ms"),r.tMs)
      val view=ProjectView.state(pid,r.tMs,ProjectView.rowTimes(s.events),s.views)!!
      val entries=json(key,"view.json").getJSONArray("entries")
      assertEquals((0 until entries.length()).count { entries.getJSONObject(it).getString("block")=="DONE" },view.done.size)
      assertEquals(json(key,"view.json").getString("goal"),view.goal)
      assertFalse(ProjectView.doneLine(view,3),".;" in ProjectView.doneLine(view,3) || ".;" in ProjectView.nowLine(view,3))
      assertTrue(r.clock,r.clock.endsWith("UTC") && !r.clock.startsWith("Nov 3"))
      // Code: one verbatim excerpt per code.json entry, text intact.
      val code=shown(c,c.window("code",pid));val excerpts=json(key,"code.json").getJSONArray("excerpts")
      assertEquals(excerpts.length(),code.lines().count { it.startsWith("Module: ") })
      (0 until excerpts.length()).forEach { i -> val x=excerpts.getJSONObject(i)
        assertTrue(x.getString("path"),x.getString("text").lines().first().let { "  $it" in c.window("code",pid) }) }
      // Build: done and failed outcomes.
      val build=shown(c,c.window("build",pid))
      assertTrue(build,build.contains("${kind("done").size} done · ${kind("error").size} failed"))
      val work=c.currentWork(pid,true);assertFalse(work,"not recorded" in work)
      // No empty panel and no placeholder or wrong-date text anywhere a judge reads.
      for(v in listOf("fleet","blueprint","code","build","chat")) {
        val page=shown(c,c.window(v,pid))
        assertTrue("$key $v",page.lines().count { it.isNotBlank() }>=3)
        placeholder.find(page)?.let { fail("$key $v: '${it.value}' in ${page.lines().first { l -> placeholder.containsMatchIn(l) }}") }
        report.appendLine(page)
      }
      listOf(c.currentWork(pid),c.currentWork(pid,true),s.badge,"Architecture snapshot · ${s.planLabel}","Cutoff snapshot · ${s.planLabel}",
        "Recorded snapshot · ${s.dayLabel}",r.clock,s.clock(motion.dayAt(0)),s.clock(motion.dayAt(30_000))).forEach { report.appendLine(shown(c,it)) }
      comps.forEach { report.appendLine(shown(c,c.componentDetail(pid,it))) }
      ProjectContext.graphLines(view,r.clock,r.step,r.count).forEach { report.appendLine(it.text) }
      ds.forEach { report.appendLine(it.getString("question"));report.appendLine(it.optString("recommendation"))
        RecordedContent.rows(it.getJSONArray("options")).forEach { o -> report.appendLine(o.getString("label")) } }
      val out=File("build/reports/rendered-demo-$key.txt");out.parentFile.mkdirs();out.writeText(report.toString());report.clear()
      println("$key: rows=${rows.size} tasks=${end.total} decisions=${ds.size} components=${comps.size} changes=${motion.changes.size} first60=${motion.shown(60_000)} excerpts=${excerpts.length()}")
    }
  }

  @Test fun demoClockReadsRealDatesBetweenRows() {
    val s=source("cards");val first=s.events.first()
    assertEquals("Aug 4 23:49 UTC",s.clock(first.getLong("t_ms")))
    assertEquals("Aug 4 23:49:02 UTC",RecordedContent.stamp(first))
    assertEquals("Aug 4 to Aug 20, 2026",s.dayLabel)
    assertTrue(s.clock(Long.MAX_VALUE).startsWith("Aug 20"))
    assertEquals("Nov 3 01:00",source(RecordedSource.DAY).clock(3_600_000))
  }
}
