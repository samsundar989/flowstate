package com.overhand.app.windows

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import java.io.File
import kotlin.math.pow
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1370: a view panel's surface (Theme.panelBackground) before and after the look_nov17 glass, drawn over the
 * #C8C8C8 backdrop of look_nov17 s8 with the alpha kept, and the contrast of body and caption text measured on the
 * composited pixels. Pictures go to runs/look17/K1/ (git-ignored PNGs).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1600dp-h800dp-288dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LookGlassPictureTest {
  private val out = File(System.getProperty("user.dir"), "../../../runs/look17/K1").canonicalFile.apply { mkdirs() }
  private fun lin(c: Int) = (c / 255.0).let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
  private fun lum(c: Int) = 0.2126 * lin(c shr 16 and 255) + 0.7152 * lin(c shr 8 and 255) + 0.0722 * lin(c and 255)
  private fun ratio(a: Int, b: Int) = (maxOf(lum(a), lum(b)) + 0.05) / (minOf(lum(a), lum(b)) + 0.05)
  /** src over dst, straight alpha, onto an opaque dst. */
  private fun over(src: Int, dst: Int): Int {
    val a = (src ushr 24) / 255.0
    fun ch(s: Int) = ((src shr s and 255) * a + (dst shr s and 255) * (1 - a)).roundToInt()
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
  }

  private fun render(name: String, fill: String, edge: String, edgeDp: Int, radiusDp: Int, text: String, caption: String): Pair<Double, Double> {
    val a = Robolectric.buildActivity(Activity::class.java).setup().get()
    val d = a.resources.displayMetrics.density
    val w = (1280 * d / 2).roundToInt(); val h = (360 * d / 2).roundToInt()
    val bm = Bitmap.createBitmap(w + 80, h + 80, Bitmap.Config.ARGB_8888)
    val c = Canvas(bm); c.drawColor(Theme.color("#C8C8C8FF"))
    val bg = android.graphics.drawable.GradientDrawable().apply {
      setColor(Theme.color(fill)); cornerRadius = radiusDp * d / 2; setStroke((edgeDp * d / 2).roundToInt().coerceAtLeast(1), Theme.color(edge)) }
    bg.setBounds(40, 40, 40 + w, 40 + h); bg.draw(c)
    val surface = bm.getPixel(40 + w / 2, 40 + h - (40 * d / 2).roundToInt())
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    p.textSize = Theme.TITLE_DP * d / 2; p.color = Theme.color(text); c.drawText("Building", 40 + 32 * d / 2, 40 + 80 * d / 2, p)
    p.textSize = 30 * d / 2; p.color = Theme.color(caption); c.drawText("Answer received", 40 + 32 * d / 2, 40 + 140 * d / 2, p)
    File(out, "$name.png").outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
    val body = ratio(over(Theme.color(text), surface), surface)
    val cap = ratio(over(Theme.color(caption), surface), surface)
    val line = "$name surface=#%06X body=%.2f caption=%.2f".format(java.util.Locale.ROOT, surface and 0xFFFFFF, body, cap)
    println(line); File(out, "$name.txt").writeText(line + "\n")
    return body to cap
  }

  @Test fun beforeAndAfterOverMidGrey() {
    // Before: Theme.kt as main had it (PANEL_FILL #141A22E0, HAIRLINE #FFFFFF24 1 dp, radius 28, TEXT #E8EEF4, no caption token).
    render("view_panel_before", "#141A22E0", "#FFFFFF24", 1, 28, "#E8EEF4FF", "#E8EEF4FF")
    val (body, cap) = render("view_panel_after", Theme.PANEL_FILL, Theme.HAIRLINE, Theme.HAIRLINE_DP, Theme.PANEL_RADIUS_DP, Theme.TEXT, Theme.CAPTION_TEXT)
    assertTrue("body $body under 7:1", body >= 7.0)
    assertTrue("caption $cap under 4.5:1", cap >= 4.5)
  }
}
