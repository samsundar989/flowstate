package com.overhand.app.hostlink

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.cert.X509Certificate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Pairing by confirming six digits on the computer (docs/host/PROTOCOL.md 2d): the Kotlin client against the real
 * host under Node, including relays with their own certificate (host/test/mitm_relay.ts), and the typed-code fallback.
 */
class ConfirmPairingTest {
  private val closers=mutableListOf<AutoCloseable>()
  @After fun tearDown() { closers.asReversed().forEach { runCatching { it.close() } } }
  private fun host()=RealHost().also { closers.add(it) }
  private val fast=LinkTiming(backoffBaseMs=200,backoffMaxMs=2_000,pingMs=5_000,connectTimeoutMs=2_000,pairTimeoutMs=5_000,confirmTimeoutMs=20_000)
  private fun link(store: PairedHostStore=InMemoryPairedHostStore(),timing: LinkTiming=fast)=
    HostLink(store,NoDiscovery,"Test headset",{ true },timing).also { l -> closers.add(AutoCloseable { l.shutdown() }) }
  private fun waitUntil(what: String,ms: Long=15_000,cond: () -> Boolean) {
    val end=System.currentTimeMillis()+ms
    while(!cond()) { if(System.currentTimeMillis()>end) fail("timed out: $what");Thread.sleep(5) }
  }
  private fun pairAsync(l: HostLink,ep: Endpoint)=CompletableFuture.supplyAsync { runCatching { l.pairByConfirm(ep) } }
  private fun record(name: String,o: JSONObject) { File(Repo.out,name).writeText(o.toString(1)) }

  @Test fun fixtureMatchesTheHostsDerivation() {
    val fx=JSONObject(File(Repo.fixtures,"pair_confirm.json").readText());val i=fx.getJSONObject("inputs")
    val frames=fx.getJSONArray("frames").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    val fp=i.getString("fp");val pub=i.getString("device_pub");val hn=i.getString("host_nonce");val dn=i.getString("device_nonce")
    assertEquals(frames.first { it.getString("type")=="pair_commit" }.getString("c"),Confirm.commitment(fp,pub,hn))
    assertEquals(i.getString("digits"),Confirm.digits(fp,pub,hn,dn))
    assertEquals(frames.first { it.getString("type")=="paired" }.getString("check"),Pin.check(fp,i.getString("digits")))
    fun keys(o: JSONObject)=o.keys().asSequence().filter { it!="dir" }.toSortedSet()
    assertEquals(keys(frames[0]),keys(Frames.pairRequest(pub,"Headset")))
    assertEquals(keys(frames[2]),keys(Frames.pairNonce(dn)))
    assertTrue(Regex("^[A-Za-z0-9_-]{22}$").matches(Confirm.newNonce()))
    for(r in listOf(Refusal.DECLINED,Refusal.CONFIRM_EXPIRED,Refusal.BUSY,Refusal.NOT_THIS_COMPUTER,Refusal.USE_CODE,Refusal.CANCELLED))
      assertFalse(r.words,Regex("(?i)quest|meta|oculus|claude|codex|openai").containsMatchIn(r.words))
  }

  @Test fun happyPathSameDigitsBothScreensAndNothingStoredBeforeYes() {
    val h=host();val store=InMemoryPairedHostStore();val l=link(store)
    val seen=CopyOnWriteArrayList<LinkStatus>();l.statusListeners.add { seen.add(it) }
    val f=pairAsync(l,h.endpoint)
    waitUntil("digits shown") { l.status.state==LinkState.CONFIRM_ON_COMPUTER }
    val c=h.cmd("confirm")
    assertEquals(c.getString("digits"),l.status.digits)
    assertEquals(0,c.getInt("devices"));assertNull("fingerprint stored before paired",store.load())
    assertTrue(l.status.detail.contains(Confirm.spaced(l.status.digits!!)))
    h.cmd("decide","yes" to true)
    val p=f.get(20,TimeUnit.SECONDS).getOrThrow()
    assertEquals(h.fp,store.load()!!.fp)
    assertEquals(Pin.check(h.fp,c.getString("digits")),p.check)
    waitUntil("connected") { l.status.state==LinkState.CONNECTED }
    assertEquals(listOf(LinkState.PAIRING,LinkState.CONFIRM_ON_COMPUTER),seen.map { it.state }.distinct().take(2))
    record("jvm_happy.json",JSONObject().put("check",p.check).put("headset_digits",l.status.digits ?: JSONObject.NULL).put("host_digits",c.getString("digits")).put("bind",h.address))
  }

  @Test fun theComputerSaysNo() {
    val h=host();val store=InMemoryPairedHostStore();val l=link(store)
    val f=pairAsync(l,h.endpoint)
    waitUntil("digits shown") { l.status.state==LinkState.CONFIRM_ON_COMPUTER }
    h.cmd("decide","yes" to false)
    val e=f.get(20,TimeUnit.SECONDS).exceptionOrNull() as PairRefused
    assertEquals(Refusal.DECLINED,e.refusal);assertEquals(LinkStatus(LinkState.REFUSED,Refusal.DECLINED.words),l.status)
    assertNull(store.load());assertEquals(0,h.cmd("confirm").getInt("devices"))
  }

  @Test fun nobodyConfirmsWithin120s() {
    val h=host();val store=InMemoryPairedHostStore();val l=link(store)
    val f=pairAsync(l,h.endpoint)
    waitUntil("digits shown") { l.status.state==LinkState.CONFIRM_ON_COMPUTER }
    h.cmd("advance","ms" to 121_000);h.cmd(JSONObject().put("cmd","tick"))
    val e=f.get(20,TimeUnit.SECONDS).exceptionOrNull() as PairRefused
    assertEquals(Refusal.CONFIRM_EXPIRED,e.refusal);assertNull(store.load())
    assertFalse(h.cmd("decide","yes" to true).getBoolean("decided"))
  }

