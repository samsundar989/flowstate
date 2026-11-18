package com.overhand.app.features

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.overhand.app.OverhandActivity
import com.overhand.app.layoutlint.LayoutLintRenderer
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w1600dp-h1000dp-288dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LookK5aTest {
  private fun all(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
  @Test fun captureRealBuildersAndCheckControls() {
    val a=Robolectric.buildActivity(OverhandActivity::class.java).get()
    val out=File(System.getenv("K5A_RENDER") ?: "build/k5a").apply { mkdirs() }
    val renderer=LayoutLintRenderer(a,out)
    val before=System.getenv("K5A_PHASE")=="before"
    val manifest=mutableListOf("panel\tfile\talpha\twords")
    fun capture(name: String,root: LinearLayout,w: Int,h: Int) {
      a.setContentView(root)
      val d=a.resources.displayMetrics.density
      renderer.settle(root,(w*d).toInt(),(h*d).toInt())
      val words=all(root).filterIsInstance<TextView>().filter { v ->
        var p: View?=v;var shown=true
        while(p!=null) { if(p.visibility!=View.VISIBLE) shown=false;p=p.parent as? View };shown
      }.sumOf { it.text.toString().trim().split(Regex("\\s+")).count { it.isNotEmpty() } }
      if(!before) assertTrue("$name has $words words",words<25)
      val raw=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
      root.draw(Canvas(raw));File(out,"$name-alpha.png").outputStream().use { raw.compress(Bitmap.CompressFormat.PNG,100,it) }
      val grey=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
      Canvas(grey).apply { drawColor(Color.rgb(200,200,200));drawBitmap(raw,0f,0f,null) }
      File(out,"$name.png").outputStream().use { grey.compress(Bitmap.CompressFormat.PNG,100,it) }
      manifest+="$name\t$name.png\t$name-alpha.png\t$words"
    }
    val dock=LinearLayout(a);ControlDock.build(dock) {}
    ControlDock.paint(dock,setOf("code","build"))
    capture("dock",dock,if(before)704 else 136,if(before)704 else 660)
    if(!before) {
      assertEquals(listOf("Fleet","Blueprint","Code","Yard","Settings"),ControlDock.items().map { it.label })
      for(open in listOf(emptySet(),setOf("fleet"),setOf("settings"),setOf("build"),setOf("code","build"))) {
        ControlDock.paint(dock,open)
        ControlDock.items().forEach { assertEquals(it.view in open,dock.findViewWithTag<View>("dock-icon-${it.view}").isSelected) }
      }
    }
    val shell=Shell(a);val settings=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL }
    shell.settings.build(settings)
    for(tab in SettingsTab.shown()) {
      shell.settings.select(tab);capture("settings-${tab.key}",settings,853,1000)
    }
    if(!before) {
      shell.settings.select(SettingsTab.RUN)
      for(tag in listOf("settings-recordings","settings-debugging")) {
        val destination=settings.findViewWithTag<View>(tag)
        val ordinary=settings.findViewWithTag<View>("settings-run-controls")
        ordinary.visibility=View.GONE;destination.visibility=View.VISIBLE
        capture(tag,settings,853,1000);destination.visibility=View.GONE;ordinary.visibility=View.VISIBLE
      }
      assertEquals(listOf("Voice","View","Place","Link","Run","About"),SettingsTab.shown().map { it.label })
      assertTrue(all(settings).none { it is CheckBox || it.javaClass==Button::class.java })
      assertNotNull(settings.findViewWithTag<View>("settings-close"))
      val switches=all(settings).filterIsInstance<SettingsSwitch>()
      assertTrue(switches.size>=10)
      switches.filter { it.isEnabled }.forEach { val old=it.isChecked;it.performClick();assertEquals(!old,it.isChecked);it.performClick();assertEquals(old,it.isChecked) }
    }
    File(out,"manifest.tsv").writeText(manifest.joinToString("\n")+"\n")
  }
}
