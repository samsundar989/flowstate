package com.overhand.app.hands

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** A recorded frame replays through the recognizer with the same verdict, and every NONE names its failing check. */
class HandFrameLogTest {
  private fun up(): HandJoints {
    fun p(x: Double,y: Double,z: Double=0.0)=Point(x,y,z)
    val fingers=(0..3).map { i -> val x=-0.03+i*0.02;Finger(p(x,0.0),p(x,0.03),p(x,0.02,0.02),p(x,0.0,0.02)) }
    return HandJoints(p(0.0,-0.06),Finger(p(-0.055,-0.02),p(-0.055,0.01),p(-0.055,0.04),p(-0.055,0.07)),fingers)
  }
  @Test fun recordRoundTripsThroughTheRecognizer() {
    val h=up()
    val line=HandFrameLog.record(5L,mapOf("right" to (h to "up"),"left" to (null to "no_joints")),true,0.93,1,"visible=true")
    val back=HandFrameLog.parse(line)
    assertNull(back["left"])
    assertEquals(ThumbPose.UP,ThumbsRecognizer.classify(back["right"]))
    val o=JSONObject(line)
    assertEquals(21,o.getJSONObject("hands").getJSONObject("right").getJSONArray("joints").length())
    assertTrue(o.getBoolean("aimed"));assertEquals(1,o.getInt("tracked"))
  }
  @Test fun evaluateNamesTheFailingCheck() {
    assertEquals(ThumbPose.UP to "up",ThumbsRecognizer.evaluate(up()))
    assertEquals("no_joints",ThumbsRecognizer.evaluate(null).second)
    val h=up()
    val bent=h.copy(thumb=h.thumb.copy(tip=Point(-0.035,0.04,0.0)))
    assertEquals(ThumbPose.NONE to "thumb_bent",ThumbsRecognizer.evaluate(bent))
    val open=h.copy(fingers=h.fingers.map { f -> Finger(f.base,Point(f.base.x,0.03,0.0),Point(f.base.x,0.05,0.0),Point(f.base.x,0.07,0.0)) })
    assertEquals(ThumbPose.NONE to "fingers_open",ThumbsRecognizer.evaluate(open))
  }
  @Test fun holdRingProgressFillsOverFourHundredMilliseconds() {
    val g=PoseHoldGate()
    g.update(null,0)
    assertEquals(0f,g.progress(0),0f)
    g.update("c/right/UP",10_000);assertEquals(0f,g.progress(10_000),0.001f)
    g.update("c/right/UP",10_200);assertEquals(0.5f,g.progress(10_200),0.001f)
    assertTrue(g.update("c/right/UP",10_400).fire)
    assertEquals(0f,g.progress(10_450),0f)
  }
}
