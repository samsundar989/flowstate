package com.overhand.app.voice

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1055: every canned answer in res/raw/voice_answers.json is 45 words or fewer (same split as runs/voice_gate/score_ai_answers.py). */
class CannedAnswerLengthTest {
  @Test fun everyCannedAnswerIsAtMost45Words() {
    val answers = JSONObject(File("src/main/res/raw/voice_answers.json").readText()).getJSONArray("answers")
    assertTrue("no answers parsed", answers.length() > 10)
    val over = (0 until answers.length()).map { answers.getJSONObject(it) }
      .map { it.getString("id") to it.getString("text").trim().split(Regex("\\s+")).size }
      .filter { it.second > 45 }
    assertTrue("canned answers over 45 words: $over", over.isEmpty())
  }
}
