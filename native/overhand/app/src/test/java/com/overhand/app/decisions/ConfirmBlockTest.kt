package com.overhand.app.decisions

import com.overhand.app.data.FakeReceiver
import com.overhand.app.data.LiveSource
import com.overhand.app.data.SessionSource
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** t745: every way a confirm used to return silently now yields a sentence the card shows. */
class ConfirmBlockTest {
  @After fun off() { LiveSafety.sendReal=false }
  @Test fun everyFormerSilentReturnHasAVisibleReason() {
    val cases=mapOf(
      ConfirmBlock.State(stopped=true) to ConfirmBlock.CLOSING,
      ConfirmBlock.State(busy=true) to ConfirmBlock.BUSY,
      ConfirmBlock.State(trials=true) to ConfirmBlock.TRIALS,
      ConfirmBlock.State(tutorial=true) to ConfirmBlock.TUTORIAL,
      ConfirmBlock.State(receipt=true) to ConfirmBlock.SAVED,
      ConfirmBlock.State(stale=true) to ConfirmBlock.STALE)
    cases.forEach { (state,reason) -> assertEquals(state.toString(),reason,ConfirmBlock.reason(state)) }
    // The remaining returns (guard, no option, look away, Live practice) have their own constants.
    listOf(ConfirmBlock.GUARD,ConfirmBlock.NO_OPTION,ConfirmBlock.LOOK,LiveSafety.PRACTICE).forEach { assertTrue(it.isNotBlank()) }
    assertEquals(cases.values.size,cases.values.toSet().size)
  }
  @Test fun nothingBlockedMeansProceed() { assertNull(ConfirmBlock.reason(ConfirmBlock.State())) }
  @Test fun reasonsAreWordsNotCodes() {
    listOf(ConfirmBlock.CLOSING,ConfirmBlock.BUSY,ConfirmBlock.TRIALS,ConfirmBlock.TUTORIAL,ConfirmBlock.SAVED,ConfirmBlock.STALE,ConfirmBlock.GUARD)
      .forEach { assertTrue(it,it.contains(' ') && it.first().isUpperCase() && !it.contains('_')) }
  }

  private class Counting(override val recorded: Boolean): SessionSource {
    var calls=0
    override fun decisions()=JSONArray()
    override fun answer(decision: JSONObject,label: String,input: String): String { calls++;return "ok" }
  }
  @Test fun switchStartsOff() { assertFalse(LiveSafety.sendReal);assertFalse(LiveSafety.maySend(recorded=false)) }
  @Test fun liveWithSwitchOffNeverCallsAnswer() {
    val live=Counting(recorded=false)
    repeat(3) { assertThrows(LiveSafety.PracticeOnly::class.java) { LiveSafety.answer(live,false,JSONObject().put("id",1),"A","pinch") } }
    assertEquals(0,live.calls)
  }
  @Test fun liveSourceWithSwitchOffSendsNoRequest() {
    val r=FakeReceiver();val d=r.add(7)
    val live=LiveSource(r.http,"http://a:1").also { it.validate() }
    val card=live.adopt(JSONArray().put(JSONObject(d.toString()))).getJSONObject(0)
    assertThrows(LiveSafety.PracticeOnly::class.java) { LiveSafety.answer(live,false,card,"A","thumbs_up") }
    assertEquals(0,r.posts);assertEquals("open",r.rows[7]!!.getString("status"))
    // The same card does go through once the wearer turns the switch on.
    LiveSafety.answer(live,true,card,"A","thumbs_up")
    assertEquals(1,r.posts)
  }
  @Test fun recordedIsUnchangedBySwitch() {
    val rec=Counting(recorded=true)
    assertEquals("ok",LiveSafety.answer(rec,false,JSONObject(),"A","pinch"))
    assertEquals(1,rec.calls)
  }
}
