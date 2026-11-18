package com.overhand.app.features

import android.app.Activity
import android.graphics.Color
import android.widget.CheckBox
import android.widget.LinearLayout
import com.overhand.app.BuildConfig
import com.overhand.app.decisions.DecisionCards
import com.overhand.app.features.Shell.Companion.USB

/** Debug-only Settings conveniences. The release source set has an empty twin, so none of this is compiled there. */
internal object DeveloperSettings {
  val serverPresets: List<Pair<String,String>> = listOf("USB" to USB,"Home network" to "http://192.0.2.10:8790","Private network" to "http://192.0.2.10:8790")

  fun addPoseTrials(root: LinearLayout, decisions: DecisionCards) {
    root.addView(CheckBox(root.context).apply {
      text="Pose trials (practice only)";textSize=40f;setTextColor(Color.WHITE)
      setOnCheckedChangeListener { button,checked ->
        decisions.setTrials(checked)
        if(button.isChecked!=decisions.trials.enabled) button.isChecked=decisions.trials.enabled
      }
    })
  }

  /** Voice spike (task 736). Built only with -PoverhandVoice, which adds src/voice; reflection keeps default debug builds free of it. */
  fun addVoice(root: LinearLayout, activity: Activity) {
    if(!BuildConfig.VOICE) return
    Class.forName("com.overhand.app.voice.PushToTalk").getMethod("addRow",LinearLayout::class.java,Activity::class.java).invoke(null,root,activity)
  }

  /** #1182: the lag check's one control. Debug only: the release twin adds nothing, so no "Lag check" text ships. */
  fun addLagCheck(root: LinearLayout, start: () -> Unit) {
    settingsButton(root, "Lag check (2 min)", start)
    root.getChildAt(root.childCount-1).tag="lag-check-button"
  }
  /** #1233: the orb, once a second for the whole run: "Lag check step 3 of 12" over "107 s left"; at the end
   *  "Lag check done" over whether the settings came back ([restored] null: not known, e.g. restored at a launch). */
  fun lagCheckText(step: Int?, done: Boolean, secondsLeft: Int? = null, restored: Boolean? = null): String? = when {
    done -> "Lag check done\n" + when(restored) { true -> "settings restored"; false -> "restore FAILED"; null -> "settings back" }
    step == null -> null
    else -> "Lag check step ${step+1} of ${LagCheck.STEPS}" + (secondsLeft?.let { "\n$it s left" } ?: "")
  }
}
