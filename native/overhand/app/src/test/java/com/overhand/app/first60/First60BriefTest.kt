package com.overhand.app.first60

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** The Kotlin port of tools/first60/brief.py on the cases of tools/first60/test_brief.py. */
class First60BriefTest {
  private val judge = listOf(File("src/main/assets/judge"), File("app/src/main/assets/judge")).first { it.isDirectory }
  private val raw = listOf(File("src/main/res/raw"), File("app/src/main/res/raw")).first { it.isDirectory }
  private val logs = mapOf("day" to File(judge, "real_day.jsonl"), "cards" to File(judge, "demo/cards/day.jsonl"), "cells" to File(judge, "demo/cells/day.jsonl"))
  private val b = First60Brief(JSONObject(File(raw, "first60_spoken_cards.json").readText()).getJSONObject("cards"))
  private fun ev(k: String) = First60Brief.parse(logs.getValue(k).readText())

  @Test fun fixtureGreetingIsShortCleanAndDeterministic() {
    for (k in logs.keys) {
      val a = b.brief(ev(k)); assertEquals(a, b.brief(ev(k)))
      assertTrue(a.wordsBeforeAsk <= First60Brief.MAX_WORDS_BEFORE_ASK)
      assertTrue(a.sentences[a.askIndex].endsWith("?"))
      assertFalse("digits are dropped by the headset voice", Regex("\\d").containsMatchIn(a.text))
    }
  }

  @Test fun portSaysExactlyWhatThePythonRenderedIntoTheWavs() {
    val sets = JSONObject(File(raw, "first60_cues.json").readText()).getJSONObject("sets")
    for (k in logs.keys) {
      val s = sets.getJSONObject(k); val a = b.brief(ev(k))
      val cues = s.getJSONArray("cues").let { c -> (0 until c.length()).map { c.getJSONObject(it).getString("text") } }
      assertEquals(k, cues, a.sentences)
      val d = s.getJSONObject("decision")
      assertEquals(d.getString("decision_id"), a.decision!!.decisionId); assertEquals(d.getString("yes_option"), a.decision!!.yesOption)
      assertEquals(d.getString("project_name"), a.decision!!.projectName)
      assertEquals(s.getString("then_yes"), b.afterAnswer(a.decision, true))
    }
  }

  /** t873: one clause of context, then the question; the rendered question ends <= 9 s after the greeting starts. */
  @Test fun questionIsSecondAndEndsWithinNineSeconds() {
    val sets = JSONObject(File(raw, "first60_cues.json").readText()).getJSONObject("sets")
    for (k in logs.keys) {
      val a = b.brief(ev(k)); assertEquals(k, 1, a.askIndex); assertTrue(a.sentences[1].startsWith("Should they "))
      val askEnd = sets.getJSONObject(k).getJSONArray("cues").getJSONObject(a.askIndex).getDouble("end_s")
      assertTrue("$k question ends at $askEnd s", askEnd <= 9.0)
    }
  }

  @Test fun countsMatchTheRows() {
    val c = b.counts(ev("day")); val a = b.brief(ev("day"))
    assertEquals(22, c["done"]); assertEquals(3, c["failed"])
    assertTrue(a.text.startsWith("In this replay, agents did twenty two tasks, and three questions block them. Should they add"))
    assertTrue("agents did twenty five tasks, and asked three questions." in b.brief(ev("cards")).text)
    assertTrue("agents did thirty seven tasks, and asked three questions." in b.brief(ev("cells")).text)
  }

  @Test fun theOneThingIsTheJudgesFirstCardAndYesMeansTheRecommendation() {
    val a = b.brief(ev("day"))
    assertEquals("16", a.decision!!.decisionId); assertEquals("Geothermal faults", a.decision!!.projectName)
    for (k in logs.keys) {
      val d = b.brief(ev(k)).decision!!
      val row = ev(k).first { it.optString("kind") == "decision" && it.opt("decision_id").toString() == d.decisionId }
      assertTrue(row.getString("recommendation").startsWith(d.yesOption + ":"))
    }
    val row = ev("day").first { it.optString("kind") == "decision" && it.opt("decision_id").toString() == "16" }
    assertEquals("1", row.opt("judge_order").toString())
  }

  @Test fun answeredDecisionIsSkippedAndBlockingWins() {
    val e = ev("day") + JSONObject().put("kind", "answer").put("project", "s4a3f3b09ec1c0d92").put("decision_id", "16").put("t_ms", 72000000)
    val a = b.brief(e)
    assertEquals("17", a.decision!!.decisionId); assertTrue("two questions block them." in a.text)
  }

  @Test fun fallbackWordingForEveryCardIsShort() {
    for (k in logs.keys) for (d in ev(k).filter { it.optString("kind") == "decision" }) {
      val ln = b.cardLines(JSONObject(d.toString()).put("project_name", d.optString("project_name") + " (unlisted)"))
      assertTrue(ln.values.filter { it.isNotEmpty() }.joinToString(" ").split(" ").size <= 30)
    }
  }

  @Test fun emptyAndLiveVariants() {
    val e = b.brief(emptyList(), "empty")
    assertNull(e.decision); assertEquals("What do you want to build?", e.sentences.last()); assertTrue(e.wordsBeforeAsk <= 45)
    val lv = b.brief(ev("day"), "live")
    assertTrue(lv.sentences[0].startsWith("While you were away, your agents did twenty two tasks")); assertTrue(lv.wordsBeforeAsk <= 45)
  }

  @Test fun afterAnswerLines() {
    for (k in logs.keys) {
      val d = b.brief(ev(k)).decision
      for (yes in listOf(true, false)) assertFalse(Regex("\\d").containsMatchIn(b.afterAnswer(d, yes)))
    }
    assertTrue(b.afterAnswer(null, true).startsWith("Got it."))
    assertEquals("Practice answer saved here. The recorded run keeps its original outcome. Ask me anything, or look around.", b.afterAnswer(b.brief(ev("day")).decision, true))
    assertEquals("Practice answer saved here. The recorded run keeps its original outcome. Ask me anything, or look around.", b.afterAnswer(b.brief(ev("day")).decision, false))
  }
}
