package com.overhand.app.hostlink

import com.overhand.app.hostlink.project.FollowUpFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * #973: the confirmed change chip reaches the real host as exactly one `prompt` on the open project, and nothing is
 * sent before the yes or while sending is off.
 */
class HostLinkFollowUpTest {
  private val closers=mutableListOf<AutoCloseable>()
  @After fun tearDown() { closers.asReversed().forEach { runCatching { it.close() } } }
  private var sendReal=true
  private val fast=LinkTiming(backoffBaseMs=200,backoffMaxMs=2_000,pingMs=5_000,connectTimeoutMs=2_000,pairTimeoutMs=5_000,discoveryMs=200)
  private fun waitUntil(what: String,ms: Long=15_000,cond: () -> Boolean) {
    val end=System.currentTimeMillis()+ms
    while(!cond()) { if(System.currentTimeMillis()>end) throw AssertionError("timed out: $what");Thread.sleep(5) }
  }
  private fun prompts(h: RealHost)=h.stats().getJSONObject("counts").optInt("prompt",0)

  @Test fun theChipGoesToPromptOnlyAfterYes() {
    val h=RealHost().also { closers.add(it) }
    val l=HostLink(InMemoryPairedHostStore(),NoDiscovery,"Test headset",{ sendReal },fast).also { l -> closers.add(AutoCloseable { l.shutdown() }) }
    l.pairWithQr(h.qr);waitUntil("connected") { l.status.state==LinkState.CONNECTED }
    val made=l.createProject("new project: a web page with a counter button","c_np").get(15,TimeUnit.SECONDS)
    val pid=made.getString("project_id")

    val f=FollowUpFlow().apply { reqIdMaker={ "c_change_1" } }
    fun say(t: String)=f.heard(t,"counter",pid).also { if(it==FollowUpFlow.Effect.SEND) (f.state as FollowUpFlow.State.Sending).let { s -> pending=l.followUp(s.projectId,s.text,s.reqId) } }
    say("make the buttons bigger")
    Thread.sleep(500)
    assertEquals("chip only: nothing reached the computer",0,prompts(h));assertEquals(0,l.promptFramesSent)
    sendReal=false
    say("yes")
    Thread.sleep(800)
    assertEquals("sending is off",0,prompts(h));assertFalse(pending!!.isDone)
    sendReal=true;l.flush()
    val queued=pending!!.get(15,TimeUnit.SECONDS)
    assertEquals(pid,queued.getString("project_id"));assertTrue(queued.optString("task_id").isNotEmpty())
    assertEquals(1,prompts(h));assertEquals(1,l.promptFramesSent);assertEquals(0,l.creatingCount)
  }
  private var pending: java.util.concurrent.CompletableFuture<org.json.JSONObject>?=null
}
