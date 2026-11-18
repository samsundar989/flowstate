package com.overhand.app.onboarding

import com.overhand.app.hands.*
import org.junit.Assert.*
import org.junit.Test

class OnboardingFlowTest {
  private val up=mapOf("right" to ThumbPose.UP)
  @Test fun noTimerOrThumbCanSkipSelection() {
    val f=OnboardingFlow();f.frame(up,true,0);f.frame(up,true,60000)
    assertEquals(OnboardingFlow.Step.PINCH,f.step)
  }
  @Test fun selectionThenReleasedThumbHeldFourHundredMillisecondsCompletes() {
    val f=OnboardingFlow();f.select();f.frame(emptyMap(),true,0)
    assertFalse(f.frame(up,true,1));assertFalse(f.frame(up,true,400))
    assertTrue(f.frame(up,true,401));assertFalse(f.active)
    assertFalse(f.frame(up,true,900))
  }
  @Test fun alreadyHeldThumbRequiresReleaseAfterSelecting() {
    val f=OnboardingFlow();f.select();f.frame(up,true,0)
    assertFalse(f.frame(up,true,1000));assertTrue(f.active)
  }
  @Test fun trackingLossLookAwayAndHandSwitchResetHold() {
    val f=OnboardingFlow();f.select();f.frame(emptyMap(),true,0);f.frame(up,true,1)
    f.frame(up,false,400);assertFalse(f.frame(up,true,500))
    f.frame(emptyMap(),true,800);assertFalse(f.frame(up,true,801))
    assertFalse(f.frame(mapOf("left" to ThumbPose.UP),true,1201))
    assertTrue(f.frame(mapOf("left" to ThumbPose.UP),true,1601))
  }
  @Test fun downCannotConfirm() {
    val f=OnboardingFlow();f.select();f.frame(emptyMap(),true,0)
    f.frame(mapOf("right" to ThumbPose.DOWN),true,1)
    assertFalse(f.frame(mapOf("right" to ThumbPose.DOWN),true,999));assertTrue(f.active)
  }
  @Test fun skipSeenAndReplay() {
    val f=OnboardingFlow(true);assertFalse(f.active);f.replay();assertTrue(f.active)
    f.select();f.skip();assertFalse(f.active);f.replay();assertEquals(OnboardingFlow.Step.PINCH,f.step)
  }
  @Test fun pinchConfirmFinishesRecordedThumbStepOnly() {
    val f=OnboardingFlow()
    assertFalse("cannot confirm before choosing",f.confirmPractice(true));assertEquals(OnboardingFlow.Step.PINCH,f.step)
    f.select();assertTrue(f.confirmPractice(true));assertFalse(f.active)
    assertFalse("second confirm is not a second answer",f.confirmPractice(true))
  }
  @Test fun liveTutorialNeverConfirmsAnAnswer() {
    val f=OnboardingFlow();f.select()
    assertFalse(f.confirmPractice(false));assertEquals(OnboardingFlow.Step.THUMB,f.step)
  }
  @Test fun firstRunIsNotARehearsalButEveryReplayIs() {
    val f=OnboardingFlow();assertFalse(f.rehearsal);f.select();assertTrue(f.confirmPractice(true));assertFalse(f.rehearsal)
    f.replay();assertTrue(f.rehearsal);f.select();assertTrue(f.confirmPractice(true));assertTrue("replay completion must stay distinguishable",f.rehearsal)
  }
  @Test fun skipDuringAReplayFinishesWithoutSelectionCarriedIntoTheFlow() {
    val f=OnboardingFlow(true);f.replay();f.select();f.skip()
    assertFalse(f.active);assertFalse(f.confirmPractice(true))
  }
  @Test fun everyInstructionBlockIncludingControlsFitsTwentyFiveWords() {
    for(text in listOf(OnboardingFlow.PINCH,OnboardingFlow.THUMB))
      assertTrue(("1 / 2 $text Skip").split(Regex("\\s+")).size<=25)
  }
  @Test fun syntheticJointsPassRecognizerThenTutorialGate() {
    fun p(x: Double,y: Double,z: Double=0.0)=Point(x,y,z)
    val fingers=(0..3).map { i -> val x=-0.03+i*0.02
      Finger(p(x,0.0),p(x,0.03),p(x,0.02,0.02),p(x,0.0,0.02)) }
    val h=HandJoints(p(0.0,-0.06),Finger(p(-0.055,-0.02),p(-0.055,0.01),p(-0.055,0.04),p(-0.055,0.07)),fingers)
    val pose=ThumbsRecognizer.classify(h);assertEquals(ThumbPose.UP,pose)
    val f=OnboardingFlow();f.select();f.frame(emptyMap(),true,0)
    f.frame(mapOf("right" to pose),true,100);assertTrue(f.frame(mapOf("right" to pose),true,500))
  }
}
