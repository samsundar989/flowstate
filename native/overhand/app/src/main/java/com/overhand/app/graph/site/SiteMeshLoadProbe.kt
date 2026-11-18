package com.overhand.app.graph.site

import java.util.concurrent.CompletableFuture

/** Diagnostic only: MeshManager destroys the previous object before completing the replacement future. */
internal class SiteMeshLoadProbe(private val now: () -> Long) {
  var sequence=0L;private set
  var pending=false;private set
  private var before: CompletableFuture<*>?=null
  private var writtenAt=0L
  fun write(previous: CompletableFuture<*>?) {
    sequence++;writtenAt=now();before=previous;pending=true
  }
  fun observe(current: CompletableFuture<*>?,ready: (Long,Long,Throwable?) -> Unit) {
    if(!pending || current==null || current===before) return
    pending=false
    val seq=sequence;val start=writtenAt
    current.whenComplete { _,error -> ready(seq,now()-start,error) }
  }
}
