package com.overhand.app.features

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Fix G (q4 wear: 9 `grab event=miss panel=none why=not_bar`, no dock bar): the dock is a GrabFeature target with the
 *  same SDK bar as every panel; the bar carries it and a release saves a place the next put-on keeps. */
class DockGrabTest {
  private fun src(name: String)=File("src/main/java/com/overhand/app/features/$name").readText()
  private fun flat(name: String)=src(name).replace(Regex("\\s+"),"")

  @Test fun dockIsAGrabTargetWhileShownWithItsOwnSdkBar() {
    val grab=flat("GrabFeature.kt");val dock=flat("ControlDockFeature.kt")
    // targets(): the dock entry, present only while the dock is placed and visible.
    assertTrue(grab.contains("dockFeature?.grabEntity?.let{m[DOCK]=it}"))
    assertTrue(dock.contains("valgrabEntity:Entity?get()=entity?.takeIf{dock.place!=null&&!putOnPending&&!recenterPending&&it.tryGetComponent<Visible>()?.isVisible==true}"))
    // The bar path is the panels' own (drawBars -> place -> GrabFeature_place; IsdkGrabbable on the box).
    assertTrue(grab.contains("place(b.bar,Pose(at.f(),"))
    assertTrue(grab.contains("e.windowPose(it,\"GrabFeature_place\")"))
    assertTrue(grab.contains("valtarget=box((PanelGrab.BAR_W/2).toFloat(),(PanelGrab.BAR_H/2).toFloat(),Tone.IDLE).also{"))
    assertTrue(grab.contains("if(id==DOCK)it.setComponent(Box(Vector3(-(PanelGrab.BAR_W/2).toFloat(),-(barHitH(id)/2).toFloat(),-0.006f)"))
    assertTrue(grab.contains("it.setComponent(IsdkGrabbable())"))
    // The SDK carry and release go through the dock's own state machine (logged writer, saved pref).
    assertTrue(grab.contains("if(id==DOCK)dockFeature?.barMove(c)"))
    assertTrue(grab.contains("if(id==DOCK)dockFeature?.barRelease()elses.recenter.onRelease(e)"))
    assertTrue(dock.contains("s.prefs.edit().putString(ControlDock.PREF,ControlDock.encode(dock.place!!)).apply()"))
    // The dead strip (a white box that looked like a handle and grabbed nothing) and the dock's own grab loop are gone.
    assertFalse(dock.contains("mesh://box"));assertFalse(dock.contains("moveBar"));assertFalse(dock.contains("PanelGrab()"))
    assertFalse(dock.contains("sampleHands"))
  }

  @Test fun barHitAreaIsAtLeast64DpAndNotCoveredByTheDock() {
    val dpM=ControlDock.WIDTH_M.toDouble()/ControlDock.DP
    val hitH=GrabFeature.barHitH(GrabFeature.DOCK)
    assertTrue("hit ${hitH/dpM} dp",hitH/dpM>=64.0-1e-6)
    assertTrue(PanelGrab.BAR_W/dpM>=64.0)
    val size=GrabBarLook.size(ControlDock.WIDTH_M.toDouble(),ControlDock.DP.toDouble())
    assertEquals(160*dpM,size.w,1e-9);assertEquals(8*dpM,size.h,1e-9)    // K7 pill, in the dock's own dp
    val drop=GrabFeature.barDrop(GrabFeature.DOCK,ControlDock.HEIGHT_M.toDouble(),size)
    val top=drop+hitH/2;val bottom=-ControlDock.HEIGHT_M/2.0
    assertTrue("hit top $top under dock bottom $bottom",top<bottom)
    // Other panels keep the K7 placement and lane-C hit size.
    assertEquals(PanelGrab.BAR_H,GrabFeature.barHitH("chat"),0.0)
    assertEquals(-0.5-size.gap-size.h/2,GrabFeature.barDrop("chat",1.0,size),1e-12)
  }

  /** A JVM replay of an SDK bar grab of the dock: the native rows GrabFeature writes, then the dock's writer rows. */
  @Test fun releaseSavesAPlaceTheNextPutOnKeeps() {
    val head=V3(0.0,1.6,0.0);val yaw=0.0
    val rows=mutableListOf<String>()
    val dock=ControlDock { p,why -> rows+="dock event=pose_set why=$why grabbed=${why=="grab"} pose=${ControlDock.encode(p)}" }
    dock.putOn(head,yaw,null)
    val start=dock.place!!
    val panel=PanelGrab.Panel(GrabFeature.DOCK,start.centre,start.yawDeg,ControlDock.WIDTH_M.toDouble(),ControlDock.HEIGHT_M.toDouble(),resizable=false)
    val origin=panel.centre+V3(0.0,ControlDock.BAR_OFFSET_M,0.0)
    rows+="window_write why=GrabFeature_place panel=${GrabFeature.DOCK}"
    rows+=GrabFeature.nativeRow(GrabFeature.DOCK,true)
    // He carries the bar 30 cm toward the middle and 10 cm up over 20 frames (ControlDockFeature.barMove).
    for(i in 1..20) {
      val c=PanelGrab.carried(panel,origin,origin+V3(0.015*i,0.005*i,0.0))
      dock.grab(SettingsPanel.Place(c,Comfort.facingYawDeg(head,c)))
    }
    rows+=GrabFeature.nativeRow(GrabFeature.DOCK,false)
    dock.grabEnd(head,yaw,dock.place!!)           // ControlDockFeature.barRelease
    val saved=ControlDock.decode(ControlDock.encode(dock.place!!))!!
    assertTrue((saved.centre-start.centre).length()>0.25)
    assertTrue(rows.any { it.startsWith("grab event=start panel=dock ") && it.endsWith("mode=isdk") })
    assertTrue(rows.any { it.startsWith("grab event=end panel=dock ") })
    assertTrue(rows.any { it.startsWith("dock event=pose_set why=grab_end ") })
    // The next put-on passes check 1 and keeps it.
    val again=ControlDock { _,_ -> };again.putOn(head,yaw,saved)
    assertEquals(saved,again.place)
    assertTrue(ControlDock.validSaved(head,yaw,saved))
    val out=File(System.getenv("DOCK_OUT") ?: "build/dock").apply { mkdirs() }
    File(out,"grab_rows.json").writeText(JSONObject().put("rows",JSONArray(rows.filter { !it.contains("why=grab ") }+
      rows.filter { it.contains("why=grab ") }.let { listOf(it.first(),"... ${it.size} grab rows ...",it.last()) })).toString(2))
  }
}
