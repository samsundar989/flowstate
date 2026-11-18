package com.overhand.app.first60

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

class GreetingAskTest {
  private val raw = listOf(File("src/main/res/raw"), File("app/src/main/res/raw")).first { it.isDirectory }
  private val meta = JSONObject(File(raw, "first60_cues.json").readText())
  private fun cues(set: String) = meta.getJSONObject("sets").getJSONObject(set).getJSONArray("cues").let { a ->
    (0 until a.length()).map { a.getJSONObject(it) }.map { Cue(it.getInt("i"), (it.getDouble("start_s") * 1000).toLong(), (it.getDouble("end_s") * 1000).toLong(), it.getString("text")) } }

  @Test fun cuesFireAtTheirOffsetsAndTheMicOpensWhenTheQuestionEnds() {
    val c = cues("day"); val seq = GreetingSequencer(c, 1, 11_650)
    seq.start(1000)
    val seen = mutableListOf<Pair<Long, GreetingSequencer.Event>>()
    for (t in 1000L..23_000L step 10) seq.tick(t).forEach { seen += t - 1000 to it }
    val sentences = seen.filter { it.second is GreetingSequencer.Event.Sentence }
    assertEquals(c.size, sentences.size)
    sentences.zip(c).forEach { (s, cue) -> assertTrue(s.first - cue.startMs in 0..10) }
    val ask = seen.first { it.second == GreetingSequencer.Event.AskEnded }.first
    assertEquals(8_700.0, ask.toDouble(), 10.0)
    assertEquals("Should they add laser elevation maps to find geothermal faults?", c[1].text)
    assertTrue(seen.last().second == GreetingSequencer.Event.Finished)
  }

  @Test fun aPinchOrSpeechFadesOutWithin50msAndNothingMoreFires() {
    val seq = GreetingSequencer(cues("day"), 1, 11_650); seq.start(0)
    seq.tick(6_000)
    val ev = seq.cut(6_000, "pinch")
    assertTrue(ev.first() is GreetingSequencer.Event.Cut && GreetingSequencer.Event.AskEnded in ev)
    assertEquals(1f, GreetingSequencer(cues("day"), 1, 11_650).also { it.start(0) }.gain(5000), 0f)
    assertTrue(seq.gain(6_020) in 0.01f..0.99f)
    assertEquals(0f, seq.gain(6_000 + GreetingSequencer.FADE_MS), 0f)
    assertTrue(GreetingSequencer.FADE_MS <= 50)
    assertTrue(seq.tick(21_000).isEmpty())
  }

  private class Rec : AskState.Effects {
    val log = mutableListOf<String>()
    override fun openMic() { log += "open" }
    override fun closeMic() { log += "close" }
    override fun reprompt() { log += "reprompt" }
    override fun otherSpeech(text: String) { log += "other:$text" }
    override fun answer(a: AskState.Answer) { log += "answer:${a.option}:${a.input}:${a.saidYes}" }
  }
  private val opts = listOf("A: Image model on the map layers", "B: A + laser elevation maps")

  @Test fun yesPicksTheRecommendationNoTheOtherOption() {
    for ((said, want) in listOf("Yes." to "B", "yeah sure" to "B", "I agree" to "B", "No." to "A", "I don't agree" to "A", "nope" to "A")) {
      val r = Rec(); val a = AskState(opts, "B").also { it.bind(r) }
      a.questionEnded(0); a.heard(2000, said)
      assertEquals(said, listOf("open", "close", "answer:$want:voice:${want == "B"}"), r.log)
    }
    val r = Rec(); val a = AskState(listOf("A: Hold the pair", "B: Re-enter"), "A").also { it.bind(r) }
    a.questionEnded(0); a.heard(1, "yes"); assertEquals("answer:A:voice:true", r.log.last())
  }

  @Test fun anythingElseGoesToVoiceCAndThePinchedOptionWins() {
    val r = Rec(); val a = AskState(opts, "B").also { it.bind(r) }
    a.questionEnded(0)
    assertEquals(AskState.Kind.OTHER, a.heard(1500, "Why?"))
    assertEquals("other:Why?", r.log.last()); assertEquals(AskState.Phase.LISTENING, a.phase)
    a.pinched("A"); assertEquals("answer:A:pinch:false", r.log.last())
    a.heard(3000, "yes"); a.pinched("B"); assertEquals(1, r.log.count { it.startsWith("answer") })
    // A pinch during the greeting, before the mic opened, is an answer too.
    val r2 = Rec(); AskState(opts, "B").also { it.bind(r2) }.pinched("B"); assertEquals(listOf("answer:B:pinch:true"), r2.log)
  }

  @Test fun oneRepromptAfterEightSecondsNeverTwo() {
    val r = Rec(); val a = AskState(opts, "B").also { it.bind(r) }
    a.questionEnded(1000)
    a.tick(8_999); assertFalse("reprompt" in r.log)
    a.tick(9_000); assertEquals(1, r.log.count { it == "reprompt" })
    a.tick(30_000); assertEquals(1, r.log.count { it == "reprompt" })
  }

  @Test fun endpointerFindsTheEndOfARenderedLineAndIgnoresSilence() {
    val ep = Endpointer()
    val silence = ShortArray(800)
    repeat(40) { assertFalse(ep.feed(silence, 800)) }
    val pcm = com.overhand.app.voice.Wav.read(File(raw, "first60_then_no.wav").readBytes()).samples + ShortArray(16000)
    var endedAt = -1
    var i = 0
    while (i + 800 <= pcm.size && endedAt < 0) { if (ep.feed(pcm.copyOfRange(i, i + 800), 800)) endedAt = i; i += 800 }
    assertTrue("ended at $endedAt of ${pcm.size}", endedAt > 0)
    assertTrue(ep.utterance!!.size > 16000 * 3)
    // A 1 kHz tone of 0.5 s at speech level is one utterance.
    val ep2 = Endpointer(); val tone = ShortArray(8000) { (3000 * sin(2 * PI * 1000 * it / 16000)).toInt().toShort() }
    var ended = false
    (tone + ShortArray(16000)).toList().chunked(800).forEach { if (ep2.feed(it.toShortArray(), it.size)) ended = true }
    assertTrue(ended)
  }
}
