package com.overhand.app.features

/**
 * t1282: one pinch does one thing. Worn nov15g (events 1133-1148): one pinch started a corner grab of the card and,
 * 73 ms later, pressed its Close button; the card vanished. The grab path ([PanelGrab]) and the Android press path
 * ([com.overhand.app.decisions.CardTouchTrace]) saw the same pinch independently. Both now ask this arbiter.
 *
 * Owner of a pinch, fixed at its down and kept until release or tracking loss. Priority, highest first:
 *  1. PRESS: a clickable view under the pointer (an Android down on one, or a grab-zone pinch whose ray lies on one).
 *  2. GRAB: a grab zone (bar, corner, frame) with no clickable view under it.
 *  3. Empty panel face: nobody owns it; the press path runs as before (its miss ring), and it never blocks a grab.
 * A later claimant on the same panel loses: a held grab swallows the press, a held press refuses the grab.
 * No duration threshold: a short tap is still a tap.
 *
 * The Android press carries no hand, so its claim is per panel. It ends at its up or cancel, and also when every hand's
 * pinch has ended (an up the platform never delivered must not block grabs for ever).
 * [enforce]=false is the planted fault for tests: every claim is granted, exactly the pre-t1282 behaviour.
 */
class PinchOwner(val enforce: Boolean=true) {
  enum class Owner { PRESS, GRAB }
  data class Claim(val owner: Owner, val panel: String, val atMs: Long)
  private val hands=mutableMapOf<String,Claim>()
  private val pinching=mutableSetOf<String>()
  private var press: Claim?=null
  /** What the arbiter decided, newest last (bounded): for logs and the replay. */
  val decisions=ArrayDeque<String>()

  @Synchronized fun owner(hand: String)=hands[hand]
  @Synchronized fun grabbing(panel: String)=hands.values.any { it.owner==Owner.GRAB && it.panel==panel }
  @Synchronized fun pressing(panel: String)=press?.panel==panel

  /** A hand's pinch began (any target). */
  @Synchronized fun pinchDown(hand: String) { pinching.add(hand) }

  /**
   * A hand's pinch began on [panel]'s grab zone; [clickable]: a clickable view lies under its ray there.
   * True: the grab may start and this hand owns the pinch as GRAB until [release].
   */
  @Synchronized fun grabDown(hand: String,panel: String,clickable: Boolean,nowMs: Long): Boolean {
    pinching.add(hand)
    val pressHeld=press?.panel==panel
    if(enforce && (clickable || pressHeld)) {
      hands[hand]=Claim(Owner.PRESS,panel,nowMs)
      note("grab_refused hand=$hand panel=$panel why=${if(clickable) "clickable" else "press_held"}")
      return false
    }
    hands[hand]=Claim(Owner.GRAB,panel,nowMs)
    note("grab_owned hand=$hand panel=$panel")
    return true
  }

  /** An Android down on [panel]; [clickable]: it landed on a clickable view. False: a grab owns this pinch, swallow it. */
  @Synchronized fun pressDown(panel: String,clickable: Boolean,nowMs: Long): Boolean {
    if(enforce && grabbing(panel)) { note("press_refused panel=$panel why=grab_held");return false }
    if(clickable) press=Claim(Owner.PRESS,panel,nowMs)
    return true
  }
  @Synchronized fun pressUp(panel: String) { if(press?.panel==panel) press=null }

  /** The hand's pinch ended or its tracking was lost. */
  @Synchronized fun release(hand: String) {
    hands.remove(hand);pinching.remove(hand)
    if(pinching.isEmpty()) press=null
  }

  private fun note(s: String) { decisions.addLast(s);while(decisions.size>64) decisions.removeFirst() }

  companion object {
    /** The app's one arbiter: the windows' grab path and the card's press path share it. */
    val shared=PinchOwner()
  }
}
