package com.overhand.app.mr

/**
 * Decides when the room-data permission may be requested. Android thread only; no Android
 * dependency so the whole boundary runs in JVM tests.
 *
 * Only [explicitAction] can reach [request]. Startup, resume and permission results re-evaluate
 * the current state and never ask.
 */
class ScenePermissionCoordinator(private val granted: () -> Boolean, private val enabled: () -> Boolean,
  private val request: () -> Unit, private val load: () -> Unit, private val fallback: (Reason) -> Unit) {
  enum class Reason { PASSTHROUGH_OFF, NOT_REQUESTED, WAITING, DENIED }

  private var inFlight=false
  private var lastGranted: Boolean?=null // null until the first evaluation
  private var denied=false

  /** Startup or a change that is not a request for room access. */
  fun refresh() { evaluate() }

  /** The user chose desk placement. At most one system dialog at a time. */
  fun explicitAction() {
    if(inFlight) return
    if(!enabled() || granted()) { evaluate();return }
    lastGranted=false
    denied=false
    inFlight=true
    fallback(Reason.WAITING)
    request()
  }

  fun permissionResult() {
    inFlight=false
    denied=!granted()
    evaluate()
  }

  /** Permission can change in system settings while the app is away. Never asks. */
  fun resumed() {
    if(inFlight || lastGranted==null) return
    if(enabled() && granted()!=lastGranted) { denied=false;evaluate() }
  }

  private fun evaluate() {
    val allowed=granted()
    lastGranted=allowed
    when {
      !enabled() -> fallback(Reason.PASSTHROUGH_OFF)
      allowed -> load()
      denied -> fallback(Reason.DENIED)
      else -> fallback(Reason.NOT_REQUESTED)
    }
  }
}
