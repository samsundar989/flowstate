package com.overhand.app.first60

import kotlin.math.acos
import kotlin.math.sqrt

/**
 * t804: nothing is spoken or placed until the wearer's head is on and steady.
 * Steady = mounted, tracked, a live pose (the forward vector changed within [staleMs]; an identical pose is frozen or
 * head-locked, not a still head), and angular speed (angle over the last [windowMs]) under [maxDegPerS] for [holdMs].
 * If a mounted, tracked head never holds that still (looking around), [fallbackMs] after mount counts as steady.
 *
 * The numbers come from developer's four worn put-ons on Nov 12 (runs/first60/putons.json, tools/first60/steady_sweep.py):
 * 15 deg/s for 1.5 s never fired before the end of donning (0 of 4; the one put-on with a full donning trace ended
 * donning at 5.6 s and the gate fires at 7.7 s). 15 deg/s / 1.0 s and 10 deg/s / 1.0 s fired at 1.7 s and 4.4 s in that
 * put-on, while he was still adjusting the strap.
 */
class SteadyGate(val maxDegPerS: Double = MAX_DEG_PER_S, val holdMs: Long = HOLD_MS, val windowMs: Long = WINDOW_MS,
                 val staleMs: Long = STALE_MS, val fallbackMs: Long = FALLBACK_MS) {
  companion object {
    const val MAX_DEG_PER_S = 15.0
    const val HOLD_MS = 1500L
    const val WINDOW_MS = 200L
    const val STALE_MS = 300L
    const val FALLBACK_MS = 15_000L
    fun angleDeg(a: DoubleArray, b: DoubleArray): Double {
      val la = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]); val lb = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
      if (la < 1e-9 || lb < 1e-9) return 0.0
      return Math.toDegrees(acos(((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (la * lb)).coerceIn(-1.0, 1.0)))
    }
  }

  private class S(val t: Long, val f: DoubleArray)
  private val window = ArrayDeque<S>()
  private var mountedAt = -1L
  private var lastChange = -1L
  private var since = -1L
  private var prev: DoubleArray? = null
  var steadyAt = -1L; private set
  var reason = ""; private set
  /** Last smoothed speed, for the log. */
  var speed = Double.NaN; private set
  val steady get() = steadyAt >= 0
  val mounted get() = mountedAt >= 0

  fun mount(now: Long) { if (mountedAt < 0) { reset(); mountedAt = now } }
  fun unmount() { reset(); mountedAt = -1L }
  private fun reset() { window.clear(); lastChange = -1L; since = -1L; prev = null; steadyAt = -1L; reason = ""; speed = Double.NaN }

  /** One head sample (unit forward, any frame). Returns true on the sample the head first counts as steady. */
  fun sample(now: Long, forward: DoubleArray, tracked: Boolean): Boolean {
    if (steady || mountedAt < 0) return false
    if (!tracked) { window.clear(); since = -1L; prev = null; return false }
    val p = prev
    if (p == null || !p.contentEquals(forward)) lastChange = now
    prev = forward.copyOf()
    window.addLast(S(now, prev!!))
    while (window.size > 1 && now - window.first().t > windowMs + 1) window.removeFirst()
    val live = lastChange >= 0 && now - lastChange < staleMs
    speed = if (window.size >= 2) angleDeg(window.first().f, window.last().f) / ((window.last().t - window.first().t).coerceAtLeast(1) / 1000.0) else Double.NaN
    if (live && !speed.isNaN() && speed < maxDegPerS) {
      if (since < 0) since = now
      if (now - since >= holdMs) return fire(now, "steady")
    } else since = -1L
    if (now - mountedAt >= fallbackMs) return fire(now, "fallback_after_${fallbackMs}ms")
    return false
  }

  private fun fire(now: Long, why: String): Boolean { steadyAt = now; reason = why; return true }
}
