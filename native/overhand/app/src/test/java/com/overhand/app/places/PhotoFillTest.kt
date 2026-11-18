package com.overhand.app.places

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.widget.LinearLayout
import android.widget.TextView
import com.overhand.app.layoutlint.LayoutLintRenderer
import com.overhand.app.layoutlint.Panel
import com.overhand.app.windows.Theme
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoFillTest {
  @Test fun nativeAndWebUseCurrentPlaceAndDefaultRemains65Percent() {
    val saved=Places.store
    try {
      Places.store=Places.MemoryStore()
      for(place in Place.values()) {
        Places.set(place)
        val fill=Theme.panelFill()
        val byte=Color.alpha(Theme.color(fill))
        assertEquals(when(place) { Place.MOUNTAIN -> 215;Place.BEACH -> 214;else -> 166 },byte)
        val bg=Theme.panelBackground(1f) as LayerDrawable
        assertEquals(Theme.color(fill),(bg.getDrawable(0) as GradientDrawable).color!!.defaultColor)
        assertEquals("rgba(12,18,26,${"%.3f".format(java.util.Locale.ROOT,byte/255f)})",Theme.tokens()["panelFill"])
        if(place==Places.DEFAULT) {
          val pct=(100.0*byte/255).roundToInt()
          assertEquals(65,pct);println("default_place_fill_pct=$pct alpha_byte=$byte")
        }
      }
    } finally { Places.store=saved }
  }

  @Test fun renderPlaceGlassOverMidGrey() {
    val out=File(System.getenv("PHOTO_GLASS_OUT") ?: "../../../runs/fix17/O/pictures").apply { mkdirs() }
    val saved=Places.store
    try {
      Places.store=Places.MemoryStore()
      val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
      val renderer=LayoutLintRenderer(activity,out)
      for(place in listOf(Place.MOON,Place.MOUNTAIN,Place.BEACH)) {
        Places.set(place)
        renderer.capture(Panel(place.name.lowercase(),640f,240f,.6f,.225f,1.6f,"Theme.panelBackground") { column ->
          column.setBackgroundColor(Color.rgb(200,200,200))
          column.addView(LinearLayout(activity).apply {
            background=Theme.panelBackground(renderer.density)
            setPadding(32,32,32,32)
            addView(TextView(activity).apply { text="Building\nBody text over glass";textSize=Theme.BODY_DP.toFloat();setTextColor(Theme.color(Theme.TEXT)) })
          },LinearLayout.LayoutParams(-1,-1))
        })
      }
    } finally { Places.store=saved }
  }
}
