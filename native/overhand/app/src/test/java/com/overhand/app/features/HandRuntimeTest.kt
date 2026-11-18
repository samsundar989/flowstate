package com.overhand.app.features

import com.overhand.app.graph.*
import com.overhand.app.windows.RepeatInputGate
import org.junit.Assert.*
import org.junit.Test

/** Production graph edge gate + hit test in their runtime order; no Android/hand simulation claim. */
class HandRuntimeTest {
  private class Input {
    val model=GraphLayout.build(listOf(Component("bridge",emptyList())))
    val gate=RepeatInputGate()
    val selected=mutableListOf<String>()
    fun frame(down: Boolean,now: Long,empty: Boolean=false) {
      if(gate.update("left",down,true,now)) {
        GraphLayout.hit(model,Vec(if(empty) 10.0 else 0.0,0.0,-1.0),Vec(0.0,0.0,1.0))
          ?.let { selected.add(it) }
      }
    }
  }

  @Test fun doublePinchSelectsOnceThenDeliberatePinchStillWorks() {
    val input=Input()
    input.frame(true,1000)
    input.frame(false,1020)
    input.frame(true,1100)
    input.frame(true,1800) // held second pinch must not fire after cooldown
    assertEquals(listOf("bridge"),input.selected)
    input.frame(false,1810)
    input.frame(true,1900)
    assertEquals(listOf("bridge","bridge"),input.selected)
  }

  @Test fun releaseOnEmptySpaceDoesNotSelectOrLatchNextValidPinch() {
    val input=Input()
    input.frame(true,1000,empty=true)
    input.frame(true,1600) // moving an already-held pinch onto a node is no new edge
    input.frame(false,1610,empty=true) // release/drop has no action
    assertTrue(input.selected.isEmpty())
    input.frame(true,1700)
    input.frame(false,1710,empty=true)
    assertEquals(listOf("bridge"),input.selected)
    input.frame(true,2300)
    assertEquals(listOf("bridge","bridge"),input.selected)
  }
}
