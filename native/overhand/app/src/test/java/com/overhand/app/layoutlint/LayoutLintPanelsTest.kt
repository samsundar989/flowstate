package com.overhand.app.layoutlint

import android.app.Activity
import android.graphics.Color
import android.widget.LinearLayout
import android.widget.TextView
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Worked example of the capture entry point (#807). A panel built the way a registered one is, rendered by
 * the same LayoutLintRenderer the gate uses, gives a PNG and an element tree tools/layout_lint/lint.py reads.
 * A Compose panel (#772) is added to LayoutLintPanels.panels in the same shape (see its KDoc).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w1600dp-h800dp-288dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LayoutLintPanelsTest {
  private val activity=Robolectric.buildActivity(Activity::class.java).setup().get()

  private val example=Panel("example_registered",1000f,720f,1f,0.72f,1.2f,"LayoutLintPanelsTest.example") { column ->
    column.setBackgroundColor(Color.BLACK)
    listOf("Registered title","Second line").forEach { value ->
      column.addView(TextView(column.context).apply { text=value;textSize=32f;setTextColor(Color.WHITE);tag="example" })
    }
  }

  @Test fun registeredPanelRendersToPngAndElementTree() {
    val out=Files.createTempDirectory("layoutlint-example").toFile()
    val meta=LayoutLintRenderer(activity,out).capture(example)
    assertTrue(out.resolve("example_registered.png").length()>0)
    assertTrue(out.resolve("example_registered.json").isFile)
    val texts=(0 until meta.getJSONArray("elements").length()).map { meta.getJSONArray("elements").getJSONObject(it) }
      .filter { it.optString("tag")=="example" }
    assertEquals(2,texts.size)
    assertTrue(texts.all { it.getJSONArray("lines").length()==1 && !it.isNull("visible_rect") })
    assertEquals(1800,meta.getInt("width_px"))
  }

  @Test fun registryEntriesAreUniqueWindows() {
    val names=LayoutLintPanels.panels.map { it.name }
    assertEquals(names.size,names.toSet().size)
  }
}
