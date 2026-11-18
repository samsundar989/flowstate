package com.overhand.app.windows

import com.overhand.app.data.*
import com.overhand.app.graph.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * #1096: no dock window in Recorded mode (the recorded day's projects and the four demo projects) shows filler
 * where recorded content belongs. Prints recorded_placeholder_rows; other empty-state lines are listed, not counted.
 */
class RecordedPlaceholderRowsTest {
  private val read={ path: String -> File("src/main/assets/$path").readText() }
  private val words=File("src/main/assets/windows.js").readText().let { shell ->
    Regex("'([^']+)'").findAll(shell.substringAfter("const deny = [").substringBefore("];")).map { it.groupValues[1] }.toList() }+ArchitectureGraph.EXTRA_DENY
  private val filler=Regex("""recorded detail|\[redacted]|label not recorded|(?<![A-Za-z0-9_])placeholder(?![A-Za-z0-9_])|lorem|\bTODO\b""",RegexOption.IGNORE_CASE)
  private val emptyState=Regex("""not recorded|\[name hidden]""",RegexOption.IGNORE_CASE)

  @Test fun aHiddenWordIsLeftOutOfItsSentenceAndCodeKeepsItsIndentation() {
    assertEquals("head started (gen 1, resumed)",RecordedText.neutral("head started ([redacted], gen 1, resumed)"))
    assertEquals("head started",RecordedText.neutral("head started ([redacted])"))
    assertEquals("write (date + URL), then check status",RecordedText.neutral("write [redacted] (date + URL), then check [redacted] status"))
    assertEquals("U-Net on held-out tiles",RecordedText.neutral("U-Net on [redacted], held-out tiles"))
    assertEquals("schema, fixtures.",RecordedText.neutral("schema, fixtures, [redacted]."))
    assertEquals("",RecordedText.neutral("[redacted]"))
    assertEquals("read the agent's status feed",RecordedText.neutral("read live [redacted]"))
    assertEquals("    val  x = 1 // see",RecordedText.neutral("    val  x = 1 // see [redacted]"))
    assertEquals("    untouched  spacing",RecordedText.neutral("    untouched  spacing"))
  }

  @Test fun fillerWordsMatchButAnIdentifierQuotedFromTheRecordingDoesNot() {
    assertTrue(filler.containsMatchIn("File: placeholder"));assertTrue(filler.containsMatchIn("File: recorded detail"))
    assertFalse(filler.containsMatchIn("fill RUNTIME_PLACEHOLDER in commit"))
  }

  @Test fun noDockWindowShowsFillerInRecordedMode() {
    val sets=listOf(RecordedSource.DAY)+RecordedSource.demos(read).map { it.getString("key") }
    assertEquals(5,sets.size)
    val hits=StringBuilder();val listed=StringBuilder();var n=0
    for(key in sets) {
      val s=RecordedSource(read,LocalReceiptStore(Files.createTempDirectory("filler").resolve("r.json").toFile()),requested=key)
      val c=RecordedContent(s)
      fun shown(v: String)=RecordedText.display(v,c.decisionText(),GraphText.pattern(words),RecordedText.decisionGate(words))
      c.projects.forEach { p ->
        val id=p.getString("id")
        val pages=listOf("fleet","blueprint","code","build","chat").map { it to c.window(it,id) }+
          listOf("work" to c.currentWork(id),"work-full" to c.currentWork(id,true))+
          c.components(id).map { "detail:"+it.getString("id") to c.componentDetail(id,it) }
        pages.forEach { (view,text) -> shown(text).lines().forEach { line ->
          if(filler.containsMatchIn(line)) { n++;hits.appendLine("$key/${id.take(4)}/$view\t$line") }
          else if(emptyState.containsMatchIn(line)) listed.appendLine("$key/${id.take(4)}/$view\t$line")
        } }
      }
    }
    val out=File("build/reports/recorded-placeholder-rows.txt");out.parentFile.mkdirs()
    out.writeText("recorded_placeholder_rows=$n\n$hits\n# empty-state lines (listed, not counted)\n$listed")
    println("recorded_placeholder_rows=$n")
    assertEquals(hits.toString(),0,n)
  }
}
