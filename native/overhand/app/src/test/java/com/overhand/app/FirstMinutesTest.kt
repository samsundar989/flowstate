package com.overhand.app

import org.junit.Assert.*
import org.junit.Test

class FirstMinutesTest {
  @Test fun graphAndTaskStatesUseSectionFiveVocabulary() {
    val cases=mapOf("SUBMITTED" to "queued","parked" to "queued","waiting" to "queued",
      "WORKING" to "working","claimed" to "working","building" to "working",
      "INPUT_REQUIRED" to "needs you","at_risk" to "needs you","COMPLETED" to "done",
      "verified" to "done","FAILED" to "failed","CANCELED" to "canceled","dropped" to "canceled")
    cases.forEach { (wire,word) -> assertEquals(word,FirstMinutes.statusWord(wire)) }
    assertEquals("future_state",FirstMinutes.statusWord("future_state"))
  }
  @Test fun purposeNamesTheWorkAndAction() {
    assertTrue(FirstMinutes.PURPOSE.contains("agent projects"))
    assertTrue(FirstMinutes.PURPOSE.contains("answer decisions"))
    assertTrue(FirstMinutes.TAGLINE.length <= 140)
  }
  @Test fun recordedFirstCardStatesPurposeActionAndLocalOutcome() {
    val c=RecordedFirstCard
    assertTrue(c.PURPOSE.contains("Replay"));assertTrue(c.PURPOSE.contains("real coding-agent work"))
    assertTrue(c.CHOOSE.contains("Pinch"));assertTrue(c.CONFIRM.contains("Confirm"));assertTrue(c.CONFIRM.contains("thumbs up"))
    assertTrue(c.PRACTICE.contains("headset"));assertTrue(c.PRACTICE.contains("no agent receives"))
    for(text in listOf(c.CHOOSE,c.CONFIRM)) assertTrue(("$text ${c.PRACTICE}").split(Regex("\\s+")).size<=25)
    assertEquals("You chose · B: A plus lidar",c.savedChoice("B: A plus lidar"))
  }
  @Test fun droppedCountSaysWhyAndAskerIsPlainWords() {
    assertEquals("Question 1 of 11",RecordedFirstCard.position(1,11,0))
    assertEquals("Question 2 of 11 · 1 practiced so far",RecordedFirstCard.position(1,10,1))
    assertEquals("Question 4 of 11 · 2 practiced so far",RecordedFirstCard.position(2,9,2))
    assertEquals("Question 2 of 11 · 1 answered",RecordedFirstCard.position(1,10,1,practice=false))
    assertEquals("Question 2 of 11 · 1 practiced so far · 2 block work",RecordedFirstCard.position(1,10,1,blocking=2))
    val lead=RecordedFirstCard.askedBy("Geothermal faults","Head",true)
    assertEquals("Geothermal faults: The coding agent leading this project asked a person. “Original run choice” means that person's earlier decision. “Practice answer” means your choice.",lead)
    assertFalse(lead.contains("Head"))
    assertTrue(RecordedFirstCard.askedBy("","Agent",false).startsWith("A coding agent on this project is asking"))
    assertTrue(RecordedFirstCard.askedBy("X","Planner",false).contains("“Planner”"))
  }
  @Test fun savedPracticeNeverClaimsDeliveryOrResumption() {
    val all=listOf(RecordedFirstCard.SAVED_TITLE,RecordedFirstCard.SAVED_DETAIL).joinToString(" ").lowercase()
    assertTrue(all.contains("no live agent was contacted or resumed"))
    assertFalse(all.contains("developer"))
    for(claim in listOf("agent resumed","unblocked","sent to","delivered")) assertFalse(claim,all.contains(claim))
  }
  @Test fun fixturesAreRecognizedWithoutHidingRealQuestions() {
    assertTrue(FirstMinutes.isFixture("[Overhand test 261] Approve this probe?"))
    assertTrue(FirstMinutes.isFixture("  [overhand TEST 999] Another probe"))
    assertFalse(FirstMinutes.isFixture("Should we test the new build?"))
    assertFalse(FirstMinutes.isFixture("Should we keep [Overhand test 261] in the audit?"))
  }
  @Test fun firstDrawUsesMonotonicTimeAndRecordsOnce() {
    val trace=LaunchTrace(100,1234567890)
    val line=trace.firstDraw(1350)!!
    assertTrue(line.contains("duration_ms=1250"))
    assertTrue(line.contains("launch_unix_ms=1234567890"))
    assertTrue(line.contains("content_elapsed_ms=1350"))
    assertTrue(line.contains("compositor_verified=false"))
    assertNull(trace.firstDraw(1500))
  }
  @Test fun slowDrawIsNotClampedToPassThreshold() {
    assertTrue(LaunchTrace(0,0).firstDraw(8001)!!.contains("duration_ms=8001"))
  }
  @Test fun documentUsesNativeVocabularyAndNeverLoadsDemoOnFailure() {
    val source="const STATE_WORD = {idle: 'idle'};\nDEMO = true; setState(demoState()); setInterval(demoTick, 2500);"
    val result=FirstMinutes.dashboardDocument(source)
    assertTrue(result.contains("const STATE_WORD = window.afStatusWords;"))
    assertFalse(result.contains("setState(demoState())"))
    assertTrue(result.contains("Reconnect in Settings"))
  }
  @Test(expected=IllegalArgumentException::class) fun changedTemplateFailsClosed() {
    FirstMinutes.dashboardDocument("unknown document")
  }
}
