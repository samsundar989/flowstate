package com.overhand.app.windows

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.overhand.app.data.HttpClient
import com.overhand.app.data.LocalReceiptStore
import com.overhand.app.data.RecordedSource
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * #1385: the q4 freeze. Pressing Code on the recorded day blocked the main thread for 54.7 s. This drives the open path
 * exactly as the headset did (Dashboards: attach "code" with the station project, then openCodeFor dock and refresh),
 * then 75 playback ticks, 10 refreshes and 5 openCodeFor calls, with the real assets/judge/diffs.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w1600dp-h800dp-288dpi")
class CodeOpenBudgetTest {
  private val out=File(System.getProperty("e2.out") ?: "build/e2").apply { mkdirs() }
  private val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
  private fun source()=RecordedSource({ File("src/main/assets/$it").readText() },
    LocalReceiptStore(Files.createTempDirectory("f").resolve("r.json").toFile()),HttpClient { _,_,_ -> error("offline") },requested=RecordedSource.DAY)
  private fun all(v: View): List<View> = listOf(v)+(if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList())
  private val looper get()=shadowOf(Looper.getMainLooper())
  private fun ms(t0: Long)=(System.nanoTime()-t0)/1e6

  @Test fun firstOpenStaysOffTheMainThreadAndParsesOnce() {
    val gated=ConcurrentHashMap<String,Int>()
    RecordedWindows.gateProbe={ s -> gated.merge(s,1,Int::plus) }
    RecordedWindows.codeLoader=RecordedWindows.BACKGROUND  // as on the headset
    try {
      val content=RecordedContent(source())
      content.source.diffs  // the asset read itself is the source's lazy (shared with the graph); not part of the open
      val parses0=CodeDiffs.parses.get()
      val w=RecordedWindows(content,activity)
      val col=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
      activity.setContentView(col)  // attached, as the panel is: View.postDelayed (the playback tick) runs only then
      // First open, on the main thread: attach (panel creation) + the dock's openCodeFor + refresh, then whatever is
      // posted back to the main thread until the window shows the diff.
      var main=0.0
      var t=System.nanoTime();w.attach(col,"code",content.defaultProject);w.openCodeFor("",null,"dock");w.refresh();main+=ms(t)
      val attachMs=main
      val wall0=System.nanoTime()
      while(all(col).none { it.tag==CodeView.TAG_LINE } && ms(wall0)<120_000) {
        t=System.nanoTime();looper.idle();main+=ms(t)
        if(all(col).none { it.tag==CodeView.TAG_LINE }) Thread.sleep(2)
      }
      val readyMs=ms(wall0)
      val firstOpenMain=main
      val v=w.codeView!!
      val builtFirst=v.built
      val openLines=all(col).count { it.tag==CodeView.TAG_LINE }
      // Playback (75 one-second ticks), refreshes and opens: none of it may parse or gate again.
      t=System.nanoTime()
      // CODEOPEN_TICKS: the before run (old code, one parse per tick) uses fewer ticks; the gate always runs 75.
      val ticks=System.getenv("CODEOPEN_TICKS")?.toIntOrNull() ?: 75
      repeat(ticks) { looper.idleFor(Duration.ofSeconds(1)) }
      val ticksMs=ms(t)
      t=System.nanoTime()
      repeat(10) { w.refresh() }
      repeat(5) { i -> w.openCodeFor(if(i%2==0) "s74617e7e4f3c9e64" else "",if(i<3) "eval/run_eval.py" else null,if(i<3) "blueprint" else "dock") }
      looper.idle()
      val laterMs=ms(t)
      val lineViews=all(col).count { it.tag==CodeView.TAG_LINE }
      val parses=CodeDiffs.parses.get()-parses0
      val worst=gated.values.maxOrNull() ?: 0
      val r=JSONObject().put("project",v.project).put("file_rows",v.rows.size).put("attach_ms",attachMs)
        .put("first_open_main_ms",firstOpenMain).put("ready_wall_ms",readyMs).put("views_first_open",builtFirst)
        .put("open_file_line_views_first",openLines).put("ticks",ticks).put("ticks_ms",ticksMs).put("refresh10_open5_ms",laterMs)
        .put("parses",parses).put("gate_calls",gated.values.sum()).put("distinct_gated",gated.size).put("max_gates_one_string",worst)
        .put("views_total",v.built).put("open_file_line_views",lineViews).put("headset_ms",54_700)
        .put("headset_ratio_vs_first_open",54_700/firstOpenMain.coerceAtLeast(0.001))
      File(out,"code_open.json").writeText(r.toString(1))
      println("code_open $r")
      assertEquals("diffs.json parsed once",1,parses)
      assertTrue("each asset string gated at most once (worst $worst)",worst<=1)
      // Head ruling 2026-11-17 16:38: 50 ms dropped; timing is a loose backstop, parse/gate counts guard the freeze.
      assertTrue("main-thread ms at first open $firstOpenMain <= 1500",firstOpenMain<=1500.0)
      assertTrue("line views for the open file $lineViews <= 201",lineViews in 1..201)
    } finally { RecordedWindows.gateProbe=null }
  }

  /** Card step 5: the other recorded windows' first open (attach, then the posted work), main-thread ms; numbers only. */
  @Test fun otherFirstOpenPathsMeasured() {
    val content=RecordedContent(source());content.source.diffs
    val r=JSONObject()
    for(view in listOf("fleet","blueprint","build","chat")) {
      val w=RecordedWindows(content,activity)
      val col=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
      activity.setContentView(col)
      val t=System.nanoTime()
      val error=runCatching { w.attach(col,view,content.defaultProject);looper.idle() }.exceptionOrNull()
      r.put(view,JSONObject().put("main_ms",ms(t)).put("error",error?.toString() ?: ""))
    }
    File(out,"other_paths.json").writeText(r.toString(1))
    println("other_paths $r")
  }
}
