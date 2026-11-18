package com.overhand.app.windows

import org.junit.Assert.*
import org.junit.Test

class PolishRulesTest {
  @Test fun sectionFiveVocabularyAcrossRecordedAndLiveAliases() {
    mapOf("submitted" to "queued","planned" to "queued","building" to "working",
      "at_risk" to "needs you","input_required" to "needs you","built" to "done",
      "verified" to "done","success" to "done","failed" to "failed","dropped" to "canceled")
      .forEach { (wire,word) -> assertEquals(word,PolishRules.status(wire)) }
  }
  @Test fun paginationKeepsEveryCharacterAndNeverStartsOrEndsInsideALine() {
    val ends=listOf(8,19,34,48,60)
    val ranges=PolishRules.pages(ends,listOf(35,70,105,140,175),80)
    assertEquals(listOf(0..18,19..47,48..59),ranges)
    assertEquals((0 until 60).toList(),ranges.flatMap { it.toList() })
  }
  @Test fun graphStatusOccupiesTheFirstFixedWidthLineEvenForLongNames() {
    val label=PolishRules.graphLabel("A very long captured component name","at_risk")
    assertEquals("needs you",label.chunked(9).first())
    assertTrue(label.endsWith("A very long captured component name"))
    assertEquals("done     ",PolishRules.graphLabel("bridge","built").chunked(9).first())
  }
  @Test fun pagesMoveAnIncompleteYardRowToTheNextPageWithoutLosingText() {
    val text="Header\n\n#21 done: first row\n\n#27 failed: second row\n"
    val ends=listOf(8,29,text.length)
    val pages=PolishRules.rowPages(text,ends,listOf(30,60,90),75)
    assertEquals(0 until 29,pages.first())
    assertEquals(text,pages.joinToString("") { text.substring(it) })
  }
  @Test fun aRowEndingOnTheLastLineThatFitsStaysOnThePage() {
    // #904: Yard's second row ended exactly on the page's last line and was pushed to page 2 anyway.
    val text="Header\n\nrow a\n\nrow b\n\nrow c\n"
    val ends=listOf(7,8,14,15,21,22,28)
    val pages=PolishRules.rowPages(text,ends,listOf(30,60,90,120,150,180,210),150)
    assertEquals("Header\n\nrow a\n\nrow b",text.substring(pages.first()).trimEnd())
    assertEquals(text,pages.joinToString("") { text.substring(it) })
  }
  @Test fun aPageIsNotLeftUnderHalfFullForTheNextRow() {
    // #880: header (1 line) then a 4-line row on a page of 4.3 lines: page 1 takes whole lines of that row, not the header alone.
    val text="Header\n\nrow a\nrow b\nrow c\nrow d\n"
    val ends=listOf(7,8,14,20,26,32)
    val pages=PolishRules.rowPages(text,ends,listOf(30,60,90,120,150,180),130)
    assertEquals(0 until 20,pages.first())
    assertEquals(text,pages.joinToString("") { text.substring(it) })
  }
  @Test fun oversizedRowKeepsEveryCharacterAcrossWholeLinePages() {
    val text="abcdefghijklmnopqrstuvwxyz"
    val pages=PolishRules.rowPages(text,listOf(8,16,26),listOf(30,60,90),40)
    assertEquals(text,pages.joinToString("") { text.substring(it) })
    assertEquals(3,pages.size)
  }
  @Test fun emptyPageIsSafe() { assertEquals(listOf(0 until 0),PolishRules.pages(emptyList(),emptyList(),60)) }
  @Test fun pinchIsLatchedUntilReleaseAndRapidSecondPinchIsIgnored() {
    val gate=RepeatInputGate()
    assertTrue(gate.update("left",true,true,1000))
    assertFalse(gate.update("left",true,true,2000))
    assertFalse(gate.update("left",false,true,2010))
    assertTrue(gate.update("left",true,true,2020))
    assertFalse(gate.update("left",false,true,2030))
    assertFalse(gate.update("left",true,true,2100))
    gate.update("left",false,true,2600)
    assertTrue(gate.update("left",true,true,2700))
  }
  @Test fun secondHandCannotDoubleActivateAndTrackingLossMustRearm() {
    val gate=RepeatInputGate()
    assertTrue(gate.update("left",true,true,1000))
    assertFalse(gate.update("right",true,true,1100))
    assertFalse(gate.update("left",false,false,2000))
    assertFalse(gate.update("left",true,true,2500))
    assertFalse(gate.update("left",false,true,2600))
    assertTrue(gate.update("left",true,true,2700))
  }
}
