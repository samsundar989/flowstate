package com.overhand.app.first60

import com.overhand.app.data.LocalReceiptStore
import com.overhand.app.data.RecordedSource
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #1206 asked whether the fixed greeting ("... three questions block them") plays on a second launch that already has a
 * practice receipt. #1230 (ruling R405): it always plays, as on main, because film shot 1 is shot on a headset that
 * already holds a receipt. First60Feature.onSceneReady's only gate is First60.on(recorded), which a receipt cannot
 * change; Simple mode (not recorded) never greets.
 */
@RunWith(RobolectricTestRunner::class)
class GreetingRelaunchTest {
  private val assets = File("src/main/assets")
  private fun launch(receipts: File) = RecordedSource({ File(assets, it).readText() }, LocalReceiptStore(receipts), null)
  private fun receipts(src: RecordedSource) = src.decisions().let { d -> (0 until d.length()).count { d.getJSONObject(it).has("practice_receipt") } }

  @Test fun theGreetingPlaysOnASecondLaunchWithAPracticeReceipt() {
    val file = Files.createTempDirectory("greet").resolve("judge-receipts.json").toFile()
    val first = launch(file)
    assertEquals("the first launch has no receipt", 0, receipts(first))
    val greetsFirst = First60.on(true)
    val d = first.decisions().let { a -> (0 until a.length()).map { a.getJSONObject(it) }.first { it.optString("status") == "open" } }
    first.answer(d, d.getJSONArray("options").getJSONObject(0).getString("label"), "pinch")
    val second = launch(file)
    val held = receipts(second); val greetsSecond = First60.on(true)
    println("first_launch greets=$greetsFirst | second_launch receipts=$held greets=$greetsSecond | simple greets=${First60.on(false)}")
    assertTrue("the second launch must hold a practice receipt", held >= 1)
    assertTrue("the first launch greets", greetsFirst)
    assertTrue("R405: a second launch with a practice receipt still greets", greetsSecond)
    assertFalse("Simple mode never greets", First60.on(false))
  }
}
