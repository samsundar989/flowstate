package com.overhand.app.features

import android.app.Activity
import android.widget.LinearLayout
import com.overhand.app.decisions.DecisionCards

/** Release twin of the debug-only Settings conveniences: no server presets, no Pose trials control. */
internal object DeveloperSettings {
  val serverPresets: List<Pair<String,String>> = emptyList()

  @Suppress("UNUSED_PARAMETER")
  fun addPoseTrials(root: LinearLayout, decisions: DecisionCards) {}

  @Suppress("UNUSED_PARAMETER")
  fun addVoice(root: LinearLayout, activity: Activity) {}

  @Suppress("UNUSED_PARAMETER")
  fun addLagCheck(root: LinearLayout, start: () -> Unit) {}

  @Suppress("UNUSED_PARAMETER")
  fun lagCheckText(step: Int?, done: Boolean, secondsLeft: Int? = null, restored: Boolean? = null): String? = null
}
