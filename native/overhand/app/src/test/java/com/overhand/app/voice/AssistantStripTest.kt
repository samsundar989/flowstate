package com.overhand.app.voice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.overhand.app.hostlink.project.NewProjectFlow
import com.overhand.app.hostlink.project.FollowUpFlow
import java.io.File
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class AssistantStripTest {
  private val intents get() = VoiceIntents.parse(File("src/main/res/raw/voice_answers.json").readText())
  private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
  private fun visibleText(v: View): List<String> {
    if (v.visibility != View.VISIBLE) return emptyList()
    return (if (v is TextView) listOf(v.text.toString()) else emptyList()) +
      if (v is ViewGroup) (0 until v.childCount).flatMap { visibleText(v.getChildAt(it)) } else emptyList()
  }
  private fun controller(): VoiceController {
    val a = Robolectric.buildActivity(Activity::class.java).setup().get()
    a.getSharedPreferences(Voice.PREFS,Context.MODE_PRIVATE).edit().clear().commit()
    return VoiceController(a,intents,{ _,_ -> "" },{ ShortArray(160) },16000).apply {
      worn = { false }
      player = object : ReplyOut {
        override fun play(pcm: ShortArray, onFirstAudio: (Long) -> Unit, onDone: () -> Unit) {}
        override fun stop() {}; override fun release() {}
      }
    }
  }
  private fun click(c: VoiceController, tag: String) = c.view.findViewWithTag<View>(tag).performClick()
  @Test fun fourStatesTransitionsAndQuietCollapseUseTheSameMic() {
    val c = controller()
    try {
      assertEquals(AssistantState.Listening,c.assistantState)
      assertEquals(1,views(c.view).count { it.tag == "transcript-mic" })
      c.answerSubject = { "Importer" }
      c.machine.listen(); c.machine.endOfSpeech()
      c.machine.heard(c.machine.turn,"what needs me")
      assertEquals(AssistantState.Speaking,c.assistantState)
      assertTrue(visibleText(c.view).contains("Importer"))
      assertTrue(views(c.view).filterIsInstance<MicRing>().single().state == MicState.SPEAKING)
      c.machine.playbackDone(c.machine.turn)
      val n = NewProjectFlow(); n.heard("new project a tide chart for my town")
      fun bind() = c.projectConfirmation(n.state,FollowUpFlow.State.Idle) { if (it == "discard") n.discard() else n.confirm(); c.projectConfirmation(n.state,FollowUpFlow.State.Idle) {} }
      bind(); assertEquals(AssistantState.Confirm,c.assistantState)
      assertEquals(listOf("Build this?","“a tide chart for my town”","Start","Discard"),visibleText(c.view))
      shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(7))
      assertFalse("pending confirmation must remain actionable",c.captionStrip)
      click(c,"transcript-mic"); assertEquals(AssistantState.Muted,c.assistantState)
      assertEquals(listOf("Muted"),visibleText(c.view))
      click(c,"transcript-mic"); assertEquals(AssistantState.Confirm,c.assistantState)
      bind(); click(c,"assistant-discard"); assertEquals(NewProjectFlow.State.Idle,n.state)
      assertEquals(AssistantState.Listening,c.assistantState)
      shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(7))
      assertTrue(c.captionStrip)
      assertTrue(visibleText(c.view).contains("Listening"))
      assertTrue(ShadowLog.getLogsForTag(Voice.TAG).any { it.msg == "voice event=mute on=1 why=button" })
      assertTrue(ShadowLog.getLogsForTag(Voice.TAG).any { it.msg.startsWith("voice event=state from=Listening to=Speaking why=") })
    } finally { c.close() }
  }
  @Test fun startAndDiscardUseBothExistingFlowActionsWithoutChangingTheirPayload() {
    val c = controller(); val n = NewProjectFlow(); val f = FollowUpFlow()
    val actions = mutableListOf<String>()
    fun bind() = c.projectConfirmation(n.state,f.state) { key ->
      actions += key
      when(key) { "start" -> n.confirm(); "discard" -> n.discard(); "followup_send" -> f.confirm(); "followup_discard" -> f.discard() }
    }
    try {
      n.heard("new project a tide chart"); bind(); click(c,"assistant-start")
      assertEquals("a tide chart",(n.state as NewProjectFlow.State.Sending).description)
      f.heard("add local times","Tide chart","p1"); bind(); click(c,"assistant-discard")
      assertEquals(FollowUpFlow.State.Idle,f.state)
      f.heard("add local times","Tide chart","p1"); bind(); click(c,"assistant-start")
      assertEquals("add local times",(f.state as FollowUpFlow.State.Sending).text)
      assertEquals(listOf("start","followup_discard","followup_send"),actions)
    } finally { c.close() }
  }
  @Test fun buttonMuteStopsTheAudioSourceAndRejectsLateFramesTranscriptsAndCommands() {
    val a = Robolectric.buildActivity(Activity::class.java).setup().get()
    shadowOf(a.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
    a.getSharedPreferences(Voice.PREFS,Context.MODE_PRIVATE).edit().clear().commit()
    val c = VoiceController(a,intents,{ _,_ -> "" },{ ShortArray(160) },16000)
    val ui = FakeUi(); c.commands = UiCommands(ui); c.answerToName = false
    var sink: ((ShortArray,Int) -> Unit)? = null
    var running = false; var stops = 0; var hostCalls = 0
    c.newMic = { output -> sink = output; object : Mic {
      override fun start() { running = true }; override fun stop() { running = false; stops++ }; override val alive get() = running
    } }
    try {
      c.resume(); assertTrue(c.micRunning)
      sink!!(ShortArray(320),320)
      click(c,"transcript-mic"); assertTrue(c.muted); assertFalse(running); assertEquals(1,stops)
      val samples = c.loop.samples
      sink!!(ShortArray(320) { 10000 },320)
      assertEquals(samples,c.loop.samples)
      c.routeTo({ hostCalls++; true },"")
      assertFalse(c.onFinalTranscript("open settings",1.0))
      c.injectText("open settings")
      c.hands(10,emptyMap()); shadowOf(Looper.getMainLooper()).idle()
      assertTrue(ui.calls.isEmpty()); assertEquals(0,hostCalls); assertFalse(c.micRunning)
      val oldTurn = c.loop.turn
      click(c,"transcript-mic"); assertTrue(c.micRunning)
      val listener = VoiceLoop::class.java.getDeclaredField("listener").apply { isAccessible = true }.get(c.loop) as VoiceLoop.Listener
      listener.final(oldTurn,"open settings",16000,16000,1)
      shadowOf(Looper.getMainLooper()).idle()
      assertEquals("a result decoded before mute is stale even after unmute",0,hostCalls)
    } finally { c.close() }
  }
  @Test fun wordBudgetAndLevelsAreBoundedByRealAudio() {
    val c = controller()
    try {
      fun budget() = assertTrue(visibleText(c.view).joinToString(" ").split(Regex("\\s+")).filter { it.isNotBlank() }.size < 25)
      budget()
      val many = (1..80).joinToString(" ") { "word$it" }
      c.projectConfirmation(NewProjectFlow.State.Chip(many),FollowUpFlow.State.Idle) {}
      budget(); c.setMuted(true,"test"); budget()
      c.setMuted(false,"test"); c.projectConfirmation(NewProjectFlow.State.Idle,FollowUpFlow.State.Idle) {}
      c.machine.listen(); c.machine.endOfSpeech(); c.machine.heard(c.machine.turn,"what needs me")
      budget()
      assertEquals(0f,voiceLevel(ShortArray(320),0,320))
      assertTrue(voiceLevel(ShortArray(320) { 8000 },0,320) > 0.9f)
      val ring = views(c.view).filterIsInstance<MicRing>().single()
      ring.level = 0f; shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
      assertEquals("no synthetic time animation",0f,ring.level)
    } finally { c.close() }
  }
}
