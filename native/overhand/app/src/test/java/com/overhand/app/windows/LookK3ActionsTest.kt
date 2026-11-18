package com.overhand.app.windows

import android.app.Activity
import android.os.Looper
import android.widget.LinearLayout
import com.overhand.app.data.HttpClient
import com.overhand.app.data.LocalReceiptStore
import com.overhand.app.data.RecordedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.nio.file.Files

/** #1382: every look K3 button reaches a real path; none only writes a log line. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class LookK3ActionsTest {
  private val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
  private val content=RecordedContent(RecordedSource({ File("src/main/assets/$it").readText() },
    LocalReceiptStore(Files.createTempDirectory("k3").resolve("r.json").toFile()),HttpClient { _,_,_ -> error("offline") }))
  private val headset="s74617e7e4f3c9e64"

  private class Calls : LookK3.Hooks {
    val log=mutableListOf<String>()
    override fun select(project: String) { log+="select $project" }
    override fun show(view: String) { log+="show $view" }
    override fun close(view: String) { log+="close $view" }
    override fun code(project: String,part: String) { log+="code $project $part" }
    override fun ask(project: String,part: String,name: String,status: String) { log+="ask $project $part $name $status" }
  }
  private fun codeOpens()=ShadowLog.getLogs().map { it.msg }.filter { it.startsWith("code event=open") }
  /** A part of the headset project that has recorded changes, so the Code window has a real diff to show. */
  private fun partWithCode(): String {
    val rows=CodeDiffs.recorded(content.source.diffs,headset)
    return content.components(headset).map { it.getString("id") }.first { CodeDiffs.files(rows,it).isNotEmpty() }
  }

  @Test fun fleetRowShowsBlueprintAndSelectsTheProject() {
    val c=Calls()
    val line=LookK3.act("fleet","open_project",headset,"",content,c)
    assertEquals(listOf("show blueprint","select $headset"),c.log)
    assertEquals("fleet event=open_project project=$headset then=blueprint",line)
    // Through the real hooks: Blueprint is shown by WindowsFeature.open and opens on the selected project even if new.
    val w=RecordedWindows(content,activity);val shown=mutableListOf<String>()
    w.showWindow={ v,why -> shown+="$v $why" }
    LookK3.act("fleet","open_project",headset,"",content,LookK3.hooks(w))
    assertEquals(listOf("blueprint look_k3"),shown)
  }

  @Test fun codeReachesTheCodeEntryPointWithWhyBlueprint() {
    val part=partWithCode()
    val w=RecordedWindows(content,activity);val shown=mutableListOf<String>()
    w.showWindow={ v,why -> shown+="$v $why" }
    // Code window not created yet: the press shows it, and the part opens once it attaches.
    ShadowLog.clear()
    assertEquals("blueprint event=code part=$part",LookK3.act("blueprint","code",part,headset,content,LookK3.hooks(w)))
    assertEquals(listOf("code blueprint"),shown)
    val col=LinearLayout(activity);w.attach(col,"code","");shadowOf(Looper.getMainLooper()).idle()
    assertEquals(listOf("code event=open why=blueprint target=$part"),codeOpens())
    // Code window open: the same press goes straight to the entry point.
    ShadowLog.clear()
    LookK3.act("blueprint","code",part,headset,content,LookK3.hooks(w))
    assertEquals(listOf("code event=open why=blueprint target=$part"),codeOpens())
  }

  @Test fun askLocksThePartThroughTheYardPath() {
    val part=partWithCode();val name=content.components(headset).first { it.getString("id")==part }.optString("name")
    val w=RecordedWindows(content,activity);val asked=mutableListOf<String>()
    w.askPart={ p,m,n,_ -> asked+="$p $m $n" }
    LookK3.act("blueprint","ask",part,headset,content,LookK3.hooks(w))
    assertEquals(listOf("$headset $part $name"),asked)
  }

  @Test fun noActionHandlerOnlyLogs() {
    val js=File("src/main/assets/windows.js").readText().let { it.substring(it.indexOf("/* look K3 */"),it.indexOf("/* end look K3 */")) }
    val actions=Regex("data-act=\"([a-z_]+)\"").findAll(js).map { it.groupValues[1] }.toSet()
    assertEquals(setOf("open_project","pick","code","ask","close"),actions)
    val part=partWithCode()
    for(view in LookK3.VIEWS) for(a in actions) {
      val c=Calls()
      val line=LookK3.act(view,a,if(view=="fleet") headset else part,headset,content,c)
      if(line==null) continue  // not an action of this view
      // `pick` redraws the picked tile inside the page (windows.js) and logs; every other action calls a real path.
      if(a!="pick") assertTrue("$view $a only logs",c.log.isNotEmpty())
    }
    val fleet=Calls();assertNotNull(LookK3.act("fleet","close","fleet","",content,fleet));assertEquals(listOf("close fleet"),fleet.log)
    val bp=Calls();LookK3.act("blueprint","close","blueprint",headset,content,bp);assertEquals(listOf("close blueprint"),bp.log)
  }
}
