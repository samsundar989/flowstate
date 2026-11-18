package com.overhand.app.features

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Guard every registration site, including automatically generated SDK panel corners. */
class GrabSurfaceContractTest {
  @Test fun noApplicationPointerOrPanelCornerRegistration() {
    val root=File("src/main/java/com/overhand/app")
    val sources=root.walkTopDown().filter { it.extension=="kt" }.associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
    val grab=sources.getValue("features/GrabFeature.kt")
    assertFalse("Application cursor draw path",grab.contains("drawCursors") || grab.contains("box(0.012f"))
    assertFalse("Application corner draw path",grab.contains("b.corners") || grab.contains("box(0.035f"))
    sources.forEach { (path,code) ->
      assertFalse("SDK resize registered in $path",Regex("IsdkPanelResize\\s*\\(").containsMatchIn(code))
      assertFalse("SDK corner handle registered in $path",Regex("IsdkPanelGrabHandle\\s*\\(").containsMatchIn(code))
      if(path!="features/GrabFeature.kt") assertFalse("Panel-face grabbable creates SDK handles in $path",Regex("IsdkGrabbable\\s*\\(").containsMatchIn(code))
    }
    assertTrue("Old saved resizes cannot set dimensions",sources.getValue("features/WindowLayout.kt").contains("val size=defaultSize(sp)"))
    assertTrue("Live grabs must be native",grab.contains("IsdkGrabbable()"))
    assertTrue("Use SDK's exact cursor hit",grab.contains("sdk.registerObserver(observer)") && grab.contains("ev.hitInfo.point"))
    assertTrue("Release persists placement",grab.contains("s.recenter.onRelease(e)"))
  }

  @Test fun nativeBarTranslationMovesEveryPanelWithoutChangingDimensions() {
    for(sp in WindowLayout.defaultSpecs) {
      val p=PanelGrab.Panel(sp.view,V3(sp.x.toDouble(),sp.y.toDouble(),sp.z.toDouble()),0.0,sp.w.toDouble(),sp.h.toDouble())
      assertEquals(p.centre,PanelGrab.carried(p,p.bar,p.bar))
      val delta=V3(0.3,0.12,-0.2)
      assertTrue(PanelGrab.dist(p.centre+delta,PanelGrab.carried(p,p.bar,p.bar+delta))<1e-9)
    }
  }

  @Test fun onlyBarHasAGrabZoneEvenWhenResizableWasSaved() {
    val p=PanelGrab.Panel("panel",V3(0.0,1.0,2.0),0.0,1.0,0.7,resizable=true)
    val head=V3(0.0,1.3,0.0);val g=PanelGrab()
    for(x in listOf(-1.0,1.0)) for(y in listOf(-1.0,1.0)) {
      val c=p.centre+V3(x*p.w/2,y*p.h/2,0.0)
      assertNotEquals(PanelGrab.Zone.CORNER,g.zone(head,(c-head).unit(),p)?.zone)
    }
    val side=p.centre+V3(p.w/2+0.03,0.0,0.0)
    assertNull(g.zone(head,(side-head).unit(),p))
    assertEquals(PanelGrab.Zone.BAR,g.zone(head,(p.bar-head).unit(),p)?.zone)
    assertEquals(PanelGrab.Zone.FACE,g.zone(head,(p.centre-head).unit(),p)?.zone)
  }
}
