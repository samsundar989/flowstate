package com.overhand.app.windows

import com.overhand.app.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RecordedContentTest {
  private var calls=0
  private fun content(read: (String)->String={ File("src/main/assets/$it").readText() }): RecordedContent {
    val store=LocalReceiptStore(Files.createTempDirectory("recorded-windows").resolve("receipts.json").toFile())
    return RecordedContent(RecordedSource(read,store,HttpClient { _,_,_ -> calls++;error("Offline: HTTP forbidden") }))
  }
  @Test fun fleetIncludesAllProjectsTaskTextAndDatedMetricValuesWithoutHttp() {
    val c=content();val text=c.window("fleet")
    c.projects.forEach { assertTrue(text.contains(it.getString("name"))) }
    c.source.events.filter { it.optString("kind")=="task_assigned" }.forEach { assertTrue(text.contains(it.getString("title"))) }
    c.source.events.filter { it.optString("kind")=="metric" }.forEach { assertTrue(text.contains(it.getString("name")+" = "+it.get("value")));assertTrue(text.contains(RecordedContent.stamp(it))) }
    assertEquals(0,calls)
  }
  @Test fun blueprintIncludesRealComponentFieldsAndSeparateDateWithoutHttp() {
    val c=content();val text=c.window("blueprint")
    assertTrue(text.startsWith("Architecture snapshot · Nov 5"));assertEquals(16,c.components(c.defaultProject).size)
    c.components(c.defaultProject).forEach { row -> listOf("desc","why","technique","shown").forEach { assertTrue(text.contains(if(it=="shown") com.overhand.app.FirstMinutes.statusWord(row.getString(it)) else row.getString(it))) } }
    assertEquals(0,calls)
  }
  @Test fun codeIncludesModuleFileAndSymbolFromCapturedMapWithoutHttp() {
    val c=content();val text=c.window("code");val m=c.source.code(c.defaultProject).getJSONArray("modules").getJSONObject(0)
    assertTrue(text.contains("Module: "+m.getString("label")))
    // #1096: a hidden file name is left out; the first shown one is listed, and no row is filler.
    val file=RecordedContent.rows(c.source.code(c.defaultProject).getJSONArray("modules")).flatMap { RecordedContent.rows(it.optJSONArray("sample")) }
      .map { it.getString("label") }.firstOrNull { RecordedContent.shown(it) }
    if(file!=null) assertTrue(text.contains("File: $file")) else assertFalse(text.contains("File: "))
    assertFalse(RecordedContent.FILLER.containsMatchIn(text))
    assertTrue(text.contains(m.getJSONArray("top").getJSONObject(0).getString("name")))
    assertEquals(0,calls)
  }
  @Test fun yardHasExactlyTwentyTwoSuccessesAndThreeFailuresWithTimesWithoutHttp() {
    val c=content();val text=c.window("build")
    assertTrue(text.contains("22 done · 3 failed"))
    val outcomes=c.source.events.filter { it.optString("kind") in listOf("done","error") }
    assertEquals(25,outcomes.size)
    outcomes.forEach { assertTrue(text.contains(PolishRules.rowTitle(it.getString("title"))));assertTrue(text.contains(RecordedContent.stamp(it))) }
    assertEquals(0,calls)
  }
  @Test fun chatHasReadOnlyTimelineHistoricalAnswersAndPracticeReceiptWithoutHttp() {
    val c=content();val d=RecordedContent.rows(c.source.decisions()).first { it.getString("status")=="open" }
    val label=d.getJSONArray("options").getJSONObject(0).getString("label")
    c.source.answer(d,label,"test")
    val text=c.window("chat")
    assertTrue(text.contains("Practice: $label"));assertTrue(text.contains("Recorded developer answer:"));assertTrue(text.contains("read only"))
    c.source.events.filter { it.optString("kind")=="message" }.forEach { assertTrue(text.contains(it.getString("detail"))) }
    assertEquals(0,calls)
  }
  @Test fun graphMatchesAllBundledNodesAndDependenciesWithoutHttp() {
    val c=content()
    c.projects.forEach { p ->
      val id=p.getString("id");val components=c.components(id);val g=c.graph(id)
      assertEquals(components.map { it.getString("id") }.toSet(),g.nodes.map { it.id }.toSet())
      val edges=components.flatMap { row -> val a=row.getJSONArray("after");(0 until a.length()).map { a.getString(it) to row.getString("id") } }.toSet()
      assertEquals(edges,g.edges.map { it.from to it.to }.toSet())
    }
    assertEquals(16,c.graph(c.defaultProject).nodes.size);assertEquals(23,c.graph(c.defaultProject).edges.size);assertEquals(0,calls)
  }
  @Test fun stationsInstantiateSixRecordedProjectsAndTenOpenProjectDecisionLightsWithoutHttp() {
    val c=content();val stations=c.stations()
    assertEquals(6,stations.size);assertEquals(c.projects.map { it.getString("id") }.toSet(),stations.map { it.id }.toSet())
    assertEquals(10,stations.sumOf { it.decisions });assertTrue(stations.any { it.needsYou });assertEquals(0,calls)
  }
  @Test fun practiceReceiptReducesOnlyItsRecordedStationLight() {
    val c=content();val before=c.stations();val d=RecordedContent.rows(c.source.decisions()).first { it.getString("status")=="open" && it.getString("project")!="chief" }
    c.source.answer(d,d.getJSONArray("options").getJSONObject(0).getString("label"),"test")
    val after=c.stations()
    before.zip(after).forEach { (a,b) -> assertEquals(a.decisions-(if(a.id==d.getString("project")) 1 else 0),b.decisions) }
    assertEquals(9,after.sumOf { it.decisions });assertEquals(0,calls)
  }
  @Test fun missingSnapshotsAreExplicitAndDoNotInventNodesOrSymbols() {
    val c=content();val missing=c.projects.first { c.components(it.getString("id")).isEmpty() }.getString("id")
    assertTrue(c.window("blueprint",missing).contains("Not recorded for this project"));assertTrue(c.graph(missing).nodes.isEmpty())
    assertTrue(c.window("code","unknown").contains("Not recorded for this project"));assertEquals(0,calls)
  }
  @Test fun missingComponentCodeAssociationIsExplicit() {
    val c=content();val schema=c.components(c.defaultProject).first { it.getString("id")=="schema" }
    assertTrue(c.componentDetail(c.defaultProject,schema).contains("Not recorded for this component"));assertEquals(0,calls)
  }
  @Test fun chiefDecisionStaysInChatAndIsNotAssignedToAProjectStation() {
    val c=content();val chief=RecordedContent.rows(c.source.decisions()).single { it.getString("project")=="chief" }
    assertTrue(c.window("chat").contains(chief.getString("question")))
    assertFalse(c.stations().any { it.id=="chief" });assertEquals(11,RecordedContent.rows(c.source.decisions()).count { it.getString("status")=="open" })
  }
  @Test fun yardProjectionMapsSuccessPrefixesToDoneAndKeepsFailedWithoutChangingRecording() {
    val c=content();val raw=c.source.events.first { it.optString("title").contains("success:") }
    assertTrue(c.window("build").contains(PolishRules.rowTitle(raw.getString("title"))))
    assertFalse(c.window("build").contains(" success:"))
    assertTrue(c.window("build").contains(" failed:"))
    assertTrue(raw.getString("title").contains("success:"))
  }
  @Test fun codeProjectionUsesSectionFiveWordsForCapturedRowLabels() {
    val c=content { path ->
      val raw=File("src/main/assets/$path").readText()
      if(path.startsWith("judge/code/")) org.json.JSONObject(raw).also {
        it.getJSONArray("modules").optJSONObject(0)?.put("label","#21 success: captured module")
      }.toString() else raw
    }
    val text=c.window("code")
    assertTrue(text.contains("Module: #21 done: captured module"))
    assertFalse(text.contains("success:"))
    assertEquals("#27 failed: captured module",PolishRules.rowTitle("#27 failed: captured module"))
    assertEquals("Prose says success: unchanged",PolishRules.rowTitle("Prose says success: unchanged"))
  }
  @Test fun cutoffWorkRemainsNamedAndTextualForEveryProject() {
    val c=content()
    c.projects.forEach { p ->
      val text=c.currentWork(p.getString("id"))
      assertTrue(text.contains(p.getString("name")))
      val task=RecordedContent.rows(c.source.snapshot.getJSONArray("tasks")).firstOrNull { it.getString("project")==p.getString("id") }
      if(task!=null) { assertTrue(text.contains("Worker #"+task.getInt("id")));assertTrue(text.contains(PolishRules.status(task.getString("status"))));assertTrue(c.currentWork(p.getString("id"),true).contains(task.getString("title"))) }
    }
  }
  @Test fun timestampIsOriginalMidnightCstOffset() {
    val c=content();assertEquals("Nov 3 00:29:39 CST",RecordedContent.stamp(c.source.events.first()))
  }
  @Test fun receiptObserverSeesDurableAnswerBeforeRefreshingChat() {
    val file=Files.createTempDirectory("receipt-observer").resolve("receipts.json").toFile()
    var observed=""
    val source=RecordedSource({ File("src/main/assets/$it").readText() },LocalReceiptStore(file),receiptChanged={ observed=file.readText() })
    val d=RecordedContent.rows(source.decisions()).first { it.getString("status")=="open" }
    val label=d.getJSONArray("options").getJSONObject(0).getString("label")
    source.answer(d,label,"test")
    assertTrue(observed.contains(d.getString("id")));assertTrue(RecordedContent(source).window("chat").contains("Practice: $label"))
  }
  @Test fun firstCardPracticeSavesLocallyWithoutHttpAndKeepsQuestionAndOptions() {
    val file=Files.createTempDirectory("first-card").resolve("receipts.json").toFile()
    val read={ name: String -> File("src/main/assets/$name").readText() }
    val source=RecordedSource(read,LocalReceiptStore(file),HttpClient { _,_,_ -> calls++;error("Offline: HTTP forbidden") })
    val d=RecordedContent.rows(source.decisions()).filter { it.getString("status")=="open" }.minBy { it.optInt("judge_order",Int.MAX_VALUE) }
    assertEquals("Which approach should we use to map geothermal faults?",d.getString("question"))
    val options=d.getJSONArray("options");assertEquals(3,options.length())
    val label=options.getJSONObject(1).getString("label")
    assertEquals("B",options.getJSONObject(1).getString("id"))
    val result=source.answer(d,label,"thumbs_up")
    assertEquals(0,calls);assertTrue(result.contains("No live agent was contacted"));assertTrue(file.readText().contains(d.getString("id")))
    val after=RecordedContent.rows(source.decisions()).first { it.getString("id")==d.getString("id") }
    assertEquals("answered",after.getString("status"));assertEquals(label,after.getJSONObject("practice_receipt").getString("option_label"))
  }
  @Test fun failedDisplayObserverDoesNotTurnADurableReceiptIntoAnAnswerFailure() {
    val file=Files.createTempDirectory("receipt-observer-error").resolve("receipts.json").toFile()
    val source=RecordedSource({ File("src/main/assets/$it").readText() },LocalReceiptStore(file),receiptChanged={ error("View detached") })
    val d=RecordedContent.rows(source.decisions()).first { it.getString("status")=="open" }
    val result=source.answer(d,d.getJSONArray("options").getJSONObject(0).getString("label"),"test")
    assertTrue(result.startsWith("Practice answer saved"));assertTrue(file.isFile)
  }
  @Test fun recordedComponentPinchDetailUsesCapturedAssociationsWithoutNetwork() {
    val c=content();val component=c.components(c.defaultProject).first { it.getString("id")=="bridge" };val text=c.componentDetail(c.defaultProject,component)
    assertTrue(text.contains(component.getString("desc")));assertTrue(text.contains("Recorded code associations · Nov 5"));assertTrue(text.contains("Module:"));assertEquals(0,calls)
  }
}
