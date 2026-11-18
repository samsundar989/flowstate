package com.overhand.app.windows

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import com.meta.spatial.runtime.AlphaMode
import com.meta.spatial.runtime.PanelConfigOptions
import com.meta.spatial.runtime.PanelShapeLayerBlendType
import com.meta.spatial.toolkit.PanelSettings
import com.overhand.app.places.Place
import com.overhand.app.places.Places
import kotlin.math.roundToInt

/**
 * One look for every window: rounded translucent panels, one type scale, glow states.
 * Native views read these constants; Live pages get the same values as CSS variables
 * (see [webScript], consumed by assets/windows.js). Sizes are panel dp: a 1.2 m wide
 * panel is 1280 dp, so 1 dp = 0.94 mm and, at the windows' 1.6 m distance, 1 deg = 30 dp.
 */
object Theme {
  /** The one switch. false = the app looks exactly as it did before this theme. */
  const val ENABLED = true

  // Shape (dp). Panel corner ~0.9 deg, control corner ~0.6 deg at 1.6 m.
  const val PANEL_RADIUS_DP = 32      // look_nov17 s2 line 23: panel 32
  const val CONTROL_RADIUS_DP = 999   // look_nov17 s2 line 23: every control 999 (round or pill)
  const val HAIRLINE_DP = 2           // look_nov17 s2 line 22: edge 2 dp
  const val RIM_DP = 3
  const val GLOW_DP = 8
  // Fill and accent (#RRGGBBAA). look_nov17 s2: panel #0C121A at 65 % (line 19), inner tile white 7 % (line 20).
  const val PANEL_FILL = "#0C121AA6"
  /** Smallest 8-bit alphas meeting PlaceSkyTest's 4.5:1 in both blend models; photos stay unchanged. */
  fun panelFill(place: Place = Places.current): String = when (place) {
    Place.MOUNTAIN -> "#0C121AD7" // 215/255: 4.582365:1 at sky L=0.994954; 214 gives 4.499650.
    Place.BEACH -> "#0C121AD6"    // 214/255: 4.519975:1 at sky L=0.988944; 213 gives 4.439958.
    else -> PANEL_FILL          // Moon (default), Plain and My room retain 65 %.
  }
  const val RAISED_FILL = "#FFFFFF12"
  const val LIFTED_FILL = "#2A3A4CF5"
  const val PRESSED_FILL = "#33506BFF"
  const val HAIRLINE = "#FFFFFF26"   // look_nov17 s2 line 22: white 15 %
  const val ACCENT = "#5CE1E6FF"
  const val HOVER_GLOW = "#5CE1E659"
  const val SELECTED_GLOW = "#5CE1E68C"
  const val TEXT = "#F2F6FAFF"       // look_nov17 s2 line 24
  // look_nov17 s2 tokens added by #1370 (spec line in each comment). Alphas are round(255 x share).
  const val TILE_RADIUS_DP = 20            // line 23: inner tile 20
  const val TILE_EDGE = "#FFFFFF1F"        // line 20: inner tile edge white 12 %
  const val CODE_WELL_FILL = "#00000059"   // line 21: black 35 %
  const val EDGE_TOP = "#FFFFFF4D"         // line 22: top edge white 30 %
  const val CAPTION_TEXT = "#FFFFFFB3"     // line 25: white 70 %
  const val CYAN = "#5CE1E6FF"             // line 26 (same as ACCENT)
  const val CYAN_SELECTED_FILL = "#5CE1E629" // line 27: cyan 16 %
  const val CYAN_PRESSED_FILL = "#5CE1E647"  // line 27: cyan 28 %
  const val AMBER = "#FFB547FF"            // line 28
  const val GREEN = "#45D99AFF"            // line 29
  const val RED = "#FF6B6BFF"              // line 30
  const val ON_CYAN_TEXT = "#06222AFF"     // line 31
  const val GRAB_BAR_W_DP = 160            // line 32: 160 x 8 dp, radius 999, white 40 %, cyan when pointed at, 24 dp under
  const val GRAB_BAR_H_DP = 8
  const val GRAB_BAR_RADIUS_DP = 999
  const val GRAB_BAR_FILL = "#FFFFFF66"
  const val GRAB_BAR_POINTED = CYAN
  const val GRAB_BAR_GAP_DP = 24
  // Type scale (dp at 1.6 m): title 1.2 deg, control 1.07, body 0.94, caption 0.74.
  const val TITLE_DP = 36
  const val CONTROL_DP = 32
  const val BODY_DP = 28
  const val CAPTION_DP = 22
  // Spacing (dp).
  const val SPACE_S_DP = 8
  const val SPACE_M_DP = 16
  const val SPACE_L_DP = 24
  // Timing (ms): hover on/off, press, release.
  const val HOVER_IN_MS = 300
  const val HOVER_OUT_MS = 400
  const val PRESS_MS = 80
  const val RELEASE_MS = 100

