package com.overhand.app.voice

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import com.overhand.app.OverhandActivity
import com.overhand.app.features.Shell
import com.overhand.app.features.VoiceFeature
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class TranscriptMicTest {
  @Test fun buttonSettingsAndSpokenMuteShareOnePersistedState() {
    val a=Robolectric.buildActivity(OverhandActivity::class.java).get()
    a.getSharedPreferences(Voice.PREFS,Context.MODE_PRIVATE).edit().clear().commit()
    val s=Shell(a)
    val intents=VoiceIntents.parse(File("src/main/res/raw/voice_answers.json").readText())
    fun controller()=VoiceController(a,intents,{ _,_ -> "" },{ ShortArray(160) },16000).apply { worn={false} }
    val c=controller()
    val field=VoiceFeature::class.java.getDeclaredField("controller").apply { isAccessible=true }
    field.set(s.voice,c)
    val root=LinearLayout(a)
    s.voice.settingsRows().first { it.order==11 }.build(root,root)
    val setting=root.findViewWithTag<CheckBox>("listen-voice-switch")
    val mic=c.view.findViewWithTag<View>("transcript-mic")
    fun synced(on: Boolean) {
      shadowOf(Looper.getMainLooper()).idle()
      assertEquals(on,s.voice.listening);assertEquals(on,setting.isChecked)
      assertEquals(!on,c.muted);assertEquals(on,mic.isSelected)
      assertEquals(!on,a.getSharedPreferences(Voice.PREFS,Context.MODE_PRIVATE).getBoolean("muted",false))
    }
    try {
      synced(true)
      mic.performClick();synced(false)
      assertEquals("Unmute microphone",mic.contentDescription)
      mic.performClick();synced(true)
      setting.isChecked=false;synced(false)
      setting.isChecked=true;synced(true)
      c.answerToName=false
      assertTrue(c.onFinalTranscript("mute",1.0));synced(false)
      assertTrue(ShadowLog.getLogsForTag(Voice.TAG).any { it.msg=="voice event=mic_toggle on=0 from=button" })
      assertTrue(ShadowLog.getLogsForTag(Voice.TAG).any { it.msg=="voice event=mic_toggle on=1 from=button" })
      c.close()
      val restarted=controller()
      try {
        assertTrue("controller reconstruction restores muted preference",restarted.muted)
        val button=restarted.view.findViewWithTag<View>("transcript-mic")
        assertFalse(button.isSelected)
        button.performClick();assertFalse(restarted.muted)
      } finally { restarted.close() }
      field.set(s.voice,null)
      assertTrue(s.voice.listening)
    } finally { s.voice.onDestroy();c.close() }
  }
}
