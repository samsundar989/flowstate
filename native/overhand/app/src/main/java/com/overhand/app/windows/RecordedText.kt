package com.overhand.app.windows

/** Display-only wording; the bundled recording and its receipt hash remain untouched. */
object RecordedText {
  /** Visible marker for a hidden name; a denied product name must never read as "agent". */
  const val HIDDEN="[name hidden]"
  // On the windows' extra list only: not on the shipped brand denylist, and the card shows it.
  private val DECISION_SHOWN=setOf("e4b")

  /** Whole-word gate for recorded question and option text, matching the card's boundaries. */
  fun decisionGate(words: List<String>)=Regex("\\b(?:"+words.filter { it.lowercase() !in DECISION_SHOWN }
    .map(Regex::escape).sortedByDescending { it.length }.joinToString("|")+")\\b",RegexOption.IGNORE_CASE)

  /** Scrubs window text, using [decision] inside exact recorded decision text and [prose] elsewhere. */
  fun display(value: String,decisionText: List<String>,prose: Regex,decision: Regex): String=displayer(decisionText,prose,decision)(value)

  /** [display] with its span pattern compiled once (#1385: the Code window gates thousands of diff lines). */
  fun displayer(decisionText: List<String>,prose: Regex,decision: Regex): (String)->String {
    val hidden=Regex.escapeReplacement(HIDDEN)
    val spans=decisionText.filter { it.isNotBlank() }.distinct().sortedByDescending { it.length }
    if(spans.isEmpty()) return { value -> prose.replace(value,hidden) }
    val within=Regex("(?<![A-Za-z0-9])(?:"+spans.joinToString("|",transform=Regex::escape)+")(?![A-Za-z0-9])")
    return { value ->
      val out=StringBuilder();var at=0
      within.findAll(value).forEach {
        out.append(prose.replace(value.substring(at,it.range.first),hidden))
        out.append(decision.replace(it.value,hidden));at=it.range.last+1
      }
      out.append(prose.replace(value.substring(at),hidden)).toString()
    }
  }

  /** The app's retired name, as a whole word; never the "[Overhand test N]" fixture marker. */
  val RETIRED_NAME=Regex("(?<![A-Za-z0-9])overhand(?![A-Za-z0-9])(?! test \\d)",RegexOption.IGNORE_CASE)

  // #1096: a hidden word is left out of its sentence, never shown as filler ("recorded detail").
  // It becomes a marker first, so the tidying below touches only the marker's neighbours (code indentation stays).
  private const val M="\u0001"
  private val IC=RegexOption.IGNORE_CASE
  // Reviewed prose repairs, before removing privacy markers. The recording and receipt hash stay intact.
  // Specific phrases only: do not collapse repeated tokens or shorten authentic code excerpts.
  val PROSE_REPAIRS=linkedMapOf(
    "Charter at [path] is your standing brief" to "The charter is your standing brief (local path omitted)",
    "product [path]" to "a local environment (path omitted)",
    "PRIVATE the repo host remote, origin = [link] [redacted], with main pushed" to "private repository remote (address omitted), with main pushed",
    "Snapshot official [redacted] pages; write [redacted]" to "Snapshot official pages and document the rules",
    "derived label rasters (needs [redacted])" to "derived label rasters (dependency omitted from recording)",
    "Evaluation harness core: [redacted], [redacted], [redacted];" to "Evaluation harness core;",
    "project config to [redacted] to match" to "project config to match",
    "the [redacted] vector [redacted] vector [redacted] repo host" to "the vector-store and repository-host names",
    "the agent agent stall" to "the coding agent stall",
    "as an [redacted] checklist" to "as a checklist",
    "[redacted]: one command for two-run comparisons" to "Run comparison: one command for two-run comparisons",
    "[redacted]: every format, model, cap, skills and adapter check" to "Submission checks: every format, model, cap, skills and adapter check",
    "[redacted]: correct run_skill_script classification" to "Skill accounting: correct run_skill_script classification",
    "Skill scripts: [redacted] (grep+BM25) and [redacted] (targeted pytest), with [redacted] measured" to "Skill scripts for search and targeted tests, with measurements recorded",
    "answers posted back to /[redacted]" to "answers posted back to the local server"
  )
  fun neutral(value: String): String = PROSE_REPAIRS.entries.fold(value) { text,(before,after) -> text.replace(before,after) }
    .replace("[path]","a local path omitted from this recording")
    .replace("[link]","a link omitted from this recording")
    .replace(Regex("""live /?\[redacted]/?""",IC),"the agent's status feed")
    .replace(Regex("""validate /?\[redacted]/?""",IC),"validate a local model")
    .replace(Regex("""/?\[redacted]/?""",IC),M)
    .replace(Regex("""[ \t]*\(\s*$M\s*\)"""),"")
    .replace(Regex("""\(\s*$M\s*,\s*"""),"(")
    .replace(Regex("""[ \t]*,[ \t]*$M(?=[ \t]*(?:[,.;:)\n]|$))"""),"")
    .replace(Regex("""$M[ \t]*,[ \t]*"""),"")
    .replace(Regex("""(?<=\S)[ \t]*$M"""),"")
    .replace(Regex("""$M[ \t]*"""),"")
    // R212: the product is Flowstate. The recording keeps its words (receipt hash); the fixture marker stays (FirstMinutes).
    .replace(RETIRED_NAME,"Flowstate")

  fun title(value: String): String {
    val clean=neutral(value).replace(Regex("^#[0-9]+\\s*"),"")
    val lead=clean.substringBefore(":").substringBefore("(").substringBefore(" ? ").substringBefore(" with ").substringBefore(" on ").trim()
    val human=lead.replace(Regex("[<>]=?|\\bp5\\b|(?:[A-Za-z]:)?/\\S+"),"").trim()
    val title=human.ifBlank { "Recorded task" }
    return if(title.length<=60) title else title.take(61).substringBeforeLast(' ',title.take(60)).trimEnd()
  }
}