  /** #RRGGBBAA to an Android colour int. */
  fun color(hex: String): Int {
    val v = hex.removePrefix("#")
    val rgb = v.substring(0, 6).toLong(16).toInt()
    val a = if (v.length >= 8) v.substring(6, 8).toInt(16) else 255
    return (a shl 24) or (rgb and 0xFFFFFF)
  }

  private fun css(hex: String): String {
    val c = color(hex)
    return "rgba(${Color.red(c)},${Color.green(c)},${Color.blue(c)},${"%.3f".format(java.util.Locale.ROOT, Color.alpha(c) / 255f)})"
  }

  /** Tokens for the Live pages, in dp; windows.js divides by the page zoom. */
  fun tokens(): Map<String, Any> = linkedMapOf(
    "panelRadius" to PANEL_RADIUS_DP, "controlRadius" to CONTROL_RADIUS_DP, "hairline" to HAIRLINE_DP,
    "rim" to RIM_DP, "glow" to GLOW_DP, "panelFill" to css(panelFill()), "raisedFill" to css(RAISED_FILL),
    "liftedFill" to css(LIFTED_FILL), "pressedFill" to css(PRESSED_FILL), "hairlineColor" to css(HAIRLINE),
    "accent" to css(ACCENT), "hoverGlow" to css(HOVER_GLOW), "selectedGlow" to css(SELECTED_GLOW), "text" to css(TEXT),
    "title" to TITLE_DP, "control" to CONTROL_DP, "body" to BODY_DP, "caption" to CAPTION_DP,
    "spaceS" to SPACE_S_DP, "spaceM" to SPACE_M_DP, "spaceL" to SPACE_L_DP,
    "hoverIn" to HOVER_IN_MS, "hoverOut" to HOVER_OUT_MS, "press" to PRESS_MS, "release" to RELEASE_MS,
    // look_nov17 s2 (#1370)
    "tileRadius" to TILE_RADIUS_DP, "pillRadius" to CONTROL_RADIUS_DP, "edge" to HAIRLINE_DP, "edgeColor" to css(HAIRLINE),
    "edgeTop" to css(EDGE_TOP), "tileFill" to css(RAISED_FILL), "tileEdge" to css(TILE_EDGE), "codeWell" to css(CODE_WELL_FILL),
    "captionText" to css(CAPTION_TEXT), "cyan" to css(CYAN), "cyanSelected" to css(CYAN_SELECTED_FILL),
    "cyanPressed" to css(CYAN_PRESSED_FILL), "amber" to css(AMBER), "green" to css(GREEN), "red" to css(RED),
    "onCyan" to css(ON_CYAN_TEXT), "grabBarW" to GRAB_BAR_W_DP, "grabBarH" to GRAB_BAR_H_DP, "grabBarRadius" to GRAB_BAR_RADIUS_DP,
    "grabBarFill" to css(GRAB_BAR_FILL), "grabBarPointed" to css(GRAB_BAR_POINTED), "grabBarGap" to GRAB_BAR_GAP_DP)

  /** Prefix for the injected page script. Empty when off, so pages are byte-identical to before. */
  fun webScript(): String = if (!ENABLED) "" else
    "window.AF_THEME=" + org.json.JSONObject(tokens()).toString() + ";"

  fun webRefreshScript(): String = webScript() +
    "document.documentElement.style.setProperty('--hull',window.AF_THEME.panelFill);"

  // UI thread only. Weak keys let destroyed panel roots leave without a scene scan.
  private val panels = java.util.WeakHashMap<android.view.View, Boolean>()
  fun applyPanelBackground(root: android.view.View) {
    if (!ENABLED) return
    panels[root] = true
    root.background = panelBackground(root.resources.displayMetrics.density)
  }
  private val customFills = java.util.WeakHashMap<GradientDrawable, Boolean>()
  fun trackFill(fill: GradientDrawable) {
    if (!ENABLED) return
    customFills[fill] = true
    fill.setColor(color(panelFill()))
  }
  fun refreshPanels() {
    if (!ENABLED) return
    customFills.keys.toList().forEach { it.setColor(color(panelFill())) }
    fun pages(v: android.view.View) {
      if (v is android.webkit.WebView) v.evaluateJavascript(webRefreshScript(),null)
      if (v is android.view.ViewGroup) for (i in 0 until v.childCount) pages(v.getChildAt(i))
    }
    panels.keys.toList().forEach { applyPanelBackground(it);pages(it) }
  }

