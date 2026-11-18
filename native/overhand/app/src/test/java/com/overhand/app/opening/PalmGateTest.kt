package com.overhand.app.opening

import com.overhand.app.hands.*
import org.junit.Assert.*
import org.junit.Test

class PalmGateTest {
  private val head=WristMenu.Head(Point(0.0,1.3,0.0),Point(0.0,0.0,1.0))
  private fun hand(): HandJoints {
    val c=Point(0.0,1.05,0.4)
    fun p(x: Double,a: Double)=Point(c.x+x,c.y+a*.848,c.z+a*.53)
    val fs=listOf(-.025,-.008,.010,.026).map { x -> Finger(p(x,.04),p(x,.075),p(x,.10),p(x,.12)) }
    return HandJoints(p(0.0,-.035),Finger(p(-.025,-.02),p(-.045,0.0),p(-.065,.01),p(-.085,.02)),fs)
  }
  private fun run(m: WristMenu,a: Long,b: Long,h: HandJoints=hand(),hold: Boolean=false,rays: Set<String> = emptySet(),other: HandJoints?=null): List<String> =
    (a..b step 14L).flatMap { m.tick(it,head,mapOf("right" to h,"left" to other),hold,rays) }
  @Test fun shapeAndDwell() {
    val h=hand();val m=WristMenu();assertTrue(m.openShape(h))
    assertTrue(run(m,0,238).isEmpty());assertEquals(listOf("show:right"),run(m,252,280))
    h.fingers.indices.forEach { i ->
      val f=h.fingers[i];val curled=h.copy(fingers=h.fingers.mapIndexed { j,x -> if(j==i) f.copy(tip=f.base) else x })
      assertFalse("finger $i",m.openShape(curled))
    }
    assertFalse(m.openShape(h.copy(thumb=h.thumb.copy(tip=h.fingers[0].tip))))
  }
  @Test fun eitherPinchOrHoldClosesImmediatelyAndLatches() {
    val m=WristMenu();run(m,0,400);assertTrue(m.showing)
    val other=hand().let { it.copy(thumb=it.thumb.copy(tip=it.fingers[0].tip)) }
    assertEquals(listOf("hide:busy"),run(m,414,442,other=other))
    assertTrue(run(m,456,2000).isEmpty())
    m.tick(2014,head,emptyMap(),false)
    assertEquals(listOf("show:right"),run(m,2028,2400))
    assertEquals(listOf("hide:busy"),run(m,2414,2442,hold=true))
  }
  @Test fun rayAndReleaseCooldown() {
    val m=WristMenu();assertTrue(run(m,0,1000,rays=setOf("right")).isEmpty())
    assertTrue(run(m,1014,1580).isEmpty())
    assertEquals(listOf("show:right"),run(m,1594,1900))
  }
  @Test fun movingHandCannotDwell() {
    val m=WristMenu();val h=hand()
    for(t in 0L..1000L step 14L) {
      fun p(v: Point)=Point(v.x+t*.0005,v.y,v.z)
      fun f(v: Finger)=Finger(p(v.base),p(v.pip),p(v.dip),p(v.tip))
      assertTrue(m.tick(t,head,mapOf("right" to HandJoints(p(h.wrist),f(h.thumb),h.fingers.map(::f))),false).isEmpty())
    }
  }
  @Test fun otherIndexPokeTargetsAndGaps() {
    val p=WristMenu().target(hand(),false,head)
    val right=PalmInput.right(p);val up=PalmInput.up(p)
    fun tip(x: Double,y: Double,z: Double)=Point(p.centre.x+right.x*x+up.x*y+p.facing.x*z,
      p.centre.y+right.y*x+up.y*y+p.facing.y*z,p.centre.z+right.z*x+up.z*y+p.facing.z*z)
    repeat(4) { i ->
      val x=if(i%2==0) -.052 else .052;val y=if(i<2) .028 else -.028
      val poke=PalmInput.Poke();assertNull(poke.tick(p,tip(x,y,-.02)))
      assertEquals(i,poke.tick(p,tip(x,y,0.0)));assertNull(poke.tick(p,tip(x,y,.01)))
    }
    val poke=PalmInput.Poke();poke.tick(p,tip(0.0,0.0,-.02));assertNull(poke.tick(p,tip(0.0,0.0,0.0)))
    assertTrue((p.centre-Opening.palmCentre(hand())).length()>=.14)
  }
}
