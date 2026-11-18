package com.overhand.app.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meta.spatial.uiset.button.PrimaryButton
import com.meta.spatial.uiset.button.SecondaryButton
import com.meta.spatial.uiset.button.TextTileButton
import com.meta.spatial.uiset.card.SecondaryCard
import com.meta.spatial.uiset.theme.SpatialTheme
import com.meta.spatial.uiset.theme.icons.SpatialIcons
import com.meta.spatial.uiset.theme.icons.regular.CheckCircle
import com.meta.spatial.uiset.theme.icons.regular.ChevronLeft
import com.meta.spatial.uiset.theme.icons.regular.ChevronRight
import com.meta.spatial.uiset.theme.icons.regular.HandCursor
import com.meta.spatial.uiset.theme.icons.regular.History
import com.meta.spatial.uiset.theme.icons.regular.Refresh
import com.meta.spatial.uiset.theme.icons.regular.ThumbsUp
import com.overhand.app.design.FlowstateTokens as T

/**
 * What the decision card shows, already decided by DecisionCards (selection, receipt, notice).
 * Pure data so the same card renders off-device (Roborazzi) and, once wired, in the panel.
 */
data class DecisionCardUi(
  val position: Int,            // 0-based index of the open card
  val total: Int,
  val question: String,
  val project: String,
  val agent: String,
  val options: List<String>,
  val recommended: Int = -1,
  val selected: Int = -1,
  /** Short provenance chip in Recorded mode, e.g. "Replay · Nov 3"; null in Live. */
  val replayChip: String? = null,
  /** One line under the options. The card's IDLE sentence is replaced by this short form. */
  val hint: String = HINT,
  val notice: String? = null,
  val noticeIsError: Boolean = false,
  /** 0..1 thumb hold; drawn as the ring inside Confirm. */
  val holdProgress: Float = 0f,
  val historyLabel: String? = null,
  val receipt: Receipt? = null,
  // #874, the live card (DecisionCards.compose). Defaults keep the #772 design exactly as it was.
  /** Confirm stays pinchable with nothing selected (drawn dimmed) so DecisionCards can refuse it where the hand is. */
  val confirmRefusesInPlace: Boolean = false,
  /** Lines under the header in the upper half: the Recorded badge, the tracking caveat. */
  val captions: List<String> = emptyList(),
  /** Expanded earlier answers; shown in the upper half in place of the question. */
  val history: List<String> = emptyList(),
  val showRefresh: Boolean = false,
  /** Bumped by a refused Confirm: the options dip and return, right above the hand (#800's pulse). */
  val pulse: Int = 0,
  /** The selected option's letter, drawn in the hold ring while it fills. */
  val holdLabel: String? = null,
  val advanceLabel: String = "Next decision",
) {
  /** [where] set (#881, the live card): three labelled answers, what was asked, what was answered, where it went. */
  data class Receipt(val question: String, val context: String, val choice: String, val where: String? = null)
  companion object {
    const val HINT = "Pinch Confirm or hold thumbs up"

    /** "A: run the deploy" -> ("A", "run the deploy"); labels without a letter keep a number. */
    fun splitOption(label: String, index: Int): Pair<String, String> {
      val m = Regex("^\\s*([A-Z])\\s*[:.)\\-]\\s*(.+)$", RegexOption.DOT_MATCHES_ALL).find(label)
      return if (m != null) m.groupValues[1] to m.groupValues[2].trim() else ('A' + index).toString() to label.trim()
    }

    /** Option tile text budget: the UI Set tile clips rather than ellipsizes, so cut at a word and add "…". */
    const val OPTION_CHARS = 64
    fun budget(text: String, max: Int = OPTION_CHARS): String {
      if (text.length <= max) return text
      val cut = text.take(max).let { t -> t.substring(0, t.lastIndexOf(' ').takeIf { it > max / 2 } ?: t.length) }
      return cut.trimEnd(' ', ',', '(', '—', '-') + "…"
    }
  }
}

