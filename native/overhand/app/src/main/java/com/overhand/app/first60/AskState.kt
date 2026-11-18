package com.overhand.app.first60

import com.overhand.app.voice.VoiceIntents

/**
 * The first card's question, answered by voice or pinch. Pure: the caller passes the clock and applies [Effects].
 * - The microphone opens when the question ends ([questionEnded]).
 * - "yes" picks the card's recommended option, "no" the other one; a pinch picks the pinched option.
 * - Any other recognised speech goes to the Voice C matcher ([Effects.otherSpeech]) and the mic stays open.
 * - No answer [REPROMPT_MS] after the question: one re-prompt, never two.
 */
class AskState(val options: List<String>, val yesOption: String) {
  enum class Phase { WAITING, LISTENING, ANSWERED }
  enum class Kind { YES, NO, OTHER }
  data class Answer(val option: String, val saidYes: Boolean, val input: String, val transcript: String)

  interface Effects {
    fun openMic()
    fun closeMic()
    fun reprompt()
    fun otherSpeech(text: String)
    fun answer(a: Answer)
  }

  companion object {
    const val REPROMPT_MS = 8_000L
    private val YES = listOf("yes", "yeah", "yep", "yup", "sure", "agree", "i agree", "okay", "ok", "correct", "do it", "go ahead", "sounds good")
    private val NO = listOf("no", "nope", "nah", "disagree", "do not", "not really", "don't")
    fun kind(text: String): Kind {
      val w = VoiceIntents.words(text)
      if (w.isEmpty()) return Kind.OTHER
      val no = NO.any { VoiceIntents.contains(w, VoiceIntents.words(it)) }
      val yes = YES.any { VoiceIntents.contains(w, VoiceIntents.words(it)) }
      // "I don't agree" contains both: a negation wins. Questions ("why?") contain neither.
      return when { no -> Kind.NO; yes -> Kind.YES; else -> Kind.OTHER }
    }
  }

  var phase = Phase.WAITING; private set
  var openedAt = -1L; private set
  var reprompted = false; private set
  var answer: Answer? = null; private set
  private var effects: Effects? = null
  fun bind(e: Effects) { effects = e }

  /** The id ("A", "B") of an option label like "B: A + laser elevation maps". */
  fun idOf(label: String) = label.substringBefore(':').trim()
  private val other get() = options.map(::idOf).firstOrNull { it != yesOption } ?: yesOption

  fun questionEnded(now: Long) {
    if (phase != Phase.WAITING) return
    phase = Phase.LISTENING; openedAt = now; effects?.openMic()
  }

  /** A recogniser result while listening. Returns what it was taken as. */
  fun heard(now: Long, text: String): Kind {
    if (phase != Phase.LISTENING) return Kind.OTHER
    val k = kind(text)
    when (k) {
      Kind.YES -> finish(Answer(yesOption, true, "voice", text.trim()))
      Kind.NO -> finish(Answer(other, false, "voice", text.trim()))
      Kind.OTHER -> { openedAt = now; effects?.otherSpeech(text) }
    }
    return k
  }

  /** The wearer pinched an option on the card (any time before an answer, even while the greeting plays). */
  fun pinched(optionId: String) {
    if (phase == Phase.ANSWERED) return
    finish(Answer(optionId, optionId == yesOption, "pinch", ""))
  }

  fun tick(now: Long) {
    if (phase == Phase.LISTENING && !reprompted && openedAt >= 0 && now - openedAt >= REPROMPT_MS) { reprompted = true; effects?.reprompt() }
  }

  private fun finish(a: Answer) {
    val wasListening = phase == Phase.LISTENING
    phase = Phase.ANSWERED; answer = a
    if (wasListening) effects?.closeMic()
    effects?.answer(a)
  }
}
