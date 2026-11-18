package com.overhand.app.windows

import org.junit.Assert.*
import org.junit.Test

class PanelTransitionTest {
  @Test fun showAndHideTakeTwoHundredMilliseconds() {
    val m=PanelTransition();m.target(true,1000)
    assertEquals(0f,m.alpha(1000),0.001f)
    assertTrue(m.alpha(1120) in 0.6f..0.7f)
    assertEquals(1f,m.alpha(1240),0.001f)
    m.target(false,2000)
    assertEquals(1f,m.alpha(2000),0.001f)
    assertTrue(m.alpha(2120) in 0.3f..0.4f)
    assertEquals(0f,m.alpha(2240),0.001f)
    assertEquals(0.975f,m.scale(2240),0.001f)
  }
  @Test fun interruptionReversesFromCurrentValueWithoutAPop() {
    val m=PanelTransition();m.target(true,1000)
    val a=m.alpha(1100);m.target(false,1100)
    assertEquals(a,m.alpha(1100),0.00001f)
    assertFalse(m.target(false,1150))
    assertEquals(0f,m.alpha(1300),0.00001f)
  }
}
