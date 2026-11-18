package com.overhand.app.windows

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.overhand.app.OverhandActivity
import com.overhand.app.data.LocalReceiptStore
import com.overhand.app.data.ModeGeneration
import com.overhand.app.data.RecordedSource
import com.overhand.app.decisions.DecisionCards
import com.overhand.app.features.ControlDock
import com.overhand.app.features.ControlDockFeature
import com.overhand.app.features.Shell
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** PC mount seam, as CodeOpenBudgetTest: nanoTime around dispatch, attach and main-looper completions.
 * Spatial SDK entities/swapchains and WebView's renderer are absent on this JVM. Each target gets a fresh
 * recorded-day session already showing its startup briefing; reopening retains its mounted panel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w1600dp-h800dp-288dpi")
class OpenEveryWindowStallTest {
  private val looper get()=shadowOf(Looper.getMainLooper())
  private fun all(v: View): List<View> = listOf(v)+if(v is ViewGroup)
    (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
  private fun ms(t: Long)=(System.nanoTime()-t)/1e6
  /** Y2: profile the PC mount seam without pretending it executes the native scene.
   * Native create counts deliberately remain NA; zero would be false evidence.
   */
  @Test fun profileRecordedLaunchAndYardIntent() {
    val output=File(System.getenv("Y2_OUT") ?: "build/y2").apply { mkdirs() }
    fun <T> profile(phase: String,action: ()->T): T {
      val main=Thread.currentThread()
      val samples=java.util.concurrent.ConcurrentHashMap<String,Int>()
      val running=java.util.concurrent.atomic.AtomicBoolean(true)
      val sampler=Thread({ while(running.get()) {
        main.stackTrace.filter { it.className.startsWith("com.overhand") &&
          !it.className.contains("OpenEveryWindowStallTest") }.distinct().forEach {
          samples.merge(it.toString(),1,Int::plus)
        }
        Thread.sleep(5)
      } },"y2-sampler").apply { isDaemon=true;start() }
      try { return action() } finally {
        running.set(false);sampler.join()
        File(output,"profile_$phase.tsv").writeText("samples\tframe\n"+
          samples.entries.sortedByDescending { it.value }.joinToString("\n") { "${it.value}\t${it.key}" }+"\n")
      }
    }
    val s=profile("launch") { Session().also { looper.idle() } }
    try {
      val web=all(s.root).filterIsInstance<WebView>().size
      profile("yard") {
        val dock=LinearLayout(s.activity)
        val feature=ControlDockFeature(s.shell)
        val pick=feature.javaClass.getDeclaredMethod("pick",String::class.java).apply { isAccessible=true }
        ControlDock.build(dock) { pick.invoke(feature,it) }
        repeat(2) { assertTrue(dock.findViewWithTag<View>("control-dock-build").performClick()) }
        // Do not drain Shell.commands: this harness has no native ECS runtime.
      }
      File(output,"counts.tsv").writeText("phase\tEntity.create\tText3D.create\tmesh://box\tweb_views\tcoverage\n"+
        "launch\tNA\tNA\tNA\t$web\tPC recorded briefing mount only; native ECS absent\n"+
        "yard\tNA\tNA\tNA\t0\tdock intent only; queued native commands not executed\n")
    } finally {
      s.cards.close();s.shell.commands.clear();s.root.removeAllViews();s.host.pause().stop().destroy();looper.idle()
    }
  }
  private class Session {
    val activity=Robolectric.buildActivity(OverhandActivity::class.java).get()
    val host=Robolectric.buildActivity(Activity::class.java).setup()
    val shell=Shell(activity)
    val source=RecordedSource({ File("src/main/assets",it).readText() },
      LocalReceiptStore(Files.createTempDirectory("f2").resolve("receipts.json").toFile()),null,requested=RecordedSource.DAY)
    val content=RecordedContent(source)
    val windows=RecordedWindows(content,activity)
    val root=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
    val brief=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
    val panels=linkedMapOf<String,LinearLayout>()
    @Suppress("unused") private val onboarded=activity.getSharedPreferences("hands-onboarding-v1",0)
      .edit().putBoolean("recorded",true).commit()
    val cards=DecisionCards(activity,{ "" },{ source },ModeGeneration()) { }
    init {
      host.get().setContentView(root);root.addView(brief)
      windows.attach(brief,"chat",content.defaultProject)
    }
    fun mount(view: String) {
      if(view in panels) { panels.getValue(view).visibility=View.VISIBLE;return }
      val col=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
      panels[view]=col;root.addView(col)
      when(view) {
        "settings" -> shell.settings.build(col)
        "decision" -> cards.attach(col)
        else -> windows.attach(col,view,content.defaultProject)
      }
    }
  }

  @Test fun everyFirstAndSecondOpenFitsTheBudget() {
    val mode=System.getenv("F2_MODE") ?: "after"
    val output=File(System.getenv("F2_OUT") ?: "build/e2/f2").apply { mkdirs() }
    val rows=mutableListOf("target\trun\ttotal_ms\tlongest_block_ms")
    val slow=mutableListOf<String>()
    val targets=ControlDock.items().map { "dock-${it.view}" }+
      listOf("briefing-needs","briefing-building","briefing-done","briefing-history","briefing-back")
    assertEquals(10,targets.size)
    RecordedWindows.codeLoader=RecordedWindows.BACKGROUND
    try { repeat(System.getenv("F3_RUNS")?.toInt() ?: 5) { run -> for(target in targets) {
      val s=Session()
      try {
        looper.idle() // startup briefing is already present, as on put-on
        val view=when(target) {
          "briefing-needs" -> "decision"
          "briefing-building" -> "fleet"
          "briefing-done" -> "build"
          else -> target.removePrefix("dock-")
        }
        fun open(v: String) {
          s.mount(v)
          if(v=="code") s.windows.openCodeFor("",null,"dock")
          if(v !in listOf("settings","decision")) s.windows.refresh()
        }
        LookK8.paths=LookK8.Paths(
          { s.mount("decision");LookK8.raise(s.cards::open,s.cards::openIds,s.cards::showDecision) },
          { open("fleet") },{ open("build") })
        val bridge=shadowOf(s.brief.findViewWithTag<WebView>(LookK8.WEB_TAG)).getJavascriptInterface("AfK8")!!
        fun act(name: String,row: String="") {
          bridge.javaClass.getMethod("act",String::class.java,String::class.java)
            .apply { isAccessible=true }.invoke(bridge,name,row)
        }
        // The dock's production press handler queues ECS intent. Invoke that exact handler, then emulate the
        // SDK panel-setup callback with the real UI mount; native ECS commands are deliberately not executed.
        val dock=LinearLayout(s.activity)
        val feature=ControlDockFeature(s.shell)
        val pick=feature.javaClass.getDeclaredMethod("pick",String::class.java).apply { isAccessible=true }
        ControlDock.build(dock) { v ->
          pick.invoke(feature,v)
          if(mode=="control" && v=="settings") Thread.sleep(400)
          if(v!="build") open(v) // dock Yard toggles the 3D site, not the text window
        }
        fun history() { act("history");looper.idle() }
        if(target=="briefing-back") history()
        fun press() {
          when {
            target.startsWith("dock-") -> assertTrue(dock.findViewWithTag<View>("control-dock-$view").performClick())
            target=="briefing-history" -> act("history")
            target=="briefing-back" -> assertTrue(s.brief.findViewWithTag<Button>(LookK8.BACK_TAG).performClick())
            else -> act("open",target.removePrefix("briefing-"))
          }
        }
        fun pendingSlices()=s.windows.codeView?.let { v ->
          // Reflection lets the identical harness also compile against the saved baseline source.
          v.javaClass.methods.firstOrNull { it.name=="getPendingSlices" }?.invoke(v) as? Int
        } ?: 0
        fun ready()=when(view) {
          "code" -> pendingSlices()==0 && (s.panels[view]?.let { all(it).any { v -> v.tag==CodeView.TAG_LINE } } ?: false)
          "decision" -> s.cards.shown()!=null && !s.cards.openIds().isNullOrEmpty()
          else -> true
        }
        fun measure(): Pair<Double,Double> {
          var main=0.0;var longest=0.0
          val thread=Thread.currentThread()
          val samples=java.util.concurrent.ConcurrentHashMap<String,Int>()
          val sampling=java.util.concurrent.atomic.AtomicBoolean(true)
          val active=java.util.concurrent.atomic.AtomicBoolean(false)
          val sampler=Thread({ while(sampling.get()) {
            if(active.get()) thread.stackTrace.map { it.toString() }.filter {
              (it.startsWith("android.") || it.startsWith("com.overhand.app.")) &&
                !it.contains("OpenEveryWindowStallTest")
            }.distinct().forEach { samples.merge(it,1,Int::plus) }
            Thread.sleep(5)
          } },"f3-sampler").apply { isDaemon=true;start() }
          fun block(action: ()->Unit) {
            active.set(true);val t=System.nanoTime()
            try { action() } finally { val n=ms(t);main+=n;longest=maxOf(longest,n);active.set(false) }
          }
          fun drain() { while(!looper.isIdle) block { looper.runOneTask() } }
          try {
            block { press() };drain()
            val wall=System.nanoTime()
            while(!ready() && ms(wall)<120_000) {
              if(pendingSlices()>0) block { looper.runOneTask() }
              else Thread.sleep(2) // loader wait is not a main-looper message
              drain()
            }
            assertTrue("$target completed its mount",ready())
          } finally {
            sampling.set(false);sampler.join()
            if(view in listOf("settings","code")) File(output,"profile_${view}_run${run+1}.tsv").writeText(
              "samples\tframe\n"+samples.entries.sortedByDescending { it.value }.take(15).joinToString("\n") { "${it.value}\t${it.key}" }+"\n")
          }
          return main to longest
        }
        val first=measure()
        val mounted=s.panels[view] ?: s.brief
        val counts=all(mounted)
        val bytes=counts.filterIsInstance<WebView>().sumOf { shadowOf(it).lastLoadDataWithBaseURL?.data?.toByteArray()?.size ?: 0 }
        val detail=if(target=="dock-build") "0 views; ECS site visibility intent (no UI mount)" else
          "${counts.size} views; ${counts.count { it.tag==CodeView.TAG_LINE }} code lines; $bytes HTML bytes; "+
            "${counts.filterIsInstance<TextView>().sumOf { it.text.length }} text chars"
        rows+="$target\t${run+1}\t${first.first}\t${first.second}"
        File(output,"opens.tsv").writeText(rows.joinToString("\n")+"\n")
        if(first.second>250) slow+="$target/run${run+1}=${first.second}"
        if(view=="fleet" || view=="blueprint") assertTrue("$target WebView mounted",bytes>0)
        if(view=="decision") assertEquals(s.cards.openIds()!!.first(),s.cards.shown()!!.id)
        if(target=="briefing-history") assertEquals(View.GONE,s.brief.findViewWithTag<View>(LookK8.WEB_TAG).visibility)
        if(target=="briefing-back") assertEquals(View.VISIBLE,s.brief.findViewWithTag<View>(LookK8.WEB_TAG).visibility)
      } finally {
        s.cards.close();s.shell.commands.clear();s.root.removeAllViews();s.host.pause().stop().destroy()
        looper.idle()
      }
    } } } finally { LookK8.paths=null;RecordedWindows.codeLoader=RecordedWindows.BACKGROUND }
    if(mode!="before") assertTrue("longest blocks exceed 250 ms: $slow",slow.isEmpty())
  }
}
