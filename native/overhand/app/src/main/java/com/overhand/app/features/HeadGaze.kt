package com.overhand.app.features

import com.meta.spatial.core.*
import kotlin.math.*

/** A focus-only result: there is deliberately no select/confirm action in this API. */
class HeadGaze(private val dwellMs: Long=300) {
  private var candidate=0
  private var since=0L
  var target=0; private set
  var focusedAfterMs=0L; private set
  fun update(enabled: Boolean, hit: Int, now: Long): Int? {
    if(!enabled || hit==0) { candidate=0;target=0;return null }
    if(candidate!=hit || now<since) { candidate=hit;since=now;target=0 }
    if(now-since<dwellMs || target==hit) return null
    focusedAfterMs=now-since
    target=hit
    return hit
  }
  companion object {
    /** Front face only; intersect the actual quad rather than a cone around its centre. */
    fun hit(viewer: Pose,panel: Pose,width: Float,height: Float): Float? {
      val n=panel.forward();val dir=viewer.forward()
      val denominator=dir.dot(n)
      if(denominator<=0.0001f) return null
      val distance=(panel.t-viewer.t).dot(n)/denominator
      if(distance<=0) return null
      val offset=viewer.t+dir*distance-panel.t
      val right=panel.q*Vector3(1f,0f,0f);val up=panel.q*Vector3(0f,1f,0f)
      return if(abs(offset.dot(right))<=width/2 && abs(offset.dot(up))<=height/2) distance else null
    }
  }
}

/** Upright and eye-relative at activation, then stationary; MR anchors cannot override near mode. */
object NearTouch {
  const val DISTANCE=0.44f
  const val DROP=0.055f
  const val WIDTH=0.72f
  const val HEIGHT=0.45f
  const val CARD_WIDTH=0.75f
  const val CARD_HEIGHT=0.54f
  fun pose(viewer: Pose): Pose = WindowPose.upright(viewer)*Pose(
    Vector3(0f,-DROP,sqrt(DISTANCE*DISTANCE-DROP*DROP)))
}
