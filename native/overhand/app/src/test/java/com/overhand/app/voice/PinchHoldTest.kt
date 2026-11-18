package com.overhand.app.voice

import com.overhand.app.hands.Finger
import com.overhand.app.hands.HandJoints
import com.overhand.app.hands.Point
import org.junit.Assert.assertEquals
import org.junit.Test

class PinchHoldTest {
  /** A hand whose thumb tip is [gap] metres from its index tip. */
  private fun hand(gap: Double): HandJoints {
    val p = Point(0.0, 0.0, 0.0); val f = Finger(p, p, p, Point(0.1, 0.0, 0.0))
    return HandJoints(p, Finger(p, p, p, Point(0.1 + gap, 0.0, 0.0)), listOf(f, f, f, f))
  }

  @Test fun aHeldPinchStartsAfterTheHoldTimeAndEndsWhenOpened() {
    val ph = PinchHold()
    assertEquals(PinchHold.Edge.NONE, ph.update(0, mapOf("right" to hand(0.01))))
    assertEquals(PinchHold.Edge.NONE, ph.update(300, mapOf("right" to hand(0.01))))
    assertEquals(PinchHold.Edge.START, ph.update(360, mapOf("right" to hand(0.01))))
    assertEquals(PinchHold.Edge.NONE, ph.update(900, mapOf("right" to hand(0.03)))) // hysteresis: half open still holds
    assertEquals(PinchHold.Edge.END, ph.update(950, mapOf("right" to hand(0.05))))
  }

  @Test fun aQuickPinchNeverOpensTheMic() {
    val ph = PinchHold()
    ph.update(0, mapOf("left" to hand(0.01))); ph.update(200, mapOf("left" to hand(0.05)))
    assertEquals(PinchHold.Edge.NONE, ph.update(400, mapOf("left" to hand(0.01))))
  }

  @Test fun losingTheHoldingHandEndsTheHold() {
    val ph = PinchHold()
    ph.update(0, mapOf("left" to hand(0.01))); assertEquals(PinchHold.Edge.START, ph.update(400, mapOf("left" to hand(0.01))))
    assertEquals(PinchHold.Edge.END, ph.update(450, mapOf("left" to null)))
  }
}
