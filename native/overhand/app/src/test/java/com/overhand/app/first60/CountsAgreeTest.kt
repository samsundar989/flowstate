package com.overhand.app.first60

import com.overhand.app.RecordedFirstCard
import com.overhand.app.data.RecordedReducer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** #1039: on the recorded day the greeting and the first card give the same number of blocking questions, from the same rows. */
class CountsAgreeTest {
  private val judge = listOf(File("src/main/assets/judge"), File("app/src/main/assets/judge")).first { it.isDirectory }
  private val raw = listOf(File("src/main/res/raw"), File("app/src/main/res/raw")).first { it.isDirectory }
  private val day = File(judge, "real_day.jsonl").readText()

  @Test fun greetingNumberEqualsTheFirstCardsCounterOnTheRecordedDay() {
    val b = First60Brief(JSONObject(File(raw, "first60_spoken_cards.json").readText()).getJSONObject("cards"))
    val greeting = b.brief(First60Brief.parse(day), "recorded")
    val greetingBlocking = greeting.counts.getValue("blocking")
    // The card's list: what DecisionCards.accept keeps from RecordedSource.decisions() before any practice answer.
    val all = RecordedReducer.reduce(day).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    val waiting = all.filter { it.optString("status") == "open" }
    val cardBlocking = RecordedFirstCard.blocking(waiting)
    val heading = RecordedFirstCard.position(1, waiting.size, 0, blocking = cardBlocking)

    assertEquals(greetingBlocking, cardBlocking)
    assertTrue(greeting.text, greeting.text.contains("${First60Brief.words(greetingBlocking)} questions block them"))
    assertEquals("Question 1 of 11 · 3 block work", heading)
    // Every blocking question is still waiting, so the greeting's 3 are among the card's 11.
    assertEquals(all.count { First60Brief.truthy(it.opt("blocking")) }, cardBlocking)
    // The page's "stopped 14 times": 14 asked, 3 answered that night, 11 still waiting.
    assertEquals(14, all.size); assertEquals(3, all.count { it.optString("status") == "historical" }); assertEquals(11, waiting.size)
  }
}
