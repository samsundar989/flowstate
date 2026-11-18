package com.overhand.app.hostlink.project

import com.overhand.app.hostlink.Frames
import com.overhand.app.hostlink.project.FollowUpFlow.Effect
import com.overhand.app.hostlink.project.FollowUpFlow.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #973: a spoken change to the open project shows a chip; only its Send or a spoken yes makes the `prompt`. */
class FollowUpFlowTest {
  @Test fun aChangeShowsTheChipAndNothingIsSentUntilYes() {
    val sent=mutableListOf<String>()
    val f=FollowUpFlow().apply { reqIdMaker={ "c_change_1" } }
    assertEquals(Effect.SHOW,f.heard("Make the buttons bigger.","web page counter button","p_web"))
    assertEquals(State.Chip("web page counter button","p_web","make the buttons bigger"),f.state)
    assertEquals("Change web page counter button: make the buttons bigger?",FollowUpFlow.chip("web page counter button","make the buttons bigger"))
    // not a yes or no: the chip stays, nothing is made
    assertEquals(Effect.HINT,f.heard("what time is it","web page counter button","p_web"))
    assertTrue(f.state is State.Chip);assertTrue(sent.isEmpty())
    assertEquals(Effect.SEND,f.heard("yes","web page counter button","p_web"))
    val s=f.state as State.Sending
    val frame=Frames.prompt(s.reqId,s.projectId,s.text)
    assertEquals("prompt",frame.getString("type"));assertEquals("c_change_1",frame.getString("req_id"))
    assertEquals("p_web",frame.getString("project_id"));assertEquals("make the buttons bigger",frame.getString("text"))
    assertEquals(Effect.NONE,f.confirm())                 // a second pinch does not send twice
    assertEquals(Effect.HINT,f.heard("add a reset button","web page counter button","p_web"))   // one change in flight
    f.sent("c_change_1");assertEquals(State.Idle,f.state)
  }

  @Test fun noOrDiscardDropsIt() {
    val f=FollowUpFlow()
    f.heard("now add a reset button","counter","p_web")
    assertEquals(Effect.DISCARDED,f.heard("no","counter","p_web"));assertEquals(State.Idle,f.state)
    f.heard("change the colour to green","counter","p_web")
    assertEquals(Effect.DISCARDED,f.discard());assertEquals(Effect.NONE,f.confirm())
  }

  @Test fun onlyRequestsThatLeadWithChangeMakeAddOrNowCount() {
    for(t in listOf("change the title","Make it blue","add a reset button","Now show the total","okay, make the text larger"))
      assertTrue(t,FollowUpFlow.isChange(t))
    for(t in listOf("yes","no","make","the agent will make it later","new project a quiz game","make a new project, a timer",""))
      assertFalse(t,FollowUpFlow.isChange(t))
    assertFalse(FollowUpFlow().wants("yes"))              // with no chip a yes is not ours (it may answer a question)
  }

  companion object {
    /** The project window with the change chip on it, for the layout lint. */
    fun screens(): List<Pair<String,ProjectScreen>> {
      val f=FollowUpFlow();f.heard("Make the buttons bigger and add a reset button under the count","web page counter button","p_web")
      val chip=FollowUpFlow.screen(f.state)!!
      f.confirm()
      return listOf("followup_chip" to chip,"followup_sending" to FollowUpFlow.screen(f.state)!!)
    }
  }
}
