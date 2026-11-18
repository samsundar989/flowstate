package com.overhand.app.features

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Fix G (q4 wear, Nov 17 16:02): the dock is in view at the left every time the headset goes on, after a Recenter,
 *  and after a press on its own centre button, whatever place was saved. */
class DockPlaceTest {
  /** q4 log, pid 17676: `recenter event=first_place via=settled head=-0.02,0.97,0.00/yaw-1` then
   *  `dock event=pose_set why=restore grabbed=false pose=-0.8458,0.6603,-0.8480,-125.82`. */
  private val q4Head=V3(-0.02,0.97,0.0)
  private val q4Yaw=-1.0
  private val q4Saved=ControlDock.decode("-0.8458,0.6603,-0.8480,-125.82")!!

  private fun forward(yaw: Double)=Math.toRadians(yaw).let { V3(Math.sin(it),0.0,Math.cos(it)) }
  private fun offAxis(head: V3,yaw: Double,p: SettingsPanel.Place)=SettingsPanel.offAxisDeg(head,forward(yaw),p.centre)
  private fun facing(head: V3,c: V3)=SettingsPanel.Place(c,Comfort.facingYawDeg(head,c))

  /** The six saved cases of the card, relative to the head (except q4, the absolute pose he saw). */
  private fun cases(head: V3,yaw: Double): List<Pair<String,SettingsPanel.Place?>> = listOf(
    "none" to null,
    "in_view" to facing(head,head+SettingsPanel.offset(yaw-20,-10.0,1.0)),
    "q4" to q4Saved,
    "behind" to facing(head,head+SettingsPanel.offset(yaw+180,-10.0,1.0)),
    "too_far" to facing(head,head+SettingsPanel.offset(yaw-10,0.0,2.0)),
    "too_low" to facing(head,head+forward(yaw)*0.8+V3(0.0,-0.7,0.0)))

  @Test fun q4SavedPlaceIsRejectedAndLogged() {
    assertFalse(ControlDock.validSaved(q4Head,q4Yaw,q4Saved))
    val bearing=Comfort.bearing(q4Saved.centre-q4Head,q4Yaw)
    assertTrue("q4 bearing $bearing",Math.abs(bearing)>120)
    val rows=mutableListOf<String>()
    val dock=ControlDock { p,why -> rows+="dock event=pose_set why=$why grabbed=${why=="grab"} pose=${ControlDock.encode(p)}" }
    dock.putOn(q4Head,q4Yaw,q4Saved)
    assertTrue(rows.single(),rows.single().startsWith("dock event=pose_set why=saved_out_of_view "))
    assertEquals(ControlDock.default(q4Head,q4Yaw),dock.place)
    assertTrue(offAxis(q4Head,q4Yaw,dock.place!!)<45)
    // The same place after a Recenter.
    dock.systemRecenter(q4Head,q4Yaw,q4Saved)
    assertTrue(rows.last().startsWith("dock event=pose_set why=saved_out_of_view "))
    File(out(),"q4.json").writeText(JSONObject().put("before",ControlDock.encode(q4Saved)).put("before_bearing_deg",bearing)
      .put("before_off_axis_deg",offAxis(q4Head,q4Yaw,q4Saved)).put("after",ControlDock.encode(dock.place!!))
      .put("after_bearing_deg",Comfort.bearing(dock.place!!.centre-q4Head,q4Yaw)).put("after_off_axis_deg",offAxis(q4Head,q4Yaw,dock.place!!))
      .put("rows",JSONArray(rows)).toString(2))
  }

  /** 3b, q4 wear 17:01:11.935: the centre press with the q4 head and the q4 saved place ends with the dock in view. */
  @Test fun centrePressWithQ4PoseBringsTheDockIntoView() {
    val rows=mutableListOf<String>()
    val dock=ControlDock { p,why -> rows+="dock event=pose_set why=$why grabbed=false pose=${ControlDock.encode(p)}" }
    // Worst case: the q4 place is where it is (as q4 restored it), then he presses centre.
    dock.grab(q4Saved);dock.grabEnd(q4Head,q4Yaw,q4Saved)
    dock.systemRecenter(q4Head,q4Yaw)   // ControlDockFeature.center(): the default, never the saved place
    assertTrue(rows.last().startsWith("dock event=pose_set why=system_recenter "))
    assertEquals(ControlDock.default(q4Head,q4Yaw),dock.place)
    assertTrue(offAxis(q4Head,q4Yaw,dock.place!!)<45)
    // The q4 cause: "control_dock" was not an asked-for reason, so centerAll skipped it after a bar grab
    // (`recenter event=center_skipped why=control_dock moved_by_hand=true`) and kept a placed stage otherwise.
    assertFalse(SettingsPanel.followsRecenter("control_dock"))
    assertTrue(StageWindows.keepsPlaced(true,SettingsPanel.followsRecenter("control_dock")))
    // Now the press asks with an explicit reason: neither gate can drop it.
    assertTrue(SettingsPanel.followsRecenter(ControlDockFeature.CENTER_WHY))
    assertFalse(StageWindows.keepsPlaced(true,SettingsPanel.followsRecenter(ControlDockFeature.CENTER_WHY)))
    val src=File("src/main/java/com/overhand/app/features/ControlDockFeature.kt").readText().replace(Regex("\\s+"),"")
    assertTrue(src.contains("\"center\"->center()"))
    assertTrue(src.contains("s.recenter.request(CENTER_WHY)"))
    assertTrue(src.contains("dock.systemRecenter(h,yaw)"))
  }

  @Test fun gridOfHeadsHeadingsAndSavedPlacesAlwaysEndsInView() {
    var cases=0;var inView=0
    val rows=JSONArray()
    for(height in listOf(1.2,1.6,1.9)) for(i in 0 until 8) {
      val yaw=-180.0+i*45;val head=V3(-0.02,height,0.0)
      for((name,saved) in cases(head,yaw)) for(path in listOf("put_on","recenter")) {
        val dock=ControlDock { _,_ -> }
        if(path=="put_on") dock.putOn(head,yaw,saved) else dock.systemRecenter(head,yaw,saved)
        val off=offAxis(head,yaw,dock.place!!);cases++;if(off<=45.0) inView++
        rows.put(JSONObject().put("height",height).put("yaw",yaw).put("saved",name).put("path",path)
          .put("off_axis_deg",off).put("bearing_deg",Comfort.bearing(dock.place!!.centre-head,yaw)).put("ok",off<=45.0))
        if(name=="in_view") assertNotEquals(ControlDock.default(head,yaw),dock.place)   // a good saved place is kept
        if(name in setOf("q4","behind","too_far","too_low") && !(name=="q4" && ControlDock.validSaved(head,yaw,saved!!)))
          assertEquals("$name h=$height yaw=$yaw",ControlDock.default(head,yaw),dock.place)
      }
    }
    val rate=inView.toDouble()/cases
    File(out(),"grid.json").writeText(JSONObject().put("cases",cases).put("in_view",inView).put("dock_in_view_rate",rate).put("rows",rows).toString(2))
    println("dock_in_view_rate=$rate cases=$cases")
    assertEquals(3*8*6*2,cases)
    assertEquals(1.0,rate,0.0)
  }

  private fun out()=File(System.getenv("DOCK_OUT") ?: System.getProperty("dock.out") ?: "build/dock").apply { mkdirs() }
}
