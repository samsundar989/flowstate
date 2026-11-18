package com.overhand.app.diag

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * #1385 main-thread stall watchdog. A daemon thread posts a no-op to the main looper every [PERIOD_MS]; when the main
 * thread is more than [LATE_MS] late it logs once per stall `stall event=main ms=<so far> at=<top 8 frames>`, and on
 * recovery `stall event=end ms=<total>`. During a stall it also samples the full stack every 250 ms, up to 40 times,
 * with separate SDK and app callers. Nothing is posted while the app is paused. The timing logic is pure (fake
 * clock in MainStallTest); [start] wires it to the real looper.
 */
class MainStall(private val clock: ()->Long,private val paused: ()->Boolean,private val stack: ()->String,private val log: (String)->Unit,
  private val sampleStack: ()->Array<StackTraceElement> = { emptyArray() }) {
  companion object {
    const val PERIOD_MS=250L
    const val LATE_MS=500L
    const val MAX_SAMPLES=40
    private var started: Thread?=null

    /** Starts the watchdog once per process (OverhandActivity.onCreate). */
    @Synchronized fun start(paused: ()->Boolean) {
      if(started!=null) return
      val main=Looper.getMainLooper();val handler=Handler(main)
      val w=MainStall({ android.os.SystemClock.uptimeMillis() },paused,{ frames(main.thread) },{ Log.w("Overhand",it) },{ main.thread.stackTrace })
      started=Thread({
        while(true) {
          try { Thread.sleep(PERIOD_MS) } catch(_: InterruptedException) { return@Thread }
          if(w.check()) handler.post { w.answered() }
        }
      },"main-stall").apply { isDaemon=true;start() }
    }
    /** The top 8 frames of [t]'s stack, innermost first: `Class.method:line<...`. */
    fun frames(t: Thread)=t.stackTrace.take(8).joinToString("<") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
    /** Search the entire stack: expensive SDK helpers can put our caller far below frame eight. */
    fun sampleFrames(frames: Array<StackTraceElement>): String {
      fun format(rows: List<StackTraceElement>)=rows.joinToString("<") { "${it.className}.${it.methodName}:${it.lineNumber}" }
      return "sdk=${format(frames.take(3))} app=${format(frames.filter { it.className.startsWith("com.overhand") }.take(4))}"
    }
  }

  private var postedAt=-1L    // when the outstanding probe was posted; -1 when none is
  private var logged=false
  private var samples=0
  private var sampledAt=-1L

  /** Watchdog thread, every [PERIOD_MS]. True when a new probe must be posted now (and it is counted as posted). */
  @Synchronized fun check(): Boolean {
    if(paused()) return false
    val now=clock()
    if(postedAt<0) { postedAt=now;return true }
    val late=now-postedAt
    if(late>LATE_MS && !logged) { logged=true;log("stall event=main ms=$late at=${stack()}") }
    if(logged && samples<MAX_SAMPLES && (sampledAt<0 || now-sampledAt>=PERIOD_MS)) {
      sampledAt=now;samples++
      log("stall event=sample ms=$late ${sampleFrames(sampleStack())}")
    }
    return false
  }

  /** Main thread: the probe ran. Closes a logged stall with its total. */
  @Synchronized fun answered() {
    if(postedAt<0) return
    if(logged) log("stall event=end ms=${clock()-postedAt}")
    logged=false;postedAt=-1;samples=0;sampledAt=-1
  }
}
