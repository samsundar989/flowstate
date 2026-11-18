package com.overhand.app.hands

import org.junit.Assert.assertTrue
import org.junit.Test

/** #800: what always-on hand frames cost the UI thread per frame (the JSON line; the file write is on its own thread). */
class HandFrameLogCostTest {
  private fun hand(dx: Double): HandJoints {
    fun p(x: Double,y: Double,z: Double=0.31)=Point(x+dx,y,z)
    val fingers=(0..3).map { i -> val x=-0.03+i*0.02;Finger(p(x,0.70),p(x,0.73),p(x,0.72,0.33),p(x,0.70,0.33)) }
    return HandJoints(p(0.0,0.64),Finger(p(-0.055,0.68),p(-0.055,0.71),p(-0.055,0.74),p(-0.055,0.77)),fingers)
  }
  @Test fun recordingBothHandsCostsWellUnderAMillisecond() {
    val hands=mapOf("right" to (hand(0.1) to "fingers_open"),"left" to (hand(-0.1) to "no_pose"))
    val gate="visible=true aimed=true busy=false receipt=false selected=-1 armed=true holding=false fire=false"
    repeat(2000) { HandFrameLog.record(it.toLong(),hands,true,0.97,2,gate) }  // warm the JIT
    val n=20000;var bytes=0L
    val t0=System.nanoTime()
    repeat(n) { bytes+=HandFrameLog.record(it.toLong(),hands,true,0.97,2,gate).length }
    val us=(System.nanoTime()-t0)/1000.0/n
    println("HANDFRAME_COST record_us=%.1f bytes_per_line=%d bytes_per_min_at_20hz=%d".format(us,bytes/n,bytes/n*20*60))
    assertTrue("record() took $us us per frame",us<1000.0)
  }
}
