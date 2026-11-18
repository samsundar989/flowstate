package com.overhand.app.features

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class PlacementTest {
  private val head=V3(0.0,1.2,0.0)
  private fun fwd(pitch: Double,yaw: Double=0.0): V3 {
    val p=Math.toRadians(pitch);val y=Math.toRadians(yaw)
    return V3(cos(p)*sin(y),sin(p),cos(p)*cos(y))
  }

  @Test fun comfortZone() {
    assertTrue(Comfort.ok(head,V3(0.0,1.2,1.3),0.0))
    assertEquals("too close",Comfort.check(head,V3(0.0,1.2,0.3),0.0).why)
    assertEquals("too far",Comfort.check(head,V3(0.0,1.2,3.0),0.0).why)
    // Nov 12: the window ring centred at 2.0 m (station y 1.25 + 0.75) with a seated eye at 1.2 m; Settings 1.1 m higher.
    assertEquals("too high",Comfort.check(head,V3(0.0,3.1,1.2),0.0).why)
    assertEquals("too low",Comfort.check(head,V3(0.0,0.2,1.0),0.0).why)
    // Behind: the ring (1.6 m) is fine, farther is not.
    assertTrue(Comfort.ok(head,V3(0.0,1.2,-1.6),0.0))
    assertEquals("behind, beyond the ring",Comfort.check(head,V3(0.0,1.2,-2.2),0.0).why)
    // In front, 2.2 m is fine.
    assertTrue(Comfort.ok(head,V3(0.0,1.2,2.2),0.0))
    // Heading matters for "behind".
    assertTrue(Comfort.ok(head,V3(0.0,1.2,-2.2),180.0))
  }

  @Test fun clampFindsNearestValidPose() {
    val cases=listOf(V3(0.0,5.2,0.5),V3(0.0,1.2,-4.0),V3(3.0,-1.0,3.0),V3(0.0,1.2,0.1),V3(0.0,1.2,0.0),V3(0.2,9.0,0.0),
      V3(Double.NaN,1.0,1.0).let { V3(0.0,1.2,0.0) })
    for(yaw in listOf(0.0,90.0,-135.0)) for(c in cases) {
      val out=Comfort.clamp(head,c,yaw)
      assertTrue("$c yaw $yaw -> $out ${Comfort.check(head,out,yaw)}",Comfort.ok(head,out,yaw))
    }
    // A valid pose is not moved.
    val fine=V3(0.4,1.1,1.4)
    assertEquals(fine,Comfort.clamp(head,fine,0.0))
    // Azimuth is kept: a window pushed 4 m up straight ahead comes down ahead, not sideways.
    val up=Comfort.clamp(head,V3(0.0,5.2,1.0),0.0)
    assertEquals(0.0,up.x,1e-6);assertTrue(up.z>0)
  }

  @Test fun defaultRingIsInsideTheZone() {
    // Every default window, around a level head at any heading, passes the rule (Settings included).
    for(yaw in listOf(0.0,77.0,-160.0)) WindowLayout.defaultSpecs.forEach { sp ->
      val a=Math.toRadians(yaw)
      val c=V3(head.x+sp.x*cos(a)+sp.z*sin(a),head.y+sp.y,head.z-sp.x*sin(a)+sp.z*cos(a))
      assertTrue("${sp.view} yaw $yaw ${Comfort.check(head,c,yaw)}",Comfort.ok(head,c,yaw))
    }
  }

  @Test fun noPlacementBeforeTrackingIsSteady() {
    val s=HeadSettle()
    var t=0L
    // Untracked: identity at the origin for 3 s.
    repeat(30) { assertFalse(s.sample(t,V3(0.0,0.0,0.0),V3(0.0,0.0,1.0)));t+=100 }
    // Lying flat on a desk, looking at the ceiling, perfectly still for 4 s: not level, so no placement.
    repeat(40) { assertFalse(s.sample(t,V3(0.3,0.75,0.2),fwd(88.0)));t+=100 }
    // Being lifted and put on: moving 10 cm and turning every frame.
    repeat(15) { i -> assertFalse(s.sample(t,V3(0.3,0.75+i*0.04,0.2),fwd(80.0-i*5,i*3.0)));t+=100 }
    assertFalse(s.settled)
    // Worn and still: settles after ~0.8 s, not before.
    var settledAt=-1L;val start=t
    repeat(20) { if(s.sample(t,V3(0.3,1.25,0.2),fwd(-5.0,40.0)) && settledAt<0) settledAt=t;t+=100 }
    assertTrue(s.settled);assertEquals("steady",s.reason)
    assertTrue("settled ${settledAt-start} ms after the head stopped",settledAt-start in 600..1000)
    // Settles once; a later sample is not a new placement until reset.
    assertFalse(s.sample(t,V3(0.3,1.25,0.2),fwd(-5.0,40.0)))
    s.reset(t)
    assertFalse(s.settled)
  }

  @Test fun smallHeadJitterStillSettles() {
    val s=HeadSettle();var t=0L;var done=false
    repeat(30) { i -> done=done || s.sample(t,V3(0.005*sin(i.toDouble()),1.2,0.0),fwd(2*sin(i*0.7),1.5*cos(i*0.9)));t+=100 }
    assertTrue(done)
  }

  @Test fun lookingAtTheCeilingFallsBackAfterTenSeconds() {
    val s=HeadSettle();var t=0L;var at=-1L
    while(t<=12_000) { if(s.sample(t,V3(0.0,1.2,0.0),fwd(70.0,10.0))) at=t;t+=100 }
    assertTrue("fallback at $at",at in HeadSettle.FALLBACK_MS..HeadSettle.FALLBACK_MS+200)
    assertTrue(s.reason.startsWith("fallback"))
  }

  @Test fun headHeightOutOfRangeIsNotTracked() {
    assertFalse(HeadSettle.tracked(V3(0.0,0.1,0.0),V3(0.0,0.0,1.0)))
    assertFalse(HeadSettle.tracked(V3(0.0,3.0,0.0),V3(0.0,0.0,1.0)))
    assertTrue(HeadSettle.tracked(V3(0.0,1.2,0.0),V3(0.0,0.0,1.0)))
  }
}
