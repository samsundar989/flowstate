package com.overhand.app.mr
import org.junit.Assert.*
import org.junit.Test
class PanelFacingTest {
  @Test fun savedPanelsStayUpUnderACardAndYieldToTheGraph() { // #1341 (was: yield to the card, #337)
    assertTrue(com.overhand.app.features.ControlDock.panelVisible(false,PanelFacing.front(0f,0f,1f,0f,0f,-1f)))
    assertFalse(com.overhand.app.features.ControlDock.panelVisible(true,PanelFacing.front(0f,0f,1f,0f,0f,-1f)))
    assertFalse(com.overhand.app.features.ControlDock.panelVisible(false,PanelFacing.front(0f,0f,1f,0f,0f,1f)))
  }
  @Test fun readableFrontRemainsVisible() { assertTrue(PanelFacing.front(0f,0f,1f,0f,0f,-1f)) }
  @Test fun mirroredBackIsHidden() { assertFalse(PanelFacing.front(0f,0f,1f,0f,0f,1f)) }
  @Test fun edgeIsHidden() { assertFalse(PanelFacing.front(0f,0f,1f,1f,0f,0f)) }
  @Test fun rotatedFrontRemainsVisible() { assertTrue(PanelFacing.front(1f,0f,0f,-2f,1f,0f)) }
}
