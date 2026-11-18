package com.overhand.app.graph

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TaskReplayTest {
  private fun e(time: Long,project: String,id: Int,verb: String,kind: String="task_assigned")=
    JSONObject().put("project",project).put("t_ms",time).put("kind",kind).put("title","#$id $verb: schema JSONL fixtures")
  private fun replay()=TaskReplay(listOf(e(100,"p",1,"queued"),e(200,"q",1,"queued"),
    e(300,"p",1,"ready"),e(400,"p",1,"success","done"),e(500,"p",2,"queued"),
    e(600,"p",2,"cancelled"),e(700,"p",3,"partial","done"),e(800,"p",4,"failed","error")),"fixture-day")

  @Test fun fixedDenominatorNeverDropsCancelledOrFutureTasks() {
    val r=replay()
    assertEquals(4,r.afterSteps("p",0).total)
    assertEquals(4,r.afterSteps("p",0).count("pending"))
    assertEquals(4,r.afterSteps("p",r.count).total)
    assertEquals(1,r.afterSteps("p",r.count).count("cancelled"))
  }
  @Test fun wrongProjectAndReusedIdsAreIsolated() {
    val r=replay()
    assertEquals(1,r.at("p",400).success)
    assertEquals(0,r.at("q",400).success)
    assertEquals(1,r.at("q",400).total)
    assertEquals(0,r.at("unknown",400).total)
  }
  @Test fun fixtureAndDateDoNotCarryPriorTaskState() {
    val first=replay();first.seek(first.count)
    val next=TaskReplay(listOf(e(100,"p",1,"queued")),"other-recording-other-day")
    next.seek(next.count)
    assertEquals(0,next.progress("p").success)
    assertNotEquals(first.snapshot("p").getString("fixture_sha256"),next.snapshot("p").getString("fixture_sha256"))
  }
  @Test fun readyAndRepeatedCompletionDoNotDuplicateTask() {
    val r=TaskReplay(listOf(e(1,"p",1,"queued"),e(2,"p",1,"ready"),e(3,"p",1,"success","done"),e(4,"p",1,"success","done")),"f")
    assertEquals(1,r.at("p",99).success);assertEquals(1,r.at("p",99).total)
  }
  @Test fun partialAndFailureAreNotSuccess() {
    val p=replay().at("p",800)
    assertEquals(1,p.success);assertEquals(1,p.count("partial"));assertEquals(1,p.count("failed"))
    assertEquals(0.25,p.fraction,1e-9)
  }
  @Test fun cancellationCanBeSupersededOnlyByLaterRecordedOutcome() {
    val r=TaskReplay(listOf(e(1,"p",1,"cancelled"),e(2,"p",1,"partial","done")),"f")
    assertEquals(1,r.at("p",1).count("cancelled"));assertEquals(1,r.at("p",2).count("partial"))
  }
  @Test fun rewindRestartAndResumeAreDeterministic() {
    val r=replay();r.seek(4);val four=r.snapshot("p").toString()
    r.seek(r.count);r.seek(4);assertEquals(four,r.snapshot("p").toString())
    r.restart();assertEquals(0,r.step);assertEquals(r.start,r.tMs);assertEquals(0,r.progress("p").success)
    r.seek(4);assertEquals(four,r.snapshot("p").toString())
  }
  @Test fun equalTimestampsUseRowOrderAndDoNotApplyFutureSteps() {
    val r=TaskReplay(listOf(e(10,"p",1,"queued"),e(10,"p",1,"success","done")),"f")
    assertEquals(0,r.afterSteps("p",1).success);assertEquals(1,r.afterSteps("p",2).success)
  }
  @Test fun wordsAndLaterComponentTagsNeverProduceMembership() {
    val row=e(100,"p",1,"queued").put("component","schema").put("plan_date","2026-11-05")
    val r=TaskReplay(listOf(row,e(200,"p",1,"success","done")),"f");r.seek(r.count)
    assertEquals(0,r.snapshot("p").getJSONArray("component_event_links").length())
    assertFalse(r.snapshot("p").getBoolean("architecture_animated"))
  }
  @Test fun animationRetainsT361CosineAndClockPacing() {
    assertEquals(0f,TaskReplay.ease(0f,1f,1000,1000),1e-6f)
    assertEquals(.5f,TaskReplay.ease(0f,1f,1000,1100),1e-6f)
    assertEquals(1f,TaskReplay.ease(0f,1f,1000,1200),1e-6f)
    assertEquals(3,TaskReplay.step(4200,10));assertEquals(10,TaskReplay.step(99999,10))
  }
  @Test fun everyBundledStepMatchesRawRowsWithProjectAndTaskDeduplication() {
    val dir=listOf(File("src/main/assets/judge"),File("app/src/main/assets/judge")).first { it.isDirectory }
    val rows=File(dir,"real_day.jsonl").readLines().filter { it.isNotBlank() }.map(::JSONObject)
    val sourceHash=java.security.MessageDigest.getInstance("SHA-256").digest(File(dir,"real_day.jsonl").readBytes()).joinToString("") { "%02x".format(it) }
    assertEquals("a8c1e211cb04ce06295e91048e2d0354d71880632279962369071977f960b8a4",sourceHash)
    val r=TaskReplay(rows,sourceHash)
    val tasks=rows.filter { it.optString("kind") in setOf("task_assigned","done","error") && Regex("^#\\d+ ").containsMatchIn(it.optString("title")) }
      .sortedBy { it.getLong("t_ms") }
    val projects=tasks.map { it.getString("project") }.toSet()
    assertEquals(tasks.size,r.count)
    for(n in 0..r.count) {
      r.seek(n)
      for(project in projects) {
        val mine=tasks.filter { it.getString("project")==project }
        fun id(row: JSONObject)=row.getString("title").substringAfter('#').substringBefore(' ').toInt()
        val latest=tasks.take(n).filter { it.getString("project")==project }.associateBy(::id)
        val p=r.progress(project)
        assertEquals(mine.map(::id).toSet().size,p.total)
        assertEquals(latest.values.count { it.getString("kind")=="done" && it.getString("title").substringAfter(' ').startsWith("success:") },p.success)
        p.tasks.filter { it.status!="pending" }.forEach { t ->
          assertEquals(latest.getValue(t.id).getLong("t_ms"),t.tMs)
          assertEquals(latest.getValue(t.id).getString("title"),t.title)
        }
      }
    }
    val end=r.snapshot(projects.first()).toString();r.restart();r.seek(r.count)
    assertEquals(end,r.snapshot(projects.first()).toString())
    val headset=r.progress("s74617e7e4f3c9e64")
    assertEquals(32,headset.total);assertEquals(11,headset.success)
    assertEquals(4,headset.count("partial"));assertEquals(1,headset.count("failed"))
    assertEquals(9,headset.count("cancelled"));assertEquals(7,headset.count("active"))
  }
}
