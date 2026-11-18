package com.overhand.app.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.meta.spatial.uiset.theme.SpatialTheme
import com.meta.spatial.uiset.theme.SpatialTypography
import com.meta.spatial.uiset.theme.darkSpatialColorScheme

/** The panel the content is drawn on; tokens in degrees become dp through it. */
val LocalPanelGeometry = staticCompositionLocalOf { FlowstateTokens.CARD }

/** UI Set type ramp re-sized so each role keeps its visual angle on [g] (see [FlowstateTokens]). */
fun flowstateTypography(g: FlowstateTokens.PanelGeometry): SpatialTypography {
  val base = SpatialTypography()
  fun TextStyle.at(deg: Float): TextStyle {
    val size = g.dpForDegrees(deg)
    // Inter reads best at ~1.25 line height on a panel; lines stay on the 4 dp half-grid.
    val line = (Math.ceil(size * 1.25 / 4.0) * 4.0).toFloat()
    return copy(fontSize = size.sp, lineHeight = line.sp)
  }
  return base.copy(
    headline1Strong = base.headline1Strong.at(FlowstateTokens.TYPE_DISPLAY_DEG),
    headline1 = base.headline1.at(FlowstateTokens.TYPE_DISPLAY_DEG),
    headline2Strong = base.headline2Strong.at(FlowstateTokens.TYPE_TITLE_DEG),
    headline2 = base.headline2.at(FlowstateTokens.TYPE_TITLE_DEG),
    headline3Strong = base.headline3Strong.at(FlowstateTokens.TYPE_HEADING_DEG),
    headline3 = base.headline3.at(FlowstateTokens.TYPE_HEADING_DEG),
    body1Strong = base.body1Strong.at(FlowstateTokens.TYPE_BODY_DEG),
    body1 = base.body1.at(FlowstateTokens.TYPE_BODY_DEG),
    body2Strong = base.body2Strong.at(FlowstateTokens.TYPE_CAPTION_DEG),
    body2 = base.body2.at(FlowstateTokens.TYPE_CAPTION_DEG),
  )
}

/**
 * Every Flowstate panel's root: the UI Set [SpatialTheme] (dark scheme, its buttons, tiles, cards,
 * icons and hover/press indication) with our translucent panel fill and a distance-true type ramp.
 */
@Composable
fun FlowstateTheme(geometry: FlowstateTokens.PanelGeometry = FlowstateTokens.CARD, content: @Composable () -> Unit) {
  val scheme = darkSpatialColorScheme().copy(
    panel = Brush.verticalGradient(listOf(FlowstateTokens.PANEL_TOP, FlowstateTokens.PANEL_BOTTOM)),
  )
  CompositionLocalProvider(LocalPanelGeometry provides geometry) {
    SpatialTheme(colorScheme = scheme, typography = flowstateTypography(geometry), content = content)
  }
}
