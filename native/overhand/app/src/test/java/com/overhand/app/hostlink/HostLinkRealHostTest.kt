package com.overhand.app.hostlink

import com.overhand.app.data.DecisionScope
import com.overhand.app.data.Delivery
import com.overhand.app.data.DeliveryException
import com.overhand.app.data.LivePendingStore
import com.overhand.app.decisions.LiveSafety
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

/**
 * The Kotlin client against the real host (host/src/server.ts under Node) on loopback: pairing, refusals,
 * resume after drops, answers after drops, the answers switch, and a change of address found by discovery.
 */
class HostLinkRealHostTest {
  private val closers=mutableListOf<AutoCloseable>()
  @After fun tearDown() { closers.asReversed().forEach { runCatching { it.close() } } }
  private fun host()=RealHost().also { closers.add(it) }
  private var sendReal=true
  private val fast=LinkTiming(backoffBaseMs=200,backoffMaxMs=2_000,pingMs=5_000,connectTimeoutMs=2_000,pairTimeoutMs=5_000,discoveryMs=200)
  private fun link(store: PairedHostStore=InMemoryPairedHostStore(),discovery: HostDiscovery=NoDiscovery,timing: LinkTiming=fast)=
    HostLink(store,discovery,"Test headset",{ sendReal },timing).also { l -> closers.add(AutoCloseable { l.shutdown() }) }

  private fun waitUntil(what: String,ms: Long=15_000,cond: () -> Boolean) {
    val end=System.currentTimeMillis()+ms
    while(!cond()) { if(System.currentTimeMillis()>end) fail("timed out: $what");Thread.sleep(5) }
  }
  private fun connected(l: HostLink)=waitUntil("connected, was ${l.status}") { l.status.state==LinkState.CONNECTED }
  private fun answersAt(h: RealHost,id: String): Int { val a=h.stats().getJSONArray("answers");return (0 until a.length()).count { a.getJSONObject(it).getString("decision_id")==id } }
  private fun frameCount(h: RealHost,type: String)=h.stats().getJSONObject("counts").optInt(type,0)

  // ---- pairing ----
  @Test fun qrPayloadPairsPinsAndConnects() {
    val h=host();val store=InMemoryPairedHostStore();val l=link(store)
    val token=h.info.getString("token")
    val p=l.pairWithQr(h.qr)
    assertEquals(Pin.check(h.fp,token),p.check)
    assertEquals(h.fp,store.load()!!.fp)
    assertTrue(store.load()!!.deviceSecret.length>=40)
    connected(l)
    assertEquals(1,h.cmd("info").getInt("devices"))
  }

  @Test fun sixDigitCodePairs() {
    val h=host();val l=link()
    val code=h.info.getString("code")
    val p=l.pairWithCode(h.endpoint,code.substring(0,3)+" "+code.substring(3))
    assertEquals(Pin.check(h.fp,code),p.check)
    connected(l)
  }

  @Test fun wrongFingerprintIsRefusedBeforeAnyFrame() {
    val h=host();val store=InMemoryPairedHostStore();val l=link(store)
    val forged=h.qr.replace(h.fp,h.fp.reversed())
    try { l.pairWithQr(forged);fail("paired with the wrong certificate") } catch(e: PairRefused) { assertEquals(Refusal.WRONG_CERTIFICATE,e.refusal) }
    assertEquals(LinkState.REFUSED,l.status.state)
    assertEquals(Refusal.WRONG_CERTIFICATE.words,l.status.detail)
    assertEquals(null,store.load())
    val s=h.stats()
    assertEquals(0,s.getInt("devices"))
    assertEquals("TLS failed before any WebSocket upgrade",0,s.getInt("connections"))
    // A stored pairing whose certificate no longer matches never connects either.
    val l2=link(InMemoryPairedHostStore(PairedHost(h.endpoint.copy(fp=h.fp.reversed()),"d_x","secret")))
    l2.start();Thread.sleep(1_500)
    assertTrue(l2.status.state!=LinkState.CONNECTED)
  }

  @Test fun wrongCodeIsRefused() {
    val h=host();val store=InMemoryPairedHostStore();val l=link(store)
    val wrong=((h.info.getString("code").toInt()+1)%1_000_000).toString().padStart(6,'0')
    try { l.pairWithCode(h.endpoint,wrong);fail("wrong code accepted") } catch(e: PairRefused) { assertEquals(Refusal.BAD_CODE,e.refusal) }
    assertEquals(LinkStatus(LinkState.REFUSED,"Wrong code. Check the six digits on the computer."),l.status)
    assertEquals(null,store.load())
    assertEquals(0,h.cmd("info").getInt("devices"))
  }

