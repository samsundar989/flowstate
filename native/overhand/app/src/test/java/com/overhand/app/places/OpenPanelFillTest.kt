package com.overhand.app.places

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.meta.spatial.toolkit.LayoutXMLPanelRegistration
import com.overhand.app.OverhandActivity
import com.overhand.app.R
import com.overhand.app.features.Shell
import com.overhand.app.features.WebPanel
import com.overhand.app.windows.Theme
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Actual panel registration callbacks and place event, without starting the spatial runtime or a sky decode. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class OpenPanelFillTest {
  private fun all(v: View): List<View> = listOf(v) + if(v is ViewGroup)
    (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
  private fun bare(c: Class<*>): Any {
    val u=Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible=true }.get(null)
    return u.javaClass.getMethod("allocateInstance",Class::class.java).invoke(u,c)
  }
  @Suppress("UNCHECKED_CAST")
  @Test fun alreadyOpenWindowsFollowMountainBeachMoon() {
    val saved=Places.store
    Places.clearListeners();Places.store=Places.MemoryStore()
    val activity=Robolectric.buildActivity(OverhandActivity::class.java).get()
    val host=Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
    val shell=Shell(activity)
    val roots=linkedMapOf<String,View>()
    val pages=mutableListOf<WebView>()
    val rows=JSONArray()
    try {
      shell.features.filterIsInstance<PlacesFeature>().single().onPreCreate()
      val registrations=shell.windows.panels().filter { reg -> shell.layout.specs.any { it.id==reg.registrationId } } + shell.opening.panels()
      val setup=LayoutXMLPanelRegistration::class.java.getDeclaredField("panelSetupWithRootView").apply { isAccessible=true }
      val container=LinearLayout(host);host.setContentView(container)
      registrations.forEach { reg ->
        val root=activity.layoutInflater.inflate(R.layout.web_panel,null)
        container.addView(root)
        (setup.get(reg) as Function3<View,Any?,Any?,Unit>).invoke(root,
          bare(com.meta.spatial.runtime.PanelSceneObject::class.java),bare(com.meta.spatial.core.Entity::class.java))
        roots[host.resources.getResourceEntryName(reg.registrationId)]=root.findViewById<LinearLayout>(R.id.panel_root)
        pages+=all(root).filterIsInstance<WebView>()
      }
      val card=LinearLayout(host)
      com.overhand.app.decisions.QuestionCard.draw(host,card,
        com.overhand.app.decisions.QuestionCard.Model(com.overhand.app.decisions.CardState.ASK,"Project","Continue?",null,listOf("Yes","No"),-1,null,null),
        com.overhand.app.decisions.QuestionCard.Handlers({},{},{},{},{ text,click -> android.widget.Button(host).apply { this.text=text;setOnClickListener { click() } } }))
      container.addView(card);roots["question_card"]=card
      val dock=LinearLayout(host)
      com.overhand.app.features.ControlDock.build(dock) {}
      container.addView(dock);roots["control_dock"]=dock
      val sp=shell.layout.specs.first { it.view=="blueprint" }
      val web=WebView(host)
      web.evaluateJavascript(Theme.webScript(),null)
      shell.dashboards.panels[sp.id]=WebPanel(sp,web,ImageView(host).apply { visibility=View.GONE },TextView(host),loaded=true)
      pages+=web
      shadowOf(Looper.getMainLooper()).idle()
      var stale=0
      var maxMs=0.0
      fun nativeFill(v: View): String {
        fun fill(d: android.graphics.drawable.Drawable?): Int = when(d) {
          is LayerDrawable -> fill(d.getDrawable(0))
          is InsetDrawable -> fill(d.drawable)
          is GradientDrawable -> d.color!!.defaultColor
          is ColorDrawable -> d.color
          else -> error("Unsupported fill: $d")
        }
        return "#%08X".format(fill(v.background))
      }
      fun pageFill(web: WebView): String {
        val script=shadowOf(web).lastEvaluatedJavascript.orEmpty()
        return if(!script.startsWith("window.AF_THEME=")) "missing" else JSONObject(script.substringAfter("window.AF_THEME=").substringBefore(';')).optString("panelFill")
      }
      fun snapshot()=JSONObject().apply {
        roots.forEach { (name,root) -> put(name,nativeFill(root)) }
        pages.forEachIndexed { i,page -> put("page_$i",pageFill(page)) }
      }
      rows.put(JSONObject().put("place","MOON").put("before",snapshot()).put("after",snapshot()).put("stale",0))
      val staleNames=linkedSetOf<String>()
      for(place in listOf(Place.MOUNTAIN,Place.BEACH,Place.MOON)) {
        val before=snapshot()
        val start=System.nanoTime()
        Places.set(place);shadowOf(Looper.getMainLooper()).idle()
        val ms=(System.nanoTime()-start)/1e6;maxMs=maxOf(maxMs,ms)
        val expected="#%08X".format(Theme.color(Theme.panelFill(place)))
        val after=snapshot()
        val wrong=roots.filter { nativeFill(it.value)!=expected }.keys.toList()+
          pages.mapIndexedNotNull { i,page -> if(pageFill(page)!=Theme.tokens()["panelFill"]) "page_$i" else null }
        stale+=wrong.size;staleNames.addAll(wrong)
        rows.put(JSONObject().put("place",place.name).put("before",before).put("after",after)
          .put("expected_native",expected).put("expected_page",Theme.tokens()["panelFill"])
          .put("stale_surfaces",JSONArray(wrong)).put("stale",wrong.size).put("refresh_ms",ms))
      }
      val out=File(System.getenv("OPEN_PANEL_OUT") ?: "../../../runs/fix17/O2/job").apply { mkdirs() }
      File(out,"switches.json").writeText(JSONObject().put("switches",rows).put("native_panels",roots.size).put("pages",pages.size)
        .put("moon_alpha_percent",android.graphics.Color.alpha(Theme.color(Theme.panelFill(Place.MOON)))*100.0/255)
        .put("open_panels_stale_fill",stale).put("place_switch_ms",maxMs).toString(2))
      println("open_panels_stale_fill=$stale place_switch_ms=$maxMs")
      assertEquals("stale surfaces: $staleNames; see switches.json",0,stale)
      assertEquals("Moon alpha unchanged",166,android.graphics.Color.alpha(Theme.color(Theme.panelFill(Place.MOON))))
      assertTrue("PC main-thread refresh under 50ms: $maxMs",maxMs<50)
    } finally {
      shell.features.filterIsInstance<PlacesFeature>().single().onDestroy()
      shell.dashboards.panels.values.forEach { it.web.destroy() }
      pages.filter { p -> shell.dashboards.panels.values.none { it.web===p } }.forEach { it.destroy() }
      Places.store=saved
    }
  }

  private class PausedWeb(context: android.content.Context): WebView(context) {
    var visual: VisualStateCallback?=null
    override fun evaluateJavascript(script: String,callback: android.webkit.ValueCallback<String>?) {
      super.evaluateJavascript(script,null);callback?.onReceiveValue("null")
    }
    override fun postVisualStateCallback(id: Long,callback: VisualStateCallback) { visual=callback }
  }

  @Test fun pausedStillIsReplacedAfterTheNewThemePaints() {
    val saved=Places.store
    Places.clearListeners();Places.store=Places.MemoryStore()
    val activity=Robolectric.buildActivity(OverhandActivity::class.java).get()
    val shell=Shell(activity)
    val web=PausedWeb(activity).apply {
      // Robolectric's WebView provider does not lay out this unattached page.
      // Seed View bounds explicitly; the callback and bitmap replacement remain real.
      org.robolectric.util.ReflectionHelpers.setField(this,"mRight",100)
      org.robolectric.util.ReflectionHelpers.setField(this,"mBottom",80)
    }
    val old=android.graphics.Bitmap.createBitmap(100,80,android.graphics.Bitmap.Config.RGB_565)
    val still=ImageView(activity).apply { visibility=View.VISIBLE;setImageBitmap(old) }
    val sp=shell.layout.specs.first()
    val p=WebPanel(sp,web,still,TextView(activity),bitmap=old,loaded=true)
    shell.dashboards.panels[sp.id]=p
    try {
      shell.features.filterIsInstance<PlacesFeature>().single().onPreCreate()
      Places.set(Place.MOUNTAIN);shadowOf(Looper.getMainLooper()).idle()
      assertNotNull("wait for the changed Web theme to paint",web.visual)
      assertSame(old,p.bitmap)
      assertFalse(shell.stopped)
      assertSame(p,shell.dashboards.panels[sp.id])
      assertEquals(View.VISIBLE,still.visibility)
      assertEquals(100,web.width);assertEquals(80,web.height)
      web.visual!!.onComplete(0)
      assertNotSame(old,p.bitmap);assertTrue(old.isRecycled)
      assertEquals(View.VISIBLE,still.visibility)
      assertEquals(100,p.bitmap!!.width);assertEquals(80,p.bitmap!!.height)
    } finally {
      shell.features.filterIsInstance<PlacesFeature>().single().onDestroy()
      web.destroy();p.bitmap?.recycle();Places.store=saved
    }
  }
}
