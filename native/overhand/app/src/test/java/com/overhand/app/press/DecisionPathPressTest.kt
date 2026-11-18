package com.overhand.app.press

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.meta.spatial.toolkit.LayoutXMLPanelRegistration
import com.meta.spatial.toolkit.PanelRegistration
import com.overhand.app.OverhandActivity
import com.overhand.app.R
import com.overhand.app.decisions.ConfirmGuard
import com.overhand.app.decisions.QuestionCard
import com.overhand.app.features.Shell
import com.overhand.app.features.StageWindows
import com.overhand.app.features.WindowLayout
import com.overhand.app.windows.ControlWalker
import com.overhand.app.windows.LookK8
import com.overhand.app.windows.RecordedContent
import java.io.File
import java.time.Duration
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Check D1 (#1411): the decision path the film ends on, pressed on one production [Shell] and judged by state.
 *
 * The briefing (LookK8, a WebView) and the question card (DecisionCards) are built by their own panel registrations.
 * JavaScript does not run on the JVM, so a briefing press enters here where the page hands it over, `AfK8.act(name,row)`
 * (runs/fix17/D1/web_presses.mjs proves the visible web buttons make exactly those calls). Every press is judged by what
 * changed: the card shown and on which question, the option marked, the receipt text, the saved answer, the history
 * lines, the window shown. Rows go to `$D1_OUT/path.tsv`; any DEAD fails the test.
 * `D1_PLANT=<control>` removes that control's click handler before its press (the planted control: the test must fail).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class DecisionPathPressTest {
  private val out=File(System.getenv("D1_OUT") ?: "../../../runs/fix17/D1/local").apply { mkdirs() }
  private val plant=System.getenv("D1_PLANT").orEmpty()
  private val looper get()=shadowOf(Looper.getMainLooper())
  private val prefNames=listOf("windows-v1","hands-onboarding-v1","hostlink-v1","overhand-audio","voice-v1")

  // ---- the Shell, as PressEverythingTest builds it (put-on done, refused transport) ----
  private fun freshShell(): Shell {
    val a=Robolectric.buildActivity(OverhandActivity::class.java).get()
    prefNames.forEach { a.getSharedPreferences(it,0).edit().clear().commit() }
    a.deleteFile("judge-receipts.json")
    // The film's state: the hands tutorial is done (the headset has answered its first card).
    a.getSharedPreferences("hands-onboarding-v1",0).edit().putBoolean("recorded",true).commit()
    val s=Shell(a)
    s.prefs
    com.overhand.app.places.Places.store=com.overhand.app.places.Places.MemoryStore()
    s.fakeHead=com.meta.spatial.core.Pose()
    val gate=s.recenter.javaClass.getDeclaredField("gate").apply { isAccessible=true }.get(s.recenter)
    gate.javaClass.getDeclaredField("placedOnce").apply { isAccessible=true }.setBoolean(gate,true)
    val client=okhttp3.OkHttpClient.Builder().addInterceptor { throw java.io.IOException("fixture transport refused") }.build()
    val link=com.overhand.app.hostlink.HostLink(com.overhand.app.hostlink.InMemoryPairedHostStore(),mayTransmit={ false },baseClient=client)
    val lazy=s.javaClass.getDeclaredField("hostLinkLazy").apply { isAccessible=true }.get(s)
    lazy.javaClass.getDeclaredField("_value").apply { isAccessible=true }.set(lazy,link)
    return s
  }
  @Suppress("UNCHECKED_CAST")
  private fun registered(s: Shell,regs: List<PanelRegistration>,id: Int): View {
    val frame=FrameLayout(s.activity)
    s.activity.layoutInflater.inflate(R.layout.web_panel,frame,true)
    val root=frame.getChildAt(0).also { frame.removeView(it) }
    val f=LayoutXMLPanelRegistration::class.java.getDeclaredField("panelSetupWithRootView").apply { isAccessible=true }
    (f.get(regs.first { it.registrationId==id }) as Function3<View,Any?,Any?,Unit>)
      .invoke(root,bare(com.meta.spatial.runtime.PanelSceneObject::class.java),bare(com.meta.spatial.core.Entity::class.java))
    return root
  }
  private fun bare(c: Class<*>): Any {
    val unsafe=Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible=true }.get(null)
    return unsafe.javaClass.getMethod("allocateInstance",Class::class.java).invoke(unsafe,c)
  }

  // ---- judging ----
  class Row(val control: String,val should: String,var did: String="",var result: String="")
  private val rows=mutableListOf<Row>()
  private val sceneStops=mutableListOf<String>()
  private fun clean(s: String)=s.replace('\t',' ').replace('\n',' ').replace(Regex("\\s+")," ").trim()
  private fun all(v: View)=ControlWalker.descendants(v)
  private fun tagged(root: View,tag: String)=all(root).filter { it.tag==tag && it.isShown }
  private fun texts(root: View)=all(root).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }
  private fun button(root: View,label: String)=all(root).filterIsInstance<Button>().firstOrNull { it.isShown && it.text.toString()==label }

  /** Runs [press]; then [judge] says what changed, true when it is what should have. */
  private fun step(control: String,should: String,press: () -> Unit,judge: () -> Pair<Boolean,String>): Boolean {
    val r=Row(control,should);rows+=r
    val ok=runCatching { press();judge() }.getOrElse { e -> false to "threw ${e.javaClass.simpleName}: ${e.message}" }
    r.did=clean(ok.second);r.result=if(ok.first) "OK" else "DEAD"
    return ok.first
  }
  /** A View press; the planted control loses its click handler first. */
  private fun click(control: String,v: View?) {
    checkNotNull(v) { "$control is not on screen" }
    if(control==plant) v.setOnClickListener(null)
    v.performClick()
  }

  @Test fun briefingNeedsYouToReceiptNextAndHistory() {
    val s=freshShell()
    val host=Robolectric.buildActivity(Activity::class.java).setup()
    try {
      val chat=registered(s,s.windows.panels(),WindowLayout.defaultSpecs.first { it.view==LookK8.VIEW }.id)
      val card=registered(s,s.interaction.panels(),R.id.decision_panel)
      val d=s.activity.resources.displayMetrics.density
      val cardW=(StageWindows.CARD_W_DP*d).toInt();val cardH=(StageWindows.CARD_H_DP*d).toInt()
      val column=LinearLayout(host.get()).apply { orientation=LinearLayout.VERTICAL }
      column.addView(chat,LinearLayout.LayoutParams(1800,2160));column.addView(card,LinearLayout.LayoutParams(cardW,cardH))
      host.get().setContentView(column)
      fun drain() { var n=0;while(n++<200) { val c=s.commands.poll() ?: break
        try { c.invoke() } catch(e: Throwable) { sceneStops+="${e.javaClass.simpleName}@${e.stackTrace.firstOrNull { it.className.startsWith("com.overhand") }}" } } }
      fun settle(ms: Long=60) {
        drain()
        column.measure(View.MeasureSpec.makeMeasureSpec(maxOf(1800,cardW),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2160+cardH,View.MeasureSpec.EXACTLY))
        column.layout(0,0,column.measuredWidth,column.measuredHeight)
        column.viewTreeObserver.dispatchOnGlobalLayout();column.viewTreeObserver.dispatchOnPreDraw()
        looper.idleFor(Duration.ofMillis(ms));drain()
      }
      fun until(what: () -> Boolean) { val end=System.nanoTime()+3_000_000_000L;settle();while(!what() && System.nanoTime()<end) { Thread.sleep(5);settle(20) } }
      settle(600)
      val decisions=s.interaction.decisions
      fun cardShown()=decisions.javaClass.getDeclaredField("visible").apply { isAccessible=true }.getBoolean(decisions)
      val web=chat.findViewWithTag<WebView>(LookK8.WEB_TAG)
      val back=chat.findViewWithTag<View>(LookK8.BACK_TAG)
      // The page the briefing actually loads, for the Chromium press check.
      shadowOf(web).lastLoadDataWithBaseURL?.data?.let { File(out,"briefing.html").writeText(it) }
      val bridge=shadowOf(web).getJavascriptInterface("AfK8")!!
      val act=bridge.javaClass.getMethod("act",String::class.java,String::class.java).apply { isAccessible=true }
      fun web(name: String,row: String) { act.invoke(bridge,name,row) }
      fun open()=RecordedContent.rows(s.sessionSource.decisions()).filter { it.optString("status")=="open" }
        .sortedBy { it.optDouble("judge_order",1e9) }
      val firstOpen=open().map { it.optString("id") }

      // 1. Needs you: the card comes up on the first waiting question.
      step("briefing/needs","card shown on the first waiting question ${firstOpen.firstOrNull()}",{ web("open","needs");until { decisions.shown()!=null } }) {
        val sh=decisions.shown()
        (cardShown() && sh?.id==firstOpen.firstOrNull()) to "card visible=${cardShown()} on ${sh?.id}; question='${tagged(card,QuestionCard.TAG_QUESTION).joinToString { (it as TextView).text }}'"
      }
      val asked=decisions.shown()
      val question=tagged(card,QuestionCard.TAG_QUESTION).map { (it as TextView).text.toString() }.firstOrNull().orEmpty()
      // 2. Option B: its ring fills, Confirm becomes pressable.
      var labelB=""
      step("card/option-b","option B marked (filled ring, selected), Confirm enabled",{
        val b=tagged(card,QuestionCard.TAG_OPTION)[1] as Button;labelB=b.text.toString()
        click("card/option-b",b);settle()
      }) {
        val opts=tagged(card,QuestionCard.TAG_OPTION)
        val marked=opts.indexOfFirst { (it.parent as ViewGroup).getChildAt(0).tag==QuestionCard.TAG_RING_ON }
        val confirm=tagged(card,QuestionCard.TAG_CONFIRM).singleOrNull()
        (marked==1 && opts[1].isSelected && confirm?.isEnabled==true && confirm.alpha==1f) to "marked option index $marked ('$labelB'); Confirm enabled=${confirm?.isEnabled}"
      }
      // 3. Confirm: a receipt with the choice, and the answer saved by the source.
      step("card/confirm","receipt 'Answer received', You said = option B, answer saved",{
        click("card/confirm",tagged(card,QuestionCard.TAG_CONFIRM).singleOrNull());until { tagged(card,QuestionCard.TAG_NEXT).isNotEmpty() }
      }) {
        val said=tagged(card,QuestionCard.TAG_YOU_SAID).map { (it as TextView).text.toString() }.firstOrNull()
        val saved=RecordedContent.rows(s.sessionSource.decisions()).firstOrNull { it.optString("id")==asked?.id }?.optJSONObject("practice_receipt")
        val savedLabel=saved?.optString("option_label")
        val header=QuestionCard.RECEIVED in texts(card)
        (header && said==labelB && savedLabel==asked?.options?.getOrNull(1)) to "header received=$header; you said='$said'; saved option_label='$savedLabel' on ${asked?.id}"
      }
      // 4. Next: the following question (the answered one has left the waiting list).
      val following=open().map { it.optString("id") }.firstOrNull()
      step("card/next","card on the following waiting question $following, receipt gone",{
        looper.idleFor(Duration.ofMillis(ConfirmGuard.QUIET_MS+100));settle()
        click("card/next",tagged(card,QuestionCard.TAG_NEXT).singleOrNull());settle()
      }) {
        val sh=decisions.shown()
        (sh!=null && sh.id==following && sh.id!=asked?.id && tagged(card,QuestionCard.TAG_NEXT).isEmpty()) to "card on ${sh?.id} (answered ${asked?.id}); Next still shown=${tagged(card,QuestionCard.TAG_NEXT).isNotEmpty()}"
      }
      // 5. More: the reading view with History.
      step("card/more","reading view shown (question card replaced, Back and History present)",{ click("card/more",tagged(card,QuestionCard.TAG_MORE).singleOrNull());settle() }) {
        val reading=tagged(card,"reading-page").isNotEmpty()
        (reading && tagged(card,QuestionCard.TAG_QUESTION).isEmpty() && button(card,"Back")!=null && button(card,"History")!=null) to
          "reading-page shown=$reading; Back=${button(card,"Back")!=null}; History=${button(card,"History")!=null}"
      }
      // 6. History: the answered question and its choice are listed (paged with Read more).
      step("card/history","history lists the answered question and option B",{ click("card/history",button(card,"History"));settle() }) {
        val seen=mutableListOf<String>()
        repeat(40) {
          seen+=texts(card).filter { it.isNotBlank() }
          val more=button(card,"Read more")?.takeIf { it.isEnabled } ?: return@repeat
          more.performClick();settle(20)
        }
        val page=seen.joinToString(" ")
        val listed=clean(page).contains(clean(question).take(40)) && clean(page).contains(clean(labelB).take(24))
        listed to "answered question listed=${clean(page).contains(clean(question).take(40))}; option B listed=${clean(page).contains(clean(labelB).take(24))}; pages read=${seen.size} lines"
      }
      // 7. Back: the question card again.
      step("card/back","question card shown again",{ click("card/back",button(card,"Back"));settle() }) {
        (tagged(card,QuestionCard.TAG_QUESTION).isNotEmpty() && tagged(card,"reading-page").isEmpty()) to "question shown=${tagged(card,QuestionCard.TAG_QUESTION).isNotEmpty()}"
      }
      // 8-9. The briefing's History and its Briefing button.
      step("briefing/history","chat list shown, briefing hidden, Briefing button shown",{ web("history","");settle() }) {
        (web.visibility==View.GONE && back.isShown && tagged(chat,"current-work").isNotEmpty()) to "web visibility=${web.visibility}; Briefing shown=${back.isShown}; current-work shown=${tagged(chat,"current-work").isNotEmpty()}"
      }
      step("briefing/back","briefing shown again, chat list hidden",{ click("briefing/back",back);settle() }) {
        (web.isShown && !back.isShown && tagged(chat,"current-work").isEmpty()) to "web shown=${web.isShown}; Briefing shown=${back.isShown}"
      }
      // 10-11. Building and Done open windows: reached up to window creation, which needs the 3D scene.
      for((row,view) in listOf("building" to "fleet","done" to "build")) {
        s.windows.voiceHidden.add(view) // closed at put-on (Dock.closedAtPutOn)
        val stops=sceneStops.size
        val ok=step("briefing/$row","$view window opened (leaves the closed set; created in the scene)",{ web("open",row);settle() }) {
          (view !in s.windows.voiceHidden) to "$view closed=${view in s.windows.voiceHidden}; stopped at ${sceneStops.drop(stops).joinToString().ifBlank { "nothing" }}"
        }
        if(ok && sceneStops.size>stops) rows.last().result="SCENE"
      }
    } finally {
      runCatching { s.stopped=true;s.voice.onDestroy();if(s.hostLinkMade) s.hostLink.shutdown();s.interaction.decisions.close();host.pause().stop().destroy() }
      File(out,"path.tsv").writeText((listOf("where\tcontrol\tshould_change\tdid_change\tresult")+
        rows.map { "jvm\t${it.control}\t${clean(it.should)}\t${it.did}\t${it.result}" }).joinToString("\n")+"\n")
      File(out,"plant.txt").writeText("plant=${plant.ifBlank { "none" }}\n")
    }
    val dead=rows.filter { it.result=="DEAD" }
    println("decision_path rows=${rows.size} dead=${dead.size} scene=${rows.count { it.result=="SCENE" }} plant=${plant.ifBlank { "none" }} out=${out.absolutePath}")
    assertTrue("dead controls on the decision path:\n"+dead.joinToString("\n") { "${it.control}: should ${it.should}; did ${it.did}" },dead.isEmpty())
  }
}