  @Test fun wearerCancelsWhenTheDigitsDiffer() {
    val h=host();val store=InMemoryPairedHostStore();val l=link(store)
    val f=pairAsync(l,h.endpoint)
    waitUntil("digits shown") { l.status.state==LinkState.CONFIRM_ON_COMPUTER }
    l.cancelPairing()
    assertEquals(Refusal.CANCELLED,(f.get(20,TimeUnit.SECONDS).exceptionOrNull() as PairRefused).refusal)
    waitUntil("host dropped the request") { !h.cmd("confirm").getBoolean("pending") }
    assertNull(store.load());assertEquals(LinkState.UNPAIRED,l.status.state)
  }

  /** A relay on the same Wi-Fi with its own certificate; the headset pinned the fingerprint the relay advertised. */
  private fun mitm(mode: String): JSONObject {
    // The grinding relay needs ~10^6 hashes (seconds under load) before it reveals; wait long enough that the commitment,
    // not the reveal timeout, is what refuses it. The ping too: a grinding relay under build load misses 5 s pongs (#960:
    // UNREACHABLE after 11 s in job 5587), and the link must not drop before the commitment check runs.
    val h=host();val store=InMemoryPairedHostStore();val l=link(store,fast.copy(pairTimeoutMs=60_000,pingMs=60_000))
    val r=h.cmd("relay","mode" to mode)
    val f=pairAsync(l,Endpoint(h.address,r.getInt("relay_port"),r.getString("relay_fp"),h.info.getString("name")))
    waitUntil("host asks its person",30_000) { h.cmd("confirm").getInt("asks")>=1 }
    val hostDigits=h.cmd("confirm").optString("digits")
    waitUntil("headset settles",30_000) { l.status.state==LinkState.CONFIRM_ON_COMPUTER || f.isDone }
    val headsetDigits=l.status.digits
    h.cmd("decide","yes" to false)   // the person at the computer compares and refuses
    val outcome=f.get(30,TimeUnit.SECONDS).exceptionOrNull() as? PairRefused
    assertNull("nothing stored under a relay",store.load())
    val o=JSONObject().put("mode",mode).put("host_digits",hostDigits).put("headset_digits",headsetDigits ?: JSONObject.NULL)
      .put("headset_refusal",outcome?.refusal?.name ?: JSONObject.NULL).put("relay",h.cmd("relaySeen"))
      .put("digits_differ",headsetDigits!=hostDigits)
    record("jvm_mitm_$mode.json",o)
    return o
  }
  @Test fun mitmActiveRelayShowsDifferentDigits() {
    val o=mitm("active")
    assertNotEquals(o.getString("host_digits"),o.getString("headset_digits"))
  }
  @Test fun mitmTransparentRelayIsCaughtByTheCommitmentBeforeAnyDigits() {
    val o=mitm("transparent")
    assertEquals("NOT_THIS_COMPUTER",o.getString("headset_refusal"));assertTrue(o.isNull("headset_digits"))
  }
  @Test fun mitmGrindingRelayThatForcesMatchingDigitsIsCaughtByTheCommitment() {
    val o=mitm("grind")
    assertTrue("relay found matching digits",o.getJSONObject("relay").getBoolean("grindFound"))
    assertEquals("NOT_THIS_COMPUTER",o.getString("headset_refusal"));assertTrue("no digits shown",o.isNull("headset_digits"))
  }

  /** A raw client that sends pair_request and never its nonce: the host's nonce never arrives, nobody is asked. */
  @Test fun withholdingTheNonceNeverRevealsTheHostsNonce() {
    val h=host();val tm=PinningTrustManager(h.fp)
    val got=CopyOnWriteArrayList<JSONObject>()
    val client=OkHttpClient.Builder().sslSocketFactory(tm.context().socketFactory,tm)
      .hostnameVerifier { _,s -> Pin.of(s.peerCertificates[0] as X509Certificate)==h.fp }.build()
    val ws=client.newWebSocket(Request.Builder().url(h.endpoint.url).build(),object: WebSocketListener() {
      override fun onOpen(webSocket: WebSocket,response: Response) { webSocket.send(Frames.pairRequest(Confirm.newDevicePub(),"x").toString()) }
      override fun onMessage(webSocket: WebSocket,text: String) { got.add(JSONObject(text)) }
    })
    closers.add(AutoCloseable { ws.cancel();client.dispatcher.executorService.shutdown() })
    waitUntil("commit") { got.any { it.getString("type")=="pair_commit" } }
    Thread.sleep(1_500)
    assertFalse(got.any { it.getString("type")=="pair_reveal" })
    assertEquals(0,h.cmd("confirm").getInt("asks"))
  }

  @Test fun anOlderHostAsksForTheTypedCodeAndTheCodeStillPairs() {
    val h=host();val store=InMemoryPairedHostStore();val l=link(store)
    h.cmd(JSONObject().put("cmd","oldHost"))
    val e=pairAsync(l,h.endpoint).get(20,TimeUnit.SECONDS).exceptionOrNull() as PairRefused
    assertEquals(Refusal.USE_CODE,e.refusal);assertNull(store.load())
    val code=h.cmd("info").getString("code")
    assertEquals(Pin.check(h.fp,code),l.pairWithCode(h.endpoint,code).check)
    waitUntil("connected") { l.status.state==LinkState.CONNECTED }
  }
}
