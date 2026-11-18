package com.overhand.app.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meta.spatial.uiset.theme.SpatialColor
import kotlin.math.PI
import kotlin.math.tan

/**
 * Flowstate design tokens (docs/design/flowstate_design.md is the source of truth; keep in step).
 *
 * Sizes are stated as visual angle and turned into panel dp for a given panel, because our panels
 * do not share one density: the decision card is 1000 dp across 1.0 m (1 dp = 1 mm) at about 1 m,
 * the six windows are 1280 dp across 1.2 m at 1.6 m. The UI Set ramp (32/24/20/14/11) assumes
 * small near panels; at 1 m its 11 sp caption is 0.6 degrees, under our 1.0 degree floor.
 */
object FlowstateTokens {
  // ---- Geometry --------------------------------------------------------------------------
  /** Panel dp per metre of quad width (DpDisplayOptions.width / QuadShapeOptions.width). */
  data class PanelGeometry(val dpPerMetre: Float, val distanceM: Float) {
    /** dp that subtend [deg] degrees at this panel's distance. */
    fun dpForDegrees(deg: Float): Float = (2.0 * distanceM * tan(deg * PI / 360.0)).toFloat() * dpPerMetre
  }
  /** The decision card: 1000 dp on a 1.0 m quad, worn at about 1.0 m (InteractionFeature). */
  val CARD = PanelGeometry(dpPerMetre = 1000f, distanceM = 1.0f)
  /** The five windows as WindowLayout places them (#861): 1280 dp on a 0.84 m quad, the farthest at 1.12 m. */
  val WINDOW by lazy { layoutGeometry("fleet") }
  /** Settings, from the layout that places it (#861): 853 dp across its default quad (0.6 m since #799), at the
   *  farthest default window distance (1.12 m). Read from WindowLayout so type and targets cannot drift from placement. */
  val SETTINGS by lazy { layoutGeometry("settings") }
  /** A default window's geometry as WindowLayout places it: dp per metre of its quad width and the farthest distance
   *  any default window sits at (the type must hold there). */
  fun layoutGeometry(view: String): PanelGeometry {
    val sp = com.overhand.app.features.WindowLayout.defaultSpecs.first { it.view == view }
    val dp = if (view == "settings") com.overhand.app.windows.PolishRules.SETTINGS_W_DP else com.overhand.app.features.WindowLayout.PANEL_W_DP
    return PanelGeometry(dpPerMetre = dp / sp.w, distanceM = com.overhand.app.features.WindowLayout.FARTHEST_M)
  }
  /** A station label: 384 dp on a 0.6 m quad, on the arc about 3.3 m away (StationRules.arc) (#816). */
  val STATION = PanelGeometry(dpPerMetre = 384f / 0.6f, distanceM = 3.3f)

  // ---- Type (visual angle of the font size, em) -------------------------------------------
  // Floor: nothing readable below 1.0 deg; Quest 3 resolves ~25 px/deg, so a 1.0 deg em is a
  // ~14 px x-height for Inter (x-height 0.55 em). Steps are about 1.25x apart.
  const val TYPE_DISPLAY_DEG = 2.3f   // UI Set headline1   (card 40 dp) one number or one word
  const val TYPE_TITLE_DEG = 1.8f     // UI Set headline2   (card 32 dp) the question, window title
  const val TYPE_HEADING_DEG = 1.5f   // UI Set headline3   (card 26 dp) section, option text
  const val TYPE_BODY_DEG = 1.25f     // UI Set body1       (card 22 dp) buttons, short lines
  const val TYPE_CAPTION_DEG = 1.05f  // UI Set body2       (card 18 dp) meta line, hint; the floor
  // (Meta: 14 px minimum, 18 px or more comfortable, with 48 dp ~ 3 deg; 18 dp on the card is 1.05 deg.)

  // ---- Spacing: 8 dp grid on the card (scale with the panel like type) ---------------------
  val SPACE_XS: Dp = 4.dp   // only inside a control (icon to label)
  val SPACE_S: Dp = 8.dp
  val SPACE_M: Dp = 16.dp
  val SPACE_L: Dp = 24.dp
  val SPACE_XL: Dp = 32.dp
  val PANEL_PADDING: Dp = 32.dp
  /** Hit target floor for primary controls: Meta asks 60x60 dp for hand tracking (3 deg is 52 mm
   *  at 1 m, constraint 6); rounded up to the 8 dp grid. */
  val HIT_MIN: Dp = 64.dp
  /** Gap between targets: 12 mm (constraint 6), rounded up to the grid. */
  val HIT_GAP: Dp = 16.dp

