package com.overhand.app.graph.site

import android.app.Activity
import com.overhand.app.features.ControlDock
import com.overhand.app.layoutlint.LayoutLintRenderer
import com.overhand.app.layoutlint.Panel
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w1600dp-h800dp-288dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class YardDockRenderTest {
  @Test fun dockLabelsBeforeAndAfter() {
    val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
    val out=File(System.getenv("YARD_OUT") ?: "../../../runs/fix17/B1b")
    val renderer=LayoutLintRenderer(activity,out)
    val states=listOf("before" to YardPress.label(true,null),
      "after_hidden" to YardPress.label(false,null),"after_shown" to YardPress.label(true,null))
    for((name,label) in states) {
      val meta=renderer.capture(Panel(name,ControlDock.DP,ControlDock.DP,ControlDock.WIDTH_M,
        ControlDock.HEIGHT_M,ControlDock.DISTANCE_M.toFloat(),"ControlDock.build + YardPress.label") {
        ControlDock.build(it) {}
        it.findViewWithTag<android.widget.TextView>("dock-label-build").text=label
      })
      assertFalse(meta.toString(),meta.has("error"))
    }
    File(out,"manifest.tsv").writeText("file\tstate\n"+states.joinToString("\n") { "${it.first}.png\t${it.second}" }+"\n")
  }
}
