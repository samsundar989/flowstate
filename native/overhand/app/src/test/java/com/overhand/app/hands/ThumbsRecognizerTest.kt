package com.overhand.app.hands

import org.junit.Assert.*
import org.junit.Test

class ThumbsRecognizerTest {
  private fun fixture(kind: String): HandJoints {
    fun p(x: Double,y: Double,z: Double=0.0) = Point(x,if(kind=="down") -y else y,z)
    val fingers=(0..3).map { i ->
      val x=-0.03+i*0.02
      if(kind=="flat" || (kind=="pointing" && i==0)) Finger(p(x,0.0),p(x,0.03),p(x,0.05),p(x,0.07))
      else Finger(p(x,0.0),p(x,0.03),p(x,0.02,0.02),p(x,0.0,0.02))
    }
    val thumb=if(kind=="fist") Finger(p(-0.055,-0.02),p(-0.055,0.01),p(-0.035,0.0),p(-0.015,0.0))
      else Finger(p(-0.055,-0.02),p(-0.055,0.01),p(-0.055,0.04),p(-0.055,0.07))
    return HandJoints(p(0.0,-0.06),thumb,fingers)
  }
  @Test fun thumbsUp() { assertEquals(ThumbPose.UP,ThumbsRecognizer.classify(fixture("up"))) }
  @Test fun thumbsDown() { assertEquals(ThumbPose.DOWN,ThumbsRecognizer.classify(fixture("down"))) }
  @Test fun flatHand() { assertEquals(ThumbPose.NONE,ThumbsRecognizer.classify(fixture("flat"))) }
  @Test fun fist() { assertEquals(ThumbPose.NONE,ThumbsRecognizer.classify(fixture("fist"))) }
  @Test fun pointing() { assertEquals(ThumbPose.NONE,ThumbsRecognizer.classify(fixture("pointing"))) }
  @Test fun unavailable() { assertEquals(ThumbPose.NONE,ThumbsRecognizer.classify(null)) }
  @Test fun nanRejected() { val h=fixture("up"); assertEquals(ThumbPose.NONE,ThumbsRecognizer.classify(h.copy(wrist=Point(Double.NaN,0.0,0.0)))) }
  @Test fun degenerateRejected() { val h=fixture("up"); assertEquals(ThumbPose.NONE,ThumbsRecognizer.classify(h.copy(thumb=h.thumb.copy(pip=h.thumb.base)))) }
  @Test fun mirroredHand() {
    val h=fixture("up"); fun m(p: Point)=Point(-p.x,p.y,p.z)
    fun f(x: Finger)=Finger(m(x.base),m(x.pip),m(x.dip),m(x.tip))
    assertEquals(ThumbPose.UP,ThumbsRecognizer.classify(HandJoints(m(h.wrist),f(h.thumb),h.fingers.map(::f))))
  }
  @Test fun worldHorizontalRejected() {
    val h=fixture("up"); fun m(p: Point)=Point(p.y,-p.x,p.z)
    fun f(x: Finger)=Finger(m(x.base),m(x.pip),m(x.dip),m(x.tip))
    assertEquals(ThumbPose.NONE,ThumbsRecognizer.classify(HandJoints(m(h.wrist),f(h.thumb),h.fingers.map(::f))))
  }
  @Test fun holdBoundaryAndLatch() {
    val g=PoseHoldGate(); assertFalse(g.update("a",0).fire); assertFalse(g.update("a",399).fire)
    assertTrue(g.update("a",400).fire); assertFalse(g.update("a",2000).fire)
    g.update(null,2001); assertFalse(g.update("a",2002).fire); assertTrue(g.update("a",2402).fire)
  }
  @Test fun cooldownAndTargetChange() {
    val g=PoseHoldGate(); g.update("a",0); assertTrue(g.update("a",400).fire);g.update(null,500)
    assertFalse(g.update("b",1800).holding);assertFalse(g.update("b",1900).fire)
    assertFalse(g.update("c",2200).fire);assertTrue(g.update("c",2600).fire)
  }
  @Test fun newTargetRequiresRelease() {
    val g=PoseHoldGate();g.reset();assertFalse(g.update("a",1000).fire);assertFalse(g.update("a",2000).holding)
    g.update(null,2001);g.update("a",2002);assertTrue(g.update("a",2402).fire)
  }
  @Test fun trackingLossCancelsHold() {
    val g=PoseHoldGate();g.update("a",0);g.update(null,399)
    assertFalse(g.update("a",400).fire);assertTrue(g.update("a",800).fire)
  }
  /** #1033: a thumbs-up arms only on a card awaiting a decision with an option selected. */
  @Test fun thumbsUpArmsOnlyWithASelectedOptionOnAnOpenCard() {
    assertTrue(ThumbCommit.armed(ThumbPose.UP,true,1,3))
    assertFalse(ThumbCommit.armed(ThumbPose.UP,true,-1,3))
    assertFalse(ThumbCommit.armed(ThumbPose.UP,true,3,3))
    assertFalse(ThumbCommit.armed(ThumbPose.UP,false,1,3))
    assertFalse(ThumbCommit.armed(ThumbPose.UP,true,0,0))
    assertTrue(ThumbCommit.armed(ThumbPose.DOWN,true,-1,3))
  }
  /** #1033: 0.4 s hold, opening the hand cancels, one commit per hold even if the hold lasts seconds. */
  @Test fun openHandCancelsAndOneCommitPerHold() {
    val g=PoseHoldGate()
    assertTrue(g.update("c/right/UP",0).holding);assertEquals(0.5f,g.progress(200),0.01f)
    g.update(null,300) // hand opened before 400 ms
    assertFalse(g.update("c/right/UP",400).fire);assertFalse(g.update("c/right/UP",799).fire)
    assertTrue(g.update("c/right/UP",800).fire)
    for(t in 850L..6000L step 50) assertFalse(g.update("c/right/UP",t).fire)
  }
}
