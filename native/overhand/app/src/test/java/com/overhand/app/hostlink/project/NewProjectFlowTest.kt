package com.overhand.app.hostlink.project

import com.overhand.app.hostlink.project.NewProjectFlow.Effect
import com.overhand.app.hostlink.project.NewProjectFlow.State
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewProjectFlowTest {
  @Test fun oneUtteranceShowsTheChipAndNothingIsSentUntilConfirmed() {
    val f=NewProjectFlow()
    assertEquals(Effect.SHOW,f.heard("New project, a command-line tool that prints the time."))
    assertEquals(State.Chip("a command-line tool that prints the time"),f.state)
    assertEquals("Build: a command-line tool that prints the time. Start?",NewProjectFlow.chip("a command-line tool that prints the time"))
    // anything that is not a yes or a no leaves the chip as it is
    assertEquals(Effect.HINT,f.heard("what is the weather like today"))
    assertTrue(f.state is State.Chip)
    assertEquals(Effect.SEND,f.heard("Yes."))
    val s=f.state as State.Sending
    assertEquals("new project: a command-line tool that prints the time",NewProjectFlow.prompt(s.description))
    f.created(s.reqId,"p_x","t1")
    assertEquals(State.Tracking("a command-line tool that prints the time","p_x","t1"),f.state)
  }

  @Test fun bareNewProjectOpensDictationForTheDescription() {
    val f=NewProjectFlow()
    assertEquals(Effect.SHOW,f.heard("new project"))
    assertEquals(State.Dictating,f.state)
    assertEquals(Effect.HINT,f.heard("um"))
    assertEquals(Effect.SHOW,f.heard("A web page with a counter button"))
    assertEquals(State.Chip("a web page with a counter button"),f.state)
  }

  @Test fun noOrDiscardDropsTheChipAndPinchStartSends() {
    val f=NewProjectFlow()
    f.heard("start a new project called a dice roller")
    assertEquals(State.Chip("a dice roller"),f.state)
    assertEquals(Effect.DISCARDED,f.heard("no"))
    assertEquals(State.Idle,f.state)
    f.heard("new project a timer app")
    assertEquals(Effect.SEND,f.confirm())
    assertEquals(Effect.NONE,f.confirm())                     // a second pinch does not send twice
    assertEquals(Effect.HINT,f.heard("new project something else entirely"))   // one request in flight
  }

  @Test fun onlyShortRepliesCountAsYesOrNo() {
    for(y in listOf("yes","Yeah.","Yes please","go ahead","OK","Sure, start it")) assertEquals(y,true,NewProjectFlow.yesNo(y))
    for(n in listOf("no","No thanks","Discard","cancel it","never mind")) assertEquals(n,false,NewProjectFlow.yesNo(n))
    for(x in listOf("yes and no","I said no to the counter button","maybe","","a web page")) assertNull(x,NewProjectFlow.yesNo(x))
  }

  @Test fun newProjectMidSentenceIsNotACommand() {
    assertNull(NewProjectFlow.describedProject("the agent said it would start the new project later"))
    assertEquals("",NewProjectFlow.describedProject("new projects"))
    assertEquals("a quiz game",NewProjectFlow.describedProject("Okay, new project: a quiz game."))
  }

  @Test fun yesNoAnswersAQuestionOnlyWhileOneIsOpen() {
    val f=NewProjectFlow()
    f.heard("new project a clock");f.confirm();f.created((f.state as State.Sending).reqId,"p","t")
    assertEquals(Effect.HINT,f.heard("yes",questionOpen=false))
    assertEquals(Effect.ANSWER_YES,f.heard("yes",questionOpen=true))
    assertEquals(Effect.ANSWER_NO,f.heard("no",questionOpen=true))
  }

  private fun ev(seq: Long,kind: String,vararg kv: Pair<String,Any>)=JSONObject().put("type","event").put("seq",seq).put("project_id","p_clock")
    .put("kind",kind).put("title",kv.firstOrNull { it.first=="title" }?.second ?: kind).apply { kv.forEach { (k,v) -> put(k,v) } }

  @Test fun timelineShowsStartedFilesCommandsQuestionAndTheCommitWithTheProgramsFirstLine() {
    val t=ProjectTimeline("p_clock")
    assertTrue(t.add(ev(1,"project_created","name" to "command line tool prints time"),1000))
    assertFalse("another project's event",t.add(ev(2,"started").put("project_id","p_other")))
    t.add(ev(3,"task_state","state" to "running"))
    t.add(ev(4,"started"))
    t.add(ev(5,"question","title" to "Should it print seconds too?","decision_id" to "q_t1_1",
      "options" to JSONArray().put(JSONObject().put("id","o1").put("label","Yes")).put(JSONObject().put("id","o2").put("label","No"))))
    t.add(ev(6,"task_state","state" to "needs_answer"))
    assertEquals("q_t1_1",t.question!!.getString("decision_id"))
    assertEquals("question",ProjectScreens.of(NewProjectFlow.State.Tracking("x","p_clock","t1"),t,true,true).key)
    t.add(ev(7,"answered","title" to "You said: Yes","decision_id" to "q_t1_1"))
    assertNull(t.question)
    t.add(ev(8,"started"));t.add(ev(9,"file_written","path" to "clock.py"));t.add(ev(10,"command_run","command" to "python clock.py"))
    assertFalse("replayed twice",t.add(ev(10,"command_run","command" to "python clock.py")))
    t.add(ev(11,"finished","ok" to true,"commit" to "7647e1a","output" to "The time is 10:00:05"))
    assertEquals(listOf("Your coding agent started","Asks: Should it print seconds too?","You said: Yes","Your coding agent picked up your answer",
      "Wrote clock.py","Ran python clock.py","Saved as commit 7647e1a","It printed: The time is 10:00:05"),t.rows.map { it.text })
    assertEquals(1000L,t.firstEventWallMs)
    val sc=ProjectScreens.of(NewProjectFlow.State.Tracking("x","p_clock","t1"),t,true,true)
    assertEquals("finished",sc.key)
    assertEquals("Command line tool prints time",sc.title)
    assertTrue(sc.lines.size<=ProjectScreens.MAX_LINES)
    assertEquals("It printed: The time is 10:00:05",sc.lines.last())
  }

  @Test fun screensNeverNameAVendor() {
    val banned=Regex("\\b(claude|codex|openai|anthropic|gpt|meta|quest|oculus)\\b",RegexOption.IGNORE_CASE)
    val states=listOf(NewProjectFlow.State.Idle,NewProjectFlow.State.Dictating,NewProjectFlow.State.Chip("a clock"),
      NewProjectFlow.State.Sending("a clock","c1"),NewProjectFlow.State.Tracking("a clock","p","t"))
    for(st in states) for(on in listOf(true,false)) {
      val sc=ProjectScreens.of(st,null,on,on,"heard text")
      (listOf(sc.title)+sc.lines+sc.buttons.map { it.second }).forEach { assertFalse(it,banned.containsMatchIn(it)) }
    }
  }

  companion object {
    /** Every screen the window can show, for the layout lint. */
    fun all(): List<Pair<String,ProjectScreen>> {
      val t=ProjectTimeline("p_web")
      var seq=0L
      fun e(kind: String,vararg kv: Pair<String,Any>)=JSONObject().put("seq",++seq).put("project_id","p_web").put("kind",kind)
        .put("title",kv.firstOrNull { it.first=="title" }?.second ?: kind).apply { kv.forEach { (k,v) -> put(k,v) } }
      t.add(e("project_created","name" to "web page counter button"));t.add(e("started"))
      t.add(e("file_written","path" to "index.html"));t.add(e("command_run","command" to "node check.js --verbose --all-the-flags-there-are"))
      val tracking=NewProjectFlow.State.Tracking("a web page with a counter button","p_web","t1")
      val working=ProjectScreens.of(tracking,t,true,true)
      t.add(e("question","title" to "Should the counter also have a reset button that sets it back to zero?","decision_id" to "q1",
        "options" to JSONArray().put(JSONObject().put("id","o1").put("label","Yes")).put(JSONObject().put("id","o2").put("label","No"))))
      val question=ProjectScreens.of(tracking,t,true,false)
      t.add(e("answered","title" to "You said: Yes","decision_id" to "q1"));t.add(e("started"));t.add(e("file_written","path" to "app.js"))
      t.add(e("finished","ok" to true,"commit" to "a1b2c3d","output" to "Counter page ready at index.html: open it in a browser to count clicks"))
      return listOf(
        "idle" to ProjectScreens.of(NewProjectFlow.State.Idle,null,true,true,"what is the weather like in the garden this afternoon"),
        "dictating" to ProjectScreens.of(NewProjectFlow.State.Dictating,null,true,true),
        "chip" to ProjectScreens.of(NewProjectFlow.State.Chip("a command-line tool that prints the time in three different time zones"),null,true,true),
        "chip_sends_off" to ProjectScreens.of(NewProjectFlow.State.Chip("a web page with a counter button"),null,false,true),
        "sending" to ProjectScreens.of(NewProjectFlow.State.Sending("a web page with a counter button","c1"),null,true,false),
        "working" to working,"question" to question,"finished" to ProjectScreens.of(tracking,t,true,true))
    }
  }
}
