package com.overhand.app.voice

/**
 * #1035: where a turn goes when the wearer talks to an agent on the build site. The voice knows nothing of the site:
 * [lock] at speech start says whether an agent is targeted (its caption, or null for speech as before), [take] gets
 * the transcript and answers through [speak] (one caption-and-speech sentence each) then [done], and [end] lets the
 * target go when the turn is over.
 */
interface AgentVoice {
  fun lock(): String?
  /** The caption while an agent is under the ray (null: none). */
  fun hint(): String?
  fun take(text: String, speak: (String) -> Unit, done: () -> Unit): Boolean
  fun end()
}
