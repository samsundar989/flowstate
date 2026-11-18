@file:OptIn(com.meta.spatial.core.SpatialSDKExperimentalAPI::class)

package com.overhand.app.features

import android.content.Intent
import android.graphics.Color
import android.util.Log

/**
 * #1095: the "Steady frame rate" switch, on by default since R388 (#1182): the request is made once when the scene is
 * ready and once per change, each inside a guard. Off, the app makes no display-rate call, exactly as before #1095:
 * Spatial SDK 0.15.0 itself asks for 90 Hz at session start (logcat `Hmi: Set refresh rate to 90`, then
 * `VrRuntimeClient: SetDisplayRefreshRate: app has requested a new display refresh rate: 90`; no line of ours asks).
 * On, it asks for exactly 72 Hz, a 13.9 ms frame budget instead of 11.1 ms: on the Nov 13 worn session 65 % of the
 * frames that missed 90 Hz took 13.9 ms or less (`runs/perf/t1095/`). Switching it off again restores the rate the
 * SDK had confirmed. Every change logs `OverhandPerf: switch steady_fps=on|off`, which `tools/travel/worn_fps.py`
 * uses to report fps per segment.
 */
class FrameRateFeature(private val s: Shell) : Feature {
  override val name = "frame_rate"
  val enabled get() = s.prefs.getBoolean(SteadyFrameRate.PREF, SteadyFrameRate.DEFAULT_ON)
  /** The rate in force before our first request (0 = not read yet). */
  private var initialHz = 0f
  /** True while our 72 Hz request is in force. */
  @Volatile var steady = false; private set

  override fun onSceneReady() {
    if (enabled) s.commands.add { apply(true, "scene_ready") }
    if (boost) s.commands.add { applyBoost(true, "scene_ready") }
  }

  fun setEnabled(on: Boolean) {
    if (on == enabled) return
    s.prefs.edit().putBoolean(SteadyFrameRate.PREF, on).apply()
    s.commands.add { apply(on, "switch") }
  }

  /** ECS thread (Shell.commands). */
  private fun apply(on: Boolean, why: String) {
    // R388 (#1182): a refused or throwing call is logged and the app carries on at whatever rate it has.
    val r = runCatching {
      val scene = s.activity.scene
      if (!steady && initialHz == 0f) initialHz = scene.getConfirmedFrameRate()
      val hz = SteadyFrameRate.request(on, steady, initialHz) ?: return
      hz to scene.requestExactDisplayRate(hz)
    }
    val (hz, ok) = r.getOrElse { Log.w(TAG, "switch steady_fps=${if (on) "on" else "off"} ok=false error=${it.javaClass.simpleName} why=$why"); return }
    steady = on && ok
    Log.i(TAG, "switch steady_fps=${if (on) "on" else "off"} rate=${hz.toInt()} ok=$ok initial=${initialHz.toInt()} why=$why")
  }

  /** #1182 "Processor boost" (debug builds only, off by default; off makes no call). The one runtime rendering lever
   *  Spatial SDK 0.15.0 offers: `SpatialInterface.setPerformanceLevel(PerformanceLevel)` (`scene.spatialInterface`;
   *  enum SUSTAINED_LOW / SUSTAINED_HIGH / BOOST_HINT, javap of meta-spatial-sdk-0.15.0.aar). On asks BOOST_HINT; off
   *  after on asks SUSTAINED_HIGH, the level the SDK defaults to ("CPU and GPU levels", Spatial SDK docs). */
  // #1201: the boost launch cut also lets release builds ask for it (no release-visible control: the row stays debug-only).
  val boost get() = (com.overhand.app.BuildConfig.DEBUG || LaunchCuts.boost) && s.prefs.getBoolean(BOOST_PREF, false)
  @Volatile var boosted = false; private set
  fun setBoost(on: Boolean) {
    if (!(com.overhand.app.BuildConfig.DEBUG || LaunchCuts.boost) || on == boost) return
    s.prefs.edit().putBoolean(BOOST_PREF, on).apply()
    s.commands.add { applyBoost(on, "switch") }
  }
  private fun applyBoost(on: Boolean, why: String) {
    if (!on && !boosted) return
    val level = if (on) com.meta.spatial.core.PerformanceLevel.BOOST_HINT else com.meta.spatial.core.PerformanceLevel.SUSTAINED_HIGH
    val ok = runCatching { s.activity.scene.spatialInterface.setPerformanceLevel(level) }
      .getOrElse { Log.w(TAG, "perf level error=${it.javaClass.simpleName}"); false }
    boosted = on && ok
    Log.i(TAG, "switch perf_boost=${if (on) "on" else "off"} level=$level ok=$ok why=$why")
  }

  override fun onDebugIntent(intent: Intent) {
    if (!com.overhand.app.BuildConfig.DEBUG) return
    if (intent.hasExtra("steady_fps")) setEnabled(intent.getBooleanExtra("steady_fps", false))
    if (intent.hasExtra("perf_boost")) setBoost(intent.getBooleanExtra("perf_boost", false))
    intent.getStringExtra("perf_mark")?.let { Log.i(TAG, "mark ${it.replace(Regex("\\s+"), "_")}") }
  }

  override fun settingsRows() = listOf(SettingsRow(57, SettingsTab.VIEW) { root, _ ->
    root.addView(android.widget.CheckBox(root.context).apply {
      text = "Steady frame rate (72 Hz)"; textSize = 32f; setTextColor(Color.WHITE); tag = "steady-frame-rate-switch"
      isChecked = enabled
      // Qualified: inside apply{} a bare setEnabled() is the CheckBox's View.setEnabled and greys the box out.
      setOnCheckedChangeListener { _, checked -> this@FrameRateFeature.setEnabled(checked) }
    })
    settingsTab(root, SettingsTab.DEBUG)
    if (com.overhand.app.BuildConfig.DEBUG) root.addView(android.widget.CheckBox(root.context).apply {
      text = "Processor boost"; textSize = 32f; setTextColor(Color.WHITE); tag = "processor-boost-switch"
      isChecked = boost
      setOnCheckedChangeListener { _, checked -> this@FrameRateFeature.setBoost(checked) }
    })
  })

  companion object { const val TAG = "OverhandPerf"; const val BOOST_PREF = "processor-boost" }
}

/** Pure rule for [FrameRateFeature], JVM-tested. */
object SteadyFrameRate {
  const val PREF = "steady-frame-rate"
  /** R388 (#1182): on by default. */
  const val DEFAULT_ON = true
  const val STEADY_HZ = 72f
  /** What Spatial SDK 0.15.0 asks for on Quest 2 when the app asks nothing (Nov 13 logcat). */
  const val SDK_DEFAULT_HZ = 90f
  /** The rate to request now, or null for no call: off and never on makes no call (today's behaviour). */
  fun request(on: Boolean, steadyNow: Boolean, initialHz: Float): Float? = when {
    on -> STEADY_HZ
    steadyNow -> if (initialHz > 0f) initialHz else SDK_DEFAULT_HZ
    else -> null
  }
}
