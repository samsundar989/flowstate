package com.overhand.app.graph

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** #1380: the graph opens straight ahead, 1.6 m out, upright and facing the head; a saved place follows the Yard's rule. */
class GraphPlaceTest {
  private fun mul(a: DoubleArray,b: DoubleArray)=doubleArrayOf(a[0]*b[0]-a[1]*b[1]-a[2]*b[2]-a[3]*b[3],
    a[0]*b[1]+a[1]*b[0]+a[2]*b[3]-a[3]*b[2],a[0]*b[2]-a[1]*b[3]+a[2]*b[0]+a[3]*b[1],a[0]*b[3]+a[1]*b[2]-a[2]*b[1]+a[3]*b[0])
  /** Head rotation: yaw, then pitch about the head's own x axis. */
  private fun headQ(yawDeg: Double,pitchDeg: Double=0.0): DoubleArray { val p=Math.toRadians(pitchDeg)/2
    return mul(GraphPlace.yawQ(Math.toRadians(yawDeg)),doubleArrayOf(cos(p),sin(p),0.0,0.0)) }
  private fun deg(a: Vec,b: Vec)=Math.toDegrees(acos((a.unit().dot(b.unit())).coerceIn(-1.0,1.0)))
  @Test fun opensAheadUprightFacingTheHead() {
    for(h in listOf(1.2,1.5,1.8)) for(yaw in listOf(0.0,90.0,180.0,-45.0)) for(pitch in listOf(0.0,-30.0,20.0)) {
      val head=Vec(0.3,h,-0.2);val q=headQ(yaw,pitch)
      val p=GraphPlace.choose(null,head,q)
      assertEquals("open",p.why)
      val fwd=GraphPlace.flat(GraphPlace.rotate(q,Vec(0.0,0.0,1.0)))
      val to=p.centre-head
      assertTrue("ahead h=$h yaw=$yaw",deg(Vec(to.x,0.0,to.z),fwd)<5.0)
      assertTrue("dist ${p.dist}",p.dist in 1.4..1.8)
      assertEquals(h-GraphPlace.DROP,p.centre.y,1e-9)
      // Faces the head: the root's +z (away from the viewer) points from the head to the centre.
      val face=GraphPlace.rotate(p.q,Vec(0.0,0.0,1.0))
      assertTrue(deg(face,Vec(to.x,0.0,to.z))<5.0)
      assertEquals(0.0,p.q[1],1e-12);assertEquals(0.0,p.q[3],1e-12) // no pitch, no roll
      assertEquals(0.0,GraphPlace.rotate(p.q,Vec(1.0,0.0,0.0)).y,1e-12);assertEquals(0.0,face.y,1e-12)
    }
  }
  @Test fun savedPlaceKeptOnlyInsideTheYardRule() {
    val head=Vec(0.0,1.5,0.0);val q=headQ(0.0)
    fun at(d: Double,a: Double)=Vec(d*sin(Math.toRadians(a)),1.5,d*cos(Math.toRadians(a)))
    assertEquals("moved_away",GraphPlace.choose(at(3.0,0.0),head,q).why)
    assertEquals("moved_away",GraphPlace.choose(at(1.5,90.0),head,q).why)
    val kept=GraphPlace.choose(at(1.0,20.0),head,q)
    assertEquals("saved",kept.why);assertEquals(1.0,kept.dist,1e-9);assertEquals(20.0,kept.yawOffDeg,1e-6)
    assertEquals(0.0,kept.q[1],1e-12);assertEquals(0.0,kept.q[3],1e-12)
    assertTrue(deg(GraphPlace.rotate(kept.q,Vec(0.0,0.0,1.0)),at(1.0,20.0)-head)<5.0)
  }
}
