package com.overhand.app.first60

import com.overhand.app.decisions.DecisionCards

/**
 * #1281: the greeting speaks only the question the card shows. Its audio is pre-rendered for one decision per
 * recording ([First60Brief.brief] on the raw log), so the card is put on that decision; when it is no longer open
 * (practised, so absent from the card), nothing is spoken. The cue waits until the card reports that decision's
 * options as drawn views. Pure: the caller polls [step] and applies it.
 */
object OpeningBinding {
  /** How long the opening waits for the card to load and draw before it gives up silently. */
  const val WAIT_MS = 3_000L

  sealed interface Step {
    object Wait : Step
    data class Move(val id: String) : Step
    data class Speak(val id: String, val options: List<String>) : Step
    data class Skip(val why: String) : Step
  }

  /** [greeting]: the decision the audio asks; [open]: the card's open ids (null until loaded); [shown]: what it draws. */
  fun step(greeting: String?, open: List<String>?, shown: DecisionCards.Shown?, waitedMs: Long): Step = when {
    greeting == null -> Step.Skip("no_question")
    open != null && greeting !in open -> Step.Skip("question_not_open")
    shown != null && shown.id == greeting -> Step.Speak(greeting, shown.options)
    waitedMs >= WAIT_MS -> Step.Skip("card_not_ready")
    shown != null -> Step.Move(greeting)
    else -> Step.Wait
  }
}
