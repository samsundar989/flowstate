package com.overhand.app.hostlink.project

import com.overhand.app.voice.VoiceIntents

/**
 * #973: a spoken change to the open project, mirroring [NewProjectFlow]: nothing reaches the computer from a
 * transcript the wearer has not seen. "change …", "make …", "add …" or "now …" while a project window is open only
 * shows a chip, "Change <project>: <request>?"; a pinch on Send or a spoken "yes" sends it as `prompt`; "no" drops it.
 */
class FollowUpFlow {
  sealed interface State {
    object Idle: State
    data class Chip(val project: String,val projectId: String,val text: String): State
    data class Sending(val project: String,val projectId: String,val text: String,val reqId: String): State
  }
  enum class Effect { NONE, SHOW, SEND, DISCARDED, HINT }

  var state: State=State.Idle; private set
  private var nextReq=0
  var reqIdMaker: () -> String={ "c_change_"+(++nextReq)+"_"+System.currentTimeMillis().toString(36) }

  /** Whether this flow takes [text] now: a chip is waiting for yes/no, or [text] is a change request. */
  fun wants(text: String)=state is State.Chip || isChange(text)

  /** A transcript while the project [project] ([projectId]) is open. */
  fun heard(text: String,project: String,projectId: String): Effect {
    val t=text.trim();if(t.isEmpty()) return Effect.NONE
    val s=state
    if(s is State.Chip) {
      when(NewProjectFlow.yesNo(t)) { true -> return confirm(); false -> return discard(); null -> {} }
    }
    if(!isChange(t)) return Effect.HINT
    if(s is State.Sending) return Effect.HINT       // one change in flight at a time
    state=State.Chip(project,projectId,NewProjectFlow.tidy(t));return Effect.SHOW
  }
  fun confirm(): Effect {
    val s=state as? State.Chip ?: return Effect.NONE
    state=State.Sending(s.project,s.projectId,s.text,reqIdMaker());return Effect.SEND
  }
  fun discard(): Effect {
    if(state !is State.Chip) return Effect.NONE
    state=State.Idle;return Effect.DISCARDED
  }
  /** The host queued the change (its `ok` or the task in its log). */
  fun sent(reqId: String) { if((state as? State.Sending)?.reqId==reqId) state=State.Idle }

  companion object {
    private val LEADS=setOf("change","make","add","now")
    /** Leads with change/make/add/now (after an "okay"/"please"), and says something beyond it. */
    fun isChange(t: String): Boolean {
      val w=VoiceIntents.words(t).dropWhile { it in setOf("ok","okay","please","so","and") }
      return w.size>=2 && w.first() in LEADS && NewProjectFlow.describedProject(t)==null
    }
    fun chip(project: String,text: String)="Change ${project.ifBlank { "this project" }}: $text?"
    fun screen(s: State): ProjectScreen?=when(s) {
      is State.Chip -> ProjectScreen("followup_chip",chip(s.project,s.text),listOf("Pinch Send or say yes. Say no to discard."),
        listOf("followup_send" to "Send","followup_discard" to "Discard"))
      is State.Sending -> ProjectScreen("followup_sending",chip(s.project,s.text),listOf(ProjectScreens.WAITING),listOf("close" to "Close"))
      State.Idle -> null
    }
  }
}
