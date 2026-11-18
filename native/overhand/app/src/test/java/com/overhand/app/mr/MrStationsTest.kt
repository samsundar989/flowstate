package com.overhand.app.mr

import com.meta.spatial.core.*
import org.junit.Assert.*
import org.junit.Test

class MrStationsTest {
  @Test fun localizationTranslationPreservesGrabOffset() {
    val result=MrStations.follow(Pose(Vector3(0f,1f,2f)),Pose(Vector3(1f,1f,2f)),Pose(Vector3(0.2f,1.1f,2f)))
    assertEquals(1.2f,result.t.x,0.001f)
    assertEquals(1.1f,result.t.y,0.001f)
    assertEquals(2f,result.t.z,0.001f)
  }
  @Test fun localizationRotationPreservesGrabOffsetAndOrientation() {
    val result=MrStations.follow(Pose(Vector3(0f,1f,2f)),
      Pose(Vector3(1f,1f,2f),Quaternion.fromEuler(0f,90f,0f)),
      Pose(Vector3(0.2f,1.1f,2f),Quaternion.fromEuler(0f,15f,0f)))
    assertEquals(1f,result.t.x,0.001f)
    assertEquals(1.1f,result.t.y,0.001f)
    assertEquals(1.8f,result.t.z,0.001f)
    val expected=Quaternion.fromEuler(0f,105f,0f)
    assertEquals(expected.w,result.q.w,0.001f)
    assertEquals(expected.y,result.q.y,0.001f)
  }
}
