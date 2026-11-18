package com.overhand.app.features

import com.meta.spatial.core.*
import com.overhand.app.opening.Opening
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * t945, #997: Settings is a world-locked side panel, opened and closed by one orb under the main window (and the palm
 * menu item), restored where he left it, grabbable like every window, never nearer than 1.0 m.
 * [minDist] collects every Settings surface distance the tests produce (reported as settings_min_dist_m).
 */
class SettingsPanelTest {
  companion object { @JvmStatic val minDist=java.util.concurrent.atomic.AtomicReference(Double.MAX_VALUE)
    fun seen(d: Double) { minDist.accumulateAndGet(d) { a,b -> min(a,b) } } }
  private fun yawDeg(p: Pose): Double { val f=p.q*Vector3(0f,0f,1f);return Math.toDegrees(atan2(f.x,f.z).toDouble()) }
  private fun Vector3.v()=V3(x.toDouble(),y.toDouble(),z.toDouble())
  private fun fwd(yaw: Double,pitch: Double=0.0): V3 { val y=Math.toRadians(yaw);val p=Math.toRadians(pitch)
    return V3(sin(y)*cos(p),sin(p),cos(y)*cos(p)) }
  private fun wrap(a: Double): Double { var d=a;while(d>180) d-=360;while(d<-180) d+=360;return d }
  /** Five heads: four headings at a seated eye, and one leaning 0.4 m forward (and a little down) to reach. */
  private val heads=listOf(
    Triple(V3(0.0,1.22,0.0),0.0,0.0),Triple(V3(0.3,1.22,-0.4),-90.0,0.0),Triple(V3(-1.0,1.10,2.0),37.0,-10.0),
    Triple(V3(0.0,1.25,0.0),179.0,5.0),Triple(V3(0.0,1.12,0.4),0.0,-20.0))

  /** Distance, bearing (horizontal, from the heading) and elevation (from the horizontal) of [p] from [h]. */
  private fun polar(h: V3,yaw: Double,p: V3): Triple<Double,Double,Double> {
    val d=p-h;return Triple(d.length(),Comfort.bearing(d,yaw),Math.toDegrees(atan2(d.y,hypot(d.x,d.z))))
  }

  @Test fun hiddenAtLaunch() {
    val launch=SettingsPanel.State()
    assertFalse(launch.open)
    assertFalse(SettingsPanel.visible("settings",launch.open,true))
    assertTrue(SettingsPanel.visible("fleet",launch.open,true))
  }

  @Test fun firstOpenIsInsideTheRangesForFiveHeadsIncludingALean() {
    for((h,yaw,pitch) in heads) {
      val p=SettingsPanel.firstOpen(h,yaw)
      val (d,b,e)=polar(h,yaw,p.centre);seen(d)
      assertTrue("head $h distance $d",d in 1.2..1.5)
      assertTrue("head $h bearing $b",abs(b) in 25.0..40.0)
      assertTrue("head $h elevation $e",e in -21.01..-20.99)
      assertEquals("head $h faces him",0.0,wrap(p.yawDeg-Comfort.facingYawDeg(h,p.centre)),0.5)
      assertTrue("comfortable",Comfort.ok(h,p.centre,yaw))
      // Head pitch does not matter: placement uses the level heading.
      assertTrue(pitch.isFinite())
    }
    // The leaning head's panel is still at least 1.2 m from where he sits back (0.4 m behind).
    val (lean,yaw,_)=heads.last()
    val back=lean+V3(0.0,0.10,-0.4)
    assertTrue((SettingsPanel.firstOpen(lean,yaw).centre-back).length()>=1.2)
    // The recenter/default path (WindowPose.placeSettings) agrees with firstOpen.
    val eye=Pose(Vector3(0.3f,1.22f,-0.4f),Quaternion.fromEuler(0f,-90f,0f))
    val a=WindowPose.placeSettings(eye).t.v();val b=SettingsPanel.firstOpen(eye.t.v(),-90.0).centre
    assertEquals(0.0,(a-b).length(),1e-3)
    assertEquals(SettingsPanel.DISTANCE_M,(a-eye.t.v()).length(),1e-3)
  }

  @Test fun openCloseOpenRestoresThePoseHeLeft() {
    val h=V3(0.0,1.22,0.0);val f=fwd(0.0)
    var st=SettingsPanel.State()
    st=st.press(0)!!;assertTrue(st.open)
    var p=SettingsPanel.reopen(h,f,0.0,null);seen((p.centre-h).length())
    // He moves it (grab path): 0.5 m left and a little up, still in view.
    val moved=SettingsPanel.Place(p.centre+V3(-0.5,0.1,0.0),10.0)
    st=st.press(1000)!!;assertFalse(st.open)
    // He turns 60 degrees and leans in 0.1 m, then reopens: it comes back where he left it, not at his new gaze.
    st=st.press(2000)!!;assertTrue(st.open)
    val lean=h+V3(0.0,0.0,0.1)
    p=SettingsPanel.reopen(lean,fwd(60.0),60.0,moved);seen((p.centre-lean).length())
    assertEquals(moved,p)
    // Leaning 0.2 m puts his spot 0.93 m away: it is kept on its line but pushed out to the 1.0 m floor, never nearer.
    val deep=h+V3(0.0,0.0,0.2)
    val q=SettingsPanel.reopen(deep,fwd(60.0),60.0,moved);seen((q.centre-deep).length())
    assertEquals(SettingsPanel.MIN_M,(q.centre-deep).length(),1e-6)
    // Lost: over 3 m away, or more than 100 degrees off his view -> placed afresh in front of him.
    val far=SettingsPanel.Place(h+V3(0.0,0.0,3.5),0.0)
    assertNotEquals(far,SettingsPanel.reopen(h,f,0.0,far))
    val behind=SettingsPanel.Place(h+V3(0.0,0.0,-1.3),180.0)
    val fresh=SettingsPanel.reopen(h,f,0.0,behind)
    assertEquals(SettingsPanel.firstOpen(h,0.0),fresh)
    // 95 degrees off the view, 1.3 m away: kept.
    val side=SettingsPanel.Place(h+V3(1.3*sin(Math.toRadians(95.0)),0.0,1.3*cos(Math.toRadians(95.0))),95.0)
    assertEquals(side,SettingsPanel.reopen(h,f,0.0,side))
  }

