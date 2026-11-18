package com.overhand.app.voice

import android.Manifest
import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import java.io.File
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * #1206, #1230: the voice window's header word and hint in every state, as a table. Permission has four states:
 * never asked, prompt open (asked, no answer yet), refused (a denial result came back), granted. Only refused differs
 * from main. The denial result is delivered by reflection, so the same test compiles over main's and #1206's file.
 * Before #1230 this was the voice window's header word and hint in every state, as a table. With the microphone granted every row is
 * the text main shows (literals copied from main's header()); refused says so in 15 words or fewer. Writes
 * $WALK_OUT/voice_states.tsv (else build/) so the job can diff it byte for byte against main's VoiceController.
 * Uses no constant main lacks, so the same test compiles over main's file.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceHeaderStatesTest {
  private val intents = VoiceIntents.parse(File("src/main/res/raw/voice_answers.json").readText())
  private val made = mutableListOf<VoiceController>()
  @After fun closeAll() { made.forEach { it.close() } }
  private class SilentOut : ReplyOut {
    override fun play(pcm: ShortArray, onFirstAudio: (Long) -> Unit, onDone: () -> Unit) { onDone() }
    override fun stop() {}
    override fun release() {}
  }
  private class DeadMic : Mic { override fun start() {}; override fun stop() {}; override val alive = false }
  private fun all(v: View): List<View> = listOf(v) + (if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList())
  /** The header row: the first two TextViews in the window are the word and the hint. */
  private fun header(c: VoiceController) = all(c.view).filterIsInstance<TextView>().filter { it !is Button }.take(2).map { it.text.toString() }

  private class Row(val perm: String, val name: Boolean, val muted: Boolean, val host: String?)
  private val rows = listOf(true, false).flatMap { name -> listOf(false, true).flatMap { muted -> listOf(null, "Say the new project's name").flatMap { host ->
    listOf("never", "prompt", "refused", "granted").map { Row(it, name, muted, host) } } } }

  private fun see(r: Row): Pair<String, String> {
    val a = Robolectric.buildActivity(Activity::class.java).setup().get()
    if (r.perm == "granted") shadowOf(a.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
    else shadowOf(a.application).denyPermissions(Manifest.permission.RECORD_AUDIO)
    val c = VoiceController(a, intents, { _, _ -> "" }, { ShortArray(160) }, 16000).also { made += it }
    c.player = SilentOut(); c.newMic = { _ -> DeadMic() }
    // Before setMuted: "muted" persists in prefs across rows, so setMuted(false) reaches updateMic, which would ask.
    if (r.perm == "never") c.worn = { false } // not worn yet: updateMic never reaches the request
    c.answerToName = r.name; c.setMuted(r.muted, "test")
    r.host?.let { h -> c.routeTo({ false }, h) }
    c.resume(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
    val asked = shadowOf(a).lastRequestedPermission != null
    if (r.perm == "never") assertTrue("never: the app asked", !asked)
    if (r.perm == "refused" && asked) deny(c)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
    val (w, h) = header(c)
    c.close()
    return w to h
  }

  /** The system's answer "denied", as OverhandActivity passes it on. A file without permissionResult (main, #1206) ignores it. */
  private fun deny(c: VoiceController) {
    runCatching { c.javaClass.getMethod("permissionResult", Int::class.java, IntArray::class.java) }.getOrNull()
      ?.invoke(c, Voice.PERMISSION_REQUEST, intArrayOf(android.content.pm.PackageManager.PERMISSION_DENIED))
  }

  @Test fun everyHeaderStateAsATable() {
    val seen = rows.map { it to see(it) }
    val tsv = "perm\tanswer_to_name\tmuted\thost_hint\tword\thint\n" +
      seen.joinToString("") { (r, s) -> "${r.perm}\t${r.name}\t${r.muted}\t${r.host ?: "-"}\t${s.first}\t${s.second}\n" }
    val out = File(System.getenv("WALK_OUT") ?: "build").apply { mkdirs() }
    File(out, "voice_states.tsv").writeText(tsv)
    print(tsv)
    for (perm in listOf("never", "prompt", "refused", "granted")) assertEquals("rows for $perm", 8, seen.count { it.first.perm == perm })
    for ((r,s) in seen) {
      val expected=when {
        r.muted -> "Muted" to ""
        r.perm=="refused" -> "Mic off" to "Mic access"
        r.perm=="prompt" -> "Listening" to "Allow microphone"
        else -> "Listening" to ""
      }
      assertEquals("${r.perm} name=${r.name} muted=${r.muted}",expected,s)
    }
  }
}
