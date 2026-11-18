package com.overhand.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.ConnectException

/** Stateful stand-in for the existing receiver: overwrites like it, acknowledges unknown ids like it. */
class FakeReceiver {
  val rows=linkedMapOf<Int,JSONObject>()
  var postMode="ok"      // ok | commit_then_lose | connect_fail | ack_no_commit
  var getFails=false
  var posts=0;var commits=0
  fun add(id: Int,project: String="alpha",question: String="Q$id")=JSONObject().put("id",id).put("project",project)
    .put("question",question).put("status","open").put("answer",JSONObject.NULL).also { rows[id]=it }
  val http=HttpClient { method,path,body ->
    if(method=="GET") {
      if(getFails) throw IOException("unexpected end of stream")
      if(path=="/api/state") return@HttpClient "{\"projects\":[],\"decisions\":[]}"
      val project=Regex("project=([^&]+)").find(path)?.groupValues?.get(1)
      JSONObject().put("decisions",JSONArray(rows.values.filter { project==null || it.getString("project")==project })).toString()
    } else {
      if(postMode=="connect_fail") throw ConnectException("refused")
      posts++
      val id=path.substringAfterLast('/').toInt()
      if(postMode!="ack_no_commit") rows[id]?.let { commits++;it.put("status","answered").put("answer",JSONObject(body!!).getString("answer")) }
      if(postMode=="commit_then_lose") throw IOException("unexpected end of stream")
      "{\"ok\":true}"
    }
  }
}

class LiveDeliveryTest {
  @get:Rule val tmp=TemporaryFolder()
  private val origin="http://a:1"
  private fun live(r: FakeReceiver,store: LivePendingStore?=null)=LiveSource(r.http,origin,store).also { it.validate() }
  private fun card(s: LiveSource,id: Int)=s.decisions().let { a -> (0 until a.length()).map { a.getJSONObject(it) }.first { it.optString("id")==id.toString() } }
  private fun outcome(block: () -> Unit)=try { block();null } catch(e: DeliveryException) { e }

