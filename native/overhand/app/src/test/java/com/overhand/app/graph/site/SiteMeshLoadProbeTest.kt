package com.overhand.app.graph.site

import java.util.concurrent.CompletableFuture
import org.junit.Assert.*
import org.junit.Test

class SiteMeshLoadProbeTest {
  @Test fun ignoresOldCompletedObjectAndMeasuresReplacementCompletion() {
    var time=100L;val p=SiteMeshLoadProbe { time };val old=CompletableFuture.completedFuture("old")
    val rows=mutableListOf<Long>();p.write(old)
    p.observe(old) { _,gap,_ -> rows.add(gap) };assertTrue(rows.isEmpty());assertTrue(p.pending)
    val next=CompletableFuture<String>();time=110
    p.observe(next) { seq,gap,error -> assertEquals(1L,seq);assertNull(error);rows.add(gap) }
    assertTrue(rows.isEmpty());time=147;next.complete("new");assertEquals(listOf(47L),rows)
    p.observe(next) { _,gap,_ -> rows.add(gap) };assertEquals(1,rows.size)
  }
  @Test fun firstLoadAndFailureAreNotInventedZeroGaps() {
    var time=5L;val p=SiteMeshLoadProbe { time };p.write(null)
    p.observe(null) { _,_,_ -> fail("no object") };assertTrue(p.pending)
    val next=CompletableFuture<String>();var failure: Throwable?=null;var gap=-1L
    p.observe(next) { _,g,e -> gap=g;failure=e };time=25
    next.completeExceptionally(IllegalStateException("load failed"))
    assertEquals(20L,gap);assertNotNull(failure)
  }
}
