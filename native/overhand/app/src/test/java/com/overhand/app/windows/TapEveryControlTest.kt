package com.overhand.app.windows

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.overhand.app.data.*
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Robolectric rig for the tap_every_control walker (#768): real Android views, recorded content, no headset.
 * Not the Spatial SDK panel (no window context of type 2037 here), so it proves the walker and the handlers,
 * not the headset's window rules; the integrator's smoke run does that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class TapEveryControlTest {
  init { RecordedWindows.codeLoader=RecordedWindows.INLINE }  // #1385: Code loads inline so these checks see it at once
  private val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
  private val content=RecordedContent(RecordedSource({ File("src/main/assets/$it").readText() },
    LocalReceiptStore(Files.createTempDirectory("tapall").resolve("receipts.json").toFile()),
    HttpClient { _,_,_ -> error("Offline: HTTP forbidden") }))
  private val windows=RecordedWindows(content,activity)
  private fun panel(view: String): LinearLayout {
    val root=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
    windows.attach(root,view,"")
    activity.setContentView(root)   // attached, so View.post (pagination) runs as on the headset
    PanelPolish.install(root)
    layout(root);return root
  }
  private fun layout(root: View) {
    root.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1400,View.MeasureSpec.EXACTLY))
    root.layout(0,0,1280,1400);shadowOf(Looper.getMainLooper()).idle()
  }
  private fun status(root: View)=root.findViewWithTag<TextView>("recorded-status").text.toString()

  @Test fun projectChipsChangeTheProjectOnBothPickerWindows() {
    // #1371: the Code window has no project chips: it shows the changed files of the project that has them.
    for(view in listOf("blueprint")) {
      val root=panel(view)
      val row=root.findViewWithTag<LinearLayout>("chip-row")
      val chips=ControlWalker.descendants(row).filterIsInstance<Button>()
      assertEquals(content.projects.size,chips.size)
      assertTrue(chips[0].isSelected)
      chips[2].performClick();layout(root)
      assertTrue(ControlWalker.descendants(root).any { it is android.webkit.WebView && it.visibility==View.VISIBLE })
      assertTrue(chips[2].isSelected);assertFalse(chips[0].isSelected)
      windows.refresh(content.projects[1].getString("id"));layout(root)
      assertTrue(chips[1].isSelected);assertFalse(chips[2].isSelected)
    }
  }

  @Test fun walkerFindsAndTapsEveryControlWithoutAWindow() {
    val report=File("../../../runs/native/crashproof").apply { mkdirs() }
    val rows=StringBuilder("[\n")
    var total=0
    for(view in listOf("blueprint","code","build","chat")) {
      val root=panel(view)
      val listed=ControlWalker.describe(view,root)
      assertTrue("$view has controls",listed.length()>0)
      for(i in 0 until listed.length()) {
        val row=listed.getJSONObject(i)
        val hit=ControlWalker.click(root,row.getInt("index"),row.getString("text"))
        layout(root)
        assertNotNull("$view #$i ${row.getString("text")}",hit)
        assertTrue("min target ${row.getString("text")}",(hit as? Button)?.let { it.minimumHeight>=64*it.resources.displayMetrics.density-1 } ?: true)
        rows.append("  ").append(row.put("tapped",true).put("rig","robolectric").toString()).append(",\n")
        total++
      }
    }
    // A lone ChipRow, as the graph builds it.
    var picked=-1
    val chips=ChipRow(activity,listOf("A","B","C","D"),0) { picked=it }
    chips.chips[3].performClick();assertEquals(3,picked)
    picked=-1;chips.chips[3].performClick();assertEquals("re-tapping the chosen chip is a no-op",-1,picked)
    chips.select(1);assertEquals(-1,picked);assertTrue(chips.chips[1].isSelected)
    File(report,"walker_robolectric.json").writeText(rows.toString().trimEnd(',','\n')+"\n]\n")
    println("controls_enumerated=$total")
    assertTrue(total>=4*3)
  }
}