  @Test fun healthyAnswerIsConfirmedByScopedReadback() {
    val r=FakeReceiver();r.add(1);val s=live(r)
    assertEquals("Answer received",s.answer(card(s,1),"A","pinch"))
    assertEquals(1,r.posts);assertEquals(1,r.commits)
  }
  @Test fun unscopedOkWithoutCommitIsNeverConfirmed() {
    val r=FakeReceiver();r.add(1);val s=live(r);val c=card(s,1)
    r.postMode="ack_no_commit"
    assertEquals(Delivery.NOT_RECORDED,outcome { s.answer(c,"A","pinch") }!!.outcome)
    r.rows.remove(1);r.postMode="ok";s.validate()
    // Missing row before send: nothing is posted.
    val e=outcome { s.answer(c,"A","pinch") }!!
    assertEquals(Delivery.MISSING,e.outcome);assertFalse(e.sent);assertEquals(1,r.posts)
  }
  @Test fun lostAcknowledgementIsUnknownThenConfirmedReadOnly() {
    val r=FakeReceiver();r.add(1);val store=LivePendingStore(tmp.newFile("p.json"));val s=live(r,store);val c=card(s,1)
    r.postMode="commit_then_lose";r.getFails=false
    // Readback after the lost reply succeeds here: confirmed without any resend.
    assertEquals("Answer confirmed by the server after the reply was lost",s.answer(c,"A","pinch"))
    assertEquals(1,r.posts);assertTrue(store.all().isEmpty())
  }
  @Test fun lostAcknowledgementWithServerUnreachableStaysPendingAcrossRestart() {
    val r=FakeReceiver();r.add(1);val f=tmp.newFile("p.json");val s=live(r,LivePendingStore(f));val c=card(s,1)
    r.postMode="commit_then_lose"
    val http=r.http
    val flaky=LiveSource(HttpClient { m,p,b -> try { http.request(m,p,b) } finally { if(m=="POST") r.getFails=true } },origin,LivePendingStore(f))
    flaky.validate()
    val e=outcome { flaky.answer(c,"A","pinch") }!!
    assertEquals(Delivery.UNREACHABLE,e.outcome);assertTrue(e.sent);assertFalse(e.outcome.definite)
    assertFalse(flaky.writable)
    // Restart: a new source on the same file sees the pending identity; checking never posts.
    r.getFails=false;r.postMode="ok"
    val again=LiveSource(r.http,origin,LivePendingStore(f))
    val p=again.pendingAnswers().single();assertEquals("A",p.getString("label"))
    assertEquals("Answer confirmed by the server after the reply was lost",again.reconcile(p,p.getString("label")))
    assertEquals(1,r.posts);assertEquals(1,r.commits);assertTrue(again.pendingAnswers().isEmpty())
  }
  @Test fun differentAnswerWithdrawalMissingAndChangedServerStayDistinct() {
    val r=FakeReceiver();r.add(1);r.add(2);r.add(3);r.add(4);val s=live(r)
    val c1=card(s,1);val c2=card(s,2);val c3=card(s,3);val c4=card(s,4)
    r.rows[1]!!.put("status","answered").put("answer","B")
    r.rows[2]!!.put("status","withdrawn").put("answer","withdrawn by developer: x")
    r.rows.remove(3)
    r.rows[4]=JSONObject().put("id",4).put("project","alpha").put("question","another decision reusing id 4").put("status","open")
    val o1=outcome { s.answer(c1,"A","pinch") }!!;assertEquals(Delivery.CONFLICT,o1.outcome);assertEquals("B",o1.serverAnswer)
    assertEquals(Delivery.WITHDRAWN,outcome { s.answer(c2,"A","pinch") }!!.outcome)
    assertEquals(Delivery.MISSING,outcome { s.answer(c3,"A","pinch") }!!.outcome)
    assertEquals(Delivery.CHANGED_SERVER,outcome { s.answer(c4,"A","pinch") }!!.outcome)
    val other=LiveSource(r.http,"http://b:2").also { it.validate() }
    assertEquals(Delivery.CHANGED_SERVER,outcome { other.answer(c1,"B","pinch") }!!.outcome)
    assertEquals(0,r.posts)
  }
  @Test fun repeatOfTheSameAnswerIsNotPostedAgain() {
    val r=FakeReceiver();r.add(1);val s=live(r);val c=card(s,1)
    s.answer(c,"A","pinch");assertEquals("Answer already recorded on the server",s.answer(c,"A","pinch"))
    assertEquals(Delivery.CONFLICT,outcome { s.answer(c,"B","pinch") }!!.outcome)
    assertEquals(1,r.posts);assertEquals(1,r.commits)
  }
  @Test fun connectFailureIsDefinitelyNotSentAndStale() {
    val r=FakeReceiver();r.add(1);val store=LivePendingStore(tmp.newFile("p.json"));val s=live(r,store);val c=card(s,1)
    r.postMode="connect_fail"
    val e=outcome { s.answer(c,"A","pinch") }!!
    assertEquals(Delivery.NOT_SENT,e.outcome);assertFalse(s.writable);assertTrue(store.all().isEmpty())
    assertEquals(Delivery.NOT_SENT,outcome { s.answer(c,"A","pinch") }!!.outcome)
    assertEquals(0,r.posts)
  }
  @Test fun twoStoreInstancesOnOneFileDoNotLoseEntries() {
    val f=tmp.newFile("p.json");val a=LivePendingStore(f);val b=LivePendingStore(f)
    val c1=JSONObject().put("id",1).put("project","p").put("question","q1");val c2=JSONObject().put("id",2).put("project","p").put("question","q2")
    a.put(c1,"A");b.put(c2,"B");assertEquals(2,a.all().size)
    a.remove(c1);assertEquals(listOf("2"),b.all().map { it.getString("id") })
  }
  @Test fun integerIdsFromTheReceiverAreAccepted() {
    val r=FakeReceiver();r.add(7);val s=live(r)
    assertEquals("7",DecisionScope.id(card(s,7)));assertEquals(origin,card(s,7).getString(DecisionScope.ORIGIN))
  }
}
