package com.overhand.app.graph.site

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CompletableFuture

/** Import futures complete only if their production owner has not been destroyed. */
class SiteSlowSwapTest {
  private class Model(val state: Int,val due: Long) {
    val future=CompletableFuture<Model>()
    var visible=false
    var cull=false
    var destroyed=false
  }
  @Test fun continuousRequestsMakeProgressAndConverge() {
    val out=File(System.getenv("SITE_SLOW_OUT") ?: "../../../runs/site/t1274").apply { mkdirs() }
    val rows=StringBuilder("case\tfirst_visible_ms\tlatest_start_ms\tlatest_visible_ms\tconvergence_from_stop_ms\tlatest_lag_ms\tvanish\tduplicates\tmax_inflight\tmax_queued\tactivated_states\n")
    var starved=false
    for(warm in listOf(false,true)) {
      var now=0L;var first=-1L;var latestStart=-1L;var latestVisible=-1L
      var vanish=0;var duplicates=0;var maxInflight=0;var maxQueued=0;var started=false
      val activated=linkedSetOf<Int>()
      val objects=ArrayList<Model>()
      val swap=SiteSwap<Model>({ now },{ m,on ->
        if(on) { assertTrue(m.future.isDone);assertTrue(m.cull) }
        m.visible=on
      },{ m -> m.destroyed=true;m.visible=false;m.future.cancel(false) },{})
      fun request(state: Int,latency: Long) = swap.request("state_$state",{
        Model(state,now+latency).also { objects.add(it);if(state==5) latestStart=now }
      })
      if(warm) { request(-1,0);val m=swap.pending!!;m.future.complete(m);swap.poll(m,true) { m.cull=true };started=true }
      var next=0
      for(t in 0L..12000L step 14) {
        now=t
        if(next<=5 && now>=next*1000L) { request(next,2000);next++ }
        objects.filter { !it.destroyed && now>=it.due && !it.future.isDone }.forEach { it.future.complete(it) }
        val m=swap.pending
        if(m!=null) swap.poll(if(m.future.isDone && !m.future.isCancelled) m.future.getNow(null) else null,m.future.isDone && !m.future.isCancelled) { m.cull=true }
        val count=objects.count { it.visible }
        if(count>0) { if(first<0 && (swap.active?.state ?: -1)>=0) first=now;started=true }
        if(started && count==0) vanish++
        if(count>1) duplicates++
        swap.active?.let { activated.add(it.state) }
        maxQueued=maxOf(maxQueued,swap.queuedCount)
        maxInflight=maxOf(maxInflight,objects.count { !it.destroyed && !it.future.isDone })
        if(swap.active?.state==5 && latestVisible<0) latestVisible=now
      }
      rows.append("${if(warm) "warm" else "cold"}\t$first\t$latestStart\t$latestVisible\t${if(latestVisible<0) -1 else latestVisible-5012}\t${if(latestVisible<0) -1 else latestVisible-latestStart}\t$vanish\t$duplicates\t$maxInflight\t$maxQueued\t${activated.joinToString(",")}\n")
      File(out,"slow.tsv").writeText(rows.toString())
      if(first !in 0..2028) starved=true
      assertTrue("latest import never converged: $rows",latestVisible>=latestStart && latestStart>=0)
      assertTrue(latestVisible-latestStart<=2028)
      assertEquals(0,vanish);assertEquals(0,duplicates);assertTrue(maxInflight<=1);assertTrue(maxQueued<=1)
      swap.clear();assertTrue(objects.all { it.destroyed })
    }
    assertFalse("first activation/progress starved: $rows",starved)
  }
  @Test fun failureTimeoutAndStaleOwnerRecoverWithoutDiscardingVisibleModel() {
    var now=0L;var retries=0
    val visible=hashSetOf<Int>();val dead=hashSetOf<Int>();val discarded=hashSetOf<Int>()
    val swap=SiteSwap<Int>({ now },{ n,on -> if(on) visible.add(n) else visible.remove(n);Unit },{ dead.add(it);visible.remove(it);Unit },{},timeoutMs=2100,failed={ retries++ })
    fun request(n: Int)=swap.request("state_$n",{ n },{ discarded.add(n);Unit })
    request(0);swap.poll(0,true) {};assertEquals(setOf(0),visible)
    request(1);request(2);request(3)
    assertEquals(setOf(2),discarded);assertEquals(1,swap.queuedCount)
    swap.fail("import");assertEquals(setOf(0),visible);assertEquals(3,swap.pending);assertEquals(0,retries)
    swap.poll(1,true) { fail("stale failed owner must not activate") }
    now=2100;swap.poll(3,false) {};assertEquals(setOf(0),visible);assertEquals(1,retries)
    request(4);swap.poll(3,true) { fail("stale timeout owner must not activate") }
    now=4102;swap.poll(4,true) {};assertEquals(setOf(4),visible)
    request(5);request(6);swap.cancelPending();assertTrue(5 in dead);assertTrue(6 in discarded)
    swap.poll(5,true) { fail("closed completion") };assertEquals(setOf(4),visible)
    swap.request("factory_error",{ throw IllegalStateException("create") },{ discarded.add(7);Unit })
    assertEquals(setOf(4),visible);assertEquals(2,retries);assertTrue(7 in discarded)
    request(8);swap.poll(8,true) {};assertEquals(setOf(8),visible)
    swap.clear();assertTrue(visible.isEmpty());assertNull(swap.pending);assertEquals(0,swap.queuedCount)
    File(System.getenv("SITE_SLOW_OUT") ?: "../../../runs/site/t1274","recovery.json").writeText("{\"failure\":true,\"timeout\":true,\"stale_owner\":true,\"retry_callbacks\":2,\"cleanup\":true}")
  }

}
