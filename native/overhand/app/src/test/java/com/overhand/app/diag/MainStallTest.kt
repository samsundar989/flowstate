package com.overhand.app.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1385: the stall watchdog's timing on a fake clock: one `stall` row per stall, one `end` row, none while paused. */
class MainStallTest {
  private var now=0L
  private var paused=false
  private val rows=mutableListOf<String>()
  private val deep=Array(12) { StackTraceElement("sdk.Helper$it","work","Helper.kt",it) }+
    Array(6) { StackTraceElement("com.overhand.app.Caller$it","create","Caller.kt",it) }
  private val w=MainStall({ now },{ paused },{ "A.b:1<C.d:2" },{ rows.add(it) },{ deep })

  /** One watchdog period; [answers] is whether the main thread runs the posted probe within it. */
  private fun period(answers: Boolean) {
    now+=MainStall.PERIOD_MS
    val posted=w.check()
    if(posted && answers) { now+=1;w.answered() }
  }

  @Test fun threeSecondFreezeSamplesTheAppCaller() {
    w.check()
    repeat(12) { now+=250;w.check() }
    w.answered()
    assertTrue(rows.first().startsWith("stall event=main ms="))
    val samples=rows.filter { it.startsWith("stall event=sample ") }
    assertTrue("at least eight samples: $rows",samples.size>=8)
    assertTrue(samples.all { it.contains(" sdk=") && it.contains(" app=") })
    assertTrue(samples.all { it.contains("app=com.overhand.app.Caller0.create:0<com.overhand.app.Caller1.create:1<com.overhand.app.Caller2.create:2<com.overhand.app.Caller3.create:3") })
    assertTrue(samples.none { it.contains("Caller4") || it.contains("sdk.Helper3") })
    assertEquals((750L..3000L step 250).toList(),samples.map { it.substringAfter("ms=").substringBefore(" ").toLong() })
    assertEquals("stall event=end ms=3000",rows.last())
  }

  @Test fun quietMainThreadLogsNothing() {
    repeat(400) { period(true) }  // 100 s idle
    assertEquals(emptyList<String>(),rows)
  }

  @Test fun oneStallLogsOneRowAndOneEnd() {
    repeat(4) { period(true) }
    now+=MainStall.PERIOD_MS;assertTrue(w.check())   // probe posted at t0; main thread is now blocked
    val t0=now
    repeat(220) { now+=MainStall.PERIOD_MS;assertTrue(!w.check()) }  // 55 s blocked: probes are not stacked
    assertEquals(1,rows.count { it.startsWith("stall event=main") })
    assertTrue(rows[0],rows[0].matches(Regex("stall event=main ms=(\\d+) at=A\\.b:1<C\\.d:2")))
    assertTrue("logged once more than 500 ms late",Regex("ms=(\\d+)").find(rows[0])!!.groupValues[1].toLong() in 501..750)
    w.answered()
    assertEquals("stall event=end ms=${now-t0}",rows.last())
    assertEquals(40,rows.count { it.startsWith("stall event=sample ") })
    assertEquals(42,rows.size)
    repeat(20) { period(true) }
    assertEquals("recovered: no more rows",42,rows.size)
    // A second stall gets its own pair.
    now+=MainStall.PERIOD_MS;w.check();repeat(4) { now+=MainStall.PERIOD_MS;w.check() };w.answered()
    assertEquals(46,rows.size);assertTrue(rows[42].startsWith("stall event=main ms=") && rows.last().startsWith("stall event=end ms="))
  }

  @Test fun repeatedChecksWithinAPeriodDoNotDuplicateSamples() {
    w.check();now=750;w.check()
    repeat(249) { now++;w.check() }
    assertEquals(1,rows.count { it.startsWith("stall event=sample ") })
    now++;w.check()
    assertEquals(2,rows.count { it.startsWith("stall event=sample ") })
  }

  @Test fun shortDelaysAreNotStalls() {
    now+=MainStall.PERIOD_MS;w.check()
    now+=MainStall.LATE_MS;w.check();w.answered()  // exactly 500 ms late: not a stall
    assertEquals(emptyList<String>(),rows)
  }

  @Test fun nothingWhilePaused() {
    paused=true
    repeat(400) { now+=MainStall.PERIOD_MS;assertTrue("no probe posted while paused",!w.check()) }
    assertEquals(emptyList<String>(),rows)
    paused=false
    repeat(10) { period(true) }
    assertEquals(emptyList<String>(),rows)
  }
}
