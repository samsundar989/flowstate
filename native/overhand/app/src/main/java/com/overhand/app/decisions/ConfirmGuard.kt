package com.overhand.app.decisions

/** What was committed, kept apart from the live card so a saved answer can never read as the next question's. */
data class PracticeReceipt(val decisionId: String,val question: String,val context: String,val option: String)

/**
 * One confirmation per decision interaction. A second click or held pose lands while the first is
 * in flight, on the receipt of the committed decision, or just after it settled. A failed or
 * unknown result releases the guard: resend policy for those belongs to DeliveryBoard.
 */
class ConfirmGuard(private val quietMs: Long=QUIET_MS) {
  private var inFlight: String?=null
  private var committed: String?=null
  private var settledAt: Long?=null
  private var mustRelease=false
  private var releasedSince: Long?=null
  private fun quiet(now: Long)=settledAt?.let { now-it<quietMs } ?: false
  fun begin(id: String,now: Long): Boolean {
    if(inFlight!=null || committed==id || quiet(now)) return false
    inFlight=id;return true
  }
  fun settle(id: String,confirmed: Boolean,now: Long) {
    if(inFlight!=id) return
    inFlight=null
    if(confirmed) { committed=id;settledAt=now;mustRelease=true;releasedSince=null }
  }
  /** True once a deliberate Next can be told apart from a repeat of the confirming input. */
  fun canAdvance(now: Long)=inFlight==null && !quiet(now)
  /** The person has moved on from the receipt; the quiet window still applies to the next card. */
  fun acknowledge() { committed=null }
  /** False until a tracked hand has shown no thumb pose for RELEASE_MS since the last commit. */
  fun poseFrame(recognized: Boolean,tracked: Boolean,now: Long): Boolean {
    if(!mustRelease) return true
    if(recognized || !tracked) { releasedSince=null;return false }
    val since=releasedSince ?: now.also { releasedSince=it }
    if(now-since<RELEASE_MS) return false
    mustRelease=false;return true
  }
  companion object { const val QUIET_MS=600L;const val RELEASE_MS=300L }
}
