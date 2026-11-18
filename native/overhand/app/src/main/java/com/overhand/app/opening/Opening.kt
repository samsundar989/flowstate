package com.overhand.app.opening

import com.overhand.app.hands.HandJoints
import com.overhand.app.hands.Point
import kotlin.math.sqrt

/**
 * Recorded cold launch: the moon, the architecture graph building itself and the station arc.
 * No windows and no card at start; a palm-up menu opens windows; a hint at 30 s if nothing was pinched.
 * Pure rules (no Android types) so they are unit-tested on the host.
 */
object Opening {
  /** false gives the launch exactly as before (all six windows and the first card). */
  const val ENABLED=true
  /** The hint appears this long after the scene is ready, only if the wearer has not pinched. */
  const val HINT_MS=30_000L
  /** Stations show only their glow for the first seconds, then one name each. */
  const val STATION_NAMES_MS=10_000L
  /** Thumb tip to index tip, metres. */
  const val PINCH_M=0.022
  const val PINCH_RELEASE_M=0.035
  /** Palm normal's up component: show above, keep showing while above the lower value. */
  const val PALM_UP=0.65
  const val PALM_KEEP=0.35
  const val PINCH_FRAMES=2
  /** A real hand is within reach of the head and not above it. */
  const val REACH_M=0.85
  fun plausible(wrist: Point,head: Point): Boolean {
    val d=wrist-head
    return sqrt(d.dot(d))<REACH_M && d.y<0.10
  }
  /** #1236: one plain line; the menu is on the wrist (see [WristMenu]). */
  const val HINT="Open a palm toward your face. Tap the menu with your other index finger."

  data class Item(val label: String,val view: String)
  /** The six windows (views as in WindowLayout.defaultSpecs) and the day replay. */
  /** A raised hand only: the wrist at most this far below the head (a palm on the lap is not a request). */
  const val MENU_DROP_M=0.45
  val MENU=listOf(Item("Center windows","center"),Item("Blueprint","blueprint"),
    Item("Yard","build"),Item("Settings","settings"))

  fun active(recorded: Boolean)=ENABLED && recorded

  /** 0 = glow only, 1 = one name per station, 2 = full station text (after a window was opened). */
  fun stationMode(elapsedMs: Long,windowOpened: Boolean)=when {
    windowOpened -> 2
    elapsedMs<STATION_NAMES_MS -> 0
    else -> 1
  }

  fun hintDue(elapsedMs: Long,pinched: Boolean,menuSeen: Boolean)=!pinched && !menuSeen && elapsedMs>=HINT_MS

  private fun unit(p: Point): Point { val n=sqrt(p.dot(p)).coerceAtLeast(1e-9);return Point(p.x/n,p.y/n,p.z/n) }

  /**
   * Unit palm normal (out of the palm) in world space, y up. Index base and little base around the
   * wrist span the palm; the cross product's sign flips between hands.
   */
  fun palmNormal(h: HandJoints,left: Boolean): Point {
    val a=h.fingers[0].base-h.wrist;val b=h.fingers[3].base-h.wrist
    return unit(if(left) b.cross(a) else a.cross(b))
  }

  /** Hysteresis: a palm already up stays up until it drops below [PALM_KEEP]. */
  fun palmUp(h: HandJoints?,left: Boolean,wasUp: Boolean): Boolean {
    h ?: return false
    return palmNormal(h,left).y>(if(wasUp) PALM_KEEP else PALM_UP)
  }

  fun pinchDistance(h: HandJoints): Double { val d=h.thumb.tip-h.fingers[0].tip;return sqrt(d.dot(d)) }
  fun pinching(h: HandJoints?,was: Boolean)=h!=null && pinchDistance(h)<(if(was) PINCH_RELEASE_M else PINCH_M)

  /** Palm centre: between the wrist and the middle finger's base. */
  fun palmCentre(h: HandJoints)=Point((h.wrist.x+h.fingers[1].base.x)/2,(h.wrist.y+h.fingers[1].base.y)/2,(h.wrist.z+h.fingers[1].base.z)/2)

  private val rawId=Regex("\\bs[0-9a-f]{16}\\b")
  /** Lines that must never be on screen: raw project keys and redaction marks. */
  fun leaks(lines: Collection<String>)=lines.filter { rawId.containsMatchIn(it) || it.contains("[redacted]",true) }
}
