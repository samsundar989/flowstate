package com.overhand.app.features

import com.meta.spatial.toolkit.DpDisplayOptions
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
 * #1181: the Spatial SDK's own panel config for the dp sizes our panels use (layout dpi, layer or mesh, mips).
 * Robolectric because PanelConfigOptions' static init reads android.os.Build.BOARD (null on the plain JVM, job 6000).
 * No timing here, so Robolectric does not distort any number. With PERF_OUT_DIR set, writes sdk_panel.tsv.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LoadInventorySdkPanelTest {
  private val out = System.getenv("PERF_OUT_DIR")?.let { File(it).apply { mkdirs() } }
  private fun write(name: String, tsv: String) { println(tsv); out?.let { File(it, name).writeText(tsv) } }

  @Test fun sdkPanelConfig() {
    // (name, settings): the sizes registered in main (WindowsFeature, Stations, VoiceFeature, Dock, the orb), and the planted 2048 px control.
    val cases = listOf(
      "window_1280x800_dp_layer" to UIPanelSettings(QuadShapeOptions(1f, 0.625f), DpDisplayOptions(1280f, 800f)),
      "station_384x256_dp_mesh" to UIPanelSettings(QuadShapeOptions(0.6f, 0.4f), DpDisplayOptions(384f, 256f), UIPanelRenderOptions(PanelRenderMode.Mesh())),
      "orb_160x160_dp_layer" to UIPanelSettings(QuadShapeOptions(0.12f, 0.12f), DpDisplayOptions(160f, 160f)),
      "control_2048x2048_dp_at_160dpi_layer" to UIPanelSettings(QuadShapeOptions(1f, 1f), DpDisplayOptions(2048f, 2048f, 160)),
    )
    val rows = cases.map { (name, st) ->
      val o = st.toPanelConfigOptions()
      listOf(name, o.layoutDpi, o.layoutWidthInDp, o.layoutHeightInDp, o.layoutWidthInPx, o.layoutHeightInPx,
        o.layerConfig != null, o.enableLayer, o.mips, o.forceSceneTexture).joinToString("\t")
    }
    write("sdk_panel.tsv", "case\tlayout_dpi\twidth_dp\theight_dp\twidth_px\theight_px\tlayer_config\tenable_layer\tmips\tforce_scene_texture\n" + rows.joinToString("\n") + "\n")
    val dpi = cases.first().second.toPanelConfigOptions().layoutDpi
    assertEquals("SDK default dpi (DpDisplayOptions bytecode: 288)", 288, dpi)
    assertTrue("default render mode is a compositor layer", cases.first().second.toPanelConfigOptions().layerConfig != null)
  }

}
