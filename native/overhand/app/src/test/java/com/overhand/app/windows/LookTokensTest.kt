package com.overhand.app.windows

import androidx.compose.ui.graphics.toArgb
import com.overhand.app.design.FlowstateTokens as F
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1370: every row of look_nov17.md section 2's token table (.autoflow/wiki/look_nov17.md, line numbers below),
 * hardcoded here, equals Theme.kt, FlowstateTokens.kt and the window.AF_THEME JSON the web pages read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LookTokensTest {
  private val theme: JSONObject = JSONObject(Theme.webScript().removePrefix("window.AF_THEME=").removeSuffix(";"))
  private fun argb(hexRgbaOrArgb: String): Int = Theme.color(hexRgbaOrArgb)
  /** AF_THEME colours are css rgba(r,g,b,a) with a to 3 places. */
  private fun css(rgba: String): String {
    val c = argb(rgba)
    return "rgba(${c shr 16 and 255},${c shr 8 and 255},${c and 255},${"%.3f".format(java.util.Locale.ROOT, (c ushr 24) / 255f)})"
  }
  private fun row(line: Int, spec: String, theme: String, json: String, compose: androidx.compose.ui.graphics.Color?) {
    assertEquals("s2 line $line Theme.kt", argb(spec), argb(theme))
    assertEquals("s2 line $line AF_THEME.$json", css(spec), this.theme.getString(json))
    if (compose != null) assertEquals("s2 line $line FlowstateTokens", argb(spec), compose.toArgb())
  }

  @Test fun everyColourRow() {
    row(19, "#0C121AA6", Theme.PANEL_FILL, "panelFill", F.GLASS_FILL)            // panel fill #0C121A at 65 %
    row(20, "#FFFFFF12", Theme.RAISED_FILL, "tileFill", F.GLASS_TILE_FILL)       // inner tile white 7 %
    row(20, "#FFFFFF1F", Theme.TILE_EDGE, "tileEdge", F.GLASS_TILE_EDGE)         // inner tile edge white 12 %
    row(21, "#00000059", Theme.CODE_WELL_FILL, "codeWell", F.GLASS_CODE_WELL)    // code well black 35 %
    row(22, "#FFFFFF26", Theme.HAIRLINE, "edgeColor", F.GLASS_EDGE)              // edge white 15 %
    row(22, "#FFFFFF4D", Theme.EDGE_TOP, "edgeTop", F.GLASS_EDGE_TOP)            // top edge white 30 %
    row(24, "#F2F6FAFF", Theme.TEXT, "text", F.LOOK_TEXT)                        // text
    row(25, "#FFFFFFB3", Theme.CAPTION_TEXT, "captionText", F.LOOK_CAPTION)      // caption white 70 %
    row(26, "#5CE1E6FF", Theme.CYAN, "cyan", F.LOOK_CYAN)                        // cyan
    row(26, "#5CE1E6FF", Theme.ACCENT, "accent", null)                           // ACCENT unchanged
    row(27, "#5CE1E629", Theme.CYAN_SELECTED_FILL, "cyanSelected", F.LOOK_CYAN_SELECTED) // cyan 16 %
    row(27, "#5CE1E647", Theme.CYAN_PRESSED_FILL, "cyanPressed", F.LOOK_CYAN_PRESSED)    // cyan 28 %
    row(28, "#FFB547FF", Theme.AMBER, "amber", F.LOOK_AMBER)
    row(29, "#45D99AFF", Theme.GREEN, "green", F.LOOK_GREEN)
    row(30, "#FF6B6BFF", Theme.RED, "red", F.LOOK_RED)
    row(31, "#06222AFF", Theme.ON_CYAN_TEXT, "onCyan", F.LOOK_ON_CYAN)
    row(32, "#FFFFFF66", Theme.GRAB_BAR_FILL, "grabBarFill", F.GRAB_BAR_FILL)    // grab bar white 40 %
    row(32, "#5CE1E6FF", Theme.GRAB_BAR_POINTED, "grabBarPointed", null)         // cyan when pointed at
  }

  @Test fun everySizeRow() {
    fun dp(line: Int, spec: Int, code: Int, json: String, compose: Float?) {
      assertEquals("s2 line $line Theme.kt", spec, code)
      assertEquals("s2 line $line AF_THEME.$json", spec, theme.getInt(json))
      if (compose != null) assertEquals("s2 line $line FlowstateTokens", spec.toFloat(), compose)
    }
    dp(22, 2, Theme.HAIRLINE_DP, "edge", F.GLASS_EDGE_W.value)                   // edge 2 dp
    dp(23, 32, Theme.PANEL_RADIUS_DP, "panelRadius", F.RADIUS_PANEL.value)       // panel 32
    dp(23, 20, Theme.TILE_RADIUS_DP, "tileRadius", F.RADIUS_GLASS_TILE.value)    // inner tile 20
    dp(23, 999, Theme.CONTROL_RADIUS_DP, "pillRadius", F.RADIUS_PILL.value)      // every control 999
    dp(23, 999, Theme.CONTROL_RADIUS_DP, "controlRadius", null)
    dp(32, 160, Theme.GRAB_BAR_W_DP, "grabBarW", F.GRAB_BAR_W.value)             // grab bar 160 x 8 dp
    dp(32, 8, Theme.GRAB_BAR_H_DP, "grabBarH", F.GRAB_BAR_H.value)
    dp(32, 999, Theme.GRAB_BAR_RADIUS_DP, "grabBarRadius", null)
    dp(32, 24, Theme.GRAB_BAR_GAP_DP, "grabBarGap", F.GRAB_BAR_GAP.value)       // 24 dp under the panel
  }
}
