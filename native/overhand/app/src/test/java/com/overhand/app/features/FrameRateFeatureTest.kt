package com.overhand.app.features

import android.widget.CheckBox
import android.widget.LinearLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #1095, R388 (#1182): "Steady frame rate" is ON by default; the request is made once at scene ready, inside a guard;
 *  off makes no display-rate call unless 72 Hz was in force (then it restores the SDK's rate). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FrameRateFeatureTest {
  private val activity = Robolectric.buildActivity(com.overhand.app.OverhandActivity::class.java).get()
  private val shell by lazy { Shell(activity) }
  private val feature get() = shell.features.filterIsInstance<FrameRateFeature>().single()

  @Test fun onByDefaultAndSceneReadyQueuesOneGuardedRequest() {
    assertTrue(feature.enabled)
    assertTrue(SteadyFrameRate.DEFAULT_ON)
    shell.commands.clear(); val before = 0
    feature.onSceneReady()
    assertEquals("switch on: exactly one command (the 72 Hz request); Processor boost off adds none", before + 1, shell.commands.size)
    // No scene here: the call throws inside the guard, is logged, and the app carries on.
    shell.commands.poll()!!.invoke()
    assertFalse("a throwing request leaves steady off", feature.steady)
  }

  @Test fun settingsRowIsOneCheckedSwitchOutsideTheGrid() {
    val rows = feature.settingsRows()
    assertEquals(1, rows.size)
    assertFalse(rows.single().order in listOf(10, 20, 60, 70))
    val root = LinearLayout(activity); rows.single().build(root, LinearLayout(activity))
    val box = root.findViewWithTag<CheckBox>("steady-frame-rate-switch")
    assertTrue(box.isChecked)
    val before = shell.commands.size
    box.isChecked = false
    assertFalse(feature.enabled)
    assertEquals("off queues the restore", before + 1, shell.commands.size)
    box.isChecked = true
    assertTrue(feature.enabled)
    assertEquals("on queues one request", before + 2, shell.commands.size)
  }

  @Test fun processorBoostIsADebugSwitchOffByDefaultAndOffMakesNoCall() {
    assertFalse(feature.boost)
    val root = LinearLayout(activity); feature.settingsRows().single().build(root, LinearLayout(activity))
    val box = root.findViewWithTag<CheckBox>("processor-boost-switch")
    if (!com.overhand.app.BuildConfig.DEBUG) { assertNull("release has no Processor boost switch", box); return }
    assertFalse(box.isChecked)
    val before = shell.commands.size
    box.isChecked = true; assertTrue(feature.boost); assertEquals(before + 1, shell.commands.size)
    shell.commands.poll(); box.isChecked = false; assertFalse(feature.boost)
  }

  @Test fun ruleOffNeverOnMakesNoCallOnAsks72OffAfterOnRestores() {
    assertNull(SteadyFrameRate.request(on = false, steadyNow = false, initialHz = 90f))
    assertNull(SteadyFrameRate.request(on = false, steadyNow = false, initialHz = 0f))
    assertEquals(72f, SteadyFrameRate.request(on = true, steadyNow = false, initialHz = 90f)!!, 0f)
    assertEquals(72f, SteadyFrameRate.request(on = true, steadyNow = true, initialHz = 90f)!!, 0f)
    assertEquals(90f, SteadyFrameRate.request(on = false, steadyNow = true, initialHz = 90f)!!, 0f)
    assertEquals(120f, SteadyFrameRate.request(on = false, steadyNow = true, initialHz = 120f)!!, 0f)
    assertEquals("unknown initial rate restores the SDK default", 90f, SteadyFrameRate.request(on = false, steadyNow = true, initialHz = 0f)!!, 0f)
  }

  @Test fun theSwitchLineIsASegmentMarkForWornFps() {
    // tools/travel/worn_fps.py PERF_MARK: ^(?:mark (\S+)|switch (\S+=\S+))
    val re = Regex("^(?:mark (\\S+)|switch (\\S+=\\S+))")
    assertEquals("steady_fps=on", re.find("switch steady_fps=on rate=72 ok=true initial=90 why=switch")!!.groupValues[2])
    assertEquals("FrameRateFeature.TAG starts with Overhand (worn_fps keeps Overhand* tags)", "OverhandPerf", FrameRateFeature.TAG)
  }
}
