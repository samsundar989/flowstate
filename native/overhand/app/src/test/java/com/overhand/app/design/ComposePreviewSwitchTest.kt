package com.overhand.app.design

import android.widget.CheckBox
import android.widget.LinearLayout
import com.overhand.app.features.Shell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #863: the t811 Compose preview panel is off the judged path: only a Settings switch, off by default, opens it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposePreviewSwitchTest {
  private val activity = Robolectric.buildActivity(com.overhand.app.OverhandActivity::class.java).get()
  private val shell by lazy { Shell(activity) }
  private val preview get() = shell.features.filterIsInstance<ComposePreview>().single()

  @Test fun switchIsOffByDefaultAndSceneStartOpensNothing() {
    assertFalse(preview.enabled)
    val before = shell.commands.size
    preview.onSceneReady()
    assertEquals("no panel command queued while the switch is off", before, shell.commands.size)
  }

  @Test fun settingsRowIsOneUncheckedSwitchOutsideTheFrequentGrid() {
    val rows = preview.settingsRows()
    // #1079: release builds have no Compose preview at all (ComposePreview.settingsRows is BuildConfig.DEBUG only).
    if (!com.overhand.app.BuildConfig.DEBUG) { assertEquals("release shows no preview switch", 0, rows.size); return }
    assertEquals(1, rows.size)
    // Orders 10, 20, 60 and 70 go to Settings' hand-reachable grid (SettingsFeature.build); this one must not.
    assertFalse(rows.single().order in listOf(10, 20, 60, 70))
    val root = LinearLayout(activity); rows.single().build(root, LinearLayout(activity))
    val box = root.findViewWithTag<CheckBox>("compose-preview-switch")
    assertFalse(box.isChecked)
  }

  @Test fun theSwitchIsTheOnlyWayInAndOffCloses() {
    if (!com.overhand.app.BuildConfig.DEBUG) {
      // #1079: in release nothing can switch it on and nothing is queued.
      val before = shell.commands.size
      preview.setEnabled(true)
      assertFalse(preview.enabled)
      assertEquals("release queues no panel command", before, shell.commands.size)
      return
    }
    val root = LinearLayout(activity); preview.settingsRows().single().build(root, LinearLayout(activity))
    val box = root.findViewWithTag<CheckBox>("compose-preview-switch")
    val before = shell.commands.size
    box.isChecked = true
    assertTrue(preview.enabled)
    assertEquals("switching on queues the open", before + 1, shell.commands.size)
    box.isChecked = false
    assertFalse(preview.enabled)
    assertEquals("switching off queues the close", before + 2, shell.commands.size)
  }
}
