package com.overhand.app.first60

import com.overhand.app.RecordedFirstCard
import com.overhand.app.data.RecordedReducer
import com.overhand.app.graph.site.SiteRecorded
import com.overhand.app.hostlink.project.ProjectTimeline
import com.overhand.app.hostlink.recorded.RecordedBuildBundle
import com.overhand.app.hostlink.recorded.RecordedBuildScreens
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * #1100: a continuity pass over the three recorded sources (the real day, the demo projects, build from nothing).
 * Every count and name a judge sees or hears is read from the shipped assets and checked against every other one:
 * the greeting's question count and wording, the spoken "what needs me" lines, the card's "n of N" and "block work",
 * the project count in any spoken or shown line against the plots drawn, and the build replay's project name and
 * style at the pick, during the run and at the finish. Each disagreement is one line of the output.
 */
class RecordedContinuityTest {
  companion object {
    private val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private val NUM = (0..100).associateBy { First60Brief.words(it) }
    private val numWord = NUM.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
    private fun num(s: String) = NUM[s.lowercase()]
    private fun rows(raw: File, name: String) = JSONObject(File(raw, name).readText())

    /** Questions in a spoken or shown line: "<n> [adjective] questions <block|wait|are waiting>", or "asked ... <n> [adjective] questions". */
    private val waitRe = Regex("\\b($numWord)(?: [a-z]+)? questions? (block|blocks|blocked|wait|waits|are waiting|is waiting|are blocking)\\b", RegexOption.IGNORE_CASE)
    private val askedRe = Regex("\\basked [a-z ]*?\\b($numWord)(?: [a-z]+)? questions\\b", RegexOption.IGNORE_CASE)
    /** A whole-project count: "<n> projects" with nothing between the number and the noun ("three smaller projects" is a subset). */
    private val projectsRe = Regex("\\b($numWord) projects\\b", RegexOption.IGNORE_CASE)
    private val headingRe = Regex("^Question (\\d+) of (\\d+)(?: · (\\d+) block work)?$")

    /** Every disagreement across the recorded sources, read from [raw] (res/raw) and [judge] (assets/judge). */
    fun disagreements(raw: File = File(main, "res/raw"), judge: File = File(main, "assets/judge"), assets: File = File(main, "assets")): List<String> {
      val out = mutableListOf<String>()
      val cards = rows(raw, "first60_spoken_cards.json").getJSONObject("cards")
      val sets = rows(raw, "first60_cues.json").getJSONObject("sets")
      val answers = rows(raw, "voice_answers.json").getJSONArray("answers").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
      val logs = mapOf("day" to File(judge, "real_day.jsonl"), "cards" to File(judge, "demo/cards/day.jsonl"), "cells" to File(judge, "demo/cells/day.jsonl"))
      val archs = mapOf("day" to File(judge, "architecture.json"), "cards" to File(judge, "demo/cards/architecture.json"), "cells" to File(judge, "demo/cells/architecture.json"))
      for ((k, log) in logs) {
        val text = log.readText(); val ev = First60Brief.parse(text)
        val g = First60Brief(cards).brief(ev, "recorded")
        val set = sets.getJSONObject(k)
        val cues = set.getJSONArray("cues").let { c -> (0 until c.length()).map { c.getJSONObject(it).getString("text") } }
        val spoken = cues + set.optString("then_yes")
        // 1. The greeting audio's words are the rule's words.
        if (cues != g.sentences) out += "$k: greeting audio says ${cues.firstOrNull()} but the rule says ${g.sentences.firstOrNull()}"
        // 2. The card the greeting leads to: "Question 1 of N · b block work" (what DecisionCards.accept keeps from RecordedSource).
        val all = RecordedReducer.reduce(text).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        val waiting = all.filter { it.optString("status") == "open" }
        val blocking = RecordedFirstCard.blocking(waiting)
        val heading = RecordedFirstCard.position(1, waiting.size, 0, blocking = blocking)
        val h = headingRe.matchEntire(heading)
        val cardN = h?.groupValues?.get(2)?.toInt() ?: -1; val cardB = h?.groupValues?.get(3)?.ifEmpty { "0" }?.toInt() ?: -1
        if (h == null) out += "$k: card heading \"$heading\" is not \"Question n of N[ · b block work]\""
        if (g.counts.getValue("blocking") != cardB) out += "$k: greeting counts ${g.counts["blocking"]} blocking, card says $cardB block work"
        // 3. Every question count spoken in this source (greeting, then-yes; on the real day the voice answers too).
        val lines = spoken.map { "greeting" to it } + (if (k == "day") answers.map { "voice ${it.getString("id")}" to it.getString("text") } else emptyList())
        for ((where, line) in lines) {
          waitRe.findAll(line).forEach { m ->
            val n = num(m.groupValues[1])!!; val verb = m.groupValues[2].lowercase()
            val want = if (verb.startsWith("block") || verb == "are blocking") cardB else cardN
            if (n != want) out += "$k $where: \"${m.value}\" but the card counts $want (heading \"$heading\")"
          }
          askedRe.findAll(line).forEach { m -> val n = num(m.groupValues[1])!!
            if (n != all.size) out += "$k $where: \"${m.value}\" but the recording asked ${all.size} (card heading \"$heading\")" }
        }
        // 4. Project counts spoken or shown against the plots drawn (the site's manifest seeds one plot per project).
        val plots = SiteRecorded(JSONObject(archs.getValue(k).readText()), ev, { it }).names.size
        for ((where, line) in lines) projectsRe.findAll(line).forEach { m -> val n = num(m.groupValues[1])!!
          if (n != plots) out += "$k $where: \"${m.value}\" but $plots plots are drawn" }
        val named = First60Brief(cards).counts(ev).getValue("projects")
        if (named != plots) out += "$k: the log names $named projects but $plots plots are drawn"
      }
      out += buildReplay(assets)
      return out
    }

    /** Build from nothing: for each option the wearer can pick, the project name and the style the screens attribute. */
    private fun buildReplay(assets: File): List<String> {
      val out = mutableListOf<String>()
      val b = RecordedBuildBundle({ File(assets, it).readText() }, { File(assets, it).readBytes() })
      val ev = b.events; val q = ev.first { it.optString("kind") == "question" }
      val labels = q.getJSONArray("options").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("label") } }
      val answered = ev.first { it.optString("kind") == "answered" }
      val answeredLabel = q.getJSONArray("options").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        .first { it.getString("id") == answered.optString("option_id") }.getString("label")
      if (answeredLabel != b.recordedAnswer) out += "build: run.json says the recorded answer is ${b.recordedAnswer}, the answered frame picks $answeredLabel"
      val cap = "Spoken follow-up (recorded): “${b.followUpText}”"
      for (pick in labels) {
        // The pick notes are RecordedBuildFeature.pick's and onEvent's words.
        val note = if (pick == b.recordedAnswer) "You picked $pick, as the recorded run did. It stays on this headset."
          else "You picked $pick; it stays on this headset. The recorded run goes on with its recorded answer: ${b.recordedAnswer}."
        val tl = ProjectTimeline(b.projectId); var question: JSONObject? = null; var caption = ""; var picked = false
        val shown = mutableListOf<Pair<String, com.overhand.app.hostlink.project.ProjectScreen>>()
        for (e in ev) {
          tl.add(e)
          when (e.optString("kind")) {
            "question" -> question = e
            "task_state" -> { if (e.optString("task_id") == b.followUpTask && e.optString("state") == "queued") caption = cap
              if (e.optString("state") == "running") question = null }
          }
          val stage = when { question != null -> "pick"; tl.done && tl.finished?.optString("task_id") == b.followUpTask -> "finish"; else -> "run" }
          if (question != null && !picked) { shown += stage to RecordedBuildScreens.of("", tl.name, question, "", caption, tl.rows.map { it.text }, false, false); picked = true }
          shown += stage to RecordedBuildScreens.of("", tl.name, question, note, caption, tl.rows.map { it.text }, false, tl.done)
        }
        val names = shown.map { it.first to it.second.title }.distinct()
        if (names.map { it.second }.distinct().size != 1) out += "build pick $pick: the project is named ${names.joinToString { "${it.second} at ${it.first}" }}"
        for (stage in listOf("pick", "run", "finish")) {
          val screens = shown.filter { it.first == stage }.map { it.second }
          if (screens.isEmpty()) { out += "build pick $pick: no $stage screen"; continue }
          val bad = screens.flatMap { s -> s.lines.filter { l ->
            // A line that tells the wearer they chose a style other than the one they picked.
            (Regex("^You said: (.*)$").find(l)?.groupValues?.get(1)?.let { it != pick } ?: false) ||
              (pick != b.recordedAnswer && l.contains("your answer", true) && !l.contains("recorded", true)) } }.distinct()
          bad.forEach { out += "build pick $pick at $stage: \"$it\" but the wearer picked $pick" }
          // Where the run's own words name the recorded style, the same screen says it is the recorded answer.
          screens.filter { s -> pick != b.recordedAnswer && s.key != "bfn_question" && s.lines.any { it.contains(b.recordedAnswer, true) } &&
            s.lines.none { it.contains("recorded", true) && it.contains(b.recordedAnswer, true) } }
            .map { s -> s.lines.first { it.contains(b.recordedAnswer, true) } }.distinct()
            .forEach { out += "build pick $pick at $stage: \"$it\" shows ${b.recordedAnswer} with nothing saying it is the recorded answer" }
        }
      }
      return out
    }
  }

  @Test fun everyCountAndNameAgreesAcrossTheRecordedSources() {
    val d = disagreements()
    d.forEach { println("DISAGREE $it") }
    println("recorded_count_disagreements=${d.size}")
    assertEquals(d.joinToString("\n"), 0, d.size)
  }

  /** The plant: one count changed in a copy of the greeting's cue sheet is caught. */
  @Test fun aPlantedCountInACopyOfTheAssetsIsCaught() {
    val tmp = Files.createTempDirectory("t1100_plant").toFile()
    try {
      File(main, "res/raw").listFiles()!!.filter { it.name.endsWith(".json") }.forEach { it.copyTo(File(tmp, it.name)) }
      val cues = File(tmp, "first60_cues.json")
      val before = cues.readText()
      val planted = before.replaceFirst("and three questions block them", "and two questions block them")
      assertTrue("the plant changed nothing", planted != before)
      cues.writeText(planted)
      val clean = disagreements()
      val d = disagreements(raw = tmp)
      d.forEach { println("PLANT $it") }
      println("plant_disagreements=${d.size} clean=${clean.size}")
      assertTrue("the planted count was not caught", d.size > clean.size && d.any { it.contains("two questions block them") })
    } finally { tmp.deleteRecursively() }
  }
}
