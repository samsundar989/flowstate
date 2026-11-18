package com.overhand.app.features

import kotlin.math.*

/** Plain 3-vector so the placement rules are host-tested without the SDK. y is up; +z is the head's forward at yaw 0. */
data class V3(val x: Double, val y: Double, val z: Double) {
  operator fun minus(o: V3)=V3(x-o.x,y-o.y,z-o.z)
  operator fun plus(o: V3)=V3(x+o.x,y+o.y,z+o.z)
  fun length()=sqrt(x*x+y*y+z*z)
}

/**
 * Comfort rule (t765): a window, the card or the graph is reachable when its centre is 0.5 to 2.5 m from the head,
 * between 35 degrees below and 25 degrees above eye level (measured from the horizontal, not the gaze),
 * and, when it is behind the wearer, no farther out horizontally than the six-window ring.
 */
object Comfort {
  const val MIN_M=0.5
  const val MAX_M=2.5
  const val UP_DEG=25.0
  const val DOWN_DEG=-35.0
  /** The six-window ring: windows sit 1.6 m from the origin (sqrt(1.386^2+0.8^2)); 5 cm tolerance. */
  const val RING_M=1.65

  data class Check(val ok: Boolean, val distance: Double, val elevationDeg: Double, val bearingDeg: Double, val horizontal: Double, val why: String)

  /** [yawDeg] is the head's heading: 0 looks along +z, 90 along +x. */
  fun check(head: V3, centre: V3, yawDeg: Double): Check {
    val d=centre-head
    val horizontal=hypot(d.x,d.z)
    val distance=d.length()
    val elevation=Math.toDegrees(atan2(d.y,horizontal))
    val bearing=bearing(d,yawDeg)
    val why=when {
      !distance.isFinite() -> "not finite"
      distance<MIN_M -> "too close"
      distance>MAX_M -> "too far"
      elevation>UP_DEG -> "too high"
      elevation<DOWN_DEG -> "too low"
      abs(bearing)>90 && horizontal>RING_M -> "behind, beyond the ring"
      else -> ""
    }
    return Check(why.isEmpty(),distance,elevation,bearing,horizontal,why)
  }
  fun ok(head: V3, centre: V3, yawDeg: Double)=check(head,centre,yawDeg).ok

  /** Degrees from the heading to the panel, -180..180, horizontal only. */
  fun bearing(d: V3, yawDeg: Double): Double {
    if(hypot(d.x,d.z)<1e-6) return 0.0
    var b=Math.toDegrees(atan2(d.x,d.z))-yawDeg
    while(b>180) b-=360
    while(b<-180) b+=360
    return b
  }

  /** The nearest valid centre: keep the direction's azimuth, clamp elevation, then distance, then the ring behind. */
  fun clamp(head: V3, centre: V3, yawDeg: Double): V3 {
    if(ok(head,centre,yawDeg)) return centre
    var d=centre-head
    if(!d.length().isFinite() || d.length()<1e-6) {
      val a=Math.toRadians(yawDeg);d=V3(sin(a),0.0,cos(a))
    }
    val azimuth=atan2(d.x,d.z)
    val elevation=atan2(d.y,hypot(d.x,d.z)).coerceIn(Math.toRadians(DOWN_DEG+0.5),Math.toRadians(UP_DEG-0.5))
    var distance=d.length().coerceIn(MIN_M+0.01,MAX_M-0.01)
    if(abs(bearing(d,yawDeg))>90 && distance*cos(elevation)>RING_M-0.01) distance=(RING_M-0.01)/cos(elevation)
    val h=distance*cos(elevation)
    return head+V3(h*sin(azimuth),distance*sin(elevation),h*cos(azimuth))
  }

  /** Panel yaw that faces the head from [centre] (same convention as the palm menu). */
  fun facingYawDeg(head: V3, centre: V3): Double { val d=centre-head;return Math.toDegrees(atan2(d.x,d.z)) }
}

/**
 * Windows are placed only from a head pose that is tracked, level enough to have a heading, and steady.
 * Thresholds: over the last [STEADY_MS] the head moved at most [STEADY_M] and turned at most [STEADY_DEG];
 * the gaze is within [MAX_PITCH_DEG] of level (a headset lying on a desk looks at the floor or the ceiling);
 * the head is between [MIN_Y] and [MAX_Y] above the floor. If none of that holds for [FALLBACK_MS] (someone really
 * is looking at the ceiling), a steady tracked pose is used anyway, with its yaw only.
 */
class HeadSettle {
  companion object {
    const val STEADY_MS=800L
    const val STEADY_M=0.03
    const val STEADY_DEG=4.0
    const val MAX_PITCH_DEG=50.0
    const val MIN_Y=0.3
    const val MAX_Y=2.4
    const val FALLBACK_MS=10_000L
    fun pitchDeg(f: V3)=Math.toDegrees(atan2(f.y,hypot(f.x,f.z)))
    /** An untracked head reports the identity pose at the origin. */
    fun tracked(p: V3,f: V3)=p.length().isFinite() && f.length()>0.5 && !(p.length()<1e-4) && p.y in MIN_Y..MAX_Y
  }
  private data class Sample(val t: Long,val p: V3,val f: V3)
  private val samples=ArrayDeque<Sample>()
  private var since=-1L
  var settledAt=-1L; private set
  var reason=""; private set
  val settled get()=settledAt>=0

  /** Forget the settled pose (sleep, mount, system recenter): the next steady pose re-places. */
  fun reset(now: Long) { samples.clear();since=now;settledAt=-1L;reason="" }

  /** Feed one head pose (position, unit forward). Returns true on the frame the head first counts as settled. */
  fun sample(now: Long, p: V3, f: V3): Boolean {
    if(settled) return false
    if(since<0) since=now
    if(!tracked(p,f)) { samples.clear();return false }
    samples.addLast(Sample(now,p,f))
    while(samples.size>1 && now-samples.first().t>STEADY_MS) samples.removeFirst()
    val spanOk=samples.size>=3 && now-samples.first().t>=STEADY_MS*3/4
    if(!spanOk) return false
    val still=samples.all { (it.p-p).length()<=STEADY_M && angle(it.f,f)<=STEADY_DEG }
    if(!still) return false
    val level=abs(pitchDeg(f))<=MAX_PITCH_DEG
    if(level || now-since>=FALLBACK_MS) {
      settledAt=now;reason=if(level) "steady" else "fallback_after_${FALLBACK_MS}ms_pitch_${pitchDeg(f).roundToInt()}"
      return true
    }
    return false
  }
  private fun angle(a: V3,b: V3): Double {
    val c=(a.x*b.x+a.y*b.y+a.z*b.z)/(a.length()*b.length()).coerceAtLeast(1e-9)
    return Math.toDegrees(acos(c.coerceIn(-1.0,1.0)))
  }
}