  @Test fun expiredCodeAndTokenAreRefused() {
    val h=host();val l=link()
    val code=h.info.getString("code");val qr=h.qr
    h.cmd("advance","ms" to 121_000)
    try { l.pairWithCode(h.endpoint,code);fail("expired code accepted") } catch(e: PairRefused) { assertEquals(Refusal.CODE_EXPIRED,e.refusal) }
    try { l.pairWithQr(qr);fail("expired token accepted") } catch(e: PairRefused) { assertEquals(Refusal.TOKEN_EXPIRED,e.refusal) }
    assertEquals(0,h.cmd("info").getInt("devices"))
  }

  // ---- resume ----
  @Test fun dropsMidStreamLoseAndDuplicateNothing() {
    val h=host();val l=link()
    val seen=Collections.synchronizedList(mutableListOf<Long>())
    l.eventListeners.add { seen.add(it.getLong("seq")) }
    l.pairWithQr(h.qr);connected(l)
    // Host-side kills (the host's socket dies) at 3 points while 400 events stream, plus 3 client-side drops.
    val dropper=Thread { repeat(3) { Thread.sleep(150);l.dropForTest() } }.apply { start() }
    val head=h.cmd(JSONObject().put("cmd","emit").put("n",400).put("interval",2).put("killAt",JSONArray(listOf(60,180,300))),60_000).getLong("head")
    dropper.join()
    waitUntil("caught up to $head, at ${l.lastSeq}") { l.lastSeq==head && l.status.state==LinkState.CONNECTED }
    val got=seen.toList()
    val lost=(1..head).count { it !in got.toSet() }
    val dup=got.size-got.toSet().size
    File(Repo.out,"resume.json").writeText(JSONObject().put("head",head).put("delivered",got.size).put("lost",lost).put("duplicated",dup)
      .put("in_order",got==got.sorted()).put("host_kills",3).put("client_drops",3).put("connections",h.stats().getInt("connections")).toString(1))
    assertEquals("lost events",0,lost)
    assertEquals("duplicated events",0,dup)
    assertEquals((1..head).toList(),got)
    assertTrue("pairing + several resumes",h.stats().getInt("connections")>=5)
  }

  @Test fun reconnectTimeAfterTheSocketIsKilled() {
    val h=host()
    val l=link(timing=LinkTiming())   // production backoff
    l.pairWithQr(h.qr);connected(l)
    val samples=mutableListOf<Double>()
    repeat(15) {
      val connectedAt=AtomicReference<Long?>(null)
      val listener: (LinkStatus) -> Unit={ s -> if(s.state==LinkState.CONNECTED) connectedAt.compareAndSet(null,System.nanoTime()) }
      l.statusListeners.add(listener)
      val t0=System.nanoTime()
      assertEquals(1,h.cmd("kill").getInt("killed"))
      waitUntil("reconnected") { connectedAt.get()!=null }
      samples.add((connectedAt.get()!!-t0)/1e9)
      l.statusListeners.remove(listener)
      Thread.sleep(50)
    }
    val sorted=samples.sorted();val median=(sorted[7])
    File(Repo.out,"reconnect.json").writeText(JSONObject().put("samples_s",JSONArray(samples.map { "%.3f".format(it).toDouble() }))
      .put("median_s","%.3f".format(median).toDouble()).put("max_s","%.3f".format(sorted.last()).toDouble()).put("transport","loopback").toString(1))
    assertTrue("median reconnect $median s",median<2.0)
  }

  // ---- answers ----
  private fun source(l: HostLink,pending: LivePendingStore?=null)=HostSource(l,pending,{ sendReal },waitMs=10_000)
  private fun card(src: HostSource,id: String): JSONObject {
    waitUntil("card $id") { (0 until src.decisions().length()).any { src.decisions().getJSONObject(it).getString("id")==id } }
    val a=src.decisions();return (0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("id")==id }
  }

