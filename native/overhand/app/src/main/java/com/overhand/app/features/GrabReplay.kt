package com.overhand.app.features

import com.overhand.app.hands.Finger
import com.overhand.app.hands.HandJoints
import com.overhand.app.hands.Point
import kotlin.math.*

/**
 * t799: developer's recorded one-hand pinch, re-targeted at a panel for the device check and the JVM test.
 * His finger motion (thumb-index closing and opening, wrist jitter) is kept frame for frame; the whole hand is
 * translated so the pinch ray points at the target, and his held frames are repeated to [HOLD_MS] while a scripted
 * gross motion is added (he never held a pinch long enough to drag, so the drag itself is synthetic).
 * These are injected poses, not his hands.
 */
object GrabReplay {
  const val HOLD_MS=1200L
  const val REACH_M=0.42
  enum class Action { MOVE, PULL, RESIZE }

  fun shift(j: HandJoints,d: V3): HandJoints {
    fun p(a: Point)=Point(a.x+d.x,a.y+d.y,a.z+d.z)
    fun f(x: Finger)=Finger(p(x.base),p(x.pip),p(x.dip),p(x.tip))
    return HandJoints(p(j.wrist),f(j.thumb),j.fingers.map(::f))
  }

  /**
   * [frames]: (t ms, joints) of one recorded pinch, open -> closed -> open. Returns frames with t from 0.
   * [target]: the point the ray should hit at pinch start (bar centre, or a corner for resize).
   */
  fun retarget(frames: List<Pair<Long,HandJoints>>,head: V3,panel: PanelGrab.Panel,target: V3,action: Action,
               moveM: Double=0.30,pullM: Double=0.22,resizeM: Double=0.16): List<Pair<Long,HandJoints>> {
    require(frames.isNotEmpty())
    val first=frames.indexOfFirst { PanelGrab.tipGap(it.second)<PanelGrab.PINCH_ON_M }
    require(first>=0) { "no frame below the pinch threshold" }
    var last=first
    while(last+1<frames.size && PanelGrab.tipGap(frames[last+1].second)<PanelGrab.PINCH_OFF_M) last++
    val held=frames.subList(first,last+1)
    val step=if(frames.size>1) ((frames.last().first-frames.first().first)/(frames.size-1)).coerceIn(10L,100L) else 56L
    // Where his anchor must be at pinch start: on the shoulder-target line, at arm's reach (near grab; the ray replay
    // aims the pre-pinch frame instead, see [retargetRay]).
    val j0=frames[first].second
    // The shoulder estimate depends on where the wrist is, so iterate until the shifted hand's own ray hits the target.
    var base=V3(0.0,0.0,0.0)
    var shoulder=PanelGrab.shoulder(head,j0)
    repeat(8) {
      base=shoulder+(target-shoulder).unit()*REACH_M-PanelGrab.anchor(j0)
      shoulder=PanelGrab.shoulder(head,shift(j0,base))
    }
    base=shoulder+(target-shoulder).unit()*REACH_M-PanelGrab.anchor(j0)
    val dir=(target-shoulder).unit()
    val range=PanelGrab.dist(target,shoulder)
    // Gross motion of the hand at full progress.
    val gross=when(action) {
      Action.MOVE -> panel.right*(moveM*REACH_M/range)
      Action.PULL -> dir*(-pullM)
      Action.RESIZE -> (panel.right*sign((target-panel.centre).dot(panel.right))+V3(0.0,-1.0,0.0)).unit()*(resizeM*REACH_M/range)
    }
    val out=mutableListOf<Pair<Long,HandJoints>>()
    var t=0L
    frames.subList(0,first).forEach { out.add(t to shift(it.second,base));t+=step }
    val n=(HOLD_MS/step).toInt().coerceAtLeast(held.size)
    for(i in 0 until n) {
      val src=held[i%held.size].second
      val phi=(i.toDouble()/(n-1).coerceAtLeast(1)).let { it*it*(3-2*it) }
      out.add(t to shift(src,base+gross*phi));t+=step
    }
    frames.subList(last+1,frames.size).forEach { out.add(t to shift(it.second,base+gross));t+=step }
    return out
  }

