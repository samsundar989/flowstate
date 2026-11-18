package com.overhand.app.windows

import com.overhand.app.data.*
import com.overhand.app.graph.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The same recorded decision must read the same on its card and in the Chat window. */
class RecordedDecisionMeaningTest {
  private val read={ name: String -> File("src/main/assets/$name").readText() }
  private val shell=Regex("'([^']+)'").findAll(read("windows.js").substringAfter("const deny = [").substringBefore("];")).map { it.groupValues[1] }.toList()
  private val all=shell+ArchitectureGraph.EXTRA_DENY
  private val prose=GraphText.pattern(all)
  private val decision=RecordedText.decisionGate(all)
  /** DecisionCards.clean, as shipped: the card surface is not edited by this change. */
  private val cardGate=Regex("\\b(?:"+shell.map(Regex::escape).sortedByDescending { it.length }.joinToString("|")+")\\b",RegexOption.IGNORE_CASE)
  private fun card(v: String)=cardGate.replace(v,"agent")
  private fun source(mutate: (String,String)->String={ _,t -> t })=RecordedSource({ mutate(it,read(it)) },
    LocalReceiptStore(Files.createTempDirectory("meaning").resolve("receipts.json").toFile()))
  private fun open(c: RecordedContent)=RecordedContent.rows(c.source.decisions()).filter { it.getString("status")=="open" }
  private fun labels(d: JSONObject)=RecordedContent.rows(d.getJSONArray("options")).map { it.getString("label") }
  private fun show(c: RecordedContent,v: String)=RecordedText.display(v,c.decisionText(),prose,decision)

  @Test fun everyOpenDecisionReadsTheSameOnItsCardAndInChat() {
    val c=RecordedContent(source())
    val rows=open(c);assertEquals(11,rows.size)
    val table=StringBuilder("decision\tfield\tcard\tchat_before\tchat_after\n")
    var before=0;var after=0
    val chat=show(c,c.window("chat"))
    rows.forEach { d ->
      val lead=show(c,c.chatLead(d))
      val fields=listOf("question" to d.getString("question"))+labels(d).map { "option" to it }
      fields.forEach { (kind,value) ->
        val old=prose.replace(value,"agent");val now=show(c,value)
        if(old!=card(value)) before++
        if(now!=card(value)) after++
        assertTrue(value,lead.contains(card(value)))
        if(kind=="question") assertTrue(value,chat.contains(card(value)))
        table.append("${d.getString("id")}\t$kind\t${card(value)}\t$old\t$now\n")
      }
    }
    table.append("semantic_mismatches_before=$before\nsemantic_mismatches_after=$after\nopen_decisions_checked=${rows.size}\n")
    System.getenv("T437_OUT")?.let { File(it).apply { parentFile.mkdirs() }.writeText(table.toString()) }
    assertEquals(0,before);assertEquals(0,after)
  }

  @Test fun decisionsSixteenAndSeventeenKeepTheirRecordedWording() {
    val c=RecordedContent(source());val rows=open(c)
    val sixteen=rows.single { it.getString("id")=="16" };val seventeen=rows.single { it.getString("id")=="17" }
    assertEquals("Which approach should we use to map geothermal faults?",show(c,sixteen.getString("question")))
    assertEquals(labels(sixteen),labels(sixteen).map { show(c,it) })
    val text=show(c,c.chatLead(seventeen))
    assertTrue(text.contains("or add a second entry with a small model?"))
    assertTrue(text.contains("C: A, plus a small-model entry for the \$10k bonus (recommended)"))
    assertFalse(text.contains("agent entry"));assertFalse(text.contains("agent bonus"))
  }

