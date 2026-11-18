package com.overhand.app.windows

import com.overhand.app.FirstMinutes
import com.overhand.app.data.LocalReceiptStore
import com.overhand.app.data.RecordedSource
import com.overhand.app.voice.VoiceIntents
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** #1015 (R212): the app is Flowstate wherever it is seen or heard; "overhand" survives only as a silent voice alias. */
class RetiredNameTest {
  private val old = Regex("overhand", RegexOption.IGNORE_CASE)
  private val answers = JSONObject(File("src/main/res/raw/voice_answers.json").readText())
  private val intents = VoiceIntents.parse(answers.toString())

  @Test fun recordedWordsAreShownWithTheNewName() {
    assertEquals("Name Flowstate adopted.", RecordedText.neutral("Name Overhand adopted."))
    assertEquals("Flowstate", RecordedText.neutral("overhand"))
    // The fixture marker is matched, never renamed, so FirstMinutes still recognises it.
    assertEquals("[Overhand test 261] Approve?", RecordedText.neutral("[Overhand test 261] Approve?"))
  }

  @Test fun theBundledRecordingShowsNoRetiredNameAndNoFixtureMarker() {
    val src = RecordedSource({ File("src/main/assets/$it").readText() },
      LocalReceiptStore(Files.createTempDirectory("retired").resolve("receipts.json").toFile()))
    val shown = src.events.joinToString("\n") + src.architecture + src.decisions()
    assertFalse(old.find(shown)?.let { shown.substring(maxOf(0, it.range.first - 60), it.range.last + 40) } ?: "", old.containsMatchIn(shown))
    for (i in 0 until src.decisions().length()) assertFalse(FirstMinutes.isFixture(src.decisions().getJSONObject(i).optString("question")))
  }

  @Test fun voiceHearsBothSpellingsAndTheOldNameSilently() {
    for (q in listOf("what is flow state", "what's flowstate", "tell me about flow state", "what is overhand"))
      assertEquals(q, "headset", intents.match(q).answer.id)
  }

  @Test fun noAnswerSaysOrShowsTheOldName() {
    val list = answers.getJSONArray("answers")
    for (i in 0 until list.length()) assertFalse(list.getJSONObject(i).getString("text"), old.containsMatchIn(list.getJSONObject(i).getString("text")))
    assertTrue(answers.toString().contains("\"flow state:4\""))
  }
}
