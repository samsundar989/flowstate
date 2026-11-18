package com.overhand.app.hostlink

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * "Enter computer address" (#954): the typed name is parsed, the computer's certificate is read without sending
 * anything, and the six-digit confirm then runs exactly as on Wi-Fi. A relay that answers at the typed address is
 * the case ConfirmPairingTest.relay* already covers (the headset pins whatever certificate it was shown).
 */
class ManualAddressTest {
  private val closers=mutableListOf<AutoCloseable>()
  @After fun tearDown() { closers.asReversed().forEach { runCatching { it.close() } } }

  @Test fun parsesNamesAddressesAndPorts() {
    assertEquals("my-pc" to ManualAddress.DEFAULT_PORT,ManualAddress.parse(" My-PC "))
    assertEquals("my-pc.example.net" to 9000,ManualAddress.parse("my-pc.example.net:9000"))
    assertEquals("192.0.2.10" to ManualAddress.DEFAULT_PORT,ManualAddress.parse("192.0.2.10"))
    for(bad in listOf("","-pc","pc-","a..b","pc:0","pc:70000","pc:x","p c","pc/1","x".repeat(65)))
      assertNull(bad,ManualAddress.parse(bad))
  }

  @Test fun refusalsHavePlainWords() {
    assertEquals(Refusal.NOT_AN_ADDRESS,(runCatching { ManualAddress.endpoint("not an address") }.exceptionOrNull() as PairRefused).refusal)
    assertEquals(Refusal.ADDRESS_UNREACHABLE,(runCatching { ManualAddress.endpoint("127.0.0.1:1",1000) }.exceptionOrNull() as PairRefused).refusal)
  }

  @Test fun typedAddressReadsTheHostsCertificateThenPairsBySixDigits() {
    val h=RealHost().also { closers.add(it) }
    val ep=ManualAddress.endpoint("${h.address}:${h.port}")
    assertEquals("the probe saw the host's own certificate",h.fp,ep.fp)
    assertEquals("nothing reached the pairing code before the digits",false,h.cmd("confirm").getBoolean("pending"))
    val store=InMemoryPairedHostStore()
    val l=HostLink(store,NoDiscovery,"Test headset",{ true },LinkTiming(pairTimeoutMs=5_000,confirmTimeoutMs=20_000)).also { closers.add(AutoCloseable { it.shutdown() }) }
    val f=CompletableFuture.supplyAsync { l.pairByConfirm(ep) }
    val end=System.currentTimeMillis()+15_000
    while(l.status.state!=LinkState.CONFIRM_ON_COMPUTER) { assertTrue("digits shown",System.currentTimeMillis()<end);Thread.sleep(5) }
    assertEquals(h.cmd("confirm").getString("digits"),l.status.digits)
    h.cmd("decide","yes" to true)
    f.get(20,TimeUnit.SECONDS)
    assertEquals(h.fp,store.load()!!.fp);assertEquals(h.address,store.load()!!.endpoint.address)
  }
}
