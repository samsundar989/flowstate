package com.overhand.app.hostlink.project

import com.overhand.app.voice.VoiceIntents

/**
 * "New project" by voice (#815), with no Android in it. Nothing reaches the computer from a transcript the wearer
 * has not seen: "new project <description>" (or "new project", then the description in the next turn) only shows a
 * chip, "Build: <description>. Start?". A pinch on Start or a spoken "yes" sends it; "no" or Discard drops it.
 * While a project runs, a spoken "yes"/"no" answers its open question (the same answer path as a pinch on the card).
 */
class NewProjectFlow {
  sealed interface State {
    object Idle: State
    /** "New project" heard with no description: the next hold is the description. */
    object Dictating: State
    data class Chip(val description: String): State
    /** Confirmed; waiting for the computer's log to show the project. */
    data class Sending(val description: String,val reqId: String): State
    data class Tracking(val description: String,val projectId: String,val taskId: String): State
  }
  enum class Effect { NONE, SHOW, SEND, DISCARDED, ANSWER_YES, ANSWER_NO, HINT }

  var state: State=State.Idle; private set
  /** The last thing heard, shown back to the wearer when it was not understood. */
  var lastHeard=""; private set
  private var nextReq=0
  /** Request ids are made here so a resend after a drop carries the same one. */
  var reqIdMaker: () -> String={ "c_voice_"+(++nextReq)+"_"+System.currentTimeMillis().toString(36) }

  /** A transcript from one spoken turn. [questionOpen] = the running project has a question waiting. */
  fun heard(text: String,questionOpen: Boolean=false): Effect {
    val t=text.trim();lastHeard=t
    if(t.isEmpty()) return Effect.NONE
    val s=state
    describedProject(t)?.let { d ->
      if(s is State.Sending) return Effect.HINT     // one request in flight at a time
      state=if(d.isEmpty()) State.Dictating else State.Chip(d);return Effect.SHOW
    }
    val reply=yesNo(t)
    return when(s) {
      State.Idle -> Effect.HINT
      State.Dictating -> when {
        reply==false -> { state=State.Idle;Effect.DISCARDED }
        words(t).size<2 -> Effect.HINT
        else -> { state=State.Chip(tidy(t));Effect.SHOW }
      }
      is State.Chip -> when(reply) { true -> confirm(); false -> discard(); null -> Effect.HINT }
      is State.Sending -> Effect.HINT
      is State.Tracking -> when {
        questionOpen && reply==true -> Effect.ANSWER_YES
        questionOpen && reply==false -> Effect.ANSWER_NO
        else -> Effect.HINT
      }
    }
  }

  /** Start: a pinch on the chip's Start, or a spoken "yes". */
  fun confirm(): Effect {
    val s=state as? State.Chip ?: return Effect.NONE
    state=State.Sending(s.description,reqIdMaker());return Effect.SEND
  }
  fun discard(): Effect {
    if(state !is State.Chip && state !is State.Dictating) return Effect.NONE
    state=State.Idle;return Effect.DISCARDED
  }
  /** The host's log shows the queued task for [reqId]. */
  fun created(reqId: String,projectId: String,taskId: String) {
    val s=state as? State.Sending ?: return
    if(s.reqId==reqId) state=State.Tracking(s.description,projectId,taskId)
  }
  /** Close the finished project's window state; the next "new project" starts over. */
  fun reset() { if(state !is State.Sending) state=State.Idle }

  companion object {
    const val CHIP_PREFIX="Build: "
    fun chip(description: String)="$CHIP_PREFIX$description. Start?"
    /** What the host is asked to do: the description exactly as the wearer saw it on the chip. */
    fun prompt(description: String)="new project: $description"

    private val NEW=Regex("""\b(?:a\s+)?new\s+projects?\b[\s,:;.\-]*""",RegexOption.IGNORE_CASE)
    private val YES=setOf("yes","yeah","yep","yup","start","sure","ok","okay","go","confirm","correct","right")
    private val NO=setOf("no","nope","nah","discard","cancel","stop","wrong")
    private val FILLER=setOf("please","it","ahead","do","that","is","thanks","thank","you","start","go","just","sure","ok","okay")

    private fun words(t: String)=VoiceIntents.words(t)

    /** "new project a web page with a counter" -> "a web page with a counter"; "new project" -> ""; else null. */
    fun describedProject(t: String): String? {
      val m=NEW.find(t) ?: return null
      // Only when the phrase leads the utterance ("start a new project …", "okay new project …"), not mid-sentence.
      if(words(t.substring(0,m.range.first)).size>2) return null
      val rest=t.substring(m.range.last+1).replace(Regex("""^(?:called|named|for|to|that|is)\b[\s,:]*""",RegexOption.IGNORE_CASE),"")
      return tidy(rest).takeIf { words(it).size>=2 } ?: ""
    }
    /** true = yes, false = no, null = neither (only short replies count, so a sentence with "no" in it is not a no). */
    fun yesNo(t: String): Boolean? {
      val w=words(t)
      if(w.isEmpty() || w.size>4) return null
      if(w.joinToString(" ").removePrefix("oh ")=="never mind") return false
      val y=w.count { it in YES };val n=w.count { it in NO }
      if(w.any { it !in YES && it !in NO && it !in FILLER }) return null
      return when { n>0 && y==0 -> false; y>0 && n==0 -> true; else -> null }
    }
    /** Trim, drop a trailing full stop, lower-case the first letter unless it is "I" or an acronym. */
    fun tidy(t: String): String {
      var s=t.trim().trimEnd('.','!','?',',').trim().replace(Regex("\\s+")," ")
      if(s.length>1 && s[0].isUpperCase() && !s[1].isUpperCase() && !s.startsWith("I ")) s=s[0].lowercaseChar()+s.substring(1)
      return s
    }
  }
}