/** The View card's pulse length (CardTouchTrace.PULSE_MS), so both cards answer a refused Confirm alike. */
private const val PULSE_MS = 1400

/** Test tags shared with the View card (DecisionCards, HandsOnboarding.OPTION_TAG); do not rename. */
object CardTags {
  const val CONTEXT = "card-context"
  const val ACTIONS = "card-actions"
  const val OPTION = "card-option"
  const val SAVED = "card-saved"
  const val SAVED_QUESTION = "card-saved-question"
  const val SAVED_CHOICE = "card-saved-choice"
  const val CONFIRM = "card-confirm"
  /** DecisionCards.debugConfirm finds Confirm by this text; kept as its accessibility name. */
  const val CONFIRM_NAME = "Confirm selected option"
}

/** The rebuilt decision card. All behaviour stays in DecisionCards; this only draws and reports taps. */
@Composable
fun FlowstateDecisionCard(
  ui: DecisionCardUi,
  onSelect: (Int) -> Unit = {},
  onConfirm: () -> Unit = {},
  onClose: () -> Unit = {},
  onPrevious: () -> Unit = {},
  onNext: () -> Unit = {},
  onHistory: () -> Unit = {},
  onAdvance: () -> Unit = {},
  onRefresh: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  FlowstateTheme(T.CARD) {
    Column(
      modifier
        .fillMaxSize()
        .clip(RoundedCornerShape(T.RADIUS_PANEL))
        .background(SpatialTheme.colorScheme.panel)
        .border(1.dp, T.HAIRLINE, RoundedCornerShape(T.RADIUS_PANEL))
        .padding(T.PANEL_PADDING),
    ) {
      // Upper half: context only. Every frequent target sits in the lower half (constraint 8).
      Column(Modifier.weight(1f).fillMaxWidth().testTag(CardTags.CONTEXT)) {
        Header(ui, onPrevious, onNext, onRefresh)
        ui.captions.forEach { Text(it, style = SpatialTheme.typography.body2, color = T.TEXT_SECONDARY, maxLines = 2) }
        Spacer(Modifier.height(T.SPACE_L))
        if (ui.receipt != null) Saved(ui.receipt)
        else if (ui.history.isNotEmpty()) Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
          ui.history.forEach { Text(it, style = SpatialTheme.typography.body2, color = T.TEXT_PRIMARY, modifier = Modifier.padding(bottom = T.SPACE_S)) }
        }
        else Text(
          ui.question, style = SpatialTheme.typography.headline2Strong, color = T.TEXT_PRIMARY,
          maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
      }
      // The receipt has no options: its actions take only their height and the saved answer gets the rest (#881's three labels).
      Column((if (ui.receipt == null) Modifier.weight(1f) else Modifier).fillMaxWidth().testTag(CardTags.ACTIONS), verticalArrangement = Arrangement.Bottom) {
        if (ui.receipt == null) Options(ui, onSelect)
        Spacer(Modifier.height(T.SPACE_M))
        StatusLine(ui)
        Spacer(Modifier.height(T.SPACE_M))
        Actions(ui, onConfirm, onClose, onHistory, onAdvance)
      }
    }
  }
}

