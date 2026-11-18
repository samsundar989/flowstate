package com.overhand.app.graph.site

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class YardPressTest {
  @Test fun shownPressHiddenPressShownAndLitFollowsWithoutFlatWindow() {
    var shown=true;var prepared=0;var moved=0
    val states=mutableListOf<Boolean>()
    fun press() {
      YardPress.press(shown,{ prepared++ },{ moved++ },{ shown=true },{ shown=false })
      states+=shown
      assertEquals(shown,YardPress.label(shown,null).startsWith("\u25cf"))
    }
    press();assertFalse(shown);assertEquals(0,prepared);assertEquals(0,moved)
    press();assertTrue(shown);assertEquals(1,prepared);assertEquals(1,moved)
    val root=File("src/main/java/com/overhand/app/features")
    val windows=File(root,"WindowsFeature.kt").readText()
    assertTrue(windows.contains("if(view==\"build\") { s.interaction.pressYard();return@add }"))
    assertTrue(File(root,"ControlDockFeature.kt").readText().contains("\"build\" -> s.interaction.pressYard()"))
    assertTrue(windows.contains("if(view==\"build\") s.interaction.site.drawn"))
    val out=File(System.getenv("YARD_OUT") ?: "../../../runs/fix17/B1b").apply { mkdirs() }
    File(out,"toggle.tsv").writeText("initial\tpress1\tpress2\ntrue\t${states[0]}\t${states[1]}\n")
  }
  @Test fun savedPlaceMustBeWithinTwoPointFiveMetresAndSixtyDegrees() {
    val h=Pose(Vector3(0f,1f,0f))
    fun keep(x: Float,z: Float)=YardPress.keepSaved(Pose(Vector3(x,1f,z)),h)
    assertFalse(YardPress.keepSaved(null,h))
    assertTrue(keep(0f,2.5f));assertFalse(keep(0f,2.501f))
    assertTrue(keep(1.7f,1f));assertFalse(keep(1.8f,1f))
    assertFalse(keep(0f,-1f));assertFalse(keep(0f,0f))
    val p=SitePlacement();val saved=Pose(Vector3(.2f,1f,1f));val front=Pose(Vector3(0f,1f,1.2f))
    p.open(saved,"put_on");p.close("voice")
    YardPress.press(p.open,{}, { if(!YardPress.keepSaved(p.pose,h)) p.recenter(front,SitePlacement.SYSTEM_RECENTER) },{ p.open(front,"voice") },{ p.close("voice") })
    assertSame(saved,p.pose)
  }
  @Test fun hideAndResumeRetainEntitiesAndVoiceUsesSharedActions() {
    val scene=File("src/main/java/com/overhand/app/graph/site/SiteScene.kt").readText()
    val hide=scene.substringAfter("fun hide(why:").substringBefore("fun close()")
    assertFalse(hide.contains("destroy("));assertFalse(hide.contains("draw.reset"));assertFalse(hide.contains("draw.forget"))
    val resume=scene.substringAfter("fun resume(why:").substringBefore("fun hide(why:")
    assertFalse(resume.contains("Entity.create"));assertFalse(resume.contains("draw.invalidate"))
    val voice=File("src/main/java/com/overhand/app/features/VoiceFeature.kt").readText()
    assertTrue(voice.contains("if (key == \"site\" || key == \"build\") s.interaction.showSite"))
    assertTrue(voice.contains("if (key == \"site\" || key == \"build\") s.interaction.hideSite"))
  }
}
