package com.overhand.app.hands

import kotlin.math.*

/** Positions in metres, in LOCAL_FLOOR/world axes (+Y up). No SDK/Android dependency. */
data class Point(val x: Double, val y: Double, val z: Double) {
  operator fun minus(p: Point) = Point(x-p.x,y-p.y,z-p.z)
  fun dot(p: Point) = x*p.x+y*p.y+z*p.z
  fun cross(p: Point) = Point(y*p.z-z*p.y,z*p.x-x*p.z,x*p.y-y*p.x)
  fun length() = sqrt(dot(this))
  fun unit() = length().let { if(it<1e-6) Point(0.0,0.0,0.0) else Point(x/it,y/it,z/it) }
  fun finite() = x.isFinite() && y.isFinite() && z.isFinite()
}
enum class ThumbPose(val wire: String) { UP("thumbs_up"), DOWN("thumbs_down"), NONE("none") }
data class Finger(val base: Point, val pip: Point, val dip: Point, val tip: Point)
data class HandJoints(val wrist: Point, val thumb: Finger, val fingers: List<Finger>)

object ThumbsRecognizer {
  /** Internal joint angle: a straight joint is 180 degrees. */
  fun angle(a: Point,b: Point,c: Point): Double {
    val u=a-b; val v=c-b
    if(u.length()<0.001 || v.length()<0.001) return Double.NaN
    return Math.toDegrees(acos((u.dot(v)/(u.length()*v.length())).coerceIn(-1.0,1.0)))
  }
  fun classify(h: HandJoints?): ThumbPose = evaluate(h).first
  /** The pose and, when NONE, the first check that failed: the per-frame record replays offline through this. */
  fun evaluate(h: HandJoints?): Pair<ThumbPose,String> {
    if(h==null || h.fingers.size!=4) return ThumbPose.NONE to "no_joints"
    val all=listOf(h.wrist)+ (listOf(h.thumb)+h.fingers).flatMap { listOf(it.base,it.pip,it.dip,it.tip) }
    if(all.any { !it.finite() }) return ThumbPose.NONE to "non_finite"
    val width=(h.fingers.first().base-h.fingers.last().base).length()
    if(width !in 0.025..0.13) return ThumbPose.NONE to "hand_width"
    val normal=(h.fingers.first().base-h.fingers.last().base).cross(h.fingers[1].base-h.wrist)
    if(normal.length()<0.0001 || abs(normal.unit().y)>0.65) return ThumbPose.NONE to "palm_facing"
    val t=h.thumb
    // Fitted to real worn frames (runs/headset/nov12_worn2/hands_analysis.md). The body-tracking skeleton
    // never bends a finger's or the thumb's last joint (179-180 deg on every frame), so curl is read at PIP only.
    if(!(angle(t.base,t.pip,t.dip)>=140 && angle(t.pip,t.dip,t.tip)>=140)) return ThumbPose.NONE to "thumb_bent"
    if(h.fingers.any { !(angle(it.base,it.pip,it.dip)<=110) }) return ThumbPose.NONE to "fingers_open"
    if(h.fingers.any { (t.tip-it.tip).length()<width*0.4 }) return ThumbPose.NONE to "thumb_near_fingers"
    val direction=(t.tip-t.base).unit()
    return when { direction.y>=0.75 -> ThumbPose.UP to "up"; direction.y<=-0.75 -> ThumbPose.DOWN to "down"; else -> ThumbPose.NONE to "thumb_not_vertical" }
  }
}

/**
 * #1033: when a pose may start a commit hold at all. A thumbs-up arms only while a card awaits a decision
 * and an option is selected; otherwise it never fills the ring and never commits (an accidental thumb on an
 * unpicked card, or on a receipt, does nothing). Down keeps its own rules (Kept pending, or the other option
 * of a binary Live card, which also needs a selection).
 */
object ThumbCommit {
  fun armed(pose: ThumbPose, awaitingDecision: Boolean, selected: Int, optionCount: Int): Boolean =
    pose!=ThumbPose.UP || (awaitingDecision && selected in 0 until optionCount)
}

/** One global target gate prevents two hands or a held pose from answering twice. */
class PoseHoldGate {
  private var candidate=""
  private var since=0L
  private var lastAction=Long.MIN_VALUE/2
  private var latched=false
  fun reset() { candidate=""; latched=true }
  data class Update(val holding: Boolean, val fire: Boolean)
  fun update(key: String?, now: Long): Update {
    if(key==null) { candidate=""; latched=false; return Update(false,false) }
    if(latched || now-lastAction<1500) return Update(false,false)
    if(key!=candidate) { candidate=key; since=now }
    val fire=now-since>=400
    if(fire) { lastAction=now; latched=true }
    return Update(true,fire)
  }
  /** 0..1 of the 400 ms hold for the visible ring; 0 when nothing is being held. */
  fun progress(now: Long): Float = if(candidate.isEmpty() || latched) 0f else ((now-since)/400f).coerceIn(0f,1f)
}
