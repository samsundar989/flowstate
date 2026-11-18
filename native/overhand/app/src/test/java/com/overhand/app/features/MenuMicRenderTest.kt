package com.overhand.app.features

import android.app.Activity
import android.widget.LinearLayout
import com.overhand.app.layoutlint.LayoutLintRenderer
import com.overhand.app.layoutlint.Panel
import com.overhand.app.voice.VoiceController
import com.overhand.app.voice.VoiceIntents
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Runs against the baseline builders then the final builders in one queued job. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w1600dp-h800dp-288dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MenuMicRenderTest {
  @Test fun captureBothPanels() {
    val a=Robolectric.buildActivity(Activity::class.java).setup().get()
    val out=File(System.getenv("FIX17_RENDER") ?: "build/fix17/render")
    val renderer=LayoutLintRenderer(a,out)
    val dock=Panel("side_dock",704f,704f,0.44f,0.44f,1.2f,"ControlDock.build") { ControlDock.build(it) {} }
    assertFalse(renderer.capture(dock).has("error"))
    val intents=VoiceIntents.parse(File("src/main/res/raw/voice_answers.json").readText())
    val c=VoiceController(a,intents,{ _,_ -> "" },{ ShortArray(160) },16000)
    c.worn={false}
    try {
      for(muted in listOf(false,true)) {
        c.setMuted(muted,"render")
        val name=if(muted) "transcript_muted" else "transcript_listening"
        val p=Panel(name,1000f,480f,0.5f,0.24f,1.2f,"VoiceController.view") { root ->
          (c.view.parent as? android.view.ViewGroup)?.removeView(c.view)
          root.addView(c.view,LinearLayout.LayoutParams(-1,-1))
        }
        assertFalse(renderer.capture(p).has("error"))
      }
      File(out,"manifest.tsv").writeText("panel\tfile\tsource\nside dock\tside_dock.png\tControlDock.build\ntranscript listening\ttranscript_listening.png\tVoiceController.view\ntranscript muted\ttranscript_muted.png\tVoiceController.view\n")
    } finally { c.close() }
  }
}
