package com.overhand.app.mr

import com.overhand.app.mr.DeskPlacement.Point
import org.junit.Assert.*
import org.junit.Test

class DeskPlacementTest {
  private val eye=Point(0f,1.25f,-0.6f)
  private fun corners()=listOf(Point(-0.8f,0.75f,0f),Point(0.8f,0.75f,0f),
    Point(0.8f,0.75f,0.8f),Point(-0.8f,0.75f,0.8f))
  @Test fun farEdgeAndSeatedEyeHeight() {
    val f=DeskPlacement.desk(corners(),eye)!!
    assertEquals(0.8f,f.far.z,0.001f)
    assertEquals(0f,f.near.z,0.001f)
    assertTrue(f.window(0f,0f,1.1f,eye).position.y-0.375f>f.card(eye).position.y+0.36f)
  }
  @Test fun sixWindowsFaceUserAndClearDesk() {
    val f=DeskPlacement.desk(corners(),eye)!!
    val positions=listOf(Triple(0f,0f,1.1f),Triple(-1.35f,0f,0.9f),Triple(1.35f,0f,0.9f),
      Triple(0f,-0.85f,1.3f),Triple(1.25f,-0.75f,1.1f),Triple(0f,0.6f,1.45f))
    positions.forEach { (x,y,z) ->
      val p=f.window(x,y,z,eye)
      assertTrue(p.position.y-0.375f>0.75f)
      assertEquals(Math.toDegrees(kotlin.math.atan2(p.position.x-eye.x,p.position.z-eye.z).toDouble()).toFloat(),p.yaw,0.001f)
    }
  }
  @Test fun cardAboveDeskAndNearUser() {
    val f=DeskPlacement.desk(corners(),eye)!!; val p=f.card(eye).position
    assertEquals(1.15f,p.y,0.001f)
    assertEquals(0.18f,p.z,0.001f)
    assertTrue(p.distance(eye)<f.window(0f,0f,1.1f,eye).position.distance(eye))
  }
  @Test fun anchoredWindowsDoNotCollapseIntoOverlappingRows() {
    val f=DeskPlacement.desk(corners(),eye)!!
    val center=f.window(0f,0f,1.1f,eye).position
    val yard=f.window(1.386f,0f,-0.8f,eye).position
    val settings=f.window(0f,0.6f,1.45f,eye).position
    assertTrue(center.distance(yard)>1.2f)
    assertTrue(settings.y-center.y>=0.8f)
  }
  @Test fun rotationAndTranslationAreEquivariant() {
    fun move(p: Point)=Point(p.z+2f,p.y,-p.x+1f)
    val a=DeskPlacement.desk(corners(),eye)!!
    val b=DeskPlacement.desk(corners().map(::move),move(eye))!!
    val expected=move(a.window(1.35f,0f,0.9f,eye).position)
    val actual=b.window(1.35f,0f,0.9f,move(eye)).position
    assertEquals(expected.x,actual.x,0.001f);assertEquals(expected.z,actual.z,0.001f)
  }
  @Test fun oppositeSeatedSideReversesEdges() {
    val f=DeskPlacement.desk(corners(),Point(0f,1.3f,1.4f))!!
    assertEquals(0f,f.far.z,0.001f);assertEquals(-1f,f.forward.z,0.001f)
  }
  @Test fun invalidBoundsRejected() {
    assertNull(DeskPlacement.desk(emptyList(),eye))
    assertNull(DeskPlacement.desk(List(4){Point(0f,0.75f,0f)},eye))
    assertNull(DeskPlacement.desk(corners().map { Point(it.x,Float.NaN,it.z) },eye))
    assertNull(DeskPlacement.desk(corners().map { Point(it.x,2f,it.z) },eye))
    assertNull(DeskPlacement.desk(corners(),Point(10f,1.3f,10f)))
  }
  @Test fun wallNormalAlwaysFacesEye() {
    val a=DeskPlacement.wall(Point(0f,1f,2f),Point(0f,0f,1f),eye)!!
    val b=DeskPlacement.wall(Point(0f,1f,2f),Point(0f,0f,-1f),eye)!!
    assertEquals(a.far,b.far);assertEquals(a.forward.z,b.forward.z,0.001f)
    assertEquals(1.94f,a.far.z,0.001f)
    assertTrue(a.card(eye).position.distance(eye)<1f)
  }
  @Test fun ceilingIsNotWall() { assertNull(DeskPlacement.wall(Point(0f,2f,0f),Point(0f,1f,0f),eye)) }
  @Test fun noRoomFallsBack() { assertTrue(DeskPlacement.fallback(true,true,false,null)) }
  @Test fun permissionDeniedFallsBackEvenWithSurface() {
    assertTrue(DeskPlacement.fallback(true,false,false,DeskPlacement.desk(corners(),eye)))
  }
  @Test fun timeoutAndToggleOffFallBack() {
    val f=DeskPlacement.desk(corners(),eye)
    assertTrue(DeskPlacement.fallback(true,true,true,f)); assertTrue(DeskPlacement.fallback(false,true,false,f))
  }
  @Test fun validSceneEnablesMixedReality() {
    assertFalse(DeskPlacement.fallback(true,true,false,DeskPlacement.desk(corners(),eye)))
  }
  @Test fun stationArcFollowsTwoDifferentDesks() {
    val first=DeskPlacement.desk(corners(),eye)!!
    val second=DeskPlacement.desk(corners().map { it+Point(0.4f,0.2f,0.3f) },eye)!!
    val a=first.station(3,7,eye).position
    val b=second.station(3,7,eye).position
    assertEquals(first.far.y+0.15f,a.y,0.001f)
    assertTrue(a.distance(b)>0.3f)
    assertEquals(second.far.z,b.z,0.001f)
    assertTrue(second.card(eye).position.y>first.card(eye).position.y)
    val arc=(0..6).map { first.station(it,7,eye).position }
    arc.zipWithNext().forEach { (left,right) -> assertTrue(left.distance(right)>0.6f) }
  }
}
