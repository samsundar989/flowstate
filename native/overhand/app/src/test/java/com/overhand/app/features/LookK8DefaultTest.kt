package com.overhand.app.features

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.meta.spatial.core.Entity
import com.overhand.app.OverhandActivity
import com.overhand.app.data.LocalReceiptStore
import com.overhand.app.data.ModeGeneration
import com.overhand.app.data.RecordedSource
import com.overhand.app.decisions.DecisionCards
import com.overhand.app.first60.AskState
import com.overhand.app.voice.FakeUi
import com.overhand.app.voice.UiCommands
import com.overhand.app.windows.LookK8
import java.io.File
import java.nio.file.Files
import java.time.Duration
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * #1389 look K8b: the briefing is the main window's default only if every way back to Fleet still works from it
 * (the card's must-not-break list). The production Shell over a never-created OverhandActivity, the chat panel built
 * by the real Dashboards hook; commands are drained as the ECS loop would, with each window's entity standing in
 * (no Spatial SDK scene under Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class LookK8DefaultTest {
  private val looper get()=shadowOf(Looper.getMainLooper())
  private fun idle(ms: Long) { repeat((ms/20).toInt()) { Thread.sleep(1);looper.idleFor(Duration.ofMillis(20)) } }
  private fun all(v: View): List<View> = listOf(v)+(if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList())
  /** Visible up to [top]: the panel's own rule (the activity is never created, so View.isShown cannot be used). */
  private fun seen(v: View?,top: View): Boolean {
    var x: View?=v ?: return false
    while(x!=null) { if(x.visibility!=View.VISIBLE) return false;if(x===top) return true;x=x.parent as? View }
    return false
  }
  private fun layout(root: View) {
    root.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1400,View.MeasureSpec.EXACTLY))
    root.layout(0,0,1280,1400);looper.idle()
  }
  private fun logs()=ShadowLog.getLogs().map { it.msg }

  /** A set-up plain Activity hosts the panel views, so they are attached and View.post runs (as on the headset). */
  companion object { fun host(): android.app.Activity=Robolectric.buildActivity(android.app.Activity::class.java).setup().get() }
  private class Rig {
    val activity=Robolectric.buildActivity(OverhandActivity::class.java).get()
    val shell=Shell(activity)
    val column=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
    init {
      // Every window exists (as after put-on), so an open only un-hides it; Fleet starts closed (calm put-on).
      shell.layout.specs.forEach { sp -> shell.layout.entities[sp.id]=bareEntity() }
      shell.windows.voiceHidden.add("fleet")
      host().setContentView(column)
      shell.dashboards.dashboard(column,shell.layout.specs.first { it.view==LookK8.VIEW })
    }
    fun drain() { while(true) { val c=shell.commands.poll() ?: break;c() } }
    val web get()=column.findViewWithTag<WebView>(LookK8.WEB_TAG)
    val page get()=column.findViewWithTag<TextView>("recorded-page")
    fun shown(v: View?): Boolean { var x: View?=v ?: return false
      while(x!=null) { if(x.visibility!=View.VISIBLE) return false;if(x===column) return true;x=x.parent as? View };return false }
    fun briefingShown()=shown(web) && !shown(page)
    companion object {
      fun bareEntity(): Entity {
        val unsafe=Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible=true }.get(null)
        return unsafe.javaClass.getMethod("allocateInstance",Class::class.java).invoke(unsafe,Entity::class.java) as Entity
      }
    }
  }

  @Test fun mainWindowOpensOnTheBriefing() {
    ShadowLog.clear()
    val r=Rig();idle(300)
    assertTrue("recorded mode",r.shell.isRecorded())
    assertNotNull("the briefing is mounted in the main window",r.web)
    assertTrue("the briefing shows, the paged list does not",r.briefingShown())
    assertTrue(logs().contains("briefing event=shown why=mount"))
    assertNotNull("the open paths are set",LookK8.paths)
  }

  /** Must-not-break 1: the dock's Fleet button opens Fleet while the main window shows the briefing. */
  @Test fun dockFleetButtonOpensFleetFromTheBriefing() {
    ShadowLog.clear()
    val r=Rig();idle(300)
    assertFalse(r.shell.windows.isWindowOpen("fleet"))
    val pick=ControlDockFeature::class.java.getDeclaredMethod("pick",String::class.java).apply { isAccessible=true }
    pick.invoke(ControlDockFeature(r.shell),"fleet");r.drain()
    assertTrue("Fleet open after the dock press",r.shell.windows.isWindowOpen("fleet"))
    assertTrue(logs().contains("dock event=open view=fleet why=control_dock remembered=false") || logs().any { it.startsWith("dock event=open view=fleet why=control_dock") })
    assertTrue("the main window still shows the briefing",r.briefingShown())
  }

  /** Must-not-break 2: the spoken command for Fleet opens Fleet (the same call VoiceFeature's openWindow makes). */
  @Test fun spokenFleetCommandOpensFleetFromTheBriefing() {
    val src=File("src/main/java/com/overhand/app/features/VoiceFeature.kt").readText()
    assertTrue("VoiceFeature opens a window as the dock does",src.contains("else s.windows.open(key, \"voice\")"))
    for(said in listOf("open fleet")) {
      ShadowLog.clear()
      val r=Rig();idle(300)
      val ui=FakeUi()
      val res=UiCommands(ui).handle(said)
      assertEquals(said,"open_window",res.action);assertEquals(said,listOf("openWindow(fleet)"),ui.calls)
      // VoiceFeature's Ui.openWindow(key) for a window key is s.windows.open(key, "voice") (asserted above).
      ui.calls.forEach { r.shell.windows.open(it.removePrefix("openWindow(").removeSuffix(")"),"voice") }
      r.drain()
      assertTrue("\"$said\" opens Fleet",r.shell.windows.isWindowOpen("fleet"))
      assertTrue(logs().any { it.startsWith("dock event=open view=fleet why=voice") })
      assertTrue(r.briefingShown())
    }
  }

  /** The three rows' open paths as Dashboards wires them, with the log line for each. */
  @Test fun eachRowOpensItsTargetAndLogsIt() {
    ShadowLog.clear()
    val r=Rig();idle(300)
    val p=LookK8.paths!!
    assertTrue(LookK8.route("building",p));r.drain()
    assertTrue("Building opens Fleet",r.shell.windows.isWindowOpen("fleet"))
    // Done opens the Yard's own window even though the drawn site counts "build" as open.
    r.shell.windows.voiceHidden.add("build")
    assertTrue(LookK8.route("done",p));r.drain()
    assertTrue("Done opens the Yard window",logs().any { it.startsWith("dock event=open view=build why=briefing") })
    assertFalse("build no longer hidden","build" in r.shell.windows.voiceHidden)
    val opens=logs().filter { it.startsWith("briefing event=open") }
    assertEquals(listOf("briefing event=open row=building target=fleet","briefing event=open row=done target=yard"),opens)
    assertTrue(r.briefingShown())
    val dash=File("src/main/java/com/overhand/app/features/Dashboards.kt").readText()
    assertTrue("Needs you raises the question card through LookK8.raise",dash.contains("LookK8.raise(d::open,d::openIds,d::showDecision)"))
  }

  /** Must-not-break 4: History, then Briefing (its Close) returns to the briefing, not an empty window; so does a refresh. */
  @Test fun closeReturnsToTheBriefingNotAnEmptyWindow() {
    ShadowLog.clear()
    val r=Rig();idle(300)
    val bridge=shadowOf(r.web).getJavascriptInterface("AfK8")!!
    bridge.javaClass.getMethod("act",String::class.java,String::class.java).apply { isAccessible=true }.invoke(bridge,"history","");idle(200)
    assertTrue("History shows the paged list",seen(r.page,r.column));assertFalse(seen(r.web,r.column))
    val back=r.column.findViewWithTag<Button>(LookK8.BACK_TAG)
    assertTrue(seen(back,r.column));back.performClick();idle(200)
    assertTrue("Close returns to the briefing",r.briefingShown());assertFalse(seen(back,r.column))
    val visible=all(r.column).filter { it!==r.column && seen(it,r.column) }
    assertTrue("the window is not empty",visible.contains(r.web))
    // Fleet opened and closed from the dock leaves the main window on the briefing.
    r.shell.windows.dockToggle("fleet","control_dock");r.drain()
    assertTrue(r.shell.windows.isWindowOpen("fleet"))
    idle(SettingsPanel.GUARD_MS+400) // the dock's double-press guard (virtual clock)
    r.shell.windows.dockToggle("fleet","control_dock");r.drain()
    assertFalse("the second press closed Fleet",r.shell.windows.isWindowOpen("fleet"))
    assertTrue(r.briefingShown())
    r.shell.recordedWindows.refresh();idle(300)
    assertTrue("a refresh keeps the briefing",r.briefingShown())
    val l=logs().filter { it.startsWith("briefing ") }
    assertEquals(listOf("briefing event=shown why=mount","briefing event=history","briefing event=back","briefing event=shown why=back"),l)
  }

  private class Card {
    val activity=Robolectric.buildActivity(OverhandActivity::class.java).get()
    @Suppress("unused") val onboarded=activity.getSharedPreferences("hands-onboarding-v1",0).edit().putBoolean("recorded",true).commit()
    val receiptsFile: File=Files.createTempDirectory("k8b").resolve("receipts.json").toFile()
    val source=RecordedSource({ File("src/main/assets",it).readText() },LocalReceiptStore(receiptsFile),null)
    val cards=DecisionCards(activity,{ "" },{ source },ModeGeneration()) { }
    val column=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
    init {
      host().setContentView(column);cards.attach(column)
    }
    fun receipts()=if(receiptsFile.exists()) JSONArray(receiptsFile.readText()).length() else 0
  }
  private fun options(v: View)=all(v).filter { it.tag==com.overhand.app.onboarding.HandsOnboarding.OPTION_TAG }
  private fun confirm(c: Card) {
    all(c.column).filterIsInstance<Button>().first { it.text.startsWith("Confirm") }.performClick()
    repeat(300) { if(c.receipts()>=1) return;Thread.sleep(5);idle(20) }
  }
  /** The Needs you row: the card opens on the first waiting question (judge order). */
  private fun raised(c: Card): String {
    LookK8.raise(c.cards::open,c.cards::openIds,c.cards::showDecision);idle(500);layout(c.column)
    val first=c.cards.openIds()?.firstOrNull()
    assertNotNull("the open questions loaded",first)
    assertTrue("the card goes to the first waiting question",LookK8.raise(c.cards::open,c.cards::openIds,c.cards::showDecision))
    idle(300);layout(c.column)
    val shown=c.cards.shown()
    assertEquals("the card shows that question",first,shown?.id)
    return first!!
  }

  /** Must-not-break 3a: an open question raises its card from the briefing and is answered by hand (option, Confirm). */
  @Test fun needsYouRaisesTheCardAndItIsAnsweredByHand() {
    val c=Card();raised(c)
    options(c.column).first().performClick();idle(300);layout(c.column)
    confirm(c)
    assertEquals("a hand answer writes one receipt",1,c.receipts())
  }

  /** Must-not-break 3b: the same card answered by voice: "yes" picks the recommended option (AskState, the greeting's
   *  voice path), applied as First60Feature.confirm does, by the card's own option and Confirm. */
  @Test fun needsYouRaisesTheCardAndItIsAnsweredByVoice() {
    val c=Card();raised(c)
    val shown=c.cards.shown()!!
    val ask=AskState(shown.options,"A")
    ask.questionEnded(0L)
    assertEquals(AskState.Kind.YES,ask.heard(10L,"yes"))
    val a=ask.answer!!
    val i=shown.options.indexOfFirst { ask.idOf(it)==a.option }.let { if(it<0) 0 else it }
    options(c.column)[i].performClick();idle(300);layout(c.column)
    confirm(c)
    assertEquals("a voice answer writes one receipt",1,c.receipts())
  }
}