  @Test fun questionBecomesACardInTheSessionShape() {
    val h=host();val l=link();l.pairWithQr(h.qr);connected(l)
    h.cmd("ask","decision" to "d_shape","title" to "Allow running the tests?")
    val c=card(source(l),"d_shape")
    assertEquals("Allow running the tests?",c.getString("question"))
    assertEquals("open",c.getString("status"))
    assertEquals("p_test",c.getString("project"))
    assertEquals(listOf("Yes","No"),(0 until 2).map { c.getJSONArray("options").getJSONObject(it).getString("label") })
    assertEquals("host:"+h.fp,c.getString(DecisionScope.ORIGIN))
  }

  @Test fun answerLostInADropIsDeliveredExactlyOnce() {
    val h=host();val l=link();l.pairWithQr(h.qr);connected(l)
    val pending=LivePendingStore(Files.createTempDirectory("pending").resolve("p.json").toFile())
    val src=source(l,pending)
    h.cmd("ask","decision" to "d_ack")
    val c=card(src,"d_ack")
    h.cmd("killOnAnswer")           // the host takes the answer, then the socket dies before the reply
    assertEquals("Answer received",src.answer(c,"Yes","pinch"))
    connected(l)
    assertEquals(1,answersAt(h,"d_ack"))
    assertEquals("not resent: the replayed log already showed it",1,l.answerFramesSent)
    assertTrue(pending.all().isEmpty())
    assertFalse(src.decisions().toString().contains("d_ack"))
    // Asking again is answered locally from the log, with no frame.
    assertEquals("Answer already recorded on the computer",src.answer(c,"Yes","pinch"))
    assertEquals(1,answersAt(h,"d_ack"))
  }

  @Test fun answerMadeWhileTheHostMovedIsDeliveredOnceAtItsNewAddress() {
    val h=host()
    val current=AtomicReference(h.endpoint)
    val discovery=object: HostDiscovery { override fun browse(timeoutMs: Long)=listOf(current.get()) }
    val store=InMemoryPairedHostStore();val l=link(store,discovery)
    l.pairWithQr(h.qr);connected(l)
    val src=HostSource(l,null,{ sendReal },waitMs=300)
    h.cmd("ask","decision" to "d_move")
    val c=card(src,"d_move")
    val states=Collections.synchronizedList(mutableListOf<LinkState>());l.statusListeners.add { states.add(it.state) }
    h.cmd("move")                   // host restarts on another port; the old address refuses
    try { src.answer(c,"No","pinch");fail("delivered to a host that was down?") } catch(e: DeliveryException) { assertEquals(Delivery.NOT_RECORDED_YET,e.outcome) }
    current.set(h.endpoint)        // discovery now reports the new port
    waitUntil("reconnected at the new port") { l.status.state==LinkState.CONNECTED && store.load()!!.endpoint.port==h.port }
    assertTrue("searched by fingerprint",LinkState.SEARCHING in states)
    waitUntil("answer recorded by the host") { answersAt(h,"d_move")==1 }
    waitUntil("link saw the host's answered event") { l.answeredOption("d_move")=="deny" }
    Thread.sleep(300)
    assertEquals(1,answersAt(h,"d_move"))
    assertEquals(1,frameCount(h,"answer"))
    assertEquals(1,l.answerFramesSent)
  }

  @Test fun answersSwitchOffSendsNothing() {
    val h=host();val l=link();l.pairWithQr(h.qr);connected(l)
    val src=source(l)
    h.cmd("ask","decision" to "d_off")
    val c=card(src,"d_off")
    sendReal=false
    try { src.answer(c,"Yes","pinch");fail("sent with the switch off") } catch(e: LiveSafety.PracticeOnly) {}
    // Even a queued answer is held while the switch is off, across a reconnect.
    val f=l.answer("d_off","allow")
    l.dropForTest();Thread.sleep(800);connected(l);Thread.sleep(300)
    assertEquals(0,frameCount(h,"answer"))
    assertEquals(0,l.answerFramesSent)
    assertFalse(f.isDone)
    sendReal=true;l.flush()
    assertEquals(AnswerOutcome.CONFIRMED,l.awaitAnswer(f,10_000))
    assertEquals(1,answersAt(h,"d_off"))
  }

  @Test fun forgottenHeadsetIsRefusedInPlainWords() {
    val h=host();val l=link(InMemoryPairedHostStore(PairedHost(h.endpoint,"d_unknown","not-a-secret")))
    l.start()
    waitUntil("refused") { l.status.state==LinkState.REFUSED }
    assertEquals(Refusal.FORGOTTEN.words,l.status.detail)
  }
}
