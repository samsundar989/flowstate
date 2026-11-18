package com.overhand.app.first60

import org.json.JSONObject

/**
 * Kotlin port of tools/first60/brief.py (task #781): which card the greeting leads with, its sentences, and which
 * option a spoken "yes" picks. Same rules, same words; no language model. Tested on the cases of test_brief.py.
 * [cards] is res/raw/first60_spoken_cards.json's "cards" object (hand-written lines keyed project_name#decision_id).
 */
class First60Brief(private val cards: JSONObject) {
  data class Decision(val projectName: String, val decisionId: String, val project: String, val tMs: Long, val yesOption: String?)
  data class Result(val variant: String, val sentences: List<String>, val askIndex: Int, val decision: Decision?,
                    val counts: Map<String, Int>) {
    val text get() = sentences.joinToString(" ")
    val wordsBeforeAsk get() = sentences.take(askIndex + 1).joinToString(" ").split(Regex("\\s+")).filter { it.isNotEmpty() }.size
  }

  companion object {
    const val NAME = "Flowstate"
    const val MAX_WORDS_BEFORE_ASK = 45
    private val ONES = ("zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen " +
      "seventeen eighteen nineteen").split(" ")
    private val TENS = "x x twenty thirty forty fifty sixty seventy eighty ninety".split(" ")
    fun words(n: Int): String = when {
      n < 20 -> ONES[n]
      n < 100 -> TENS[n / 10] + (if (n % 10 == 0) "" else " " + ONES[n % 10])
      n < 1000 -> ONES[n / 100] + " hundred" + (if (n % 100 == 0) "" else " and " + words(n % 100))
      else -> "over a thousand"
    }
    fun cap(s: String) = s.replaceFirstChar { it.uppercase() }
    fun truthy(v: Any?) = v != null && v != JSONObject.NULL && v.toString().trim().lowercase() in setOf("true", "1", "yes")
    fun lowerFirst(t: String) = if (t.take(2) != t.take(2).uppercase()) t.take(1).lowercase() + t.drop(1) else t
    fun parse(jsonl: String) = jsonl.lineSequence().filter { it.isNotBlank() }.map(::JSONObject).toList()
    private fun key(e: JSONObject) = e.optString("project") to e.opt("decision_id")?.toString()
  }

  fun openDecisions(ev: List<JSONObject>): List<JSONObject> {
    val answered = ev.filter { it.optString("kind") == "answer" }.map(::key).toSet()
    return ev.filter { it.optString("kind") == "decision" && it.opt("status").let { s -> s == null || s.toString() == "INPUT_REQUIRED" } && key(it) !in answered }
  }

  fun theOne(dec: List<JSONObject>): JSONObject? = dec.sortedWith(compareBy<JSONObject>(
    { if (truthy(it.opt("blocking"))) 0 else 1 },
    { it.opt("judge_order")?.toString()?.takeIf { s -> s.isNotEmpty() && s.all(Char::isDigit) }?.toInt() ?: 1_000_000 },
    { -it.optLong("t_ms", 0) })).firstOrNull()

  private fun hand(d: JSONObject) = cards.optJSONObject("${d.optString("project_name")}#${d.opt("decision_id")}")

  fun optionText(label: String): String {
    var t = label.replace(Regex("^\\s*[A-Z]\\s*[:.]\\s*"), "")
    t = t.replace(Regex("\\s*\\((?:recommended|recommend)\\)\\s*$", RegexOption.IGNORE_CASE), "")
    return t.trim().trimEnd('.')
  }

  fun cardLines(d: JSONObject): Map<String, String> {
    hand(d)?.let { h -> return mapOf("topic" to h.optString("topic"), "pick" to h.optString("pick"), "ask" to h.optString("ask"), "question" to h.optString("question")) }
    val title = d.optString("title").trim().trimEnd('?').trim()
    val rec = optionText(d.optString("recommendation"))
    val oneSentence = !Regex("[.?!:;]").containsMatchIn(title)
    val topic = (if (oneSentence && title.split(Regex("\\s+")).size <= 12) title else "a decision in ${d.optString("project_name", "a project")}") + "."
    if (rec.isNotEmpty() && rec.split(Regex("\\s+")).size <= 10 && !Regex("^[A-Z],").containsMatchIn(rec))
      return mapOf("topic" to topic, "pick" to "The agents recommend: ${lowerFirst(rec)}.", "ask" to "Do you agree?")
    return mapOf("topic" to topic, "pick" to "", "ask" to "Want to hear the options?")
  }

