package com.overhand.app.windows

import com.overhand.app.data.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RecordedTextTest {
  private fun content(raw: Boolean)=RecordedContent(RecordedSource(
    { File("src/main/assets/$it").readText() },
    LocalReceiptStore(Files.createTempDirectory("text-scan").resolve("receipts.json").toFile()),
    display=if(raw) ({ it }) else RecordedText::neutral))

  @Test fun neutralWordingAndHumanTitles() {
    assertEquals("the agent's status feed",RecordedText.neutral("live /[redacted]"))
    assertEquals("Build and validate a local model (31B)",RecordedText.neutral("Build and validate [redacted] (31B)"))
    assertEquals("Stations",RecordedText.title("Stations (native): each project gets a place; p5 >= 72 runs/path"))
    assertTrue(RecordedText.title("a".repeat(100)).length<=60)
    assertEquals("E4B LoRA serving gate",RecordedText.title("E4B LoRA serving gate on a compute service with a patched wheel"))
    assertEquals("word ".repeat(12).trim(),RecordedText.title("word ".repeat(30)))
  }

  @Test fun priorPracticeReceiptIsAlsoProjectedWithoutChangingItsStoredText() {
    val store=LocalReceiptStore(Files.createTempDirectory("old-receipt").resolve("receipts.json").toFile())
    val source=RecordedSource({ File("src/main/assets/$it").readText() },store)
    val card=RecordedContent.rows(source.decisions()).first { it.getString("status")=="open" }
    store.save(source.fixtureHash,card.getString("id"),"old-option","[redacted]","test",card.getLong("t_ms"))
    assertFalse(source.decisions().toString().contains("[redacted]"))
    assertEquals("[redacted]",store.find(source.fixtureHash,card.getString("id"))!!.getString("option_label"))
  }

  @Test fun scanEveryProjectWindowCardStationAndComponentWithoutEditingRecording() {
    val before=content(true);val after=content(false)
    assertEquals(before.source.fixtureHash,after.source.fixtureHash)
    val spec=Regex("[<>]=?|\\bp5\\b|(?:\\b[A-Za-z]:)?/\\S+|\\bruns/",RegexOption.IGNORE_CASE)
    val result=JSONObject();var totalRedacted=0;var totalHeaders=0
    for(raw in listOf(true,false)) {
      val c=if(raw) before else after
      val surfaces=linkedMapOf<String,Pair<List<String>,List<String>>>()
      for(view in listOf("fleet","blueprint","code","build","chat")) {
        val body=c.projects.map { c.window(view,it.getString("id")) }
        val headers=c.projects.map { c.currentWork(it.getString("id"),raw).substringAfterLast('\n') }
        surfaces[view]=body to headers
      }
      surfaces["settings"]=listOf(File("src/main/assets/description.txt").readText()) to listOf("Settings")
      val cards=RecordedContent.rows(c.source.decisions())
      surfaces["card"]=cards.map { it.toString() } to listOf("Decision")
      surfaces["stations"]=c.stations().map { it.name } to c.stations().map { it.name }
      surfaces["components"]=c.projects.flatMap { p -> c.components(p.getString("id")).map { c.componentDetail(p.getString("id"),it) } } to listOf("Architecture")
      val counts=JSONObject()
      surfaces.forEach { (name,pair) ->
        val all=pair.first+pair.second
        val redacted=all.sumOf { Regex("\\[redacted\\]").findAll(it).count() }
        val rawHeaders=pair.second.count { spec.containsMatchIn(it) || it.length>60 && !it.contains("current work not recorded") }
        counts.put(name,JSONObject().put("redacted_tokens",redacted).put("raw_spec_headers",rawHeaders)
          .put("spec_like_body_strings",pair.first.count { spec.containsMatchIn(it) }).put("body_strings",pair.first.size).put("headers",pair.second))
        if(!raw) { assertEquals(name,0,redacted);assertEquals(name,0,rawHeaders);totalRedacted+=redacted;totalHeaders+=rawHeaders }
      }
      result.put(if(raw) "before" else "after",counts)
    }
    result.put("recording_sha256",after.source.fixtureHash).put("after_redacted_tokens",totalRedacted).put("after_raw_spec_headers",totalHeaders)
    val file=File("build/reports/recorded-scan.json");file.parentFile.mkdirs();file.writeText(result.toString(2))
  }
}
