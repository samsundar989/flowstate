package com.overhand.app.voice

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ViewGroup
import android.widget.LinearLayout
import com.overhand.app.hostlink.project.NewProjectFlow
import com.overhand.app.hostlink.project.FollowUpFlow
import com.overhand.app.layoutlint.LayoutLintRenderer
import com.overhand.app.layoutlint.Panel
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Reflection lets this exact capture fixture render the baseline source, not an illustration. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w1600dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssistantStripRenderTest {
  @Test fun fourRealViewStatesOnGreyAndWithAlpha() {
    val a = Robolectric.buildActivity(Activity::class.java).setup().get()
    val out = File(System.getenv("ASSISTANT_RENDER") ?: "build/assistant").apply { mkdirs() }
    val before = System.getenv("ASSISTANT_BASELINE") == "1"
    val renderer = LayoutLintRenderer(a,out)
    val intents = VoiceIntents.parse(File("src/main/res/raw/voice_answers.json").readText())
    val manifest = StringBuilder("state\tfile\talpha_file\tfixture\n")
    for (state in listOf("listening","speaking","confirm","muted")) {
      a.getSharedPreferences(Voice.PREFS,0).edit().clear().commit()
      val c = VoiceController(a,intents,{ _,_ -> "" },{ ShortArray(160) { 4096 } },16000)
      c.worn = { false }; c.answerToName = false
      c.player = object : ReplyOut {
        override fun play(pcm: ShortArray,onFirstAudio: (Long) -> Unit,onDone: () -> Unit) {
          val ring = c.javaClass.getDeclaredField("ring").apply { isAccessible = true }.get(c)
          ring.javaClass.methods.firstOrNull { it.name == "setLevel" }?.invoke(ring,0.5f) // RMS of this 4096 PCM fixture / 8192.
        }
        override fun stop() {}; override fun release() {}
      }
      try {
        when(state) {
          "listening" -> c.javaClass.getDeclaredMethod("caption",String::class.java,Boolean::class.javaPrimitiveType,String::class.java,Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(c,"what needs me",false,"",false)
          "speaking" -> { c.machine.listen(); c.machine.endOfSpeech(); c.machine.heard(c.machine.turn,"what needs me") }
          "confirm" -> {
            val method = c.javaClass.methods.firstOrNull { it.name == "projectConfirmation" }
            if (method != null) method.invoke(c,NewProjectFlow.State.Chip("a tide chart for my town"),FollowUpFlow.State.Idle,{ _: String -> Unit })
            else { c.machine.listen(); c.machine.endOfSpeech(); c.onFinalTranscript("New toy.",0.5) }
          }
          "muted" -> c.setMuted(true,"render")
        }
        val h = if (before) 480f else 300f
        val p = Panel(state,1000f,h,0.5f,h/2000f,1.2f,"VoiceController.view") { root ->
          (c.view.parent as? ViewGroup)?.removeView(c.view)
          root.addView(c.view,LinearLayout.LayoutParams(-1,-1))
        }
        val captureRoot = LinearLayout(a).apply {
          orientation = LinearLayout.VERTICAL
          (c.view.parent as? ViewGroup)?.removeView(c.view)
          addView(c.view,LinearLayout.LayoutParams(-1,-1))
        }
        // Fixed offscreen viewport: the Activity's later phone-size layout must not shift this capture.
        assertFalse(renderer.captureExisting(p,captureRoot).has("error"))
        val wpx = (1000*renderer.density).toInt(); val hpx = (h*renderer.density).toInt()
        for (alpha in listOf(false,true)) {
          val bitmap = Bitmap.createBitmap(wpx,hpx,Bitmap.Config.ARGB_8888)
          val canvas = Canvas(bitmap)
          if (!alpha) canvas.drawColor(Color.rgb(200,200,200))
          captureRoot.draw(canvas)
          val name = state + if (alpha) "_alpha.png" else ".png"
          File(out,name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        }
        manifest.append("$state\t$state.png\t${state}_alpha.png\t${if(before && state=="confirm") "existing unsure-offer chip" else "real controller"}\n")
      } finally { c.close() }
    }
    File(out,"manifest.tsv").writeText(manifest.toString())
  }
}