  /** The option a spoken "yes" picks: always the card's own recommendation. */
  fun yesOption(d: JSONObject): String? {
    hand(d)?.let { return it.optString("yes_option").ifEmpty { null } }
    return Regex("^\\s*([A-Za-z0-9]+)\\s*:").find(d.optString("recommendation"))?.groupValues?.get(1)
  }

  /** Decision ids the recording itself answers (the rows RecordedReducer turns into "Original run choice"). */
  fun recordedAnswers(ev: List<JSONObject>) = ev.filter { it.optString("kind") == "status" && it.optString("status") == "WORKING" && it.has("answer") }
    .map { it.opt("decision_id")?.toString() }.toSet()

  fun counts(ev: List<JSONObject>): Map<String, Int> {
    val projects = ev.filter { it.optString("kind") == "task_assigned" && it.optString("project_name").isNotEmpty() }.map { it.getString("project_name") }.toSet()
    return mapOf("projects" to projects.size, "done" to ev.count { it.optString("kind") == "done" },
      "failed" to ev.count { it.optString("kind") == "error" }, "open" to openDecisions(ev).size,
      "blocking" to openDecisions(ev).count { truthy(it.opt("blocking")) })
  }

  fun brief(ev: List<JSONObject>, variant: String = "recorded"): Result {
    if (variant == "empty" || ev.isEmpty()) {
      val s = listOf("This is $NAME.", "There is no project here yet.", "Say what you want to build, in one sentence.",
        "For example: a habit tracker for my phone.", "What do you want to build?")
      return Result("empty", s, s.size - 1, null, counts(emptyList()))
    }
    // t873: one clause of context (what the agents did, what is blocked), then the question, ending <= 9 s in.
    val c = counts(ev); val dec = openDecisions(ev); val d = theOne(dec)
    val blocking = c.getValue("blocking")
    val done = c.getValue("done")
    val did = "${words(done)} ${if (done == 1) "task" else "tasks"}"
    val wait = when {
      d == null -> "nothing waits for you"
      blocking > 1 -> "${words(blocking)} questions block them"
      blocking == 1 -> "one question blocks them"
      // #1100: a rebuilt history answers every question in its own rows (RecordedReducer: status WORKING with an
      // answer), so its card has none waiting; the greeting says they were asked, not that they wait.
      dec.all { it.opt("decision_id")?.toString() in recordedAnswers(ev) } -> if (dec.size > 1) "asked ${words(dec.size)} questions" else "asked one question"
      dec.size > 1 -> "${words(dec.size)} questions wait for you"
      else -> "one question waits for you"
    }
    val s = mutableListOf(if (variant == "live") "While you were away, your agents did $did, and $wait." else "In this replay, agents did $did, and $wait.")
    if (d == null) { s += "Want to hear what was built?"; return Result(variant, s, s.size - 1, null, c) }
    val ln = cardLines(d)
    if (ln["question"].orEmpty().isNotEmpty()) s += ln.getValue("question")
    else {
      s += cap(ln.getValue("topic"))
      if (ln.getValue("pick").isNotEmpty()) s += ln.getValue("pick")
      s += ln.getValue("ask")
    }
    val askAt = s.size - 1
    s += "Say yes, or pinch to see the options."
    return Result(variant, s, askAt, Decision(d.optString("project_name"), d.opt("decision_id").toString(), d.optString("project"),
      d.optLong("t_ms", 0), yesOption(d)), c)
  }

  /** What Flowstate says once the wearer answers. Yes picks the recommendation; any other choice is just saved. */
  fun afterAnswer(d: Decision?, saidYes: Boolean): String {
    if (d == null) return "Got it. Building the first plan now."
    val then = cards.optJSONObject("${d.projectName}#${d.decisionId}")?.optString("then").orEmpty()
    return (if (saidYes && then.isNotEmpty()) then else "Practice answer saved here. The recorded run keeps its original outcome.") + " Ask me anything, or look around."
  }
}
