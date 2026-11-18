package com.overhand.app.decisions

import com.overhand.app.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The status reducer DecisionCards renders from, driven through post / stream-loss / reconnect / restart. */
class DeliveryBoardTest {
  @get:Rule val tmp=TemporaryFolder()
  private val origin="http://a:1"
  private fun card(s: LiveSource,id: Int)=s.decisions().let { a -> (0 until a.length()).map { a.getJSONObject(it) }.first { it.optString("id")==id.toString() } }
  /** Mirrors DecisionCards.post: begin on the UI thread, source call on io, finish on the UI thread. */
  private fun post(b: DeliveryBoard,s: SessionSource,c: JSONObject,label: String): DeliveryBoard.Effect? {
    val t=b.begin(c,s.recorded) ?: return null
    assertTrue(b.busy)
    return b.finish(t,c,label,runCatching { s.answer(c,label,"pinch") },s.recorded)
  }
  private fun reconcileAll(b: DeliveryBoard,s: LiveSource)=b.checks().map { u -> b.reconciled(u.card,u.label,runCatching { s.reconcile(u.card,u.label) }) }

  @Test fun lostReplyIsUnknownBlocksResendThenConfirmsAfterReconnect() {
    val r=FakeReceiver();r.add(1);val s=LiveSource(r.http,origin).also { it.validate() };val c=card(s,1)
    val b=DeliveryBoard()
    r.postMode="commit_then_lose"
    val http=r.http
    val lossy=LiveSource(HttpClient { m,p,x -> try { http.request(m,p,x) } finally { if(m=="POST") r.getFails=true } },origin).also { it.validate() }
    val e=post(b,lossy,c,"A")!!
    assertFalse(e.removeCard);assertFalse(e.confirmed);assertFalse(b.busy)
    assertTrue(b.notice!!.text.startsWith("Answer status unknown"));assertFalse(b.notice!!.text.contains("Answer failed"))
    // No blind retry: confirming the same card again is refused before any request.
    assertNull(post(b,lossy,c,"A"));assertEquals(DeliveryBoard.BLOCKED,b.notice!!.text);assertEquals(1,r.posts)
    // Stream drops: the unknown status stays visible; navigation cannot clear it.
    b.stale("Live data is stale; reconnect before answering.");assertTrue(b.notice!!.text.startsWith(DeliveryBoard.UNKNOWN_CHECK))
    b.clear();assertEquals(DeliveryBoard.UNKNOWN_CHECK,b.notice!!.text)
    // Reconnect: a still-unreachable check leaves it unresolved; a later check confirms. Still one POST.
    assertFalse(reconcileAll(b,lossy).single().removeCard);assertEquals(setOf("1"),b.unresolvedIds())
    r.getFails=false
    val done=reconcileAll(b,lossy).single()
    assertTrue(done.removeCard);assertTrue(done.confirmed);assertTrue(b.unresolvedIds().isEmpty())
    assertEquals("Answer confirmed by the server after the reply was lost · A",b.notice!!.text)
    assertEquals(1,r.posts);assertEquals(1,r.commits)
  }
  @Test fun reconcileOutcomesStayDistinct() {
    fun after(setup: (FakeReceiver) -> Unit): String {
      val r=FakeReceiver();r.add(1);val s=LiveSource(r.http,origin).also { it.validate() };val c=card(s,1)
      val b=DeliveryBoard();b.restore(listOf(JSONObject(c.toString()).put("label","A").put("device_utc_ms",0L)))   // sent long ago: settled
      setup(r);reconcileAll(b,s);return b.notice!!.text
    }
    assertTrue(after { it.rows[1]!!.put("status","answered").put("answer","B") }.startsWith("Conflict: the server now records a different answer · B"))
    assertTrue(after { it.rows[1]!!.put("status","withdrawn") }.startsWith("Conflict: this question is now withdrawn"))
    assertTrue(after { it.rows.remove(1) }.startsWith("Answer status unknown: this question is not listed"))
    assertTrue(after { it.getFails=true }.startsWith("Answer status unknown: the connection failed after sending began"))
    assertTrue(after { it.rows[1]!!.put("question","different decision") }.startsWith("Answer status unknown: the server's decision no longer matches"))
    assertTrue(after { }.startsWith("Not recorded: the server still shows this question open"))
    assertTrue(after { it.rows[1]!!.put("status","answered").put("answer","A") }.startsWith("Answer confirmed by the server after the reply was lost"))
  }
  @Test fun lateRequestKeepsOpenRowUnknownUntilSettled() {
    // The POST is still in flight when the readback sees the row open: not a definite "not recorded".
    val r=FakeReceiver();r.add(1);var now=1_000L
    val http=r.http
    val late=HttpClient { m,p,x -> if(m=="POST") throw java.net.SocketTimeoutException("read timed out") else http.request(m,p,x) }
    val s=LiveSource(late,origin,null,settleMs=30_000,clock={ now }).also { it.validate() };val c=card(s,1)
    val b=DeliveryBoard()
    assertFalse(post(b,s,c,"A")!!.removeCard)
    assertTrue(b.notice!!.text.startsWith("Not recorded yet"));assertEquals(DeliveryBoard.Kind.UNKNOWN,b.notice!!.kind)
    assertFalse(b.canSend(c))
    r.rows[1]!!.put("status","answered").put("answer","A")          // the delayed request lands
    assertTrue(reconcileAll(b,s).single().confirmed)
    // Same, but nothing ever lands: definite only after the settle period.
    r.add(2);s.validate();val c2=card(s,2)
    post(b,s,c2,"A");assertEquals(DeliveryBoard.Kind.UNKNOWN,b.notice!!.kind)
    now+=29_000;reconcileAll(b,s);assertEquals(DeliveryBoard.Kind.UNKNOWN,b.notice!!.kind)
    now+=2_000;reconcileAll(b,s);assertEquals(DeliveryBoard.Kind.NOT_RECORDED,b.notice!!.kind);assertTrue(b.canSend(c2))
  }
  @Test fun reusedIdWithDifferentQuestionIsNotBlocked() {
    val b=DeliveryBoard();val old=JSONObject().put("id","1").put("project","alpha").put("question","old").put("label","A")
    b.restore(listOf(old))
    assertFalse(b.canSend(old));assertTrue(b.canSend(JSONObject(old.toString()).put("question","new decision reusing id 1")))
  }
  @Test fun restartRestoresPendingIdentityAndNeverPosts() {
    val r=FakeReceiver();r.add(1);val f=tmp.newFile("p.json")
    val s=LiveSource(r.http,origin,LivePendingStore(f)).also { it.validate() };val c=card(s,1)
    LivePendingStore(f).put(c,"A")       // process died between send and readback
    r.rows[1]!!.put("status","answered").put("answer","A")
    val restarted=LiveSource(r.http,origin,LivePendingStore(f))   // new activity: not validated yet
    val b=DeliveryBoard();b.restore(restarted.pendingAnswers())
    assertEquals(DeliveryBoard.UNKNOWN_RESTART,b.notice!!.text);assertFalse(b.canSend(c))
    assertTrue(reconcileAll(b,restarted).single().confirmed)
    assertEquals(0,r.posts);assertTrue(LivePendingStore(f).all().isEmpty())
    // Another server's pending entries are not restored into this session.
    LivePendingStore(f).put(JSONObject(c.toString()).put(DecisionScope.ORIGIN,"http://b:2"),"A")
    assertTrue(restarted.pendingAnswers().isEmpty())
  }
  @Test fun lateResultAfterResetIsDropped() {
    val r=FakeReceiver();r.add(1);val s=LiveSource(r.http,origin).also { it.validate() };val c=card(s,1)
    val b=DeliveryBoard();val t=b.begin(c,false)!!
    b.reset()   // server change / session restart while the request is in flight
    assertNull(b.finish(t,c,"A",runCatching { s.answer(c,"A","pinch") },false));assertNull(b.notice);assertFalse(b.busy)
  }
  @Test fun recordedWordingIsUnchanged() {
    val b=DeliveryBoard();val c=JSONObject().put("id","2")
    val t=b.begin(c,true)!!;assertEquals("Saving practice answer…",b.notice!!.text)
    assertTrue(b.finish(t,c,"x",Result.success("Practice answer saved on this headset · x. No live agent was contacted."),true)!!.removeCard)
    val t2=b.begin(c,true)!!
    assertFalse(b.finish(t2,c,"x",Result.failure(IllegalStateException("io")),true)!!.removeCard)
    assertEquals(DeliveryBoard.Notice("Could not save practice answer. Question remains pending.",true),b.notice)
  }
}
