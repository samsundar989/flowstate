package com.overhand.app.first60

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.overhand.app.data.LocalReceiptStore
import com.overhand.app.data.ModeGeneration
import com.overhand.app.data.RecordedSource
import com.overhand.app.decisions.DecisionCards
import com.overhand.app.features.StageWindows
import com.overhand.app.onboarding.HandsOnboarding
import java.io.File
import java.nio.file.Files
import java.time.Duration
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #1281: over every saved state the opening can meet, the question the greeting speaks is the question the card
 * shows, its options are drawn before the cue, and a "yes" writes the receipt for that same question. The greeting
 * side is First60Feature's own: [First60Brief] on the raw recording picks the pre-rendered question, and
 * [OpeningBinding] decides, against the real [DecisionCards], when (and whether) it is spoken.
 *
 * Each state is also scored for the pre-#1281 opening (open(), then speak at once, no binding): the card's
 * question after it settles against the spoken one, and whether the options were drawn at the cue.
 * Writes $OPENING_OUT/opening_states_<buildType>.tsv (else build/).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpeningBindingTest {
  private val assets = File("src/main/assets")
  private val cardsJson = JSONObject(File("src/main/res/raw/first60_spoken_cards.json").readText()).getJSONObject("cards")
  private fun log(ds: String) = File(assets, if (ds == "day") "judge/real_day.jsonl" else "judge/demo/$ds/day.jsonl").readText()
  private fun greeting(ds: String) = First60Brief(cardsJson).brief(First60Brief.parse(log(ds)), "recorded").decision

  private fun all(v: View): List<View> = listOf(v) + (if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList())
  private fun optionButtons(v: View) = all(v).filterIsInstance<Button>().filter { it.tag == HandsOnboarding.OPTION_TAG }

  enum class Pre { NONE, OTHER_PROJECT, RECEIPT }
  data class State(val name: String, val ds: String, val tutorialDone: Boolean, val practised: List<String>, val pre: Pre)
  data class Outcome(val state: State, val greeting: String?, val legacyShown: String?, val legacyCueBeforeOptions: Boolean,
                     val step: String, val spoken: String?, val shownAtCue: String?, val cueBeforeOptions: Boolean, val receipt: String?,
                     val kept: Boolean) {
    val legacyMismatch get() = greeting != null && legacyShown != greeting
    val mismatch get() = spoken != null && (spoken != shownAtCue || receipt != spoken)
  }

  /** The card's order for a recording with no receipts (DecisionCards.accept: open, by judge_order). */
  private fun cardOrder(ds: String): List<JSONObject> {
    val src = RecordedSource({ File(assets, it).readText() }, LocalReceiptStore(Files.createTempDirectory("order").resolve("r.json").toFile()), null, requested = ds)
    val rows = src.decisions()
    return (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.optString("status") == "open" }
      .sortedBy { it.optInt("judge_order", Int.MAX_VALUE) }
  }

  fun states(): List<State> {
    val out = mutableListOf<State>()
    for (ds in listOf("day", "cards", "cells")) {
      val order = cardOrder(ds).map { it.optString("id") }
      val g = greeting(ds)?.decisionId
      val sets = linkedMapOf<List<String>, String>()
      if (ds == "day") sets[listOf(g!!)] = "developer_nov15g"
      for (k in 0..order.size) sets.putIfAbsent(order.take(k), if (k == 0) "fresh" else if (k == order.size) "all_answered" else "first_$k")
      for (id in order) sets.putIfAbsent(listOf(id), "only_$id")
      sets.putIfAbsent(order.filter { it != g }, "all_but_greeting")
      for ((set, name) in sets) for (done in listOf(true, false)) for (pre in Pre.values())
        out += State("$ds/$name/${if (done) "tutorial_done" else "tutorial_new"}/${pre.name.lowercase()}", ds, done, set, pre)
    }
    return out
  }

  private fun run(st: State): Outcome {
    val overhand = Robolectric.buildActivity(com.overhand.app.OverhandActivity::class.java).get()
    overhand.getSharedPreferences("hands-onboarding-v1", 0).edit().putBoolean("recorded", st.tutorialDone).commit()
    val file = Files.createTempDirectory("opening").resolve("receipts.json").toFile()
    val store = LocalReceiptStore(file)
    val source = RecordedSource({ File(assets, it).readText() }, store, null, requested = st.ds)
    val byId = cardOrder(st.ds).associateBy { it.optString("id") }
    for (id in st.practised) { val d = byId.getValue(id); val o = d.getJSONArray("options").getJSONObject(0)
      store.save(source.fixtureHash, id, o.getString("id"), o.getString("label"), "pinch", d.getLong("t_ms")) }
    // Observe the persisted file under the store's save lock. A concurrent Windows reader can deny the
    // atomic replacement itself (not just fail its own read), injecting a save failure into this test.
    fun text(): String? = synchronized(store) { if (file.exists()) file.readText() else null }
    fun receipts() = text()?.let { JSONArray(it) }?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("decision_id") } } ?: emptyList()
    val before = receipts()
    val host = Robolectric.buildActivity(Activity::class.java).setup().get()
    val column = LinearLayout(overhand).apply { orientation = LinearLayout.VERTICAL }
    val frame = FrameLayout(host).apply { addView(column, FrameLayout.LayoutParams(-1, -1)) }; host.setContentView(frame)
    val dp = overhand.resources.displayMetrics.density
    fun settle(ms: Long = 20) {
      frame.measure(View.MeasureSpec.makeMeasureSpec((StageWindows.CARD_W_DP * dp).toInt(), View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec((StageWindows.CARD_H_DP * dp).toInt(), View.MeasureSpec.EXACTLY))
      frame.layout(0, 0, frame.measuredWidth, frame.measuredHeight)
      Thread.sleep(2); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }
    val cards = DecisionCards(overhand, { "" }, { source }, ModeGeneration()) { }
    val g = greeting(st.ds)
    try {
      cards.attach(column)
      fun loaded() { repeat(200) { settle(); if (cards.openIds() != null) return } }
      when (st.pre) {
        Pre.NONE -> {}
        Pre.OTHER_PROJECT -> { cards.open(); loaded(); settle()
          val other = cardOrder(st.ds).firstOrNull { it.optString("id") in cards.openIds().orEmpty() && it.optString("project_name") != g?.projectName }
          if (other != null) cards.showDecision(other.optString("id")); settle() }
        Pre.RECEIPT -> { cards.open(); loaded(); settle()
          optionButtons(column).firstOrNull()?.performClick(); settle()
          all(column).filterIsInstance<Button>().firstOrNull { it.text.startsWith("Confirm") }?.performClick()
          repeat(200) { if (receipts().size > before.size) return@repeat; settle() }; settle() }
      }
      val preReceipts = receipts()
      // First60Feature.begin: open the card unless it is up already.
      val wasShowing = st.pre != Pre.NONE
      if (!wasShowing) cards.open()
      // The pre-#1281 opening spoke here, with whatever the card had drawn at this instant.
      val legacyCueBefore = cards.shown() == null
      loaded(); repeat(5) { settle() }
      val legacyShown = cards.shown()?.id
      // #1281: First60Feature.bind, tick by tick (20 ms).
      var waited = 0L
      var step: OpeningBinding.Step
      while (true) {
        step = OpeningBinding.step(g?.decisionId, cards.openIds(), cards.shown(), waited)
        if (step is OpeningBinding.Step.Move) cards.showDecision(step.id)
        if (step is OpeningBinding.Step.Speak || step is OpeningBinding.Step.Skip) break
        settle(); waited += 20
      }
      var spoken: String? = null; var shownAtCue: String? = null; var cueBefore = false; var receipt: String? = null
      if (step is OpeningBinding.Step.Speak) {
        spoken = step.id
        val shown = cards.shown()
        shownAtCue = shown?.id
        cueBefore = shown == null || optionButtons(column).size != shown.options.size || optionButtons(column).any { !it.isLaidOut }
        // "Yes": First60Feature.confirm clicks the bound option by position, then Confirm.
        val ask = AskState(step.options, g?.yesOption ?: "A")
        val i = step.options.indexOfFirst { ask.idOf(it) == g?.yesOption }
        optionButtons(column).getOrNull(i)?.performClick(); settle(60)
        all(column).filterIsInstance<Button>().firstOrNull { it.tag == com.overhand.app.decisions.QuestionCard.TAG_CONFIRM }?.performClick()
        repeat(200) { if (receipts().size > preReceipts.size) return@repeat; settle() }
        receipt = receipts().drop(preReceipts.size).lastOrNull()
        if (receipt != spoken) println("receipt_failure state=${st.name} before=$preReceipts after=${receipts()} options=${step.options} yes=${g?.yesOption} index=$i logs=" +
          org.robolectric.shadows.ShadowLog.getLogsForTag("OverhandCard").takeLast(12).joinToString { it.msg } +
          " visible=" + all(column).filterIsInstance<android.widget.TextView>().joinToString { it.text })
      }
      // Saved practice survives: every receipt from before the opening is still there.
      val kept = receipts().take(preReceipts.size) == preReceipts && receipts().containsAll(before)
      val stepName = when (step) { is OpeningBinding.Step.Speak -> "speak"; is OpeningBinding.Step.Skip -> "skip:" + step.why; else -> step.toString() }
      return Outcome(st, g?.decisionId, legacyShown, legacyCueBefore, stepName, spoken, shownAtCue, cueBefore, receipt, kept)
    } finally { cards.close() }
  }

  private fun write(rows: List<Outcome>) {
    val out = File(System.getenv("OPENING_OUT") ?: "build").apply { mkdirs() }
    val bt = com.overhand.app.BuildConfig.BUILD_TYPE
    File(out, "opening_states_$bt.tsv").writeText("state\tgreeting\tlegacy_shown\tlegacy_mismatch\tlegacy_cue_before_options\tstep\tspoken\tshown_at_cue\treceipt\tmismatch\tcue_before_options\tsaved_kept\n" +
      rows.joinToString("") { o -> listOf(o.state.name, o.greeting, o.legacyShown, o.legacyMismatch, o.legacyCueBeforeOptions, o.step, o.spoken, o.shownAtCue, o.receipt, o.mismatch, o.cueBeforeOptions, o.kept).joinToString("\t") + "\n" })
    println("opening build_type=$bt states=${rows.size} opening_mismatch_states_before=${rows.count { it.legacyMismatch }} " +
      "opening_cue_before_options_before=${rows.count { it.legacyCueBeforeOptions }} opening_mismatch_states=${rows.count { it.mismatch }} " +
      "opening_cue_before_options=${rows.count { it.spoken != null && it.cueBeforeOptions }} spoken=${rows.count { it.spoken != null }} " +
      "receipts_match=${rows.count { it.spoken != null && it.receipt == it.spoken }} skipped=${rows.count { it.spoken == null }} saved_lost=${rows.count { !it.kept }}")
  }

  /** developer's nov15g put-on: decision 16 practised, so the card opens on 17 ("Question 2 of 11, 1 practiced so far"). */
  @Test fun DevelopersNov15gStateNeverSpeaksAnotherQuestion() {
    val st = State("day/developer_nov15g/tutorial_done/none", "day", true, listOf("16"), Pre.NONE)
    val o = run(st)
    assertEquals("the pre-#1281 opening spoke 16 over 17", "17", o.legacyShown)
    assertTrue("the old opening mismatched here", o.legacyMismatch)
    assertTrue("spoken=${o.spoken} shown=${o.shownAtCue} receipt=${o.receipt} step=${o.step}", !o.mismatch)
    assertTrue("nothing is spoken about a question the card no longer shows: ${o.step}", o.spoken == null || o.spoken == o.shownAtCue)
  }

  /** The judge's path: a fresh install speaks and shows the same first question, and "yes" saves it. */
  @Test fun freshInstallSpeaksTheQuestionItShows() {
    val o = run(State("day/fresh/tutorial_new/none", "day", false, emptyList(), Pre.NONE))
    println("fresh_install greeting=${o.greeting} spoken=${o.spoken} shown=${o.shownAtCue} receipt=${o.receipt} step=${o.step}")
    assertEquals("speak", o.step)
    assertEquals(o.greeting, o.spoken); assertEquals(o.spoken, o.shownAtCue)
  }

  @Test fun everySavedState() {
    val rows = states().map(::run)
    write(rows)
    val bad = rows.filter { it.mismatch || (it.spoken != null && it.cueBeforeOptions) || !it.kept }
    assertTrue("states=${rows.size} bad=${bad.size}: ${bad.take(5).joinToString { "${it.state.name} spoken=${it.spoken} shown=${it.shownAtCue} receipt=${it.receipt} kept=${it.kept}" }}", bad.isEmpty())
    assertTrue("the pre-#1281 opening must mismatch somewhere", rows.any { it.legacyMismatch })
  }
}
