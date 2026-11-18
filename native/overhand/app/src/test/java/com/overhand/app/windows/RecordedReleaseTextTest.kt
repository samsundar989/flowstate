package com.overhand.app.windows

import com.overhand.app.data.*
import com.overhand.app.graph.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Host text projection, not a device screenshot or a readability verdict. */
class RecordedReleaseTextTest {
  @Test fun exportEveryRecordedTextSurfaceForSubstringAudit() {
    val source=RecordedSource({ File("src/main/assets/$it").readText() },
      LocalReceiptStore(Files.createTempDirectory("release-text").resolve("receipts.json").toFile()))
    val c=RecordedContent(source)
    val shell=File("src/main/assets/windows.js").readText()
    val words=Regex("'([^']+)'").findAll(shell.substringAfter("const deny = [").substringBefore("];"))
      .map { it.groupValues[1] }.toList()+ArchitectureGraph.EXTRA_DENY
    val prose=GraphText.pattern(words)
    val decision=RecordedText.decisionGate(words)
    fun show(value: String)=RecordedText.display(value,c.decisionText(),prose,decision)
    val text=buildString {
      c.projects.forEach { p ->
        val id=p.getString("id")
        for(view in listOf("fleet","blueprint","code","build","chat")) appendLine(show(c.window(view,id)))
        appendLine(show(c.currentWork(id)))
        c.components(id).forEach { appendLine(show(c.componentDetail(id,it))) }
      }
      RecordedContent.rows(source.decisions()).forEach { appendLine(it.toString()) }
      c.stations().forEach { appendLine(it.name) }
    }
    assertEquals(714,source.events.size)
    assertEquals(14,source.decisions().length())
    assertEquals(6,c.projects.size)
    val output=File("build/reports/rendered-recorded.txt")
    output.parentFile.mkdirs();output.writeText(text)
    assertTrue(text.contains("Which approach should we use to map geothermal faults?"))
    // #748: no machine redaction on any card a judge reads (full gate: tools/recorded_cards/gate.py).
    val redaction=Regex("""\[(?:link|path|redacted)]|\bproduct\b|\b(?:headset|energy|molecule) sponsor\b|\b([A-Za-z]{2,})\s+\1\b|\*\*|`|^#+\s""",RegexOption.IGNORE_CASE)
    RecordedContent.rows(source.decisions()).forEach { d ->
      val shown=listOf(d.getString("question"),d.optString("recommendation"))+RecordedContent.rows(d.getJSONArray("options")).map { it.getString("label") }
      shown.forEach { assertFalse(it,redaction.containsMatchIn(it));assertFalse(it,Regex("""^[\w-]+(?:,[\w-]+)+$""").matches(it)) }
    }
  }
}
