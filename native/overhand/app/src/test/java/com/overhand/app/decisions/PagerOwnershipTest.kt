package com.overhand.app.decisions

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.overhand.app.data.*
import com.overhand.app.features.StageWindows
import com.overhand.app.windows.*
import java.io.File
import java.nio.file.Files
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises renderPaged's actual shared observer, without relying on collection of old views. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w1600dp-h1200dp-288dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PagerOwnershipTest {
  private fun all(v: View): List<View> = listOf(v)+(if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList())
  private fun pagers(v: View): List<ReadingPages> {
    val observer=v.viewTreeObserver
    val f=observer.javaClass.getDeclaredField("mOnPreDrawListeners").apply { isAccessible=true }
    val array=f.get(observer) ?: return emptyList()
    val data=array.javaClass.getDeclaredMethod("getArray").apply { isAccessible=true }.invoke(array) as List<*>
    return data.mapNotNull { listener ->
      if(listener is ReadingPages) listener else listener?.javaClass?.declaredFields?.firstNotNullOfOrNull { field ->
        field.isAccessible=true;field.get(listener) as? ReadingPages
      }
    }
  }
  private fun fixture(test: (DecisionCards,LinearLayout,FrameLayout,()->Unit)->Unit) {
    val overhand=Robolectric.buildActivity(com.overhand.app.OverhandActivity::class.java).get()
    overhand.getSharedPreferences("hands-onboarding-v1",0).edit().putBoolean("recorded",true).commit()
    val source=RecordedSource({ File("src/main/assets/$it").readText() },LocalReceiptStore(Files.createTempDirectory("pager-owner").resolve("receipts.json").toFile()),null)
    val host=Robolectric.buildActivity(Activity::class.java).setup().get()
    val column=LinearLayout(overhand).apply { orientation=LinearLayout.VERTICAL }
    val frame=FrameLayout(host).apply { addView(column,FrameLayout.LayoutParams(-1,-1)) };host.setContentView(frame)
    val d=overhand.resources.displayMetrics.density
    fun settle() {
      repeat(4) {
        frame.measure(View.MeasureSpec.makeMeasureSpec((StageWindows.CARD_W_DP*d).toInt(),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec((StageWindows.CARD_H_DP*d).toInt(),View.MeasureSpec.EXACTLY))
        frame.layout(0,0,frame.measuredWidth,frame.measuredHeight)
        frame.viewTreeObserver.dispatchOnGlobalLayout();frame.viewTreeObserver.dispatchOnPreDraw()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
      }
    }
    val cards=DecisionCards(overhand,{ "" },{ source },ModeGeneration()) { }
    try {
      cards.attach(column);cards.open();PanelPolish.install(column,StageWindows.CARD_TARGET,PolishRules.NEAR_GAP_DP,StageWindows.CARD_TYPE)
      repeat(100) { settle();column.findViewWithTag<Button>(QuestionCard.TAG_MORE)?.performClick();if(column.findViewWithTag<View>("reading-page")!=null) return@repeat;Thread.sleep(5) }
      assertNotNull(column.findViewWithTag<View>("reading-page"));test(cards,column,frame,::settle)
    } finally { cards.close() }
  }
  @Test fun readingTargetsAreEntirelyInLowerHalf() = fixture { _,column,_,settle ->
    settle()
    val origin=IntArray(2);column.getLocationOnScreen(origin)
    val buttons=all(column).filterIsInstance<Button>()
    assertEquals(2,buttons.count { it.text=="Read back" || it.text=="Read more" })
    buttons.filter { it.text!="Back" }.forEach { b ->
      val xy=IntArray(2);b.getLocationOnScreen(xy)
      assertTrue("${b.text}: top=${xy[1]-origin[1]} midpoint=${column.height/2}",xy[1]-origin[1]>=column.height/2)
      assertTrue("${b.text}: bottom=${xy[1]-origin[1]+b.height} height=${column.height}",xy[1]-origin[1]+b.height<=column.height)
      assertTrue("${b.text}: text clipped",b.layout.height+b.totalPaddingTop+b.totalPaddingBottom<=b.height)
      val visible=android.graphics.Rect()
      assertTrue(b.getGlobalVisibleRect(visible))
      assertEquals("${b.text}: ancestor clips target height",b.height,visible.height())
      assertEquals("${b.text}: ancestor clips target width",b.width,visible.width())
    }
  }
  @Test fun onlyCurrentAttachedPagerReceivesCallbacks() = fixture { cards,column,frame,settle ->
    val history=mutableListOf<Pair<ReadingPages,Int>>()
    val raw=mutableListOf<String>()
    var maximum=0
    var stale=0
    fun check(step: String) {
      settle()
      val active=pagers(frame);maximum=maxOf(maximum,active.size)
      raw.add("$step registrations=${active.size} calls=${active.map { it.layoutCalls }} disposed=${history.map { it.first.layoutCalls-it.second }}")
      System.getenv("PAGER_OUT")?.let { File(it).apply { parentFile.mkdirs();writeText(raw.joinToString("\n")) } }
      history.forEach { (old,calls) -> stale+=old.layoutCalls-calls }
      assertTrue("no active pager at $step",active.isNotEmpty())
      val before=active.last().layoutCalls;frame.viewTreeObserver.dispatchOnPreDraw()
      assertTrue("current pager stopped",active.last().layoutCalls>before)
    }
    check("initial")
    repeat(100) { i ->
      val old=pagers(frame).last();val count=old.layoutCalls
      val label=when(i%4) { 0 -> "Select 1";1 -> "History";2 -> "Next";else -> "Previous" }
      all(column).filterIsInstance<Button>().first { it.text==label }.performClick()
      history.add(old to count);check("selection-$i")
    }
    fun transition(name: String,action: ()->Unit) {
      val old=pagers(frame).last();val count=old.layoutCalls
      action();history.add(old to count);check(name)
    }
    transition("hands-visible") { cards.frame(emptyMap(),true,1) }
    transition("no-hands") { cards.frame(emptyMap(),true,0) }
    transition("unselected-question") { all(column).filterIsInstance<Button>().first { it.text=="Next" }.performClick() }
    transition("notice") { all(column).filterIsInstance<Button>().first { it.text=="Confirm selected option" }.performClick() }
    transition("selected") { all(column).filterIsInstance<Button>().first { it.text=="Select 1" }.performClick() }
    // The save is asynchronous; retain the last callback count once the actual receipt has replaced its pager.
    val saving=pagers(frame).last()
    all(column).filterIsInstance<Button>().first { it.text=="Confirm selected option" }.performClick()
    repeat(300) { settle();if(all(column).filterIsInstance<Button>().none { it.text=="Next decision" }) Thread.sleep(10) }
    assertTrue("receipt not rendered: "+org.robolectric.shadows.ShadowLog.getLogsForTag(DecisionCards.TAG).takeLast(12).map { it.msg }+" buttons="+all(column).filterIsInstance<Button>().map { it.text },all(column).filterIsInstance<Button>().any { it.text=="Next decision" })
    history.add(saving to saving.layoutCalls);check("receipt")
    transition("receipt-next") { all(column).filterIsInstance<Button>().first { it.text=="Next decision" }.performClick() }
    val beforeResize=pagers(frame).last().layoutCalls
    column.layoutParams=column.layoutParams.apply { width=(StageWindows.CARD_W_DP*column.resources.displayMetrics.density*0.9f).toInt() }
    check("layout-width-change")
    assertTrue(pagers(frame).last().layoutCalls>beforeResize)
    column.layoutParams=column.layoutParams.apply { width=-1 };check("layout-width-restored")
    val current=pagers(frame).last();frame.removeView(column);val count=current.layoutCalls
    frame.viewTreeObserver.dispatchOnPreDraw();assertEquals(count,current.layoutCalls)
    assertEquals(0,pagers(frame).size)
    frame.addView(column);check("reattach");check("repeat-attach-settle")
    val old=pagers(frame).last();val before=old.layoutCalls
    all(column).filterIsInstance<Button>().first { it.text=="Close" }.performClick()
    frame.viewTreeObserver.dispatchOnPreDraw();assertEquals(before,old.layoutCalls);assertEquals(0,pagers(frame).size)
    history.add(old to before);cards.open();column.findViewWithTag<Button>(QuestionCard.TAG_MORE)?.performClick();check("reopen")
    val final=pagers(frame).last();val finalCount=final.layoutCalls;cards.close()
    frame.viewTreeObserver.dispatchOnPreDraw();assertEquals(finalCount,final.layoutCalls);assertEquals(0,pagers(frame).size)
    assertEquals("callbacks on disposed pagers",0,stale)
    assertTrue("registrations maximum=$maximum",maximum<=1)
  }
}
