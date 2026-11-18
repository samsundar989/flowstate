package com.overhand.app.features

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1233: what `events.jsonl` keeps from the app's own log, its JSON, its cap, and the lag check's `lagcheck.json`. */
class EventLogTest {
  @Test fun keepsEventsFramesStateMarksAndCrashesAndDropsTheRest() {
    val vr = EventLog.parse("1794775593.123  25616  25630 I VrApi   : FPS=72/72,Prd=40ms,Tear=0,Early=0,Stale=0,Stale2/5/10/max=0/0/0/0,VSnc=1")!!
    assertEquals("frames", vr.kind); assertEquals(25616, vr.pid); assertEquals("VrApi", vr.tag); assertEquals(1794775593.123, vr.t, 1e-6)
    assertEquals("event", EventLog.parse("1794775594.000  25616  25616 I OverhandSite: site event=hidden")!!.kind)
    assertEquals("event", EventLog.parse("1794775594.000  25616  25616 I Overhand: dock event=open view=fleet why=dock remembered=false")!!.kind)
    assertEquals("log", EventLog.parse("1794775594.000  25616  25700 I OverhandVoice: listen on")!!.kind)
    assertEquals("error", EventLog.parse("1794775594.000  25616  25616 E AndroidRuntime: FATAL EXCEPTION: main")!!.kind)
    assertNull("lag-check lines are written directly, not again by the reader",
      EventLog.parse("1794775594.000  25616  25616 I OverhandPerf: lagcheck step=1 name=voice_off state=start"))
    assertNull(EventLog.parse("1794775594.000  25616  25616 I OverhandPerf: mark lagcheck1_voice_off"))
    assertNull("other tags dropped", EventLog.parse("1794775594.000  25616  25616 D OpenGLRenderer: Davey! duration=700ms"))
    assertNull(EventLog.parse("--------- beginning of main"))
    assertEquals("lagcheck", EventLog.kind("OverhandPerf", "I", "lagcheck done restored=1"))
  }

  @Test fun jsonEscapesAndIsOneLine() {
    val r = EventLog.Row(1.5, 7, 8, "I", "OverhandSite", "site event=talk \"q\" a\\b\nc\t", "event", "logcat")
    val j = r.json()
    assertEquals("{\"t\":1.500,\"pid\":7,\"tid\":8,\"lvl\":\"I\",\"tag\":\"OverhandSite\",\"kind\":\"event\",\"src\":\"logcat\"," +
      "\"msg\":\"site event=talk \\\"q\\\" a\\\\b\\nc\\t\"}", j)
    assertTrue(!j.contains('\n'))
  }

  @Test fun theCapDropsTheOldestWholeLines() {
    val f = java.io.File.createTempFile("events", ".jsonl"); f.delete()
    val s = EventLog.Sink(f, cap = 2_000, keep = 1_000)
    for (i in 0 until 100) s.write(EventLog.Row(i.toDouble(), 1, 1, "I", "Overhand", "event=n i=$i", "event", "logcat"))
    val lines = f.readLines()
    assertTrue("under the cap: ${f.length()}", f.length() <= 2_000)
    assertTrue("every line whole", lines.all { it.startsWith("{\"t\":") && it.endsWith("\"}") })
    assertTrue("newest kept", lines.last().contains("i=99\""))
    assertTrue("oldest dropped", lines.none { it.contains("i=0\"") })
    f.delete()
  }

  @Test fun theLagCheckResultFileSaysHowTheRunEnded() {
    val results = mutableListOf<String>()
    val w = object : LagCheck.World {
      var s = LagCheck.Snapshot(true, "HOME", true, true, emptySet(), true, false)
      override fun read() = s
      override fun apply(t: LagCheck.Snapshot, launch: Boolean) { s = t }
      override fun log(line: String) {}
      override fun hush(on: Boolean) {}
      override fun progress(step: Int?, done: Boolean) {}
      override fun result(json: String) { results += json }
    }
    var clock = 1_000_000L
    val m = LagCheck.Machine(w, object : LagCheck.Store { var v: String? = null; override fun load() = v; override fun save(v: String?) { this.v = v } }) { clock }
    m.start(0); var t = 1000L; while (m.tick(t)) { t += 1000; clock += 1000 }
    val done = results.single()
    println("lagcheck_json=$done")
    assertTrue(done, done.startsWith("{\"result\":\"done\",\"restored\":1,\"why\":null,\"steps_total\":12,\"step_ms\":12000,\"steps_done\":12,"))
    assertTrue(done, "\"name\":\"as_launched_again\"" in done)
    m.start(0); m.tick(1000); m.abort("pause")
    assertTrue(results.last(), results.last().startsWith("{\"result\":\"aborted\",\"restored\":null,\"why\":\"pause\""))
  }
}
