package com.overhand.app.decisions

import org.junit.Assert.*
import org.junit.Test

class ConfirmGuardTest {
  @Test fun secondConfirmWhileSavingIsRefusedForAnyCard() {
    val g=ConfirmGuard()
    assertTrue(g.begin("a",0))
    assertFalse(g.begin("a",1));assertFalse(g.begin("b",1))
  }
  @Test fun committedDecisionCannotBeAnsweredAgainEvenAfterTheQuietWindow() {
    val g=ConfirmGuard();g.begin("a",0);g.settle("a",true,10)
    assertFalse(g.begin("a",10_000))
  }
  @Test fun quickRepeatCannotAnswerTheNextCard() {
    val g=ConfirmGuard();g.begin("a",0);g.settle("a",true,100)
    g.acknowledge()
    assertFalse(g.begin("b",100+ConfirmGuard.QUIET_MS-1))
    assertTrue(g.begin("b",100+ConfirmGuard.QUIET_MS))
  }
  @Test fun nextCannotFireInTheConfirmingGesture() {
    val g=ConfirmGuard();assertTrue(g.canAdvance(0))
    g.begin("a",0);assertFalse("still saving",g.canAdvance(1))
    g.settle("a",true,100)
    assertFalse(g.canAdvance(100+ConfirmGuard.QUIET_MS-1))
    assertTrue(g.canAdvance(100+ConfirmGuard.QUIET_MS))
  }
  @Test fun heldThumbThroughOneLostFrameStaysDisarmed() {
    val g=ConfirmGuard();g.begin("a",0);g.settle("a",true,10)
    assertFalse(g.poseFrame(true,true,20))
    assertFalse("one classifier gap",g.poseFrame(false,true,30))
    assertFalse(g.poseFrame(true,true,40))
    assertFalse("tracking loss is not a release",g.poseFrame(false,false,1000))
    assertFalse(g.poseFrame(true,true,2000))
  }
  @Test fun sustainedTrackedReleaseRearmsPoses() {
    val g=ConfirmGuard();g.begin("a",0);g.settle("a",true,10)
    assertFalse(g.poseFrame(false,true,100))
    assertFalse(g.poseFrame(false,true,100+ConfirmGuard.RELEASE_MS-1))
    assertTrue(g.poseFrame(false,true,100+ConfirmGuard.RELEASE_MS))
    assertTrue(g.poseFrame(true,true,1000))
  }
  @Test fun noCommitNeverBlocksPoses() { assertTrue(ConfirmGuard().poseFrame(true,true,0)) }
  @Test fun failedSaveDoesNotRequireARelease() {
    val g=ConfirmGuard();g.begin("a",0);g.settle("a",false,1);assertTrue(g.poseFrame(true,true,2))
  }
  @Test fun failedSaveReleasesTheSameDecisionImmediately() {
    val g=ConfirmGuard();g.begin("a",0);g.settle("a",false,10)
    assertTrue(g.begin("a",11))
  }
  @Test fun aLateSettleForAnotherDecisionChangesNothing() {
    val g=ConfirmGuard();g.begin("a",0);g.settle("b",true,10)
    assertFalse(g.begin("b",10_000))
    g.settle("a",true,10_000);g.acknowledge()
    assertTrue("a reused id may be answered once acknowledged",g.begin("a",20_000))
  }
}
