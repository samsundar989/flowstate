package com.overhand.app.hostlink.ui

import com.overhand.app.hostlink.Endpoint
import com.overhand.app.hostlink.LinkState
import com.overhand.app.hostlink.LinkStatus
import com.overhand.app.hostlink.Refusal
import com.overhand.app.hostlink.ui.Diagnosis.Companion.Probe
import org.junit.Assert.*
import org.junit.Test

class ConnectScreenTest {
  companion object {
    val PC=Endpoint("192.0.2.10",8765,"a".repeat(64),"DESK-PC")
    val OTHER=Endpoint("192.0.2.10",8765,"b".repeat(64),"STUDIO")
    /** Every screen the panel can show, for the layout lint and for the word checks. */
    fun all(): Map<String,ConnectScreen> {
      val u=LinkStatus(LinkState.UNPAIRED,"Not paired with a computer")
      return linkedMapOf(
        "searching" to ConnectScreens.of(u,null,null,"",null,false,"your computer"),
        "none" to ConnectScreens.of(u,emptyList(),null,"",Diagnosis.ISOLATED,false,"your computer"),
        "hosts" to ConnectScreens.of(u,listOf(PC,OTHER),null,"",null,false,"your computer"),
        "pairing" to ConnectScreens.of(LinkStatus(LinkState.PAIRING,"Pairing with DESK-PC"),listOf(PC),null,"",null,false,"DESK-PC"),
        "confirm" to ConnectScreens.of(LinkStatus(LinkState.CONFIRM_ON_COMPUTER,"",digits="472913"),listOf(PC),null,"",null,false,"DESK-PC"),
        "keypad" to ConnectScreens.of(u,listOf(PC),PC,"472",null,false,"DESK-PC"),
        "connected" to ConnectScreens.of(LinkStatus(LinkState.CONNECTED,"Connected to DESK-PC"),null,null,"",null,true,"DESK-PC"),
        "reconnecting" to ConnectScreens.of(LinkStatus(LinkState.RECONNECTING,"Reconnecting to DESK-PC"),null,null,"",Diagnosis.FIREWALL,true,"DESK-PC"),
        "refused" to ConnectScreens.of(LinkStatus(LinkState.REFUSED,Refusal.DECLINED.words),listOf(PC),null,"",null,false,"DESK-PC"),
        "other" to ConnectScreens.of(u,listOf(PC),null,"",null,false,"your computer",AddressEntry("other")),
        "address" to ConnectScreens.of(u,emptyList(),null,"",null,false,"your computer",
          AddressEntry("address","studio-laptop.example.net:47821",Refusal.ADDRESS_UNREACHABLE.words)))
    }
  }

  @Test fun eachScreenHasItsKeyAndANextStep() {
    all().forEach { (k,s) -> assertEquals(k,s.key);assertTrue("$k has an action",s.buttons.isNotEmpty() || s.hosts.isNotEmpty()) }
  }
  @Test fun confirmShowsTheSixDigitsSpacedAndNoYesButton() {
    val s=all().getValue("confirm")
    assertEquals("472 913",s.digits);assertEquals("Confirm 472 913 on your computer",s.title)
    assertEquals(listOf("differ","cancel"),s.buttons.map { it.first })
  }
  @Test fun diagnosisCoversEveryCause() {
    assertEquals(Diagnosis.NO_WIFI,Diagnosis.classify(null,24,"192.0.2.10",null))
    assertEquals(Diagnosis.OTHER_NETWORK,Diagnosis.classify("192.0.2.10",24,"192.0.2.10",Probe.TIMEOUT))
    assertEquals(Diagnosis.FIREWALL,Diagnosis.classify("192.0.2.10",24,"192.0.2.10",Probe.TIMEOUT))
    assertEquals(Diagnosis.HOST_STOPPED,Diagnosis.classify("192.0.2.10",24,"192.0.2.10",Probe.REFUSED))
    assertEquals(Diagnosis.ISOLATED,Diagnosis.classify("192.0.2.10",24,"192.0.2.10",Probe.UNREACHABLE))
    assertNull(Diagnosis.classify("192.0.2.10",24,"192.0.2.10",Probe.OPEN))
    assertNull("same /16",Diagnosis.classify("192.0.2.10",16,"192.0.2.10",Probe.OPEN))
  }
  @Test fun addressEntryIsOnlyBehindOtherWaysToConnect() {
    val a=all()
    for(k in listOf("searching","pairing","confirm","keypad","connected","reconnecting","refused")) assertFalse(k,a.getValue(k).buttons.any { it.first=="address" })
    assertTrue(a.getValue("none").buttons.any { it.first=="other" });assertTrue(a.getValue("hosts").buttons.any { it.first=="other" })
    assertEquals(listOf("address","code","back"),a.getValue("other").buttons.map { it.first })
    assertEquals(listOf("dial","back"),a.getValue("address").buttons.map { it.first })
    assertEquals(Refusal.ADDRESS_UNREACHABLE.words,a.getValue("address").lines.single())
    // A refusal from the six-digit step shows on the address page; once paired, the page is gone.
    val e=AddressEntry("address","my-pc")
    assertEquals(Refusal.DECLINED.words,ConnectScreens.of(LinkStatus(LinkState.REFUSED,Refusal.DECLINED.words),null,null,"",null,false,"x",e).lines.single())
    assertEquals("confirm",ConnectScreens.of(LinkStatus(LinkState.CONFIRM_ON_COMPUTER,"",digits="123456"),null,null,"",null,false,"x",e).key)
    assertEquals("connected",ConnectScreens.of(LinkStatus(LinkState.CONNECTED,""),null,null,"",null,true,"x",e).key)
    assertEquals(listOf("a","b","c","d","e","f","g","h"),ConnectScreens.KEYBOARD.first())
    assertTrue(ConnectScreens.KEYBOARD.all { it.size==8 } && ConnectScreens.KEYBOARD.flatten().containsAll(listOf(".","-",":","del")))
  }
  @Test fun overlayAddressIsNotCalledAnotherNetwork() {
    assertNull(Diagnosis.classify("192.0.2.10",24,"192.0.2.10",Probe.OPEN))
    assertEquals(Diagnosis.FIREWALL,Diagnosis.classify("192.0.2.10",24,"192.0.2.10",Probe.TIMEOUT))
    assertEquals(Diagnosis.OTHER_NETWORK,Diagnosis.classify("192.0.2.10",24,"100.200.1.1",Probe.OPEN))
  }
  @Test fun noVendorNamesInAnyWords() {
    val vendor=Regex("(?i)\\b(meta|quest|oculus|windows|microsoft|android|google|apple|claude|codex|openai|anthropic)\\b")
    val words=all().values.flatMap { listOf(it.title)+it.lines+it.buttons.map { b -> b.second } }+Diagnosis.values().map { it.words }
    words.forEach { assertFalse("vendor name in: $it",vendor.containsMatchIn(it)) }
  }
}
