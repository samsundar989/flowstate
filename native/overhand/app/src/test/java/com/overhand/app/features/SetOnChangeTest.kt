package com.overhand.app.features

import com.overhand.app.graph.GraphLod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #944: per-frame component writes happen only on change. */
class SetOnChangeTest {
  private enum class Tone { IDLE, LIT, HELD }

  @Test fun unchangedStateGivesZeroWritesAfterTheFirst() {
    val c=SetOnChange<String,Tone>();var applied=0
    c.set("bar",Tone.IDLE) { applied++ }
    repeat(600) { assertFalse(c.set("bar",Tone.IDLE) { applied++ }) }
    assertEquals(1,applied);assertEquals(1L,c.writes)
  }

  @Test fun aColourChangeGivesExactlyOneWrite() {
    val c=SetOnChange<String,Tone>();val seen=mutableListOf<Tone>()
    c.set("bar",Tone.IDLE) { seen.add(it) }
    val before=c.writes
    repeat(90) { c.set("bar",Tone.HELD) { seen.add(it) } }
    assertEquals(1L,c.writes-before)
    assertEquals(listOf(Tone.IDLE,Tone.HELD),seen)
  }

  @Test fun forgetForcesTheNextWriteAndKeysAreIndependent() {
    val c=SetOnChange<String,Boolean>()
    assertTrue(c.set("a",true) {});assertTrue(c.set("b",true) {})
    assertFalse(c.set("a",true) {})
    c.forget("a");assertTrue(c.set("a",true) {})
  }

  @Test fun poseWithinEpsilonIsSkippedAndBeyondIsWritten() {
    val c=SetOnChange<Int,FloatArray> { a,b -> SetOnChange.near(a,b,SetOnChange.POSE_EPS) }
    val p=floatArrayOf(0.1f,1.2f,-0.5f,1f,0f,0f,0f)
    assertTrue(c.set(1,p) {})
    assertFalse(c.set(1,p.copyOf().also { it[0]+=0.00005f }) {})
    assertTrue(c.set(1,p.copyOf().also { it[0]+=0.001f }) {})
  }

  @Test fun resizeIsThrottledToTenHertzAndOneCentimetre() {
    val r=ResizeThrottle()
    // First step of a drag applies (>= 2 mm).
    assertTrue(r.shouldApply("w",0,0.605,0.56,0.60,0.56))
    // 50 ms later: too soon even for a big step.
    assertFalse(r.shouldApply("w",50,0.70,0.65,0.605,0.56))
    // 150 ms later but under 1 cm: skipped.
    assertFalse(r.shouldApply("w",150,0.612,0.567,0.605,0.56))
    // 150 ms later and >= 1 cm: applied.
    assertTrue(r.shouldApply("w",150,0.62,0.575,0.605,0.56))
    // A 72 Hz drag over one second applies at most 10 times.
    val r2=ResizeThrottle();var applied=0;var cur=0.60
    for(f in 0 until 72) { val w=0.60+f*0.004;if(r2.shouldApply("w",f*1000L/72,w,w,cur,cur)) { applied++;cur=w } }
    assertTrue("applied=$applied",applied in 1..10)
    // Release resets: the next drag starts unthrottled.
    r2.end("w");assertTrue(r2.shouldApply("w",1001,cur+0.003,cur+0.003,cur,cur))
  }

  @Test fun haloBreathIsFourStepsWithFewWritesPerCycle() {
    val c=SetOnChange<String,Int>();var writes=0
    for(ms in 0L until 1600L step 14) c.set("halo",GraphLod.breathStep(ms,4)) { writes++ }
    // 114 frames per 1.6 s breath were 114 Material writes; quantised they are at most 7.
    assertTrue("writes=$writes",writes in 2..7)
    assertEquals(0,GraphLod.breathStep(0,4));assertEquals(3,GraphLod.breathStep(800,4))
    assertEquals(0.20,GraphLod.breathAt(0,4),1e-9);assertEquals(0.70,GraphLod.breathAt(3,4),1e-9)
  }
}