  /** Rigid rotation of every joint about [pivot] by the rotation taking unit [from] to unit [to] (Rodrigues). */
  fun rotate(j: HandJoints,pivot: V3,from: V3,to: V3): HandJoints {
    val axis=V3(from.y*to.z-from.z*to.y,from.z*to.x-from.x*to.z,from.x*to.y-from.y*to.x)
    val s=axis.length();val c=from.dot(to)
    if(s<1e-9) return j
    val k=axis*(1.0/s)
    fun r(p: Point): Point {
      val v=V3(p.x-pivot.x,p.y-pivot.y,p.z-pivot.z)
      val kxv=V3(k.y*v.z-k.z*v.y,k.z*v.x-k.x*v.z,k.x*v.y-k.y*v.x)
      val o=pivot+v*c+kxv*s+k*(k.dot(v)*(1-c))
      return Point(o.x,o.y,o.z)
    }
    fun f(x: Finger)=Finger(r(x.base),r(x.pip),r(x.dip),r(x.tip))
    return HandJoints(r(j.wrist),f(j.thumb),j.fingers.map(::f))
  }

  /**
   * t998: one recorded pinch, re-targeted as a pointer ray. His hand stays at his own distance from his body: every frame
   * is rigidly rotated about his estimated shoulder (that is what aiming is: the arm swings, the reach and the fingers
   * are his) so the derived pointer ray of the frame before the pinch closes passes through [target]. While held, his
   * held frames repeat to [HOLD_MS] and a scripted gross motion is added (he never held a pinch long enough to drag):
   * MOVE sideways, PULL toward the shoulder, RESIZE outward from the corner. [aim] is the index of the frame that aims.
   */
  fun retargetRay(frames: List<Pair<Long,HandJoints>>,head: V3,panel: PanelGrab.Panel,target: V3,action: Action,
                  moveM: Double=0.30,pullM: Double=0.22,resizeM: Double=0.16): List<Pair<Long,HandJoints>> {
    val first=frames.indexOfFirst { PanelGrab.tipGap(it.second)<PanelGrab.PINCH_ON_M }
    require(first>=0) { "no frame below the pinch threshold" }
    var last=first
    while(last+1<frames.size && PanelGrab.tipGap(frames[last+1].second)<PanelGrab.PINCH_OFF_M) last++
    val aim=frames[(first-1).coerceAtLeast(0)].second
    // The shoulder estimate depends on the wrist, so iterate until the rotated hand's own ray hits the target.
    var pivot=PanelGrab.shoulder(head,aim)
    repeat(12) { pivot=PanelGrab.shoulder(head,rotate(aim,pivot,(PanelGrab.anchor(aim)-pivot).unit(),(target-pivot).unit())) }
    val from=(PanelGrab.anchor(aim)-pivot).unit();val to=(target-pivot).unit()
    val rotated=frames.map { it.first to rotate(it.second,pivot,from,to) }
    val a0=PanelGrab.anchor(rotated[first].second);val sh=PanelGrab.shoulder(head,rotated[first].second)
    val arm=PanelGrab.dist(a0,sh);val dir=(target-sh).unit();val range=PanelGrab.dist(target,sh)
    val gross=when(action) {
      Action.MOVE -> panel.right*(moveM*arm/range)
      Action.PULL -> dir*(-min(pullM,(arm-0.10).coerceAtLeast(0.05)))
      Action.RESIZE -> (panel.right*sign((target-panel.centre).dot(panel.right))+V3(0.0,sign((target-panel.centre).y),0.0)).unit()*(resizeM*arm/range)
    }
    val held=rotated.subList(first,last+1)
    val step=if(frames.size>1) ((frames.last().first-frames.first().first)/(frames.size-1)).coerceIn(10L,100L) else 56L
    val out=mutableListOf<Pair<Long,HandJoints>>()
    rotated.subList(0,first).forEach { out.add(it) }
    var t=rotated[first].first
    val n=(HOLD_MS/step).toInt().coerceAtLeast(held.size)
    for(i in 0 until n) {
      val src=held[i%held.size].second
      val phi=(i.toDouble()/(n-1).coerceAtLeast(1)).let { it*it*(3-2*it) }
      out.add(t to shift(src,gross*phi));t+=step
    }
    rotated.subList(last+1,rotated.size).forEach { out.add(t to shift(it.second,gross));t+=step }
    return out
  }
}
