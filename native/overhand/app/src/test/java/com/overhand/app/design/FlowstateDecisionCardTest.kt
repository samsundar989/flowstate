package com.overhand.app.design

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The rebuilt decision card off-device: behaviour hooks and tags, plus PNG renders
 * (Roborazzi on Robolectric native graphics). Renders go to $FLOWSTATE_SHOT_DIR, else build/flowstate.
 * Content is the card developer saw on device (runs/native/p0_t745/final/card_1_selected_glow.png).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1000dp-h720dp-xhdpi")
class FlowstateDecisionCardTest {
  @get:Rule(order = 0) val componentActivity = com.overhand.app.testing.ComponentActivityRule()
  @get:Rule(order = 1) val rule = createComposeRule()

  private val sample = DecisionCardUi(
    position = 0, total = 7,
    question = "WebXR hedge (#16) is live only on a 60-min anonymous hosting provider URL ([link]). " +
      "For a permanent HTTPS URL, one-time account step needed — which?",
    project = "Headset competition", agent = "Worker",
    options = listOf(
      "A: run 'npx hosting provider@latest login' in recorded detail once (recorded detail worker deploys --prod)",
      "B: add a repo host remote to Headset competition (a static-pages branch)",
      "C: skip — hedge stays local, redeploy temp URL on demand",
    ),
    recommended = 0, selected = 0, replayChip = "Replay · Nov 3",
    historyLabel = "3 earlier · 4 practice",
  )

  private fun shot(name: String) {
    val dir = File(System.getenv("FLOWSTATE_SHOT_DIR") ?: "build/flowstate").apply { mkdirs() }
    rule.onRoot().captureRoboImage(File(dir, name).path, RoborazziOptions(taskType = RoborazziTaskType.Record))
  }

  private fun card(ui: DecisionCardUi, onSelect: (Int) -> Unit = {}, onConfirm: () -> Unit = {}) = rule.setContent {
    FlowstateDecisionCard(ui, onSelect = onSelect, onConfirm = onConfirm, modifier = Modifier.size(1000.dp, 720.dp))
  }

  @Test fun openCardKeepsTheViewCardsTagsAndReportsTaps() {
    var picked = -1; var confirmed = 0
    card(sample.copy(selected = -1), onSelect = { picked = it }, onConfirm = { confirmed++ })
    rule.onNodeWithTag(CardTags.CONTEXT).assertExists()
    rule.onNodeWithTag(CardTags.ACTIONS).assertExists()
    rule.onAllNodesWithTag(CardTags.OPTION).assertCountEquals(3)
    // Nothing selected: Confirm is drawn but disabled, exactly like the View card's dimmed button.
    rule.onNodeWithTag(CardTags.CONFIRM, useUnmergedTree = true).assertExists()
    rule.onNodeWithContentDescription(CardTags.CONFIRM_NAME).assertIsNotEnabled()
    rule.onAllNodesWithTag(CardTags.OPTION)[1].performClick()
    assertEquals(1, picked)
    assertEquals(0, confirmed)
  }

  @Test fun selectedCardConfirms() {
    var confirmed = 0
    card(sample, onConfirm = { confirmed++ })
    rule.onNodeWithContentDescription(CardTags.CONFIRM_NAME).assertIsEnabled().performClick()
    assertEquals(1, confirmed)
    shot("card_after.png")
  }

  @Test fun thumbHoldFillsTheRing() {
    card(sample.copy(holdProgress = 0.6f))
    shot("card_after_hold.png")
  }

  @Test fun receiptShowsTheSavedQuestionAndChoice() {
    card(sample.copy(receipt = DecisionCardUi.Receipt(sample.question, "Headset competition · Worker",
      "You chose A: run the login once")))
    rule.onNodeWithTag(CardTags.SAVED).assertExists()
    rule.onNodeWithTag(CardTags.SAVED_QUESTION, useUnmergedTree = true).assertExists()
    rule.onNodeWithTag(CardTags.SAVED_CHOICE, useUnmergedTree = true).assertExists()
    rule.onAllNodesWithTag(CardTags.OPTION).assertCountEquals(0)
    shot("card_after_receipt.png")
  }

  @Test fun optionLettersSplitCleanly() {
    assertEquals("A" to "run it", DecisionCardUi.splitOption("A: run it", 0))
    assertEquals("C" to "skip", DecisionCardUi.splitOption("C) skip", 2))
    assertEquals("B" to "Keep both", DecisionCardUi.splitOption("Keep both", 1))
  }

  @Test fun longOptionsAreCutAtAWordWithinBudget() {
    assertEquals("skip", DecisionCardUi.budget("skip"))
    val long = "run 'npx hosting provider@latest login' in recorded detail once (recorded detail worker deploys --prod)"
    val cut = DecisionCardUi.budget(long)
    assert(cut.length <= DecisionCardUi.OPTION_CHARS + 1 && cut.endsWith("…")) { cut }
    assert(long.startsWith(cut.removeSuffix("…"))) { cut }
  }

  @Test fun typeStaysAboveOneDegreeOnEveryPanel() {
    for (g in listOf(FlowstateTokens.CARD, FlowstateTokens.WINDOW)) {
      val t = flowstateTypography(g)
      // body2 is the smallest role; it must be at least 1.0 degree on that panel.
      assert(t.body2.fontSize.value >= g.dpForDegrees(1.0f) - 0.01f)
    }
    // Card at 1 m, 1 dp = 1 mm: 1 degree is 17.5 dp.
    assertEquals(17.45f, FlowstateTokens.CARD.dpForDegrees(1f), 0.05f)
  }
}
