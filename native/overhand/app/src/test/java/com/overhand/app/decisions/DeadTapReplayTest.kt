package com.overhand.app.decisions

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.overhand.app.data.*
import com.overhand.app.windows.PanelPolish
import java.io.File
import java.nio.file.Files
import java.time.Duration
import kotlin.math.hypot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog

/**
 * #800: developer's six dead pinches on card 17 (worn2b, Nov 12, t 15702923-15708704), replayed as touches on the real
 * card views at the decision panel's size (1000 x 720 dp, 1.8 px/dp).
 *
 * Where they landed: runs/headset/t_deadtaps/where_taps_landed.py fits panel position to his pinch point from the
 * pinches whose target the log names. The six sit on Confirm's lower edge (fit y 1187-1237 px; Confirm 1057-1201),
 * 12-15 cm of hand height below every option pinch. Each produced Android's click sound (HAS_HasMixer add_input
 * about 70 ms after PinchObserver "Pinch ended", as before every working select), so each reached a click handler.
 * The replay therefore taps Confirm at the fitted x, with y clamped 12 px inside its bottom edge, with his
 * durations and gaps, starting 2260 ms after the receipt's Next decision (15700663 -> 15702923).
 *
 * A tap is dead when no pixel within [NEAR_PX] of the tap point differs between just before the down and
 * [AFTER_MS] after the up: whatever happened, the wearer looking at his hand sees nothing. [AFTER_MS] is past the
 * themed control's own press glow (64 ms unpress + 400 ms fade, Theme.HOVER_OUT_MS), which every press gets whatever it did.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w1600dp-h1200dp-288dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DeadTapReplayTest {
  /** (start offset ms after the Next decision release, pinch ms, fitted panel x px) from where_taps_landed.txt. */
  private val taps=listOf(Triple(2260L,112L,524f),Triple(3742L,112L,511f),Triple(4989L,111L,507f),
    Triple(6360L,109L,486f),Triple(7105L,58L,486f),Triple(7986L,55L,487f))
  private val looper get()=shadowOf(Looper.getMainLooper())
  private lateinit var column: LinearLayout
  private fun all(v: View): List<View> = listOf(v)+(if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList())
  private fun buttons()=all(column).filterIsInstance<Button>().filter { it.isShown }
  private fun button(prefix: String)=buttons().first { it.text.toString().startsWith(prefix) }
  private fun box(v: View): android.graphics.Rect {
    val a=IntArray(2);val b=IntArray(2);v.getLocationInWindow(a);column.getLocationInWindow(b)
    return android.graphics.Rect(a[0]-b[0],a[1]-b[1],a[0]-b[0]+v.width,a[1]-b[1]+v.height)
  }
  /** Advance the clock by [ms], running what falls due. Small steps: one long idleFor overshoots by hundreds of ms here. */
  private fun idle(ms: Long) { val end=SystemClock.uptimeMillis()+ms;while(SystemClock.uptimeMillis()<end) looper.idleFor(Duration.ofMillis(minOf(10L,end-SystemClock.uptimeMillis()))) }
  private fun shot(): Bitmap=Bitmap.createBitmap(column.width,column.height,Bitmap.Config.ARGB_8888).also { column.draw(Canvas(it)) }
  /** A pinch as the panel receives it: down, moves of [travel] px spread over the hold, up. */
  private fun tap(x: Float,y: Float,ms: Long,travel: Float=6f) {
    val t0=SystemClock.uptimeMillis()
    column.dispatchTouchEvent(MotionEvent.obtain(t0,t0,MotionEvent.ACTION_DOWN,x,y,0))
    val steps=maxOf(1,(ms/30).toInt())
    for(i in 1..steps) { idle(ms/(steps+1));column.dispatchTouchEvent(MotionEvent.obtain(t0,SystemClock.uptimeMillis(),MotionEvent.ACTION_MOVE,x,y+travel*i/steps,0)) }
    idle(ms-ms/(steps+1)*steps)
    column.dispatchTouchEvent(MotionEvent.obtain(t0,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,x,y+travel,0))
  }
  private fun waitFor(what: () -> Boolean) {
    repeat(200) { if(what()) return;Thread.sleep(10);idle(20) }
    fail("timed out: "+ShadowLog.getLogsForTag("OverhandTouch").joinToString("; ") { it.msg }+" card="+ShadowLog.getLogsForTag("OverhandCard").joinToString("; ") { it.msg })
  }
  data class Result(val i: Int,val x: Float,val y: Float,val nearChanged: Int,val farChanged: Int,val traced: Boolean,val unavailable: Boolean)

  private fun replay(): List<Result> {
    ShadowLog.clear()
    val overhand=Robolectric.buildActivity(com.overhand.app.OverhandActivity::class.java).get()
    overhand.getSharedPreferences("hands-onboarding-v1",0).edit().putBoolean("recorded",true).commit()
    val source=RecordedSource({ File("src/main/assets/$it").readText() },
      LocalReceiptStore(Files.createTempDirectory("deadtap").resolve("receipts.json").toFile()),null)
    val d=overhand.resources.displayMetrics.density
    column=LinearLayout(overhand).apply { orientation=LinearLayout.VERTICAL }
    val host=Robolectric.buildActivity(Activity::class.java).setup().get()
    host.setContentView(FrameLayout(host).apply { addView(column,FrameLayout.LayoutParams((com.overhand.app.features.StageWindows.CARD_W_DP*d).toInt(),(com.overhand.app.features.StageWindows.CARD_H_DP*d).toInt())) })
    // A panel receives touches, so its window is in touch mode (no focus highlight left on a pressed button).
    val vri=View::class.java.getMethod("getViewRootImpl").invoke(column.rootView)
    vri.javaClass.getDeclaredMethod("ensureTouchMode",Boolean::class.javaPrimitiveType).apply { isAccessible=true }.invoke(vri,true)
    // An unpaused Choreographer advances the fake clock on every frame, so an animating view makes idle(520) run
    // 1425 ms (job 5338). Paused, frames run every 16 ms and the clock moves only when the replay moves it.
    org.robolectric.shadows.ShadowChoreographer.setPaused(true)
    org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    val cards=DecisionCards(overhand,{ "" },{ source },ModeGeneration()) { }
    cards.attach(column);cards.open();PanelPolish.install(column,com.overhand.app.features.StageWindows.CARD_TARGET,com.overhand.app.windows.PolishRules.NEAR_GAP_DP,com.overhand.app.features.StageWindows.CARD_TYPE)
    idle(500)
    // Card 16: pinch option A, then Confirm, as he did; the receipt's Next decision 5 s later.
    box(buttons().first { it.tag==com.overhand.app.onboarding.HandsOnboarding.OPTION_TAG }).let { tap(it.exactCenterX(),it.exactCenterY(),58) }
    idle(1500)
    box(button("Confirm")).let { tap(it.exactCenterX(),it.exactCenterY(),110) }
    waitFor { buttons().any { it.tag==QuestionCard.TAG_NEXT } }
    idle(5000)
    box(column.findViewWithTag<Button>(QuestionCard.TAG_NEXT)).let { tap(it.exactCenterX(),it.exactCenterY(),56) }
    idle(100)
    var last=100L
    val confirmButton=button("Confirm")
    assertFalse("Ask must visibly disable Confirm",confirmButton.isEnabled)
    assertTrue(confirmButton.alpha<=0.45f)
    val confirm=box(confirmButton)
    return taps.mapIndexed { i,(at,ms,x) ->
      idle(at-last);last=at+ms+AFTER_MS
      val y=confirm.bottom-12f
      val logsBefore=ShadowLog.getLogsForTag("OverhandTouch").size
      val before=shot()
      tap(x,y,ms)
      val up=SystemClock.uptimeMillis()
      idle(AFTER_MS)
      println("DEADTAP shot ${SystemClock.uptimeMillis()-up} ms after up")
      val after=shot()
      var near=0;var far=0;val bb=android.graphics.Rect(9999,9999,-1,-1)
      for(py in 0 until before.height step 2) for(px in 0 until before.width step 2)
        if(before.getPixel(px,py)!=after.getPixel(px,py)) { bb.union(px,py);if(hypot(px-x,py-y)<=NEAR_PX) near++ else far++ }
      println("DEADTAP changed bbox=$bb confirm=$confirm size=${before.width}x${before.height}")
      val lines=ShadowLog.getLogsForTag("OverhandTouch").drop(logsBefore)
      lines.forEach { println("DEADTAP trace OverhandTouch: ${it.msg}") }
      val traced=lines.any { it.msg.startsWith("up ") && it.msg.contains("outcome=") }
      Result(i+1,x,y,near,far,traced,!confirmButton.isEnabled && confirmButton.alpha<=0.45f).also { println("DEADTAP $it") }
    }
  }

  @Test fun DevelopersSixTapsAfterTheReceiptEachGetAVisibleResponseAndATrace() {
    val r=replay()
    val dead=r.count { it.nearChanged==0 }
    assertTrue("Ask must visibly disable Confirm until a pick",r.all { it.unavailable })
    val logged=r.count { it.traced }.toDouble()/r.size
    println("DEADTAP card_dead_taps_replay=$dead pinch_outcome_logged=$logged")
    assertEquals("dead taps (no pixel changed within $NEAR_PX px of the pinch): $r",0,dead)
    assertEquals("taps with an OverhandTouch up line: $r",1.0,logged,0.0)
  }
  companion object { const val NEAR_PX=300f;const val AFTER_MS=520L }
}
