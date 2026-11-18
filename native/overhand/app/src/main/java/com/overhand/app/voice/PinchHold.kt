package com.overhand.app.voice

import com.overhand.app.hands.HandJoints

/**
 * Pinch-hold from tracked hand joints: thumb tip and index tip together for [holdMs] starts a hold, and opening
 * them past [openM] ends it. A quick pinch (selecting a button, grabbing a window) never reaches [holdMs], so it
 * does not open the mic. Either hand can hold; the hand that started the hold is the one that must open.
 */
class PinchHold(private val closeM: Double = 0.022, private val openM: Double = 0.040, private val holdMs: Long = 350) {
  enum class Edge { NONE, START, END }
  private val closedSince = mutableMapOf<String, Long>()
  var holder: String? = null; private set

  fun gap(h: HandJoints) = (h.thumb.tip - h.fingers[0].tip).length()

  /** One sample per hand ("left"/"right"; null = not tracked). Returns the edge this sample caused, if any. */
  fun update(now: Long, hands: Map<String, HandJoints?>): Edge {
    val active = holder
    if (active != null) {
      val h = hands[active]
      // A lost hand ends the hold too, or a judge who drops their hand out of view would be stuck listening.
      if (h == null || gap(h) > openM) { holder = null; closedSince.clear(); return Edge.END }
      return Edge.NONE
    }
    for ((side, h) in hands) {
      if (h == null || gap(h) > closeM) { closedSince.remove(side); continue }
      val since = closedSince.getOrPut(side) { now }
      if (now - since >= holdMs) { holder = side; return Edge.START }
    }
    return Edge.NONE
  }
}
