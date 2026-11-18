package com.overhand.app.settings

import com.overhand.app.press.PressEverythingTest
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** C1's exact fresh-panel press, including command drain and 600 ms looper idle; no retries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class SettingsLinkPressTest {
  @Test fun measureTwentyFreshLinkTabPresses() {
    val harness=PressEverythingTest()
    val surfaces=PressEverythingTest::class.java.getDeclaredMethod("surfaces").apply { isAccessible=true }
    val surface=(surfaces.invoke(harness) as List<*>).filterIsInstance<PressEverythingTest.Surface>()
      .single { it.name=="settings_voice" }
    val press=PressEverythingTest::class.java.getDeclaredMethod("pressOnce",
      PressEverythingTest.Surface::class.java,Boolean::class.javaPrimitiveType,Int::class.javaPrimitiveType)
      .apply { isAccessible=true }
    val out=File(System.getenv("SETTINGS_LINK_OUT") ?: "../../../runs/fix17/G3/manual").apply { mkdirs() }
    val values=mutableListOf<Double>()
    val rows=mutableListOf("press\tpanel\tstate\tkey\tresult\tms")
    repeat(20) { i ->
      val p=press.invoke(harness,surface,false,4) as PressEverythingTest.Press
      assertEquals("settings-tab-link",p.key)
      values+=p.ms
      rows+=listOf(i+1,p.panel,p.state,p.key,p.cls,String.format(Locale.ROOT,"%.6f",p.ms)).joinToString("\t")
      File(out,"link_ms.tsv").writeText(rows.joinToString("\n")+"\n")
      assertTrue("Link handler failed: $p",p.cls in setOf("OK","SLOW"))
    }
    val sorted=values.sorted()
    File(out,"summary.txt").writeText("count=20\nmedian_ms=${(sorted[9]+sorted[10])/2}\nmax_ms=${sorted.last()}\nover_50=${values.count { it>50 }}\n")
  }
}
