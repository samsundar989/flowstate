package com.overhand.app.hostlink

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * #815: "new project by voice" over the real host. A confirmed request reaches the computer exactly once even when
 * the Wi-Fi drops around it, and nothing is sent while the answers switch is off.
 */
class HostLinkCreateProjectTest {
  private val closers=mutableListOf<AutoCloseable>()
  @After fun tearDown() { closers.asReversed().forEach { runCatching { it.close() } } }
  private var sendReal=true
  private val fast=LinkTiming(backoffBaseMs=200,backoffMaxMs=2_000,pingMs=5_000,connectTimeoutMs=2_000,pairTimeoutMs=5_000,discoveryMs=200)
  private fun paired(): Pair<RealHost,HostLink> {
    val h=RealHost().also { closers.add(it) }
    val l=HostLink(InMemoryPairedHostStore(),NoDiscovery,"Test headset",{ sendReal },fast).also { l -> closers.add(AutoCloseable { l.shutdown() }) }
    l.pairWithQr(h.qr);waitUntil("connected") { l.status.state==LinkState.CONNECTED }
    return h to l
  }
  private fun waitUntil(what: String,ms: Long=15_000,cond: () -> Boolean) {
    val end=System.currentTimeMillis()+ms
    while(!cond()) { if(System.currentTimeMillis()>end) fail("timed out: $what");Thread.sleep(5) }
  }
  private fun projects(h: RealHost)=h.cmd("projects").getJSONArray("projects").length()
  private fun creates(h: RealHost)=h.stats().getJSONObject("counts").optInt("create_project",0)

  @Test fun confirmedRequestMakesOneProjectAndReportsItsIds() {
    val (h,l)=paired()
    val made=l.createProject("new project: a command-line tool that prints the time","c_t1").get(15,TimeUnit.SECONDS)
    assertTrue(made.getString("project_id").startsWith("p_"))
    assertTrue(made.getString("task_id").startsWith("t"))
    assertEquals(1,projects(h));assertEquals(1,creates(h));assertEquals(1,l.createFramesSent)
    val created=l.events.first { it.optString("kind")=="project_created" }
    assertEquals("c_t1",created.getString("req_id"))
    // asking again with the same id is answered from the log, without a frame
    assertEquals(made.toString(),l.createProject("ignored","c_t1").get(5,TimeUnit.SECONDS).toString())
    assertEquals(1,l.createFramesSent)
  }

  @Test fun requestLostInTheDropIsSentAgainAfterResumeAndMakesOneProject() {
    val (h,l)=paired()
    h.cmd("createDrop","when" to "before")             // the socket dies; the host never saw the frame
    val made=l.createProject("new project: a web page with a counter button","c_t2").get(20,TimeUnit.SECONDS)
    // two frames reached the socket (the harness counts the dropped one before dropping it); one project was made
    assertEquals(2,l.createFramesSent);assertEquals(2,creates(h))
    assertEquals(1,projects(h))
    assertTrue(made.getString("project_id").contains("counter"))
  }

  @Test fun requestTheHostTookBeforeTheDropIsNotSentTwice() {
    val (h,l)=paired()
    h.cmd("createDrop","when" to "after")              // handled, then the socket dies before ok/events arrive
    val made=l.createProject("new project: a script that lists files","c_t3").get(20,TimeUnit.SECONDS)
    assertEquals("seen in the replayed log, so never resent",1,l.createFramesSent)
    assertEquals(1,projects(h));assertEquals(1,creates(h))
    assertEquals("c_t3",h.cmd("projects").getJSONArray("created").getJSONArray(0).getString(0))
    assertTrue(made.has("task_id"))
  }

  @Test fun nothingIsSentWhileAnswersAreOffAndItGoesOnceTheyAreOn() {
    val (h,l)=paired()
    sendReal=false
    val f=l.createProject("new project: a tool that prints the date","c_t4")
    Thread.sleep(1_500)
    assertFalse(f.isDone);assertEquals(0,l.createFramesSent);assertEquals(0,creates(h));assertEquals(1,l.creatingCount)
    sendReal=true;l.flush()
    val made: JSONObject=f.get(15,TimeUnit.SECONDS)
    assertEquals(1,creates(h));assertTrue(made.has("project_id"));assertEquals(0,l.creatingCount)
  }
}
