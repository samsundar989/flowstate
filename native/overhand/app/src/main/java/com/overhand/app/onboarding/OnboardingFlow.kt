package com.overhand.app.onboarding

import com.overhand.app.hands.ThumbPose

/** Tutorial input never submits a decision. No timer can advance a step. A replay is a rehearsal: it saves nothing. */
class OnboardingFlow(seen: Boolean = false) {
  enum class Step { PINCH, THUMB, FINISHED }
  var step = if(seen) Step.FINISHED else Step.PINCH
    private set
  private var hand: String? = null
  private var since = 0L
  private var released = false
  val active get() = step != Step.FINISHED
  var rehearsal = false
    private set
  fun replay() { step=Step.PINCH;hand=null;released=false;rehearsal=true }
  fun skip() { step=Step.FINISHED;hand=null }
  fun select() { if(step==Step.PINCH) { step=Step.THUMB;hand=null;released=false } }
  /** Recorded practice only: pinching Confirm at the thumb step is the same confirmation. Live never submits here. */
  fun confirmPractice(recorded: Boolean): Boolean {
    if(!recorded || step!=Step.THUMB) return false
    step=Step.FINISHED;hand=null;return true
  }
  fun frame(poses: Map<String,ThumbPose>, aimed: Boolean, now: Long): Boolean {
    if(step!=Step.THUMB) return false
    val up=poses.entries.firstOrNull { it.value==ThumbPose.UP }?.key
    if(up==null) { released=true;hand=null;return false }
    if(!aimed || !released) { hand=null;return false }
    if(hand!=up) { hand=up;since=now }
    if(now-since<400) return false
    step=Step.FINISHED;return true
  }
  /** 0..1 of the tutorial's 400 ms thumb hold, for the card's hold ring. */
  fun holdProgress(now: Long): Float = if(step!=Step.THUMB || hand==null) 0f else ((now-since)/400f).coerceIn(0f,1f)
  companion object {
    const val PINCH = "Pinch an option on this card to select it."
    const val THUMB = "Hold thumbs up for 0.4 seconds to confirm. Practice only."
    const val REHEARSAL = "Tutorial replay: nothing is saved and this card stays open."
    const val REHEARSAL_DONE = "Tutorial finished. Nothing was saved."
  }
}
