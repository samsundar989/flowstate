package com.overhand.app.windows

import com.meta.spatial.runtime.AlphaMode
import com.meta.spatial.runtime.PanelShapeLayerBlendType
import com.meta.spatial.toolkit.PanelRenderMode
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.UIPanelRenderOptions
import com.meta.spatial.toolkit.UIPanelSettings
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1370 (look_nov17 s7 step 1): every panel registration in the app goes through Theme.panel(), the one glass.
 * Proof of what Theme.panel must set on SDK 0.15.0: runs/look17/K1/translucency.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LookSurfaceTest {
  /** Registrations allowed off the glass, with the reason. */
  private val exceptions = mapOf(
    "features/LadderProbe.kt" to "render-mode diagnostic spike: its steps compare Layer/Mesh settings on purpose")

  data class Reg(val file: String, val line: Int, val glass: Boolean)

  private fun registrations(): List<Reg> {
    val root = File("src/main/java/com/overhand/app")
    val out = mutableListOf<Reg>()
    root.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
      val s = f.readText()
      val at = Regex("""\b\w*PanelRegistration\(""").findAll(s).map { it.range.first }.toList()
      at.forEachIndexed { i, start ->
        // The registration's text runs to the next registration or 900 chars, whichever is first; its settings
        // argument is inside that span (the four constructors here put settings second or third).
        val end = minOf(at.getOrElse(i + 1) { s.length }, start + 900, s.length)
        val span = s.substring(start, end)
        val settings = span.indexOf("UIPanelSettings(")
        val glass = settings >= 0 && span.substring(0, settings).contains("Theme.panel(")
        out += Reg(f.relativeTo(root).invariantSeparatorsPath, s.substring(0, start).count { it == '\n' } + 1, glass)
      }
    }
    return out
  }

  @Test fun everyRegistrationIsOnTheGlass() {
    val regs = registrations()
    // #1382: 15 since t1359 (75c411fcd) folded two dock panels into the side dock; the floor guards the scan, not a count.
    assertTrue("found ${regs.size}", regs.size >= 15)
    val off = regs.filter { !it.glass && it.file !in exceptions }
    assertEquals("off the glass: $off", emptyList<Reg>(), off)
    // #1384: the ladder probe (a debug-only diagnostic) is the one registration off the glass, and it is still there.
    assertEquals(listOf("features/LadderProbe.kt"), regs.filter { !it.glass }.map { it.file })
    val share = regs.count { it.glass }.toDouble() / regs.size
    println("look_glass_panels=${"%.4f".format(java.util.Locale.ROOT, share)} on=${regs.count { it.glass }} total=${regs.size} " +
      "exceptions=" + regs.filter { !it.glass }.joinToString(",") { "${it.file}:${it.line}" })
    regs.forEach { println("registration ${it.file}:${it.line} glass=${it.glass}") }
  }

  /** look_nov17 s2 line 22: the panel edge is 2 dp at 15 % with its top run at 30 %. */
  @Test fun viewPanelsDrawTheBrighterTopEdge() {
    val bg = Theme.panelBackground(2f) as android.graphics.drawable.LayerDrawable
    assertEquals(2, bg.numberOfLayers)
    val top = bg.getDrawable(1) as android.graphics.drawable.ClipDrawable
    assertEquals(Theme.TOP_EDGE_LEVEL, top.level)
    assertTrue(top.drawable is android.graphics.drawable.GradientDrawable)
  }

  /** The default UIPanelSettings is a Layer(MASKED) panel; glass must ask for ALPHA_BLEND, which init() maps to
   *  TRANSLUCENT_HOLE_PUNCH. A Mesh panel has no layer config and keeps TRANSLUCENT. */
  @Test fun glassAsksForBlendedLayerAndTranslucentMesh() {
    val layer = Theme.panel(UIPanelSettings(shape = QuadShapeOptions(width = 1f, height = 1f))).toPanelConfigOptions()
    assertTrue("default is a layer", layer.layerConfig != null)
    assertEquals(PanelShapeLayerBlendType.ALPHA_BLEND, layer.layerBlendType)
    assertEquals(AlphaMode.TRANSLUCENT, layer.alphaMode)
    assertTrue(layer.enableTransparent)
    val mesh = Theme.panel(UIPanelSettings(shape = QuadShapeOptions(width = 1f, height = 1f),
      rendering = UIPanelRenderOptions(PanelRenderMode.Mesh()))).toPanelConfigOptions()
    assertEquals(null, mesh.layerConfig)
    assertEquals(AlphaMode.TRANSLUCENT, mesh.alphaMode)
  }
}
