package com.overhand.app.features

import android.app.Activity
import com.overhand.app.layoutlint.LayoutLintCapture
import com.overhand.app.layoutlint.LayoutLintRenderer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w1600dp-h800dp-288dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GrabChromeRenderTest {
  @Test fun renderChangedPanelSurfaces() {
    val out=File(System.getenv("GRAB_RENDER_OUT") ?: "build/grab_chrome")
    val capture=LayoutLintCapture()
    val selected=capture.specs().filter { it.name in WindowLayout.defaultSpecs.map { s -> s.view } || it.name in listOf("decision_card","architecture_panel") }+
      capture.shellPanels().filter { it.name.startsWith("settings_") }.take(1)
    val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
    val renderer=LayoutLintRenderer(activity,out)
    selected.forEach { renderer.capture(it) }
    File(out,"panels.tsv").writeText(selected.joinToString("\n") { "${it.name}\t${it.wM}\t${it.hM}" })
  }
}