@Composable
private fun Header(ui: DecisionCardUi, onPrevious: () -> Unit, onNext: () -> Unit, onRefresh: () -> Unit) {
  Row(Modifier.fillMaxWidth().height(T.HIT_MIN), verticalAlignment = Alignment.CenterVertically) {
    StateChip(if (ui.receipt != null) T.LiveState.BUILT else T.LiveState.NEEDS_YOU, if (ui.receipt != null) "Saved" else null)
    Spacer(Modifier.width(T.SPACE_M))
    Text(
      "${ui.project} · ${ui.agent}", style = SpatialTheme.typography.body2, color = T.TEXT_SECONDARY,
      maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
    )
    ui.replayChip?.let {
      Text(it, style = SpatialTheme.typography.body2, color = T.TEXT_SECONDARY,
        modifier = Modifier.border(1.dp, T.HAIRLINE, RoundedCornerShape(T.RADIUS_CHIP)).padding(horizontal = T.SPACE_S, vertical = T.SPACE_XS))
      Spacer(Modifier.width(T.SPACE_M))
    }
    if (ui.total > 1 && ui.receipt == null) {
      NavButton(onPrevious) { Icon(SpatialIcons.Regular.ChevronLeft, "Previous") }
      Text("${ui.position + 1} of ${ui.total}", style = SpatialTheme.typography.body1Strong, color = T.TEXT_PRIMARY,
        modifier = Modifier.padding(horizontal = T.SPACE_M))
      NavButton(onNext) { Icon(SpatialIcons.Regular.ChevronRight, "Next") }
    }
    if (ui.showRefresh && ui.receipt == null) {
      Spacer(Modifier.width(T.SPACE_M))
      NavButton(onRefresh) { Icon(SpatialIcons.Regular.Refresh, "Refresh") }
    }
  }
}

/** #874: a 64 dp square icon button. The UI Set circle button keeps its 40 dp width whatever the modifier (4.1 cm < 4.5 cm). */
@Composable
private fun NavButton(onClick: () -> Unit, icon: @Composable () -> Unit) {
  SecondaryButton(label = "", onClick = onClick, leading = icon, modifier = Modifier.size(T.HIT_MIN))
}

@Composable
private fun StateChip(state: T.LiveState, label: String? = null) {
  Row(
    Modifier.clip(RoundedCornerShape(T.RADIUS_CHIP)).background(state.color.copy(alpha = 0.08f))
      .padding(horizontal = T.SPACE_M, vertical = T.SPACE_S),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(12.dp).clip(CircleShape).background(state.color))
    Spacer(Modifier.width(T.SPACE_S))
    Text(label ?: state.label, style = SpatialTheme.typography.body2Strong, color = state.color)
  }
}

@Composable
private fun Options(ui: DecisionCardUi, onSelect: (Int) -> Unit) {
  val dip = remember { Animatable(1f) }
  LaunchedEffect(ui.pulse) {
    if (ui.pulse == 0) return@LaunchedEffect
    dip.snapTo(1f); dip.animateTo(0.35f, tween(PULSE_MS / 2)); dip.animateTo(1f, tween(PULSE_MS / 2))
  }
  Row(Modifier.fillMaxWidth().alpha(dip.value), horizontalArrangement = Arrangement.spacedBy(T.HIT_GAP)) {
    ui.options.forEachIndexed { i, label ->
      val (letter, body) = DecisionCardUi.splitOption(label, i)
      TextTileButton(
        label = DecisionCardUi.budget(body),
        secondaryLabel = if (i == ui.recommended) "$letter · Recommended" else letter,
        selected = i == ui.selected,
        onSelectionChange = { onSelect(i) },
        // The tile's own size is 100 dp tall: three body lines push its letter 3 px out of the box. An exact height wins.
        modifier = Modifier.weight(1f).height(T.HIT_MIN * 2 + T.SPACE_L).testTag(CardTags.OPTION),
      )
    }
  }
}

