package com.overhand.app.features

import android.app.Activity
import android.widget.Button
import android.widget.LinearLayout
import com.overhand.app.R
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
class ControlDockLayoutTest {
  @Test fun fiveControlsRenderAndDispatchInOneColumn() {
    val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
    val root=activity.layoutInflater.inflate(R.layout.control_dock,null) as LinearLayout
    val picks=mutableListOf<String>()
    ControlDock.build(root) { picks.add(it) }
    assertEquals(LinearLayout.VERTICAL,root.orientation);assertEquals(5,root.childCount)
    for(i in 0 until root.childCount) root.getChildAt(i).performClick()
    assertEquals(ControlDock.items().map { it.view },picks)
    val out=File(System.getenv("DOCK_OUT") ?: System.getProperty("dock.out") ?: "build/dock","layout")
    val renderer=LayoutLintRenderer(activity,out)
    val meta=renderer.capture(Panel("control_dock",ControlDock.DP,660f,
      ControlDock.WIDTH_M,ControlDock.HEIGHT_M,ControlDock.DISTANCE_M.toFloat(),"ControlDock.build") {
      ControlDock.build(it) {}
    })
    assertFalse(meta.toString(),meta.has("error"))
  }
}
