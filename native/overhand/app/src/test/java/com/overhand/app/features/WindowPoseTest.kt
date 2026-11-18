package com.overhand.app.features

import com.meta.spatial.core.*
import com.overhand.app.mr.DeskPlacement
import com.overhand.app.mr.DeskPlacement.Point
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class WindowPoseTest {
  private val up=Vector3(0f,1f,0f)
  private fun verify(frame: DeskPlacement.Frame?) {
    for(mode in listOf("recorded","live")) for(yaw in listOf(-175f,-90f,0f,70f,179f)) {
      val eye=Point(0f,1.22f,0f)
      val origin=Pose(Vector3(0f,1.22f,0f),Quaternion.fromEuler(0f,yaw,0f))
      val panels=WindowLayout.defaultSpecs.map { it to WindowPose.place(it,origin,frame,eye) }
      panels.forEach { (sp,p) -> assertTrue("$mode ${sp.view} up",(p.q*up).dot(up)>=cos(Math.toRadians(10.0))) }
      val stations=(0 until 7).map { i ->
        val pose=if(frame!=null) frame.station(i,7,eye).pose() else {
          val a=com.overhand.app.stations.StationRules.arc(i,7)
          Pose(Vector3(a[0],a[1],a[2]),Quaternion.fromEuler(0f,a[3],0f))
        }
        Spec(-i-1,"Station $i","station$i",0f,0f,0f,0.6f,0.22f) to pose
      }
      val visible=panels+stations
      visible.forEach { (_,p) -> assertTrue((p.q*up).dot(up)>0.9848f) }
      for(i in visible.indices) for(j in i+1 until visible.size) {
        val (a,ap)=visible[i];val (b,bp)=visible[j]
        assertFalse("$mode $yaw ${a.view}/${b.view}",WindowPose.intersects(ap,a.w,a.h,bp,b.w,b.h))
      }
    }
  }
  @Test fun floatingBothModes()=verify(null)
  // Actual selected world-space surfaces from the official MRUK JSON rooms (#331).
  @Test fun livingroomDeskBothModes()=verify(DeskPlacement.Frame(
    Point(1.1420245f,0.46172637f,-1.3859694f),Point(0.5799423f,0.46172637f,-1.1663184f),
    Point(0.93140817f,0f,-0.36397648f),1.2204562f,true))
  @Test fun bedroomWallBothModes()=verify(DeskPlacement.Frame(
    Point(-1.598639f,1.5f,-0.30983406f),Point(-1.598639f,1.5f,-0.30983406f),
    Point(-0.99224204f,0f,0.12432135f),1.220537f,false))
  @Test fun inheritedStationRollReproducesInversionAndIsRemoved() {
    val old=Pose(Vector3(0f,1.2f,0f),Quaternion.fromEuler(0f,35f,180f))
    assertTrue("legacy station transform inverts up",(old.q*up).dot(up)<-0.99f)
    assertTrue((WindowPose.upright(old).q*up).dot(up)>0.999f)
  }
  @Test fun v4UpsideDownViewerIsDistinctFromAnInvertedPanel() {
    // Raw #342 graph_metrics.json. The camera itself was nearly inverted.
    val viewer=Quaternion(0.06684033f,0.0047944067f,-0.07555411f,0.99488735f)
    assertTrue((viewer*up).dot(up)<-0.97f)
    WindowLayout.defaultSpecs.forEach { sp ->
      val pose=WindowPose.place(sp,Pose(Vector3(0f),viewer),null,Point(0f,1.22f,0f))
      assertTrue("${sp.view} stays world upright",(pose.q*up).dot(up)>0.999f)
    }
  }
  @Test fun mrWindowMustNotReceiveStationLocalizationTwice() {
    val eye=Point(0f,0f,0f)
    val frame=DeskPlacement.Frame(Point(-1.598639f,1.5f,-0.30983406f),
      Point(-1.598639f,1.5f,-0.30983406f),Point(-0.99224204f,0f,0.12432135f),0f,false)
    val sp=WindowLayout.defaultSpecs.first()
    val anchored=WindowPose.place(sp,Pose(),frame,eye)
    val delta=Pose(Vector3(0.5f,-1.25f,-2f),Quaternion.fromEuler(0f,40f,0f))
    val old=delta*anchored
    assertTrue("legacy double transform drops Fleet onto station labels",old.t.y<0f)
    val actual=WindowPose.follow(sp,anchored,delta,Pose(),frame,eye,false)
    assertEquals(anchored.t.x,actual.t.x,0.001f)
    assertEquals(anchored.t.y,actual.t.y,0.001f)
    assertEquals(anchored.t.z,actual.t.z,0.001f)
    val station=Pose(Vector3(-1.2260723f,-0.4f,-2.2521334f),Quaternion(0.24669531f,0f,-0.9690931f,0f))
    val observed=Pose(Vector3(-1.049871f,-0.55000037f,-2.438461f),Quaternion(0.48893932f,0f,-0.8723178f,0f))
    assertTrue("device reproduction",WindowPose.intersects(observed,1.2f,0.75f,station,0.6f,0.22f))
    assertFalse("world-space MR pose clears station",WindowPose.intersects(actual,1.2f,0.75f,station,0.6f,0.22f))
    val edited=WindowPose.follow(sp,anchored,delta,Pose(),frame,eye,true)
    assertEquals(old.t.y,edited.t.y,0.001f)
  }
  @Test fun separatingAxisDetectsCrossedAndCoplanarPanels() {
    assertTrue(WindowPose.intersects(Pose(),1f,1f,Pose(Vector3(0.2f,0f,0f)),1f,1f))
    assertTrue(WindowPose.intersects(Pose(),1f,1f,Pose(Vector3(0f),Quaternion.fromEuler(0f,90f,0f)),1f,1f))
    assertFalse(WindowPose.intersects(Pose(),1f,1f,Pose(Vector3(0f,1.1f,0f)),1f,1f))
    assertFalse(WindowPose.intersects(Pose(),1f,1f,Pose(Vector3(0f,0f,0.1f)),1f,1f))
  }
}