  // ---- Corner radii (UI Set SpatialShapeSizes) -------------------------------------------
  val RADIUS_PANEL: Dp = 32.dp   // twoXL, panels and the card
  val RADIUS_TILE: Dp = 24.dp    // xLarge, option tiles and cards inside a panel
  val RADIUS_CONTROL: Dp = 20.dp // large, buttons
  val RADIUS_CHIP: Dp = 12.dp    // small, state chips

  // ---- Colour: the five live states, from the UI Set palette -----------------------------
  enum class LiveState(val label: String, val color: Color) {
    PLANNED("Planned", Color(0xFF8A99A8)),
    BUILDING("Building", SpatialColor.b50),
    BUILT("Built", SpatialColor.g50),
    BLOCKED("Blocked", SpatialColor.r50),
    NEEDS_YOU("Needs you", SpatialColor.y60),
  }
  /** Translucent panel: the UI Set dark gradient at 88 % so the room shows through. */
  val PANEL_TOP = Color(0xE0414141.toInt())
  val PANEL_BOTTOM = Color(0xE0272727.toInt())
  val TEXT_PRIMARY = SpatialColor.RLDSWhite100
  val TEXT_SECONDARY = SpatialColor.white60
  val HAIRLINE = SpatialColor.white15

  // ---- look_nov17 s2 (#1370): the one glass, mirrored from windows/Theme.kt (same hex, ARGB here) ----
  val GLASS_FILL = Color(0xA60C121A.toInt())       // line 19: #0C121A at 65 %
  val GLASS_TILE_FILL = Color(0x12FFFFFF)          // line 20: white 7 %
  val GLASS_TILE_EDGE = Color(0x1FFFFFFF)          // line 20: white 12 %
  val GLASS_CODE_WELL = Color(0x59000000)          // line 21: black 35 %
  val GLASS_EDGE = Color(0x26FFFFFF)               // line 22: white 15 %, 2 dp
  val GLASS_EDGE_TOP = Color(0x4DFFFFFF)           // line 22: top edge white 30 %
  val GLASS_EDGE_W: Dp = 2.dp
  val RADIUS_GLASS_TILE: Dp = 20.dp                // line 23: inner tile 20
  val RADIUS_PILL: Dp = 999.dp                     // line 23: every control 999
  val LOOK_TEXT = Color(0xFFF2F6FA.toInt())        // line 24
  val LOOK_CAPTION = Color(0xB3FFFFFF.toInt())     // line 25: white 70 %
  val LOOK_CYAN = Color(0xFF5CE1E6.toInt())        // line 26
  val LOOK_CYAN_SELECTED = Color(0x295CE1E6)       // line 27: cyan 16 %
  val LOOK_CYAN_PRESSED = Color(0x475CE1E6)        // line 27: cyan 28 %
  val LOOK_AMBER = Color(0xFFFFB547.toInt())       // line 28
  val LOOK_GREEN = Color(0xFF45D99A.toInt())       // line 29
  val LOOK_RED = Color(0xFFFF6B6B.toInt())         // line 30
  val LOOK_ON_CYAN = Color(0xFF06222A.toInt())     // line 31
  val GRAB_BAR_W: Dp = 160.dp                      // line 32: 160 x 8 dp, radius 999, white 40 %, 24 dp under the panel
  val GRAB_BAR_H: Dp = 8.dp
  val GRAB_BAR_FILL = Color(0x66FFFFFF)
  val GRAB_BAR_GAP: Dp = 24.dp

  // ---- Glow ------------------------------------------------------------------------------
  /** Hover: the UI Set hover wash plus a thin rim; selected: full rim and an outer halo. */
  val HOVER_RIM: Dp = 2.dp
  val SELECTED_RIM: Dp = 3.dp
  val GLOW_RADIUS: Dp = 12.dp
  const val HOVER_GLOW_ALPHA = 0.25f
  const val SELECTED_GLOW_ALPHA = 0.45f
  /** Building pulse: glow alpha swings between these, never faster than 1 Hz (no flashing). */
  const val BUILDING_GLOW_MIN = 0.20f
  const val BUILDING_GLOW_MAX = 0.70f

  // ---- Motion (ms) -----------------------------------------------------------------------
  const val PANEL_IN_MS = 240        // alpha 0 to 1, scale 0.96 to 1, decelerate
  const val CONTENT_IN_DELAY_MS = 80
  const val CONTENT_IN_MS = 160
  const val HOVER_IN_MS = 120
  const val HOVER_OUT_MS = 240
  const val PRESS_MS = 80
  const val BUILDING_PULSE_MS = 1600 // one full breath; 0.6 Hz
  const val STATE_CHANGE_MS = 400    // node colour cross-fade
}