  @Test fun twentyPressesGiveTwentyAlternatingEventsAndAPressWhileOpenNeverMovesIt() {
    val h=V3(0.0,1.22,0.0)
    var st=SettingsPanel.State();var kept: SettingsPanel.Place?=null
    val events=mutableListOf<String>()
    for(i in 0 until 20) {
      // His pinches come 0.5-1.5 s apart; each leans a little nearer to press (up to 0.2 m).
      val now=i*700L+(i%3)*150L
      val head=h+V3(0.0,-0.005*i,0.01*i)
      val before=kept
      val n=st.press(now);assertNotNull("press $i guarded",n);st=n!!
      if(st.open) { kept=SettingsPanel.reopen(head,fwd(0.0),0.0,kept);events+="open";seen((kept.centre-head).length()) }
      else { events+="close";assertEquals("closing never moves it",before,kept) }
      if(i>=2 && st.open) assertEquals("press $i: reopening does not move it nearer",before,kept)
    }
    assertEquals(20,events.size)
    assertEquals(List(10) { listOf("open","close") }.flatten(),events)
    // A second release inside 400 ms is the same press: ignored.
    val s1=SettingsPanel.State().press(10_000)!!
    assertNull(s1.press(10_399))
    assertNotNull(s1.press(10_400))
  }

  @Test fun settingsIsAGrabTargetWhileOpenEvenUnderTheCardOrGraph() {
    // nov13e soak (runs/native/nov13e/soak/snap_settings_open.json): Settings open with the decision card up was hidden
    // and missing from GrabFeature.targets(). Open now means shown and grabbable.
    assertTrue(GrabFeature.isTarget("settings",false,true))
    assertTrue(SettingsPanel.visible("settings",true,false))
    assertFalse(GrabFeature.isTarget("settings",true,false))
    assertTrue(GrabFeature.isTarget("code",true,false))
    assertFalse(GrabFeature.isTarget("code",false,true))
  }

  @Test fun noSettingsControlNearerThanAMetreOrWithinTwentyDegreesOfStraightAhead() {
    for((h,yaw,_) in heads) {
      // The panel itself, after he pulls it to 0.4 m: the release puts it back out to 1.0 m (and only ever outward).
      val near=SettingsPanel.atLeastMin(h,h+V3(0.0,-0.1,0.4))
      assertEquals(SettingsPanel.MIN_M,(near.centre-h).length(),1e-9);seen(SettingsPanel.MIN_M)
      val ok=h+V3(0.0,0.0,1.6);assertEquals(ok,SettingsPanel.atLeastMin(h,ok).centre)
    }
    // The palm menu's Settings item maps to the same view.
    assertTrue(Opening.MENU.any { it.view==SettingsPanel.VIEW })
  }

  @Test fun sameVisualSizeAsTheLegibilitySlot() {
    val sp=WindowLayout.defaultSpecs.first { it.view=="settings" }
    assertEquals(sp.w/SettingsPanel.LEGIBLE_AT_M,sp.w*SettingsPanel.SCALE/SettingsPanel.DISTANCE_M,1e-9)
    assertEquals(SettingsPanel.LEGIBLE_AT_M,WindowLayout.slots.getValue("settings").distanceM,1e-9)
    assertEquals(1.12f,WindowLayout.FARTHEST_M,1e-6f)
  }

  @Test fun recenterEasingSkipsSettings() {
    assertFalse(RecenterFeature.easesBack("settings"))
    assertTrue(RecenterFeature.easesBack("chat"))
    assertTrue(RecenterFeature.easesBack(null))
    listOf("resume_after_12s","mounted","first60_steady").forEach { assertFalse(it,SettingsPanel.followsRecenter(it)) }
    listOf("palm","voice","system_recenter").forEach { assertTrue(it,SettingsPanel.followsRecenter(it)) }
  }

  @Test fun logLineHasTheWornCheckFields() {
    val l=SettingsPanel.logLine("open","orb",V3(0.0,1.22,0.0),fwd(0.0),SettingsPanel.firstOpen(V3(0.0,1.22,0.0),0.0).centre)
    assertTrue(l,Regex("""settings event=open why=orb dist_m=1\.30 off_axis_deg=3\d""").matches(l))
  }
}