  @Test fun choiceKeysAreTheFrozenOnesAndAPickSavesTheOriginalKey() {
    val raw=read("judge/real_day.jsonl").lines().filter { it.isNotBlank() }.map(::JSONObject).filter { it.getString("kind")=="decision" }.associateBy { it.getString("decision_id") }
    val c=RecordedContent(source())
    open(c).forEach { d -> assertEquals(RecordedContent.rows(raw.getValue(d.getString("id")).getJSONArray("options")).map { it.getString("id") },RecordedContent.rows(d.getJSONArray("options")).map { it.getString("id") }) }
    val d=open(c).single { it.getString("id")=="17" }
    c.source.answer(d,"C: A, plus a small-model entry for the \$10k bonus (recommended)","test")
    val saved=RecordedContent.rows(c.source.decisions()).single { it.getString("id")=="17" }.getJSONObject("practice_receipt")
    assertEquals("C",saved.getString("option_id"))
    assertTrue(show(c,c.window("chat")).contains("Practice: C: A, plus a small-model entry for the \$10k bonus (recommended)"))
  }

  @Test fun nearMatchesInDecisionTextAreNotTouched() {
    listOf("Metabolism panel","Amazonian basin","Questionnaire","Appleton plant","Excellence review","Notional value","Slackline test","E4Beta","4E4B","S3D volume","TorchScript","MetaData","Jaxon")
      .forEach { assertEquals(it,RecordedText.display(it,listOf(it),prose,decision)) }
  }

  @Test fun aShortOptionLabelCannotSplitADeniedWordOutOfTheProseGate() {
    assertEquals("[name hidden] and no",RecordedText.display("Snowflake and no",listOf("no"),prose,decision))
  }

  @Test fun stringOptionsTheCardToleratesDoNotBreakWindowText() {
    val c=RecordedContent(source { path,text ->
      if(path!="judge/real_day.jsonl") text else text.lines().joinToString("\n") { line ->
        if(line.isBlank()) line else JSONObject(line).let { if(it.optString("decision_id")=="9" && it.optString("kind")=="decision") it.put("options",JSONArray(listOf("done"))).toString() else line }
      }
    })
    assertTrue(c.decisionText().contains("done"))
  }

  @Test fun exactBrandInDecisionTextIsVisiblyHiddenNeverCalledAnAgent() {
    assertEquals("B: use [name hidden] today",RecordedText.display("B: use Kaggle today",listOf("B: use Kaggle today"),prose,decision))
    assertEquals("A: [name hidden] plus [name hidden]",RecordedText.display("A: Meta plus Unity",listOf("A: Meta plus Unity"),prose,decision))
  }

  @Test fun proseStaysUnderTheFullGateAndNoLongerSaysAgent() {
    val q="A plus an large entry";val text="Worker: E4B LoRA on Kaggle. $q"
    assertEquals("Worker: [name hidden] LoRA on [name hidden]. $q",RecordedText.display(text,listOf(q),prose,decision))
    assertEquals("[name hidden]Config [name hidden] VR",RecordedText.display("VllmConfig example-project",emptyList(),prose,decision))
  }

  @Test fun missingOrBlankProjectNamesReadAsTheFallbackEverywhere() {
    listOf<(JSONObject)->Unit>({ it.remove("name") },{ it.put("name","") },{ it.put("name","   ") }).forEach { edit ->
      val c=RecordedContent(source { path,text ->
        if(path!="judge/snapshot.json") text else JSONObject(text).also { edit(it.getJSONArray("projects").getJSONObject(0)) }.toString()
      })
      val id=c.projects[0].getString("id");val shown=ProjectContext.FALLBACK+" 1"
      assertEquals(shown,c.projectName(id))
      assertEquals(shown,c.stations().single { it.id==id }.name)
      assertTrue(c.window("fleet").contains("\n$shown\n"))
      assertTrue(c.currentWork(id).startsWith(shown))
    }
  }

  @Test fun bundledProjectNamesAreUnchangedAndUnknownKeysAreNotShown() {
    val c=RecordedContent(source())
    c.projects.forEach { assertEquals(it.getString("name"),c.projectName(it.getString("id"))) }
    assertEquals(c.projects.associate { it.getString("id") to it.getString("name") },c.stations().associate { it.id to it.name })
    assertEquals(ProjectContext.FALLBACK,c.projectName("unknown-key"));assertTrue(c.currentWork("unknown-key").startsWith(ProjectContext.FALLBACK))
  }
}
