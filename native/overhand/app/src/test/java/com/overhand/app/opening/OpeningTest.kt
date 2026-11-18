package com.overhand.app.opening

import com.overhand.app.hands.Finger
import com.overhand.app.hands.HandJoints
import com.overhand.app.hands.Point
import com.overhand.app.features.WindowLayout
import org.junit.Assert.*
import org.junit.Test

class OpeningTest {
  /** World frame: y up, -z forward. A flat hand held out palm up, fingers forward. */
  private fun palmUpHand(left: Boolean,pinch: Boolean=false): HandJoints {
    val s=if(left) 1.0 else -1.0 // left hand: index on the -x side
    fun p(x: Double,y: Double,z: Double)=Point(s*x,1.0+y,-0.4+z)
    val fingers=listOf(-0.022,-0.004,0.014,0.03).map { x -> Finger(p(x,0.0,-0.09),p(x,0.0,-0.13),p(x,0.0,-0.155),p(x,0.0,-0.175)) }
    val thumbTip=if(pinch) fingers[0].tip.let { Point(it.x+0.005,it.y,it.z) } else p(-0.08,0.01,-0.09)
    return HandJoints(p(0.0,0.0,0.0),Finger(p(-0.025,0.0,-0.02),p(-0.045,0.0,-0.045),p(-0.06,0.005,-0.07),thumbTip),fingers)
  }
  /** Turn the hand over about the forearm (z) axis. */
  private fun turned(h: HandJoints): HandJoints {
    val c=h.wrist
    fun r(p: Point)=Point(2*c.x-p.x,2*c.y-p.y,p.z)
    fun f(x: Finger)=Finger(r(x.base),r(x.pip),r(x.dip),r(x.tip))
    return HandJoints(r(h.wrist),f(h.thumb),h.fingers.map(::f))
  }

  @Test fun palmUpEitherHand() {
    listOf(true,false).forEach { left ->
      val n=Opening.palmNormal(palmUpHand(left),left)
      assertTrue("left=$left normal=$n",n.y>0.95)
      assertTrue(Opening.palmUp(palmUpHand(left),left,false))
    }
  }
  @Test fun palmDownIsNotUp() {
    listOf(true,false).forEach { left ->
      val h=turned(palmUpHand(left))
      assertTrue(Opening.palmNormal(h,left).y < -0.95)
      assertFalse(Opening.palmUp(h,left,true))
    }
  }
  @Test fun wrongHandednessReadsDown() {
    // The sign flips with handedness: a left hand read as right faces down.
    assertTrue(Opening.palmNormal(palmUpHand(true),false).y < -0.95)
  }
  @Test fun hysteresisKeepsATiltedPalm() {
    // About 60 degrees from up: normal.y = 0.5, between KEEP and UP.
    val h=palmUpHand(true)
    val c=h.wrist;val a=Math.toRadians(60.0)
    fun r(p: Point): Point { val x=p.x-c.x;val y=p.y-c.y;return Point(c.x+x*Math.cos(a)-y*Math.sin(a),c.y+x*Math.sin(a)+y*Math.cos(a),p.z) }
    fun f(x: Finger)=Finger(r(x.base),r(x.pip),r(x.dip),r(x.tip))
    val tilted=HandJoints(r(h.wrist),f(h.thumb),h.fingers.map(::f))
    assertEquals(0.5,Opening.palmNormal(tilted,true).y,0.02)
    assertFalse(Opening.palmUp(tilted,true,false))
    assertTrue(Opening.palmUp(tilted,true,true))
    assertFalse(Opening.palmUp(null,true,true))
  }
  @Test fun pinchWithRelease() {
    assertTrue(Opening.pinching(palmUpHand(true,pinch=true),false))
    assertFalse(Opening.pinching(palmUpHand(true),false))
    assertFalse(Opening.pinching(null,true))
  }
  @Test fun hintOnlyAtThirtySecondsAndOnlyWithoutAPinch() {
    assertFalse(Opening.hintDue(29_999,false,false))
    assertTrue(Opening.hintDue(30_000,false,false))
    assertFalse(Opening.hintDue(45_000,true,false))
    assertFalse(Opening.hintDue(45_000,false,true))
  }
  @Test fun stationsGlowThenOneNameThenFullText() {
    assertEquals(0,Opening.stationMode(0,false))
    assertEquals(0,Opening.stationMode(9_999,false))
    assertEquals(1,Opening.stationMode(10_000,false))
    assertEquals(2,Opening.stationMode(1_000,true))
  }
  @Test fun wristMenuKeepsTheFourControlsInOrder() {
    assertEquals(listOf("center","blueprint","build","settings"),Opening.MENU.map { it.view })
  }

  @Test fun leaksFindRawKeysAndRedactions() {
    assertEquals(2,Opening.leaks(listOf("Headset competition","s74617e7e4f3c9e64","a [REDACTED] word","Nov 3 01:36")).size)
    assertTrue(Opening.leaks(listOf(Opening.HINT)+Opening.MENU.map { it.label }).isEmpty())
  }
  @Test fun liveIsUnchanged() { assertFalse(Opening.active(false));assertEquals(Opening.ENABLED,Opening.active(true)) }
}

class OpeningReachTest {
  @Test fun handsBeyondReachOrAboveTheHeadAreIgnored() {
    val head=Point(0.0,1.6,0.0)
    assertTrue(Opening.plausible(Point(0.1,1.2,-0.35),head))
    assertFalse(Opening.plausible(Point(0.0,0.2,-0.3),head))   // far below, on a desk
    assertFalse(Opening.plausible(Point(0.0,1.8,-0.3),head))   // above the head
    assertFalse(Opening.plausible(Point(1.2,1.4,0.0),head))    // out of reach
  }
}
