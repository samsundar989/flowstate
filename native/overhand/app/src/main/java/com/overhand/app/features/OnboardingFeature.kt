package com.overhand.app.features

import android.content.Intent
import android.widget.LinearLayout
import com.overhand.app.onboarding.HandsOnboarding
import com.overhand.app.windows.PolishRules
import kotlin.math.roundToInt

/** Hands onboarding: its Settings replay row and onboarding_* probes. */
class OnboardingFeature(private val s: Shell) : Feature {
  override val name = "onboarding"
  override fun onDebugIntent(intent: Intent) { HandsOnboarding.get(s.activity).debug(intent) }
  override fun settingsRows() = listOf(SettingsRow(20, SettingsTab.ROOM) { root, _ ->
    HandsOnboarding.get(s.activity).settings(root) { s.interaction.decisions.open() }
    root.getChildAt(root.childCount-1).layoutParams=LinearLayout.LayoutParams(-1,(PolishRules.TARGET_DP*root.resources.displayMetrics.density).roundToInt())
  })
}