@Composable
private fun StatusLine(ui: DecisionCardUi) {
  val text = ui.notice ?: ui.hint
  val color = when {
    ui.notice == null -> T.TEXT_SECONDARY
    ui.noticeIsError -> T.LiveState.BLOCKED.color
    else -> T.LiveState.BUILT.color
  }
  // #1033: both commit gestures as icons beside the hint.
  Row(verticalAlignment = Alignment.CenterVertically) {
    if (ui.notice == null) {
      Icon(SpatialIcons.Regular.HandCursor, "Pinch", Modifier.size(20.dp), tint = T.TEXT_SECONDARY)
      Icon(SpatialIcons.Regular.ThumbsUp, "Thumbs up", Modifier.size(20.dp), tint = T.TEXT_SECONDARY)
      Spacer(Modifier.width(T.SPACE_S))
    }
    Text(text, style = SpatialTheme.typography.body2, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

@Composable
private fun Actions(ui: DecisionCardUi, onConfirm: () -> Unit, onClose: () -> Unit, onHistory: () -> Unit, onAdvance: () -> Unit) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(T.HIT_GAP)) {
    if (ui.receipt != null) {
      PrimaryButton(label = ui.advanceLabel, onClick = onAdvance, modifier = Modifier.weight(1f).height(T.HIT_MIN),
        leading = { Icon(SpatialIcons.Regular.ChevronRight, null) })
    } else {
      val chosen = ui.selected in ui.options.indices
      PrimaryButton(
        label = "Confirm", onClick = onConfirm, isEnabled = chosen || ui.confirmRefusesInPlace,
        leading = { HoldRing(ui.holdProgress, ui.holdLabel) },
        modifier = Modifier.weight(1f).height(T.HIT_MIN).testTag(CardTags.CONFIRM)
          .alpha(if (chosen || !ui.confirmRefusesInPlace) 1f else 0.55f)
          .semantics { contentDescription = CardTags.CONFIRM_NAME },
      )
      ui.historyLabel?.let { SecondaryButton(label = it, onClick = onHistory, modifier = Modifier.height(T.HIT_MIN), leading = { Icon(SpatialIcons.Regular.History, null) }) }
    }
    SecondaryButton(label = "Close", onClick = onClose, modifier = Modifier.height(T.HIT_MIN))
  }
}

/** Thumb-hold progress around the thumbs-up glyph. The UI Set has no progress ring, so we draw one. */
@Composable
private fun HoldRing(progress: Float, label: String? = null) {
  val accent = T.LiveState.NEEDS_YOU.color
  Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
    Canvas(Modifier.fillMaxSize()) {
      val w = 3.dp.toPx()
      drawArc(Color.Black.copy(alpha = 0.18f), 0f, 360f, false, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = Stroke(w))
      if (progress > 0f) drawArc(accent, -90f, 360f * progress.coerceIn(0f, 1f), false, Offset(w / 2, w / 2),
        Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round))
    }
    if (label != null && progress > 0f) Text(label, style = SpatialTheme.typography.body2Strong)
    else Icon(SpatialIcons.Regular.ThumbsUp, "Thumbs up", Modifier.size(20.dp))
  }
}

@Composable
private fun Saved(r: DecisionCardUi.Receipt) {
  SecondaryCard(modifier = Modifier.fillMaxWidth().testTag(CardTags.SAVED)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(SpatialIcons.Regular.CheckCircle, null, tint = T.LiveState.BUILT.color, modifier = Modifier.size(32.dp))
      Spacer(Modifier.width(T.SPACE_M))
      Text(r.context, style = SpatialTheme.typography.body2, color = T.TEXT_PRIMARY, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    // On the SecondaryCard's grey, TEXT_SECONDARY is 4.28:1 (lint low_contrast); labels use primary text, set apart by size.
    @Composable fun label(text: String) { if (r.where != null) Text(text, style = SpatialTheme.typography.body2, color = T.TEXT_PRIMARY) }
    Spacer(Modifier.height(T.SPACE_S))
    label(com.overhand.app.RecordedFirstCard.SAVED_QUESTION)
    Text(r.question, style = SpatialTheme.typography.headline3, color = T.TEXT_PRIMARY, maxLines = 2,
      overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag(CardTags.SAVED_QUESTION))
    Spacer(Modifier.height(T.SPACE_S))
    label(com.overhand.app.RecordedFirstCard.SAVED_ANSWER)
    Text(r.choice, style = SpatialTheme.typography.headline3Strong, color = T.LiveState.BUILT.color, maxLines = 2,
      overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag(CardTags.SAVED_CHOICE))
    r.where?.let { Spacer(Modifier.height(T.SPACE_S));label(com.overhand.app.RecordedFirstCard.SAVED_WHERE)
      Text(it, style = SpatialTheme.typography.body2, color = T.TEXT_PRIMARY, maxLines = 3, overflow = TextOverflow.Ellipsis) }
  }
}
