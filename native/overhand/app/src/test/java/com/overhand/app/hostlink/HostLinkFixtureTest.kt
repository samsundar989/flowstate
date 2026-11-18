package com.overhand.app.hostlink

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** The wire contract, from the host's own fixture files (host/test/fixtures/protocol). No network. */
class HostLinkFixtureTest {
  private fun fixture(name: String)=JSONObject(File(Repo.fixtures,"$name.json").readText())
  private fun frames(name: String)=fixture(name).getJSONArray("frames").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

  @Test fun qrPayloadAndCheckCodesMatchTheHost() {
    val pair=fixture("pair")
    val invite=PairingInvite.parse(pair.getString("qr"))
    val ci=pair.getJSONObject("check_inputs")
    assertEquals(ci.getString("fp"),invite.endpoint.fp)
    assertEquals("192.0.2.10",invite.endpoint.address)
    assertEquals(47821,invite.endpoint.port)
    assertEquals(ci.getString("token"),invite.token)
    assertEquals("wss://192.0.2.10:47821/v1",invite.endpoint.url)
    assertEquals(ci.getString("check_for_token"),Pin.check(ci.getString("fp"),ci.getString("token")))
    assertEquals(ci.getString("check_for_code"),Pin.check(ci.getString("fp"),ci.getString("code")))
    // The host's paired frame carries the same check the headset computes.
    assertEquals(frames("pair").first { it.getString("type")=="paired" }.getString("check"),Pin.check(ci.getString("fp"),ci.getString("token")))
  }

  @Test fun malformedPairingTextIsRefusedInPlainWords() {
    val good=fixture("pair").getString("qr")
    for(bad in listOf("https://example.com",good.replace("v=1","v=2"),good.replace(Regex("fp=[0-9a-f]+"),"fp=abc"),good.replace(Regex("&t=[^&]+"),""),"")) {
      try { PairingInvite.parse(bad);fail("accepted $bad") } catch(e: PairRefused) { assertTrue(e.message!!.isNotBlank()) }
    }
  }

  @Test fun everyHostErrorCodeHasWordsWithoutVendorNames() {
    val codes=listOf("bad_code","code_expired","bad_token","token_expired","token_used","rate_limited","unauthorized","internal")
    for(c in codes) assertTrue(Refusal.ofCode(c).words.isNotBlank())
    val brand=Regex("(?i)claude|anthropic|codex|openai|gpt|meta|quest|oculus|google|android")
    for(r in Refusal.values()) assertTrue(r.words,!brand.containsMatchIn(r.words))
    assertEquals(Refusal.BAD_CODE,Refusal.ofCode(frames("error").firstOrNull { it.optString("code")=="bad_code" }?.getString("code") ?: "bad_code"))
  }

  @Test fun everyHostFrameInTheFixturesIsUnderstood() {
    val files=Repo.fixtures.listFiles { f -> f.name.endsWith(".json") }!!
    assertTrue(files.size>=8)
    val known=setOf("paired","welcome","ok","event","error","pair_commit","pair_reveal","pair_pending","pair_refused","file_chunk") // + confirm-on-computer (2d), #972 preview files
    for(f in files) for(fr in frames(f.nameWithoutExtension).filter { it.getString("dir")=="h2c" }) {
      assertTrue("${f.name}: ${fr.getString("type")}",fr.getString("type") in known)
      if(fr.getString("type")=="event") { assertTrue(fr.getLong("seq")>=1);assertTrue(fr.getString("title").isNotBlank()) }
    }
  }

  @Test fun headsetFramesMatchTheFixturesFieldForField() {
    fun same(fixture: String,type: String,mine: JSONObject) {
      val want=frames(fixture).first { it.getString("dir")=="c2h" && it.getString("type")==type }.apply { remove("dir") }
      assertEquals("$fixture/$type keys",want.keySet(),mine.keySet())
      for(k in want.keySet()) assertEquals("$fixture/$type.$k",want.get(k).toString(),mine.get(k).toString())
    }
    val pt=frames("pair").first { it.optString("type")=="pair_token" }
    same("pair","pair_token",Frames.pairToken(pt.getString("token"),pt.getString("device_name")))
    val pc=frames("pair_code").first { it.optString("type")=="pair_code" }
    same("pair_code","pair_code",Frames.pairCode(pc.getString("code"),pc.getString("device_name")))
    val r=frames("resume").first { it.optString("type")=="resume" }
    same("resume","resume",Frames.resume(r.getString("device_id"),r.getString("device_secret"),r.getLong("last_seq")))
    val a=frames("decision_answered").first { it.optString("type")=="answer" }
    same("decision_answered","answer",Frames.answer(a.getString("req_id"),a.getString("decision_id"),a.getString("option_id")))
    val pr=frames("pair_confirm").first { it.optString("type")=="pair_request" }
    same("pair_confirm","pair_request",Frames.pairRequest(pr.getString("device_pub"),pr.getString("device_name")))
    same("pair_confirm","pair_nonce",Frames.pairNonce(frames("pair_confirm").first { it.optString("type")=="pair_nonce" }.getString("nonce")))
  }

  @Test fun pinAcceptsOnlyTheExactCertificate() {
    val tm=PinningTrustManager("0".repeat(64))
    try { tm.checkServerTrusted(emptyArray(),"ECDHE_ECDSA");fail("empty chain accepted") } catch(e: java.security.cert.CertificateException) {}
    assertEquals(0,tm.acceptedIssuers.size)
  }
}
