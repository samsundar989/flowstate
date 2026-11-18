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

/** #768's tap-every-control walk (unit level), repeated with each recording chosen (#774). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class DemoTapEveryControlTest {
  init { RecordedWindows.codeLoader=RecordedWindows.INLINE }  // #1385: Code loads inline so these checks see it at once
  private val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
  private val read={ path: String -> File("src/main/assets/$path").readText() }
  private fun layout(root: View) {
    root.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1400,View.MeasureSpec.EXACTLY))
    root.layout(0,0,1280,1400);shadowOf(Looper.getMainLooper()).idle()
  }

  @Test fun everyControlIsSafeWithEachRecordingChosen() {
    val totals=linkedMapOf<String,Int>()
    for((key,_) in RecordedSource.datasets(read)) {
      val content=RecordedContent(RecordedSource(read,LocalReceiptStore(Files.createTempDirectory("tapall-$key").resolve("r.json").toFile()),
        HttpClient { _,_,_ -> error("Offline: HTTP forbidden") },requested=key))
      assertEquals(key,content.source.dataset)
      val windows=RecordedWindows(content,activity)
      var total=0
      // Fleet builds SpatialEarcons, which needs the real activity (as in TapEveryControlTest); its text is checked in DemoProjectsTest.
      for(view in listOf("blueprint","code","build","chat")) {
        val root=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
        windows.attach(root,view,"");activity.setContentView(root);PanelPolish.install(root);layout(root)
        if(view=="code") {  // #1371: the Code window is file rows and a real diff, not a text page
          assertTrue("$key code shows a diff",ControlWalker.descendants(root).count { it.tag==CodeView.TAG_LINE }>0)
        } else if(view=="blueprint") { // K3 replaced the legacy text page with a live WebView.
          assertTrue("$key blueprint is mounted",ControlWalker.descendants(root).any { it is android.webkit.WebView && it.visibility==View.VISIBLE })
        } else {
        if(view=="chat") {
          fun dump(whenShown: String) {
            ControlWalker.descendants(root).forEach { v ->
              println("chat_tree $key $whenShown ${v.javaClass.simpleName} tag=${v.tag} visibility=${v.visibility} shown=${v.isShown} size=${v.width}x${v.height} text=${ControlWalker.label(v).take(100)}")
            }
          }
          dump("before_history")
          val web=root.findViewWithTag<android.webkit.WebView>(LookK8.WEB_TAG)
          assertTrue("$key briefing is visible",web.isShown)
          val bridge=shadowOf(web).getJavascriptInterface("AfK8")!!
          // windows.js's visible History button sends this exact bridge action.
          bridge.javaClass.getMethod("act",String::class.java,String::class.java).apply { isAccessible=true }
            .invoke(bridge,"history","")
          shadowOf(Looper.getMainLooper()).idle();layout(root)
          dump("after_history")
          assertTrue("$key History shows the chat page",root.findViewWithTag<TextView>("recorded-page").isShown)
          assertFalse("$key History hides the briefing",web.isShown)
        }
        val page=root.findViewWithTag<TextView>("recorded-page").text.toString()
        assertTrue("$key $view page is empty",page.isNotBlank())
        val status=root.findViewWithTag<TextView>("recorded-status").text.toString()
        assertFalse("$key $view: $status","Unnamed" in status || "not recorded" in status)
        }
        val listed=ControlWalker.describe(view,root)
        assertTrue("$key $view has controls",listed.length()>0)
        for(i in 0 until listed.length()) {
          val row=listed.getJSONObject(i)
          val hit=ControlWalker.click(root,row.getInt("index"),row.getString("text"));layout(root)
          assertNotNull("$key $view #$i ${row.getString("text")}",hit)
          assertTrue("min target ${row.getString("text")}",(hit as? Button)?.let { it.minimumHeight>=64*it.resources.displayMetrics.density-1 } ?: true)
          total++
        }
      }
      totals[key]=total
    }
    println("controls_tapped_per_recording=$totals")
    assertEquals(5,totals.size)   // the recorded day + four demo projects (#1023)
  }
}