  /**
   * The one glass surface (#1370, proof in runs/look17/K1/translucency.md, SDK 0.15.0 bytecode).
   * A Layer panel (UIPanelRenderOptions() defaults to PanelRenderMode.Layer(MASKED)) gets its alphaMode rewritten by
   * PanelShapeConfig.init() from layerBlendType: MASKED -> MASKED_HOLE_PUNCH (alpha tested, 65 % fill drawn solid),
   * ALPHA_BLEND -> TRANSLUCENT_HOLE_PUNCH (blended). So Layer panels need layerBlendType = ALPHA_BLEND; a Mesh panel
   * (layerConfig == null) keeps alphaMode, so TRANSLUCENT blends it in the scene.
   */
  fun panel(base: PanelSettings): PanelSettings = if (!ENABLED) base else object : PanelSettings {
    override fun toPanelConfigOptions() = glass(base.toPanelConfigOptions())
  }

  /** The glass options applied to a config; split out so a JVM test can check them without a scene. */
  fun glass(o: PanelConfigOptions): PanelConfigOptions = o.apply {
    alphaMode = AlphaMode.TRANSLUCENT
    enableTransparent = true
    layerBlendType = PanelShapeLayerBlendType.ALPHA_BLEND // read by init() only when the panel is (or becomes) a layer
  }

  private fun shape(fill: String, radius: Float, strokePx: Int = 0, stroke: String = HAIRLINE) =
    GradientDrawable().apply {
      setColor(color(fill)); cornerRadius = radius
      if (strokePx > 0) setStroke(strokePx, color(stroke))
    }

  /** Rounded translucent panel backplate: 2 dp edge at 15 %, its top run at 30 % (look_nov17 s2 line 22). */
  fun panelBackground(density: Float): Drawable {
    val r = PANEL_RADIUS_DP * density; val px = (HAIRLINE_DP * density).roundToInt().coerceAtLeast(1)
    // The top edge: the same rounded outline at EDGE_TOP, clipped to the panel's top corner height.
    val top = android.graphics.drawable.ClipDrawable(shape("#00000000", r, px, EDGE_TOP), android.view.Gravity.TOP,
      android.graphics.drawable.ClipDrawable.VERTICAL)
    return LayerDrawable(arrayOf(shape(panelFill(), r, px), top)).also { top.setLevel(TOP_EDGE_LEVEL) }
  }
  /** Share of the panel's height the 30 % top edge covers, as a ClipDrawable level (of 10000). */
  const val TOP_EDGE_LEVEL = 1000

  /** A soft outer glow drawn as a wide faint ring plus a crisp inner rim. */
  private fun glow(fill: String, rimColor: String, glowColor: String, rimDp: Float, radiusDp: Int, density: Float): Drawable {
    val g = (GLOW_DP * density).roundToInt()
    val halo = shape("#00000000", radiusDp * density + g, g, glowColor)
    val body = shape(fill, radiusDp * density, (rimDp * density).roundToInt().coerceAtLeast(1), rimColor)
    return LayerDrawable(arrayOf(halo, body)).apply { setLayerInset(1, g / 2, g / 2, g / 2, g / 2) }
  }

  /** Window-level gaze focus: soft accent glow around the whole panel. */
  fun focusRim(density: Float): Drawable =
    glow("#00000000", ACCENT, HOVER_GLOW, RIM_DP.toFloat(), PANEL_RADIUS_DP, density)

  /** Control states: hover = soft glow, selected = bright rim + lifted fill, pressed = deeper fill. */
  fun control(density: Float): StateListDrawable = StateListDrawable().apply {
    setEnterFadeDuration(HOVER_IN_MS); setExitFadeDuration(HOVER_OUT_MS)
    val r = CONTROL_RADIUS_DP
    addState(intArrayOf(android.R.attr.state_pressed), glow(PRESSED_FILL, ACCENT, SELECTED_GLOW, RIM_DP.toFloat(), r, density))
    addState(intArrayOf(android.R.attr.state_selected), glow(LIFTED_FILL, ACCENT, SELECTED_GLOW, RIM_DP.toFloat(), r, density))
    addState(intArrayOf(android.R.attr.state_activated), glow(LIFTED_FILL, ACCENT, SELECTED_GLOW, RIM_DP.toFloat(), r, density))
    addState(intArrayOf(android.R.attr.state_hovered), glow(RAISED_FILL, HOVER_GLOW, HOVER_GLOW, 1.5f, r, density))
    addState(intArrayOf(android.R.attr.state_focused), glow(RAISED_FILL, HOVER_GLOW, HOVER_GLOW, 1.5f, r, density))
    addState(intArrayOf(-android.R.attr.state_enabled), shape("#141A2299", r * density, (HAIRLINE_DP * density).roundToInt().coerceAtLeast(1)))
    val g = (GLOW_DP * density).roundToInt() / 2
    addState(intArrayOf(), LayerDrawable(arrayOf(shape(RAISED_FILL, r * density, (HAIRLINE_DP * density).roundToInt().coerceAtLeast(1))))
      .apply { setLayerInset(0, g, g, g, g) })
  }
}
