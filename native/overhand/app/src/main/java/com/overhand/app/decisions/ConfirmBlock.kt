package com.overhand.app.decisions

import com.overhand.app.data.SessionSource
import org.json.JSONObject

/**
 * Every reason a confirm can stop before it sends, in plain words for the card (t745: a Confirm that
 * returned silently left the wearer with nothing to go on). Null means the confirm may proceed.
 */
object ConfirmBlock {
  const val CLOSING="The app is closing. Nothing was sent."
  const val BUSY="Still sending the last answer. Wait a moment."
  const val TRIALS="Pose practice is on, so answers are off. Turn it off in Settings."
  const val TUTORIAL="Finish the hand tutorial on this card first, or pinch Skip."
  const val SAVED="Already saved. Pinch Next decision."
  const val STALE="This card changed. Pinch an option again."
  const val GUARD="Just confirmed. Release your hand, then try again."
  const val NO_OPTION="Select an option first"
  const val LOOK="Look at the card to use thumbs up."
  data class State(val stopped: Boolean=false,val busy: Boolean=false,val trials: Boolean=false,
    val tutorial: Boolean=false,val receipt: Boolean=false,val stale: Boolean=false)
  fun reason(s: State): String? = when {
    s.stopped -> CLOSING
    s.busy -> BUSY
    s.trials -> TRIALS
    s.tutorial -> TUTORIAL
    s.receipt -> SAVED
    s.stale -> STALE
    else -> null
  }
  /** The trace word for a [reason] text (#800: `OverhandTouch … outcome=none:<key>`). */
  fun key(text: String)=when(text) {
    CLOSING -> "closing";BUSY -> "busy";TRIALS -> "trials";TUTORIAL -> "tutorial";SAVED -> "receipt";STALE -> "stale";GUARD -> "guard"
    else -> "blocked"
  }
}

/** Live answers reach agents only while the wearer has turned on "Send real answers"; it starts off on every launch. */
object LiveSafety {
  const val PRACTICE="Practice: nothing was sent"
  const val SWITCH="Send real answers"
  @Volatile var sendReal=false
  fun maySend(recorded: Boolean,sendReal: Boolean=this.sendReal)=recorded || sendReal
  class PracticeOnly: IllegalStateException(PRACTICE)
  /** The only way the card calls [SessionSource.answer]: a Live source with the switch off is never called. */
  fun answer(source: SessionSource,sendReal: Boolean,d: JSONObject,label: String,input: String): String {
    if(!maySend(source.recorded,sendReal)) throw PracticeOnly()
    return source.answer(d,label,input)
  }
}
