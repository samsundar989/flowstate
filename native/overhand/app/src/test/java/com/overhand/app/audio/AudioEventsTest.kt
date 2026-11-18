package com.overhand.app.audio

import org.junit.Assert.*
import org.junit.Test

class AudioEventsTest {
  @Test fun attentionOnlyOnRisingEdge() {
    val e=AudioEvents()
    assertNull(e.station("a",false))
    assertEquals(Earcon.NEEDS_YOU,e.station("a",true))
    assertNull(e.station("a",true))
    assertNull(e.station("a",false))
    assertEquals(Earcon.NEEDS_YOU,e.station("a",true))
    assertEquals(Earcon.NEEDS_YOU,e.station("b",true))
  }
  @Test fun historicalCompletionsDoNotAnnounceButTransitionsDo() {
    val e=AudioEvents()
    assertNull(e.task("old","done"));assertNull(e.task("active","working"));e.finishSnapshot()
    assertNull(e.task("old","done"))
    assertNull(e.task("new","done"))
    assertEquals(Earcon.DONE,e.task("active","done"))
    assertNull(e.task("active","done"))
    assertNull(e.task("active","failed"))
  }
  @Test fun recordingUsesSameAgentRegistry() {
    assertEquals(Earcon.NEEDS_YOU,AudioEvents.replay("decision"))
    assertEquals(Earcon.DONE,AudioEvents.replay("done"))
    assertNull(AudioEvents.replay("message"));assertNull(AudioEvents.replay("error"))
    assertEquals(6,Earcon.entries.map { it.path }.toSet().size)
  }
}
