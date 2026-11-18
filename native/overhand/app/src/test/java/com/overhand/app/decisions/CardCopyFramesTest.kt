package com.overhand.app.decisions

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.overhand.app.data.*
import com.overhand.app.windows.PanelPolish
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

/**
 * #881: the shipped decision card in every state a person meets (Recorded, tutorial done), at the panel's size.
 * With CARDCOPY_OUT set, each state is written as <n>_<state>.png plus the visible text (.txt) for cold readers.
 * Always: the receipt names its own question and no other, and no string on the card is tracker or debug talk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w1600dp-h1200dp-288dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardCopyFramesTest {
  private val looper get()=shadowOf(Looper.getMainLooper())
  private lateinit var column: LinearLayout
  private fun all(v: View): List<View> = listOf(v)+(if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList())
  private fun buttons()=all(column).filterIsInstance<Button>().filter { it.isShown }
  private fun idle(ms: Long) { val end=SystemClock.uptimeMillis()+ms;while(SystemClock.uptimeMillis()<end) looper.idleFor(Duration.ofMillis(minOf(10L,end-SystemClock.uptimeMillis()))) }
  private fun waitFor(what: () -> Boolean) {
    // The clock must advance too: the thumb hold keeps ticking for 2 s and the save posts back to this looper.
    repeat(300) { if(what()) return;Thread.sleep(10);idle(20) }
    fail("timed out; card shows:\n"+text(column))
  }
  /** Visible text, top to bottom, as the snapshot intent records it on the headset. */
  private fun text(v: View): String = if(v.visibility!=View.VISIBLE) "" else when(v) {
    is TextView -> v.text.toString().takeIf { it.isNotBlank() }?.let { it+"\n" } ?: ""
    is ViewGroup -> (0 until v.childCount).joinToString("") { text(v.getChildAt(it)) }
    else -> ""
  }
  private val states=linkedMapOf<String,String>()
  /** #905: options whose text is not wholly on screen, as "state: label (shown of full px)". */
  private val clipped=mutableListOf<String>()
  private fun checkOptions(name: String) {
    val r=android.graphics.Rect()
    buttons().filter { it.tag==com.overhand.app.onboarding.HandsOnboarding.OPTION_TAG }.forEach { b ->
      val lines=b.layout?.lineCount ?: 0
      val needs=b.layout?.getLineBottom(lines-1)?.plus(b.totalPaddingTop+b.totalPaddingBottom) ?: 0
      val ellipsized=(0 until lines).any { b.layout.getEllipsisCount(it)>0 }
      if(!b.getGlobalVisibleRect(r) || r.height()<b.height || needs>b.height || ellipsized) clipped+="$name: ${b.text} (${r.height()} of ${b.height}, text $needs)"
    }
  }
  /** Each captured page must be wholly visible; reading traverses the real controls and returns to page one. */
  private fun readPages(name: String): String {
    idle(100)
    val pages=mutableListOf<String>()
    val fields=linkedMapOf<String,String>()
    while(buttons().firstOrNull { it.text=="Read back" }?.isEnabled==true) buttons().first { it.text=="Read back" }.performClick()
    repeat(500) { page ->
      idle(100)
      val body=column.findViewWithTag<TextView>("reading-page")
      if(body!=null) {
        val rect=android.graphics.Rect()
        assertTrue("$name/$page invisible",body.getGlobalVisibleRect(rect))
        assertEquals(body.height,rect.height())
        assertTrue("$name/$page clipped",body.layout.height+body.totalPaddingTop+body.totalPaddingBottom<=body.height)
      }
      if(body!=null) {
        val label=column.findViewWithTag<TextView>("reading-label").text.toString().substringBefore(" \u00b7 ")
        fields[label]=fields[label].orEmpty()+body.text.toString()
      }
      pages.add(text(column));checkOptions("$name/$page")
      System.getenv("CARDCOPY_OUT")?.let(::File)?.also { out ->
        out.mkdirs()
        Bitmap.createBitmap(column.width,column.height,Bitmap.Config.ARGB_8888).also { b ->
          val c=Canvas(b);c.drawColor(android.graphics.Color.rgb(24,26,32));column.draw(c)
          File(out,"${name}_page$page.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle()
        }
        File(out,"${name}_page$page.txt").writeText(pages.last())
      }
      if(buttons().firstOrNull { it.text=="Read more" }?.isEnabled!=true) {
        while(buttons().firstOrNull { it.text=="Read back" }?.isEnabled==true) buttons().first { it.text=="Read back" }.performClick()
        return pages.joinToString("\n")+"\n"+fields.values.joinToString("\n")
      }
      buttons().first { it.text=="Read more" }.performClick()
    }
    error("unbounded pages")
  }
  private fun capture(name: String) {
    val more=column.findViewWithTag<Button>(QuestionCard.TAG_MORE)
    more?.performClick();idle(100)
    states[name]=readPages(name)
    if(more!=null) buttons().first { it.text=="Back" }.performClick()
    idle(100);checkOptions(name+"-question-card")
  }

  @Test fun everyStateReadsForAPerson() {
    val overhand=Robolectric.buildActivity(com.overhand.app.OverhandActivity::class.java).get()
    overhand.getSharedPreferences("hands-onboarding-v1",0).edit().putBoolean("recorded",true).commit()
    val source=RecordedSource({ File("src/main/assets/$it").readText() },
      LocalReceiptStore(Files.createTempDirectory("cardcopy").resolve("receipts.json").toFile()),null)
    val d=overhand.resources.displayMetrics.density
    column=LinearLayout(overhand).apply { orientation=LinearLayout.VERTICAL }
    val host=Robolectric.buildActivity(Activity::class.java).setup().get()
    host.setContentView(FrameLayout(host).apply { setBackgroundColor(android.graphics.Color.rgb(24,26,32));addView(column,FrameLayout.LayoutParams((com.overhand.app.features.StageWindows.CARD_W_DP*d).toInt(),(com.overhand.app.features.StageWindows.CARD_H_DP*d).toInt())) })
    org.robolectric.shadows.ShadowChoreographer.setPaused(true)
    org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    val cards=DecisionCards(overhand,{ "" },{ source },ModeGeneration()) { }
    cards.attach(column);cards.open();PanelPolish.install(column,com.overhand.app.features.StageWindows.CARD_TARGET,com.overhand.app.windows.PolishRules.NEAR_GAP_DP,com.overhand.app.features.StageWindows.CARD_TYPE)
    idle(500)
    val hands=mapOf("right" to com.overhand.app.hands.ThumbPose.NONE,"left" to com.overhand.app.hands.ThumbPose.NONE)
    cards.frame(hands,true,1)
    capture("idle")
    val asked=source.decisions().let { rows -> (0 until rows.length()).map { rows.getJSONObject(it) } }
      .filter { it.optString("status","open")=="open" && !it.has("historical_answer") }
    val option=buttons().first { it.tag==com.overhand.app.onboarding.HandsOnboarding.OPTION_TAG }
    val answer=asked.sortedBy { it.optInt("judge_order",Int.MAX_VALUE) }.first().getJSONArray("options").let { it.optJSONObject(0)?.optString("label") ?: it.getString(0) }
    option.performClick();idle(300)
    capture("selected")
    // #1079: the real frame path, as DecisionCards.debugThumbHold drives it, but callable in release too (that hook is
    // BuildConfig.DEBUG only): a released hand for 0.5 s, then thumbs up to 2 s, sampled every 50 ms.
    fun hold(from: Long,to: Long) { var t=from;while(t<to) { cards.frame(mapOf("right" to (if(t<500) com.overhand.app.hands.ThumbPose.NONE else com.overhand.app.hands.ThumbPose.UP),"left" to com.overhand.app.hands.ThumbPose.NONE),true,1);idle(50);t+=50 } }
    hold(0,1300)
    capture("confirming")
    hold(1300,2050);cards.frame(hands,true,1)
    waitFor { buttons().any { it.tag==QuestionCard.TAG_NEXT } }
    idle(300)
    capture("receipt")
    // The receipt's destination is reconstructed from whole visible pages, not a hidden view.
    val whereFits=states["receipt"]!!.contains(QuestionCard.PRACTICE)
    idle(5000)
    assertEquals("Receipt must wait for the person's Next decision",states["receipt"],readPages("receipt_stable"))
    buttons().first { it.tag==QuestionCard.TAG_NEXT }.performClick();idle(300)
    cards.frame(hands,true,1)
    capture("next_question")
    cards.frame(hands,true,0);idle(100)
    capture("no_hands")
    cards.frame(hands,true,1)
    buttons().first { it.text.startsWith("Confirm") }.performClick();idle(300)
    capture("error")

    assertTrue("where-it-went field missing from rendered pages",whereFits)
    // The receipt holds its own question and answer, and no other open question.
    val receipt=states["receipt"]!!
    assertTrue(receipt,receipt.contains(answer))
    val questions=asked.map { it.optString("question") }.filter { receipt.contains(it) }
    assertEquals(receipt,0,questions.size) // Receipt has exactly two facts; the saved record retains question provenance.
    assertTrue(source.decisions().toString().contains("practice_receipt"))
    assertFalse(receipt,receipt.contains("Confirm selected option"))
    // Plain words: no tracker internals, error codes or vendor words in any state.
    val debug=Regex("(?i)\\b(joints?|tracked|tracking|tracker|http|exception|error code|null|gate|armed|pose|Meta|Oculus|OpenAI|Anthropic|Claude|GPT|Gemini|Llama)\\b")
    states.forEach { (name,words) -> assertNull("$name: $words",debug.find(words)?.value) }
    assertTrue(states["no_hands"]!!.contains(DecisionCards.HANDS_HINT))
    // #905: every option whole in every state; the asker is named in plain words; a dropped count says why.
    assertEquals(clipped.joinToString("; "),0,clipped.size)
    states.forEach { (name,words) -> assertFalse("$name: $words",words.contains("Head ·") || words.contains("· needs you")) }
    assertTrue(states["idle"]!!,states["idle"]!!.contains("leading this project"))
    assertTrue(states["next_question"]!!,states["next_question"]!!.contains(" · 1 practiced so far"))
    assertFalse(states["idle"]!!.contains(DecisionCards.HANDS_HINT))
  }
}
