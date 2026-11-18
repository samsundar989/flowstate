package com.overhand.app.hostlink.project

import org.json.JSONObject

/**
 * One project's host events (PROTOCOL.md section 5) folded into what its window shows (#815): started, files written,
 * commands run, a question and its answer, and the end with the commit and the first line the program printed.
 * Every line is plain words; the agent is "your coding agent" (rules s.4: no vendor names; the host already strips them).
 */
class ProjectTimeline(val projectId: String) {
  data class Row(val seq: Long,val kind: String,val text: String)
  val rows=mutableListOf<Row>()
  var name=""; private set
  var started=false; private set
  /** The open question's event, until it is answered or its task moves on. */
  var question: JSONObject?=null; private set
  var finished: JSONObject?=null; private set
  /** Wall time (ms) the first event of this project was applied on the headset, for voice_to_first_event_s. */
  var firstEventWallMs=0L; private set
  private var lastSeq=0L

  /** Applies [e] if it belongs to this project and is new. Returns true if what the window shows changed. */
  fun add(e: JSONObject,nowMs: Long=System.currentTimeMillis()): Boolean {
    if(e.optString("project_id")!=projectId) return false
    val seq=e.optLong("seq");if(seq<=lastSeq) return false
    lastSeq=seq
    if(firstEventWallMs==0L) firstEventWallMs=nowMs
    val title=e.optString("title")
    fun row(kind: String,text: String) { if(text.isNotBlank()) rows.add(Row(seq,kind,text)) }
    when(e.optString("kind")) {
      "project_created" -> name=e.optString("name").ifBlank { title }
      "started" -> { row("started",if(started) "Your coding agent picked up your answer" else "Your coding agent started");started=true }
      "file_written" -> row("file","Wrote "+e.optString("path").ifBlank { title.removePrefix("Wrote ") })
      "command_run" -> row("command","Ran "+short(e.optString("command").ifBlank { title.removePrefix("Ran ") },48))
      "question" -> { question=e;row("question","Asks: "+title) }
      "answered" -> { if(question?.optString("decision_id")==e.optString("decision_id")) question=null;row("answered",title) }
      "task_state" -> if(e.optString("state")!="needs_answer" && e.optString("state")!="queued") question=null
      "finished" -> {
        finished=e;question=null
        if(e.optBoolean("ok")) {
          row("finished",e.optString("commit").let { if(it.isNotBlank()) "Saved as commit $it" else "Finished, nothing changed" })
          e.optString("output").takeIf { it.isNotBlank() }?.let { row("output","It printed: "+short(it,60)) }
        } else row("finished",title.ifBlank { "Stopped" })
      }
      "error" -> row("error",title)
      else -> return false
    }
    return true
  }
  val done get()=finished!=null
  val ok get()=finished?.optBoolean("ok")==true
  val commit get()=finished?.optString("commit").orEmpty()
  val output get()=finished?.optString("output").orEmpty()

  companion object { fun short(s: String,n: Int)=s.replace(Regex("\\s+")," ").trim().let { if(it.length>n) it.take(n-1)+"…" else it } }
}

/** What the project window shows: a title, up to [MAX_LINES] lines and its buttons (key to label). */
data class ProjectScreen(val key: String,val title: String,val lines: List<String>,val buttons: List<Pair<String,String>>)

object ProjectScreens {
  const val MAX_LINES=6
  const val IDLE_TITLE="New project"
  const val HINT="Say \"new project\" and what to build."
  const val DICTATE="Now say what to build."
  const val SENDS_OFF="Sending is off. Turn on Send real answers in Settings, then pinch Start."
  const val WAITING="Waiting for the computer to start it…"
  val GRAPH="graph" to "Show plan on graph"
  const val ANSWER_ON_CARD="Answer on the card: pinch an option and confirm, or say yes or no."

  /** [sendsOn] = the Live answers switch; [connected] = the link is up; [heard] = the last transcript not understood;
   * [planned] = the project has a plan the graph can show (#978: adds "Show plan on graph"). */
  fun of(state: NewProjectFlow.State,t: ProjectTimeline?,sendsOn: Boolean,connected: Boolean,heard: String="",planned: Boolean=false): ProjectScreen {
    val link=if(connected) emptyList() else listOf("Not connected to your computer right now. It will be sent when it is back.")
    return when(state) {
      NewProjectFlow.State.Idle -> ProjectScreen("idle",IDLE_TITLE,listOfNotNull(HINT,heard.takeIf { it.isNotBlank() }?.let { "Heard: \"${ProjectTimeline.short(it,80)}\"" }),
        listOf("close" to "Close"))
      NewProjectFlow.State.Dictating -> ProjectScreen("dictating",IDLE_TITLE,listOf(DICTATE),listOf("discard" to "Cancel"))
      is NewProjectFlow.State.Chip -> ProjectScreen("chip",NewProjectFlow.chip(state.description),
        listOf("Pinch Start or say yes. Say no to discard.")+(if(sendsOn) emptyList() else listOf(SENDS_OFF)),
        listOf("start" to "Start","discard" to "Discard"))
      is NewProjectFlow.State.Sending -> ProjectScreen("sending",NewProjectFlow.CHIP_PREFIX+state.description,
        listOf(if(sendsOn) WAITING else SENDS_OFF)+link,listOf("close" to "Close"))
      is NewProjectFlow.State.Tracking -> {
        val tl=t
        val title=tl?.name?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() } ?: (NewProjectFlow.CHIP_PREFIX+state.description)
        val rows=tl?.rows?.map { it.text }.orEmpty()
        val key=when { tl==null || rows.isEmpty() -> "started"; tl.question!=null -> "question"; tl.done -> if(tl.ok) "finished" else "failed"; else -> "working" }
        val extra=if(tl?.question!=null) listOf(ANSWER_ON_CARD) else emptyList()
        val lines=(if(rows.isEmpty()) listOf(WAITING) else rows).takeLast(MAX_LINES-extra.size-link.size)+extra+link
        val buttons=(if(planned) listOf(GRAPH) else emptyList())+when(key) {
          "question" -> listOf("card" to "Open the card")
          "finished","failed" -> listOf("new" to "New project","close" to "Close")
          else -> listOf("close" to "Close")
        }
        ProjectScreen(key,title,lines,buttons)
      }
    }
  }
}
