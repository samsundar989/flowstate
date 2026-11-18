package com.overhand.app.features

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GrabBarLookTest {
  @Test fun dimensionsFollowEachPanelsOwnDpScale() {
    for((w,dp) in listOf(0.84 to 1280.0,1.0 to 1000.0,0.5 to 1000.0)) {
      val s=GrabBarLook.size(w,dp)
      assertEquals(160.0,s.w*dp/w,1e-9)
      assertEquals(8.0,s.h*dp/w,1e-9)
      assertEquals(24.0,s.gap*dp/w,1e-9)
    }
  }
  @Test fun restPointedAndHeldUseSpecColours() {
    assertEquals(0x66FFFFFF,GrabBarLook.colour(false,false))
    assertEquals(0xFF5CE1E6.toInt(),GrabBarLook.colour(true,false))
    assertEquals(0xFF5CE1E6.toInt(),GrabBarLook.colour(false,true))
    assertEquals(GrabBarLook.colour(true,false),GrabBarLook.colour(true,true))
  }
  @Test fun endsAreSemicirclesAndBoundsAre160By8() {
    val points=GrabBarLook.outline()
    assertEquals(160.0,points.maxOf { it.first }-points.minOf { it.first },1e-9)
    assertEquals(8.0,points.maxOf { it.second }-points.minOf { it.second },1e-9)
    points.forEach { (x,y) ->
      assertEquals(4.0,kotlin.math.hypot(kotlin.math.abs(x)-76.0,y),1e-9)
    }
  }
  @Test fun nativeAndReplayGrabTargetsRemainLaneCSize() {
    assertEquals(0.40,PanelGrab.BAR_W,0.0)
    assertEquals(0.035,PanelGrab.BAR_H,0.0)
    assertEquals(0.40,PanelGrab.BAR_HIT_W,0.0)
    assertEquals(0.035,PanelGrab.BAR_HIT_H,0.0)
    val code=File("src/main/java/com/overhand/app/features/GrabFeature.kt").readText()
    assertTrue(code.contains("box((PanelGrab.BAR_W/2).toFloat(),(PanelGrab.BAR_H/2).toFloat()"))
    assertTrue(code.contains("MeshCollision.NoCollision"))
    assertTrue(code.contains("bars.getOrPut(id)"))
    assertTrue(code.contains("tones.set(b.visual,tone)"))
    assertFalse(code.contains("destroy("))
  }
}
