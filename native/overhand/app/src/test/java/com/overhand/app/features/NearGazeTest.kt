package com.overhand.app.features

import com.meta.spatial.core.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class NearGazeTest {
  @Test fun nearPanelAndCardDistancesAcrossEyeHeightsAndHeadRotations() {
    for(y in listOf(0f,1.2f,1.8f)) for(yaw in listOf(-179f,-45f,0f,90f,179f)) for(pitch in listOf(-30f,0f,30f)) {
      val eye=Pose(Vector3(2f,y,-3f),Quaternion.fromEuler(pitch,yaw,0f))
      val p=NearTouch.pose(eye)
      val delta=p.t-eye.t
      val distance=sqrt(delta.dot(delta).toDouble())
      assertTrue(distance in 0.42..0.46)
      assertEquals(0.44,distance,0.0001)
      assertEquals(0.055,(eye.t.y-p.t.y).toDouble(),0.0001)
      assertEquals(1f,(p.q*Vector3(0f,1f,0f)).y,0.0001f)
    }
  }
  @Test fun sizesKeepAspectAndConservativeCaptionInkAboveOneDegree() {
    assertEquals(1280f/800f,NearTouch.WIDTH/NearTouch.HEIGHT,0.001f)
    assertEquals(1000f/720f,NearTouch.CARD_WIDTH/NearTouch.CARD_HEIGHT,0.001f)
    // Device Hx measurements: Fleet >=0.65 em; card >=0.50 em in logical display units.
    for(ink in listOf(22*0.65*NearTouch.WIDTH/1280,22*0.50*NearTouch.CARD_WIDTH/1000)) {
      assertTrue(Math.toDegrees(2*atan(ink/(2*0.44)))>=1.0)
    }
  }
  @Test fun focusAt300msWithoutRepeatedActivation() {
    val gaze=HeadGaze()
    assertNull(gaze.update(true,1,1000))
    assertNull(gaze.update(true,1,1299))
    assertEquals(1,gaze.update(true,1,1300))
    assertNull(gaze.update(true,1,5000))
    assertEquals(1,gaze.target)
    assertEquals(300L,gaze.focusedAfterMs)
  }
  @Test fun optionalAndLossOrSwitchResetsDwell() {
    val gaze=HeadGaze()
    assertNull(gaze.update(false,1,0))
    assertNull(gaze.update(false,1,10000))
    assertEquals(0,gaze.target)
    assertNull(gaze.update(true,1,10001))
    assertNull(gaze.update(true,2,10300))
    assertNull(gaze.update(true,0,10599))
    assertNull(gaze.update(true,2,10600))
    assertEquals(2,gaze.update(true,2,10900))
    gaze.update(false,2,10901)
    assertEquals(0,gaze.target)
  }
  @Test fun gazeAloneNeverEmitsDecisionInput() {
    // The production state machine exposes panel IDs only, no click/selection callbacks.
    val gaze=HeadGaze()
    val wiring=java.io.File("src/main/java/com/overhand/app/features/WindowsFeature.kt").readText()
      .substringAfter("gaze.update(enabled,gazeHit,tick)?.let").substringBefore("val nextHighlight")
    assertTrue(wiring.contains("d.activate(id)"))
    for(forbidden in listOf("performClick", "decisions.", "dispatchTouchEvent", "debugPose", "post(d,"))
      assertFalse("Gaze wiring must not generate decision input: $forbidden",wiring.contains(forbidden))
    for(t in 0L..10000L step 50) gaze.update(true,123,t)
    assertEquals(123,gaze.target)

  }
  @Test fun resizedQuadMustNotRetainOldRayBounds() {
    val eye=Pose()
    val panel=Pose(Vector3(0.5f,0f,0.44f))
    assertNotNull(HeadGaze.hit(eye,panel,1.2f,0.75f))
    assertNull(HeadGaze.hit(eye,panel,NearTouch.WIDTH,NearTouch.HEIGHT))
  }
  @Test fun headRayUsesBoundsAndRejectsBehindAndRearFaces() {
    val eye=Pose()
    val panel=Pose(Vector3(0f,0f,0.44f))
    assertEquals(0.44f,HeadGaze.hit(eye,panel,0.72f,0.45f)!!,0.0001f)
    assertNull(HeadGaze.hit(eye,Pose(Vector3(1f,0f,0.44f)),0.72f,0.45f))
    assertNull(HeadGaze.hit(eye,Pose(Vector3(0f,0f,-0.44f)),0.72f,0.45f))
    assertNull(HeadGaze.hit(eye,Pose(Vector3(0f,0f,0.44f),Quaternion.fromEuler(0f,180f,0f)),0.72f,0.45f))
    assertNotNull(HeadGaze.hit(eye,NearTouch.pose(eye),NearTouch.WIDTH,NearTouch.HEIGHT))
  }
}
