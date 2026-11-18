package com.overhand.app.decisions

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import com.overhand.app.data.*
import com.overhand.app.design.CardTags
import com.overhand.app.windows.PanelPolish
import java.io.File
import java.nio.file.Files
import java.time.Duration
import kotlin.math.hypot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog

/**
 * #874: the decision card drawn with Compose (DecisionCards.compose), against the same evidence as the View card.
 *  - developer's six taps (DeadTapReplayTest: same durations, gaps, fitted x, y 12 px inside Confirm's bottom edge,
 *    2260 ms after the receipt's Next decision): each must change pixels within 300 px and leave an OverhandTouch up line.
 *  - Switch off: the View card, unchanged.
 *  - Layout lint capture: each card state as <window>.png + <window>.json, elements from the Compose semantics tree.
 * Metrics land in [out] (system property composeCard.out, default build/compose_card).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w1600dp-h800dp-288dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposeCardTest {
  @get:Rule(order = 0) val componentActivity = com.overhand.app.testing.ComponentActivityRule()
  @get:Rule(order = 1) val composeRule = createComposeRule()
  private val out=File(System.getProperty("composeCard.out") ?: "build/compose_card").apply { mkdirs() }
  private val taps=listOf(Triple(2260L,112L,524f),Triple(3742L,112L,511f),Triple(4989L,111L,507f),
    Triple(6360L,109L,486f),Triple(7105L,58L,486f),Triple(7986L,55L,487f))
  private val looper get()=shadowOf(Looper.getMainLooper())
  private lateinit var column: LinearLayout
  private lateinit var cards: DecisionCards
  private var density=1.8f

  private fun all(v: View): List<View> = listOf(v)+(if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList())
  /** Advances the paused main looper. Snapshot writes are announced each step, as GlobalSnapshotManager does on a device
   *  (its dispatcher is a static bound to the first test's Choreographer, so later tests in the sandbox never recompose). */
  private fun idle(ms: Long) { val end=SystemClock.uptimeMillis()+ms
    do { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
      val step=maxOf(0L,minOf(10L,end-SystemClock.uptimeMillis()))
      looper.idleFor(Duration.ofMillis(step))
      // The rule's recomposer runs on its test clock, not the looper: advance it by the same step (exact, not frame-rounded).
      composeRule.mainClock.advanceTimeBy(step,ignoreFrameDuration=true) } while(SystemClock.uptimeMillis()<end) }
  private fun shot(): Bitmap=Bitmap.createBitmap(column.width,column.height,Bitmap.Config.ARGB_8888).also { column.draw(Canvas(it)) }
  private fun tap(x: Float,y: Float,ms: Long,travel: Float=6f) {
    val t0=SystemClock.uptimeMillis()
    column.dispatchTouchEvent(MotionEvent.obtain(t0,t0,MotionEvent.ACTION_DOWN,x,y,0))
    val steps=maxOf(1,(ms/30).toInt())
    for(i in 1..steps) { idle(ms/(steps+1));column.dispatchTouchEvent(MotionEvent.obtain(t0,SystemClock.uptimeMillis(),MotionEvent.ACTION_MOVE,x,y+travel*i/steps,0)) }
    idle(ms-ms/(steps+1)*steps)
    column.dispatchTouchEvent(MotionEvent.obtain(t0,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,x,y+travel,0))
  }
  private fun waitFor(what: () -> Boolean) { repeat(300) { if(what()) return;Thread.sleep(10);idle(16) };fail("timed out") }

  private fun composeView()=all(column).filterIsInstance<ComposeView>().firstOrNull()
  @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
  private fun nodes(merged: Boolean=true): List<SemanticsNode> {
    val r=(composeView()?.takeIf { it.childCount>0 }?.getChildAt(0) as? RootForTest) ?: return emptyList()
    // Robolectric's paused Choreographer does not reliably run a view traversal, so a node composed since the last draw
    // (the receipt's Next decision) is unplaced: no bounds, absent from semantics. Lay out first, as the Compose test rule does.
    r.measureAndLayoutForTest()
    fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n)+n.children.flatMap { walk(it) }
    return walk(if(merged) r.semanticsOwner.rootSemanticsNode else r.semanticsOwner.unmergedRootSemanticsNode)
  }
  private fun text(n: SemanticsNode)=n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
  /** A node's box in [column]'s pixels. */
  private fun box(n: SemanticsNode): RectF {
    val cv=composeView()!!;val a=IntArray(2);val b=IntArray(2);cv.getLocationInWindow(a);column.getLocationInWindow(b)
    val r=n.boundsInRoot;return RectF(a[0]-b[0]+r.left,a[1]-b[1]+r.top,a[0]-b[0]+r.right,a[1]-b[1]+r.bottom)
  }
  /** What the card shows, for a failed lookup: the switch, the ComposeView, its clickables, the View card's buttons, the trace. */
  private fun dump()="compose=${cards.compose} view=${composeView()?.let { "attached=${it.isAttachedToWindow} children=${it.childCount}" }} "+
    "clickables=${nodes().filter { it.config.contains(SemanticsActions.OnClick) }.map { (it.config.getOrNull(SemanticsProperties.TestTag) ?: "")+"'"+text(it)+"'"+it.boundsInRoot.let { b -> "[${b.left.toInt()},${b.top.toInt()},${b.right.toInt()},${b.bottom.toInt()}]" } }} "+
    "buttons=${all(column).filterIsInstance<Button>().map { it.text }} touch=${ShadowLog.getLogsForTag("OverhandTouch").takeLast(4).map { it.msg }} "+
    "card=${ShadowLog.getLogsForTag("DecisionCards").takeLast(4).map { it.msg }}"
  private fun node(what: (SemanticsNode) -> Boolean)=nodes().firstOrNull { it.config.contains(SemanticsActions.OnClick) && what(it) }
    ?.also { if(it.boundsInRoot.width<1f) throw AssertionError("node has no size: "+dump()) } ?: throw AssertionError("node not found: "+dump())
  private fun option(i: Int)=nodes().filter { it.config.getOrNull(SemanticsProperties.TestTag)==CardTags.OPTION }.sortedBy { it.boundsInRoot.left }.getOrNull(i) ?: throw AssertionError("option $i not found: "+dump())
  private fun confirm()=node { it.config.getOrNull(SemanticsProperties.TestTag)==CardTags.CONFIRM }
  private fun labelled(s: String)=node { text(it).contains(s) }

  /** The live card in a 1000 x 720 dp panel (1800 x 1296 px), Recorded, compose switch as given. */
  private fun build(compose: Boolean) {
    ShadowLog.clear()
    // The Compose rule owns a fresh window recomposer for each test, including this manually attached panel.
    // Clearing AndroidUiDispatcher.currentThread did not reset its separately cached Main dispatcher.
    val overhand=Robolectric.buildActivity(com.overhand.app.OverhandActivity::class.java).get()
    overhand.getSharedPreferences("hands-onboarding-v1",0).edit().putBoolean("recorded",true).commit()
    val source=RecordedSource({ File("src/main/assets/$it").readText() },
      LocalReceiptStore(Files.createTempDirectory("composecard").resolve("receipts.json").toFile()),null)
    density=overhand.resources.displayMetrics.density
    column=LinearLayout(overhand).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.BLACK) }
    val host=Robolectric.buildActivity(Activity::class.java).setup().get()
    host.setContentView(FrameLayout(host).apply { addView(column,FrameLayout.LayoutParams((1000*density).toInt(),(720*density).toInt())) })
    val vri=View::class.java.getMethod("getViewRootImpl").invoke(column.rootView)
    vri.javaClass.getDeclaredMethod("ensureTouchMode",Boolean::class.javaPrimitiveType).apply { isAccessible=true }.invoke(vri,true)
    org.robolectric.shadows.ShadowChoreographer.setPaused(true)
    org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    cards=DecisionCards(overhand,{ "" },{ source },ModeGeneration()) { }
    cards.compose=compose
    cards.attach(column);cards.open();PanelPolish.install(column,80,24)
    idle(500)
  }

  data class Result(val i: Int,val x: Float,val y: Float,val view: String,val outcome: String,val nearChanged: Int,val traced: Boolean)

  @Test fun DevelopersSixTapsOnTheComposeCardEachGetAVisibleResponseAndATrace() {
    build(compose=true)
    assertNotNull("compose card drawn",composeView())
    val ups=mutableListOf<String>()
    fun upsSince(n: Int)=ShadowLog.getLogsForTag("OverhandTouch").drop(n).filter { it.msg.startsWith("up ") }.map { it.msg }
    // Card 16: pinch option A, then Confirm, then the receipt's Next decision 5 s later (as DeadTapReplayTest).
    var n=ShadowLog.getLogsForTag("OverhandTouch").size
    box(option(0)).let { tap(it.centerX(),it.centerY(),58) };idle(200);ups+=upsSince(n)
    idle(1300)
    n=ShadowLog.getLogsForTag("OverhandTouch").size
    box(confirm()).let { tap(it.centerX(),it.centerY(),110) };idle(200);ups+=upsSince(n)
    waitFor { nodes().any { text(it).contains("Next decision") } }
    idle(5000)
    n=ShadowLog.getLogsForTag("OverhandTouch").size
    box(labelled("Next decision")).let { tap(it.centerX(),it.centerY(),56) }
    idle(100);ups+=upsSince(n)
    val setup=ups.toList()
    var last=100L
    val c=box(confirm())
    val r=taps.mapIndexed { i,(at,ms,x) ->
      idle(at-last);last=at+ms+DeadTapReplayTest.AFTER_MS
      val y=c.bottom-12f
      val logsBefore=ShadowLog.getLogsForTag("OverhandTouch").size
      val before=shot()
      tap(x,y,ms)
      idle(DeadTapReplayTest.AFTER_MS)
      val after=shot()
      var near=0
      for(py in 0 until before.height step 2) for(px in 0 until before.width step 2)
        if(before.getPixel(px,py)!=after.getPixel(px,py) && hypot(px-x,py-y)<=DeadTapReplayTest.NEAR_PX) near++
      val lines=ShadowLog.getLogsForTag("OverhandTouch").drop(logsBefore).map { it.msg }
      lines.forEach { println("COMPOSETAP trace OverhandTouch: $it") }
      val up=lines.firstOrNull { it.startsWith("up ") && it.contains("outcome=") }
      Result(i+1,x,y,up?.substringAfter("view=")?.substringBefore(' ').orEmpty(),up?.substringAfter("outcome=").orEmpty(),near,up!=null)
        .also { println("COMPOSETAP $it") }
      .also { if(i==0) { File(out,"tap1_before.png").outputStream().use { o -> before.compress(Bitmap.CompressFormat.PNG,100,o) }
        File(out,"tap1_after.png").outputStream().use { o -> after.compress(Bitmap.CompressFormat.PNG,100,o) } } }
    }
    val dead=r.count { it.nearChanged==0 }
    val logged=r.count { it.traced }.toDouble()/r.size
    val setupLogged=setup.count { it.contains("outcome=") }
    File(out,"replay.json").writeText(JSONObject().put("compose_card_dead_taps_replay",dead).put("compose_card_outcome_logged",logged)
      .put("setup_taps",JSONArray(setup)).put("taps",JSONArray(r.map { JSONObject().put("i",it.i).put("x",it.x.toDouble()).put("y",it.y.toDouble())
        .put("view",it.view).put("outcome",it.outcome).put("near_changed_px",it.nearChanged).put("traced",it.traced) }))
      .put("confirm_box_px",JSONArray(listOf(c.left,c.top,c.right,c.bottom).map { it.toDouble() })).toString(1))
    println("COMPOSETAP compose_card_dead_taps_replay=$dead compose_card_outcome_logged=$logged setup=$setup")
    // The setup taps did what they do on the View card: select, confirm, next.
    assertEquals("setup outcomes: $setup",listOf("select","confirm","next"),setup.map { it.substringAfter("outcome=") })
    assertEquals("dead taps: $r",0,dead)
    assertEquals("taps with an OverhandTouch up line: $r",1.0,logged,0.0)
    // Same rule as the View card: Confirm with nothing selected is refused as no_selection, on Confirm.
    assertTrue("outcomes: $r",r.all { it.outcome=="none:no_selection" && it.view=="confirm_selected_option" })
  }

  @Test fun switchOffIsTheViewCard() {
    build(compose=false)
    assertNull("no ComposeView with the switch off",composeView())
    val texts=all(column).filterIsInstance<Button>().filter { it.isShown }.map { it.text.toString() }
    assertTrue(texts.toString(),texts.contains(QuestionCard.CONFIRM) && texts.contains(QuestionCard.MORE))
    // On, then off again: back to exactly the View card's controls.
    cards.compose=true;idle(300);assertNotNull(composeView())
    cards.compose=false;idle(300);assertNull(composeView())
    assertEquals(texts,all(column).filterIsInstance<Button>().filter { it.isShown }.map { it.text.toString() })
  }

  @Test fun pinchingTheComposeOptionSelectsThroughDecisionCards() {
    build(compose=true)
    box(option(1)).let { tap(it.centerX(),it.centerY(),80) };idle(300)
    val up=ShadowLog.getLogsForTag("OverhandTouch").last { it.msg.startsWith("up ") }.msg
    assertTrue(up,up.contains("view=option:B") && up.endsWith("outcome=select"))
    // UI Set's tile does not publish SemanticsProperties.Selected. Verify the shared selection through its result:
    // a real Confirm pinch must save B, not the first/recommended option or a no-selection refusal.
    val choice=text(option(1)).substringBefore('\n')
    box(confirm()).let { tap(it.centerX(),it.centerY(),110) }
    waitFor { nodes().any { text(it).contains("Next decision") } }
    val saved=nodes(merged=false).filter { it.config.getOrNull(SemanticsProperties.TestTag)==CardTags.SAVED_CHOICE }
    assertEquals(1,saved.size)
    assertTrue("saved choice: ${saved.map(::text)} expected B ($choice)",text(saved.single()).startsWith("B:"))
  }

  // ---- layout lint capture --------------------------------------------------------------------------------------

  /** One card state as the lint's <window>.png + <window>.json; elements from the unmerged semantics tree. */
  private fun capture(name: String) {
    idle(600)
    val bmp=shot()
    File(out,"capture").mkdirs()
    File(out,"capture/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG,100,it) }
    val w=bmp.width;val h=bmp.height
    val els=JSONArray()
    els.put(JSONObject().put("index",0).put("parent",-1).put("name","ComposeView[card-compose]@0").put("class","androidx.compose.ui.platform.ComposeView")
      .put("tag","card-compose").put("rect",JSONArray(listOf(0,0,w,h))).put("visible_rect",JSONArray(listOf(0,0,w,h))).put("clickable",false))
    val cv=composeView()!!;val a=IntArray(2);val b=IntArray(2);cv.getLocationInWindow(a);column.getLocationInWindow(b)
    val ox=(a[0]-b[0]).toFloat();val oy=(a[1]-b[1]).toFloat()
    fun walk(n: SemanticsNode,parent: Int,scroll: Boolean) {
      val i=els.length()
      val pos=n.positionInRoot;val r=listOf(ox+pos.x,oy+pos.y,ox+pos.x+n.size.width,oy+pos.y+n.size.height)
      val vb=n.boundsInRoot;val vis=listOf(ox+vb.left,oy+vb.top,ox+vb.right,oy+vb.bottom).let { v ->
        listOf(maxOf(0f,v[0]),maxOf(0f,v[1]),minOf(w.toFloat(),v[2]),minOf(h.toFloat(),v[3])) }
      val shown=vis[2]-vis[0]>0.5f && vis[3]-vis[1]>0.5f
      val tag=n.config.getOrNull(SemanticsProperties.TestTag)
      val isScroll=n.config.contains(SemanticsProperties.VerticalScrollAxisRange) || n.config.contains(SemanticsProperties.HorizontalScrollAxisRange)
      val t=text(n)
      val o=JSONObject().put("index",i).put("parent",parent).put("name","Compose${tag?.let { "[$it]" } ?: ""}${if(t.isNotEmpty()) "'${t.take(40)}'" else ""}@$i")
        .put("class","compose:"+(n.config.getOrNull(SemanticsProperties.Role)?.toString() ?: if(t.isNotEmpty()) "Text" else "Node"))
        .put("tag",tag ?: JSONObject.NULL).put("rect",JSONArray(r.map { it.toDouble() }))
        .put("visible_rect",if(shown) JSONArray(vis.map { it.toDouble() }) else JSONObject.NULL)
        .put("clickable",n.config.contains(SemanticsActions.OnClick) && !n.config.contains(SemanticsProperties.Disabled))
        .put("scroll_container",isScroll)
      if(t.isNotEmpty()) {
        val results=mutableListOf<TextLayoutResult>()
        n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        results.firstOrNull()?.let { tl ->
          val style=tl.layoutInput.style
          o.put("text",t)
          if(style.fontSize.isSp) o.put("text_size_px",(style.fontSize.value*tl.layoutInput.density.fontScale*tl.layoutInput.density.density).toDouble())
          if(style.color.isSpecified) o.put("text_color",String.format("#%08X",style.color.toArgb()))
          o.put("gravity_h","start").put("button",false).put("max_lines",tl.layoutInput.maxLines)
          val lines=JSONArray()
          for(k in 0 until tl.lineCount) {
            val end=tl.getLineEnd(k)
            lines.put(JSONObject().put("start",tl.getLineStart(k)).put("end",end).put("text",t.substring(tl.getLineStart(k).coerceAtMost(t.length),end.coerceAtMost(t.length)))
              .put("left",(r[0]+tl.getLineLeft(k)).toDouble()).put("right",(r[0]+tl.getLineRight(k)).toDouble())
              .put("top",(r[1]+tl.getLineTop(k)).toDouble()).put("bottom",(r[1]+tl.getLineBottom(k)).toDouble())
              .put("baseline",(r[1]+tl.getLineBaseline(k)).toDouble())
              .put("ellipsis",if(tl.isLineEllipsized(k)) maxOf(1,t.length-tl.getLineEnd(k,visibleEnd=true)) else 0))
          }
          o.put("inner",JSONArray(r.map { it.toDouble() })).put("layout_height",tl.size.height).put("lines",lines)
          // Characters laid out past the last visible line. Clipping in either direction is the lint's own geometry check
          // (lines vs inner); hasVisualOverflow alone is true for every single-line UI Set label and drops nothing.
          val dropped=t.length-tl.getLineEnd(tl.lineCount-1,visibleEnd=true)
          if(dropped>0 && (0 until tl.lineCount).none { tl.isLineEllipsized(it) }) o.put("chars_dropped",dropped)
        } ?: o.put("text",t)
      }
      els.put(o)
      n.children.forEach { walk(it,i,isScroll || scroll) }
    }
    walk(nodes(merged=false).first(),0,false)
    val meta=JSONObject().put("window",name).put("kind","android-view").put("source","DecisionCards.compose + FlowstateDecisionCard (#874)")
      .put("width_px",w).put("height_px",h).put("width_dp",1000.0).put("height_dp",720.0).put("density",density.toDouble())
      .put("width_m",1.0).put("height_m",0.72).put("m_per_px",1.0/w).put("distance_m",0.85).put("grid",false)
      .put("push_distance_m",com.overhand.app.windows.PolishRules.pushed(0.85)).put("elements",els)
    File(out,"capture/$name.json").writeText(meta.toString(1))
  }

  @Test fun captureComposeCardStatesForTheLayoutLint() {
    build(compose=true)
    capture("compose_card_open")
    box(confirm()).let { tap(it.centerX(),it.bottom-12f,110) }   // refused: notice + pulse
    idle(1500);capture("compose_card_refused")
    box(option(0)).let { tap(it.centerX(),it.centerY(),58) };capture("compose_card_selected")
    box(confirm()).let { tap(it.centerX(),it.centerY(),110) }
    waitFor { nodes().any { text(it).contains("Next decision") } };capture("compose_card_receipt")
    idle(5000);box(labelled("Next decision")).let { tap(it.centerX(),it.centerY(),56) };idle(300)
    nodes().firstOrNull { it.config.contains(SemanticsActions.OnClick) && text(it).contains("Answer history") }?.let { h ->
      box(h).let { tap(it.centerX(),it.centerY(),60) };capture("compose_card_history") }
    println("COMPOSELINT captured=${File(out,"capture").list()?.count { it.endsWith(".json") }} out=${File(out,"capture").absolutePath}")
  }
}
