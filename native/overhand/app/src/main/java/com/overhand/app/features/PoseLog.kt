package com.overhand.app.features

import com.overhand.app.hands.HandJoints
import kotlin.math.*

/** Numeric observations only. No scene writes, text, projects, paths, or UI strings enter this wire format. */
object PoseLog {
  enum class Moment { TICK, GRAB, RELEASE, PLACE, BLUEPRINT_OPEN, BLUEPRINT_CLOSE, SITE_OPEN, SITE_CLOSE, MENU_OPEN, MENU_CLOSE, PALM_AWAY, PUT_ON }
  class Clock {
    private var next=0L
    fun due(now: Long): Boolean {
      if(now<next) return false
      next=now+500
      return true
    }
  }
  data class Head(val x: Double,val y: Double,val z: Double,val yaw: Double,val pitch: Double)
  data class Body(val id: String,val x: Double,val y: Double,val z: Double,val yaw: Double,val visible: Boolean,val cone: Boolean,val held: Boolean)
  data class Wrist(val height: Double,val facing: Double)
  private fun n(v: Double) = if(v.isFinite()) "%.4f".format(java.util.Locale.ROOT,v) else "null"
  fun cone(h: Head,x: Double,y: Double,z: Double): Boolean {
    val yaw=Math.toRadians(h.yaw);val pitch=Math.toRadians(h.pitch)
    val dx=x-h.x;val dy=y-h.y;val dz=z-h.z
    val length=sqrt(dx*dx+dy*dy+dz*dz)
    return length>1e-6 && (dx*sin(yaw)*cos(pitch)+dy*sin(pitch)+dz*cos(yaw)*cos(pitch))/length>=sqrt(0.5)
  }
  /** Same handed cross product as Opening.palmNormal, scalar-only for the render-rate edge check. */
  fun facing(h: HandJoints,left: Boolean,x: Double,y: Double,z: Double): Double {
    if(h.fingers.size!=4) return Double.NaN
    val a=h.fingers[0].base;val b=h.fingers[3].base;val w=h.wrist
    val ax=a.x-w.x;val ay=a.y-w.y;val az=a.z-w.z
    val bx=b.x-w.x;val by=b.y-w.y;val bz=b.z-w.z
    val sign=if(left) -1.0 else 1.0
    val nx=(ay*bz-az*by)*sign;val ny=(az*bx-ax*bz)*sign;val nz=(ax*by-ay*bx)*sign
    val m=h.fingers[1].base
    val dx=x-(w.x+m.x)/2;val dy=y-(w.y+m.y)/2;val dz=z-(w.z+m.z)/2
    val norm=sqrt((nx*nx+ny*ny+nz*nz)*(dx*dx+dy*dy+dz*dz))
    return if(norm<1e-9) Double.NaN else ((nx*dx+ny*dy+nz*dz)/norm).coerceIn(-1.0,1.0)
  }
  fun line(moment: Moment,h: Head,bodies: List<Body>,left: Wrist?,right: Wrist?,menu: Boolean,hand: String?): String {
    fun wrist(w: Wrist?)=w?.let { "[${n(it.height)},${n(it.facing)}]" } ?: "null"
    // IDs come only from this allowlist or a numeric SDK panel registration ID.
    fun id(s: String)=if(s in setOf("site","dock","fleet","build","blueprint","chat","code","settings","voice","decision","graph") || s.matches(Regex("panel_[0-9]+"))) s else "window"
    return "pose {\"moment\":\"${moment.name.lowercase()}\",\"head\":[${n(h.x)},${n(h.y)},${n(h.z)},${n(h.yaw)},${n(h.pitch)}],\"objects\":["+
      bodies.joinToString(",") { "{\"id\":\"${id(it.id)}\",\"p\":[${n(it.x)},${n(it.y)},${n(it.z)}],\"yaw\":${n(it.yaw)},\"visible\":${it.visible},\"cone\":${it.cone},\"held\":${it.held}}" }+
      "],\"left\":${wrist(left)},\"right\":${wrist(right)},\"menu\":$menu,\"hand\":"+(if(hand=="left" || hand=="right") "\"$hand\"" else "null")+"}"
  }
}
