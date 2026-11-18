package com.overhand.app.data

import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.nio.file.Files

class JudgeCoreTest {
  private fun asset(path: String)=File("src/main/assets/$path").readText()
  private fun store()=LocalReceiptStore(Files.createTempDirectory("receipts").resolve("answers.json").toFile())
  private fun source(store: LocalReceiptStore=store(),http: HttpClient?=null)=RecordedSource(::asset,store,http)
  private fun rows(a: JSONArray)=(0 until a.length()).map { a.getJSONObject(it) }
  private fun open(s: RecordedSource)=rows(s.decisions()).first { it.getString("status")=="open" }

  @Test fun fixtureHasFourteenDecisions() { assertEquals(14,RecordedReducer.reduce(asset("judge/real_day.jsonl")).length()) }
  @Test fun curatedOrderLeadsWithGeothermalAndHasUniqueCompleteRanks() {
    val ds=rows(source().decisions()).sortedBy { it.getInt("judge_order") }
    assertEquals("16",ds.first().getString("id"))
    assertEquals((1..14).toList(),ds.map { it.getInt("judge_order") })
    assertTrue(ds.first().getString("question").contains("geothermal faults"))
    assertTrue(ds.first().getJSONArray("options").getJSONObject(1).getString("label").contains("fault scarps from laser elevation maps"))
  }
  @Test fun curationPreservesChronologicalReplayEvents() {
    val times=source().events.map { it.getLong("t_ms") }
    assertEquals(times.sorted(),times)
    assertTrue(rows(source().decisions()).first().getString("id")!="16")
  }
  @Test fun historicalAnswersRemainAtEndOfJudgeOrder() {
    val ds=rows(source().decisions()).sortedBy { it.getInt("judge_order") }
    assertTrue(ds.takeLast(3).all { it.has("historical_answer") })
    assertEquals(listOf("16","17","18","10","12"),ds.take(5).map { it.getString("id") })
  }
  @Test fun fixtureHasElevenOpenAndThreeHistorical() {
    val rows=rows(source().decisions())
    assertEquals(11,rows.count { it.getString("status")=="open" })
    assertEquals(3,rows.count { it.has("historical_answer") && it.getString("status")=="historical" })
  }
  @Test fun receiptSurvivesReloadWithAllFields() {
    val file=Files.createTempDirectory("receipt-reload").resolve("answers.json").toFile()
    LocalReceiptStore(file).save("fixture","q1","B","Other option","thumbs_down",900,1234)
    val r=LocalReceiptStore(file).find("fixture","q1")!!
    assertEquals("B",r.getString("option_id"));assertEquals("Other option",r.getString("option_label"))
    assertEquals("recorded",r.getString("mode"));assertEquals(900,r.getLong("recorded_time_ms"))
    assertEquals(1234,r.getLong("device_utc_ms"));assertEquals("thumbs_down",r.getString("input_method"))
  }
  @Test fun doubleAnswerKeepsFirstReceipt() {
    val s=source();val d=open(s);val options=d.getJSONArray("options")
    val first=options.getJSONObject(0).getString("label")
    s.answer(d,first,"pinch")
    val result=s.answer(d,options.getJSONObject(1).getString("label"),"thumbs_up")
    assertTrue(result.contains(first))
    assertEquals(10,rows(s.decisions()).count { it.getString("status")=="open" })
  }
  @Test fun recordedSourceNeverCallsInjectedHttp() {
    var calls=0
    val s=source(http=HttpClient { _,_,_ -> calls++;error("Network forbidden") })
    val d=open(s);s.answer(d,d.getJSONArray("options").getJSONObject(0).getString("label"),"thumbs_up")
    s.decisions();s.restart();s.decisions()
    assertEquals(0,calls)
  }
  @Test fun historicalAnswerRemainsReadOnly() {
    val s=source();val d=rows(s.decisions()).first { it.has("historical_answer") }
    assertThrows(IllegalStateException::class.java) { s.answer(d,"anything","pinch") }
  }
  @Test fun restartClearsOnlyPracticeAnswers() {
    val s=source();val d=open(s)
    s.answer(d,d.getJSONArray("options").getJSONObject(0).getString("label"),"pinch");s.restart()
    assertEquals(11,rows(s.decisions()).count { it.getString("status")=="open" })
    assertEquals(3,rows(s.decisions()).count { it.has("historical_answer") })
  }
  @Test fun invalidOptionDoesNotResolveQuestion() {
    val s=source();assertThrows(NoSuchElementException::class.java) { s.answer(open(s),"invented","pinch") }
    assertEquals(11,rows(s.decisions()).count { it.getString("status")=="open" })
  }
  @Test fun failedPersistenceLeavesQuestionPending() {
    val parent=Files.createTempFile("not-a-directory","tmp").toFile()
    val s=source(LocalReceiptStore(File(parent,"answers")))
    val d=open(s)
    assertThrows(Exception::class.java) { s.answer(d,d.getJSONArray("options").getJSONObject(0).getString("label"),"pinch") }
    assertEquals(11,rows(s.decisions()).count { it.getString("status")=="open" })
  }
  @Test fun returnedStateCannotMutateFrozenSnapshot() {
    val s=source();s.decisions().getJSONObject(0).put("status","bad")
    assertNotEquals("bad",s.decisions().getJSONObject(0).getString("status"))
  }
  @Test fun modeSwitchRejectsPreviousGenerationEvenAfterRoundTrip() {
    val g=ModeGeneration();val old=g.token;g.advance();g.advance()
    assertFalse(g.accepts(old));assertTrue(g.accepts(g.token))
  }
  @Test fun liveRequiresValidationAndAcknowledgement() {
    // An acknowledgement alone is not decision-scoped: confirmation needs the receiver's row to show the answer.
    val r=FakeReceiver();r.add(1);r.postMode="ack_no_commit"
    val live=LiveSource(r.http,"http://a:1")
    val d=JSONObject().put("id","1").put("project","alpha").put("question","Q1").put(DecisionScope.ORIGIN,"http://a:1")
    assertThrows(IllegalStateException::class.java) { live.answer(d,"A","pinch") }
    assertEquals(0,r.posts);live.validate()
    assertThrows(IllegalStateException::class.java) { live.answer(d,"A","pinch") }
    assertFalse(live.writable);r.postMode="ok";live.validate();assertEquals("Answer received",live.answer(d,"A","pinch"))
  }
  @Test fun fixtureHashesIsolateReceipts() {
    val s=store();s.save("one","1","A","A","pinch",0)
    assertNull(s.find("two","1"))
  }
  @Test fun liveDecisionFallbackOnlyOn404() {
    val calls=mutableListOf<String>()
    val live=LiveSource(HttpClient { _,path,_ ->
      calls.add(path)
      if(path=="/api/decisions") throw HttpFailure(404)
      "{\"decisions\":[]}"
    })
    assertEquals(0,live.decisions().length())
    assertEquals(listOf("/api/decisions","/api/state"),calls)
    var attempted=0
    val broken=LiveSource(HttpClient { _,_,_ -> attempted++;throw HttpFailure(500) })
    assertThrows(HttpFailure::class.java) { broken.decisions() }
    assertEquals(1,attempted);assertFalse(broken.writable)
  }
}
