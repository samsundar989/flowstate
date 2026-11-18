package com.overhand.app

/** Copy and launch accounting shared by normal launches and host regression tests. */
object FirstMinutes {
  // The native window adapter targets the classic dashboard's DOM contract.
  const val DASHBOARD_PATH = "/classic"
  const val PURPOSE = "Supervise your agent projects. See progress and answer decisions."
  const val TAGLINE = "Your agent projects, within reach."
  private val fixture = Regex("^\\s*\\[Overhand test \\d+\\]", RegexOption.IGNORE_CASE)
  fun isFixture(question: String) = fixture.containsMatchIn(question)
  fun statusWord(state: String): String = when(state.lowercase()) {
    "submitted","ready","pending","planned","blocked","parked","waiting","rotating" -> "queued"
    "working","attached","running","claimed","building" -> "working"
    "input_required","needs","at_risk" -> "needs you"
    "completed","done","success","idle","built","verified" -> "done"
    "failed" -> "failed"
    "canceled","cancelled","dropped" -> "canceled"
    else -> state
  }

  /** Fail closed if the upstream template changes; never silently fall back to demo data. */
  fun dashboardDocument(html: String): String {
    val start=html.indexOf("const STATE_WORD = {")
    val end=html.indexOf("};",start.coerceAtLeast(0))
    val demo="DEMO = true; setState(demoState()); setInterval(demoTick, 2500);"
    require(start>=0 && end>start && html.contains(demo)) { "Unsupported dashboard template" }
    return html.replaceRange(start,end+2,"const STATE_WORD = window.afStatusWords;")
      .replace(demo,"document.querySelector('main').textContent = 'Server unavailable. Reconnect in Settings.';")
  }
}

/** Recorded first-run wording. Accompanies the verbatim recorded question and options; never replaces them. */
object RecordedFirstCard {
  const val PURPOSE = "Replay of real coding-agent work from Nov 3. An agent asked a person to decide here."
  const val CHOOSE = "Step 1 of 2 · Pinch the option you would pick."
  const val CONFIRM = "Step 2 of 2 · Pinch Confirm, or hold thumbs up."
  const val PRACTICE = "Practice only: any choice is fine. Stays on this headset; no agent receives it."
  const val SAVED_TITLE = "Practice answer saved on this headset"
  const val SAVED_QUESTION = "Question"
  const val SAVED_ANSWER = "Your answer"
  const val SAVED_WHERE = "Where it went"
  const val SAVED_DETAIL = "Kept on this headset only, as practice. No live agent was contacted or resumed."
  fun savedChoice(label: String) = "You chose · $label"
  /** #925: stable numbering. Answered questions keep their numbers, so after "Question 1 of 11" the next card reads
   *  "Question 2 of 11 · 1 practiced so far" (index is the position among the questions still waiting). */
  fun position(index: Int, waiting: Int, answered: Int, practice: Boolean = true, blocking: Int = 0) =
    "Question ${answered + index} of ${answered + waiting}" + (if (answered > 0) " · $answered " + (if (practice) "practiced so far" else "answered") else "") +
      (if (blocking > 0) " · $blocking block work" else "")
  /** #1039: the card's "block work" count. Same rule as the greeting's "questions block them" (First60Brief.counts):
   *  a still-open question whose recorded event says blocking. */
  fun blocking(waiting: List<org.json.JSONObject>) = waiting.count { com.overhand.app.first60.First60Brief.truthy(it.opt("blocking")) }
  /** #905: who is asking and why, without the team's jargon ("Head" is the agent that leads a project). */
  fun askedBy(project: String, agent: String, recorded: Boolean): String {
    val who = when {
      agent.isBlank() || agent.equals("Head", true) -> "the coding agent leading this project"
      agent.equals("Agent", true) || agent.equals("Worker", true) -> "a coding agent on this project"
      else -> "the coding agent “$agent”"
    }
    val why = if (recorded) "$who asked a person. “Original run choice” means that person's earlier decision. “Practice answer” means your choice."
      else "$who is asking and has paused its work until you choose."
    val line = why.replaceFirstChar { it.uppercase() }
    return if (project.isBlank()) line else "$project: $line"
  }
}

/** Monotonic timing; a native draw is not proof of compositor visibility. */
class LaunchTrace(private val start: Long, private val wallStart: Long) {
  private var recorded = false
  @Synchronized fun firstDraw(now: Long): String? {
    if (recorded) return null
    recorded = true
    return "launch_to_first_content event=native_first_content_draw launch_unix_ms=$wallStart " +
      "launch_elapsed_ms=$start content_elapsed_ms=$now duration_ms=${now-start} " +
      "surface=fleet_purpose compositor_verified=false"
  }
}
