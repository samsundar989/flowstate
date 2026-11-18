package com.overhand.app.layoutlint

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.overhand.app.R
import com.overhand.app.windows.PanelPolish
import com.overhand.app.features.ControlDock
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1372 (look K2): every view panel the PC renderer can build (LayoutLintCapture's windows, Settings pages, dock,
 * decision card, and LayoutLintPanels), run through PanelPolish, has no stock Button/CheckBox/ImageButton left,
 * every clickable at least 64 dp, and no text under 30 dp (code mono 26 is the spec's one exception).
 * KIT_OUT (env) is where the TSVs and pictures go; KIT_PHASE=before renders the "before" pictures only
 * (the gate runs it once with main's PanelPolish swapped in).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w1600dp-h800dp-288dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KitPolishTest {
  private val out=File(System.getenv("KIT_OUT") ?: "build/kit_polish").apply { mkdirs() }
  private val before=System.getenv("KIT_PHASE")=="before"
  private val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
  private val renderer by lazy { LayoutLintRenderer(activity,File(out,"lint")) }
  private val density get()=activity.resources.displayMetrics.density
  private val pictured=setOf("settings_voice","settings_view","dock","decision_card")

  private fun all(v: View): List<View> = if(v.visibility!=View.VISIBLE) emptyList() else
    listOf(v)+(if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList())

  private fun stockBackgrounds(): Map<Class<*>,Any?> = listOf(Button(activity),CheckBox(activity),Switch(activity),
    ToggleButton(activity),ImageButton(activity)).associate { it.javaClass to it.background?.constantState }

  private fun picture(column: View,w: Int,h: Int,file: File) {
    val bm=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
    column.draw(Canvas(bm))
    file.parentFile.mkdirs()
    File(file.parentFile,file.nameWithoutExtension+".alpha.png").outputStream().use { bm.compress(Bitmap.CompressFormat.PNG,100,it) }
    val preview=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
    Canvas(preview).apply { drawColor(Color.rgb(0xC8,0xC8,0xC8));drawBitmap(bm,0f,0f,null) }
    file.outputStream().use { preview.compress(Bitmap.CompressFormat.PNG,100,it) };bm.recycle();preview.recycle()
  }

  @Test fun everyPanelHasOnlyKitControls() {
    val cap=LayoutLintCapture()
    // ControlDockFeature registers a tall capsule, not the square shell capture fixture.
    val panels=(cap.specs()+cap.shellPanels()+LayoutLintPanels.panels).map {
      if(it.name=="dock") it.copy(wDp=ControlDock.DP,hDp=ControlDock.HEIGHT_DP) else it
    }
    val stock=stockBackgrounds()
    val rows=mutableListOf("panel\tview\tproblem\tdetail")
    val words=mutableListOf("panel\twords\tsample")
    var stockLeft=0;var small=0;var tiny=0;var built=0
    val failedBuild=mutableListOf<String>()
    for(p in panels) {
      val w=(p.wDp*density).roundToInt();val h=(p.hDp*density).roundToInt()
      val column=renderer.inflate()
      val host=FrameLayout(activity);host.addView(column,FrameLayout.LayoutParams(w,h))
      activity.setContentView(host)
      val ok=runCatching { p.build(column);PanelPolish.install(column);renderer.settle(host,w,h) }
      if(ok.isFailure) { failedBuild.add("${p.name}: ${ok.exceptionOrNull()?.javaClass?.simpleName}");rows.add("${p.name}\t-\tnot_built\t${ok.exceptionOrNull()?.message?.take(80)}");continue }
      built++
      if(p.name in pictured) picture(column,w,h,File(out,(if(before) "before/" else "after/")+p.name+".png"))
      val views=all(column)
      val text=views.filterIsInstance<TextView>().filter { it.isShown }.joinToString(" ") { it.text.toString() }
      val n=text.split(Regex("\\s+")).count { it.any(Char::isLetterOrDigit) }
      words.add("${p.name}\t$n\t${text.replace(Regex("\\s+")," ").take(120)}")
      for(v in views) {
        val id="${v.javaClass.simpleName}'${(v as? TextView)?.text?.toString()?.replace(Regex("\\s+")," ")?.take(30) ?: v.tag ?: ""}'"
        val control=v is Button || v is ImageButton
        if(control && (PanelPolish.kind(v)==null || (v.background!=null && v.background.constantState===stock[v.javaClass]))) {
          stockLeft++;rows.add("${p.name}\t$id\tstock_control\tno kit kind")
        }
        val minPx=64*density-1
        if(v.isClickable && v.isEnabled && v.isShown && (v.width<minPx || v.height<minPx)) {
          small++;rows.add("${p.name}\t$id\tclickable_under_64dp\t${(v.width/density).roundToInt()}x${(v.height/density).roundToInt()} dp")
        }
        if(v is TextView && v.isShown && v.text.isNotBlank()) {
          val sp=v.textSize/v.resources.displayMetrics.scaledDensity
          val mono=v.typeface==android.graphics.Typeface.MONOSPACE || v.typeface?.systemFontFamilyName=="monospace"
          val floor=if(mono) PanelPolish.CODE_SP else PanelPolish.REGULAR_SP
          val spans=(v.text as? android.text.Spanned)?.let { s -> s.getSpans(0,s.length,android.text.style.RelativeSizeSpan::class.java).minOfOrNull { it.sizeChange } } ?: 1f
          if(sp*minOf(1f,spans)<floor-0.01f) { tiny++;rows.add("${p.name}\t$id\ttext_under_${floor.toInt()}dp\t${"%.1f".format(sp*minOf(1f,spans))}") }
        }
      }
    }
    File(out,if(before) "panels_before.tsv" else "panels.tsv").writeText(rows.joinToString("\n")+"\n")
    File(out,if(before) "panel_words_before.tsv" else "panel_words.tsv").writeText(words.joinToString("\n")+"\n")
    File(out,if(before) "summary_before.txt" else "summary.txt").writeText(
      "panels=${panels.size} built=$built stock_left=$stockLeft clickable_under_64=$small text_under_floor=$tiny not_built=${failedBuild.joinToString(";")}\n")
    if(before) return
    sheet()
    assertEquals("unbuilt panels (see panels.tsv)",emptyList<String>(),failedBuild)
    assertEquals("stock controls left (see panels.tsv)",0,stockLeft)
    assertEquals("clickables under 64 dp (see panels.tsv)",0,small)
    assertEquals("text under 30 dp (see panels.tsv)",0,tiny)
  }

  /** Measure the production builder without PanelPolish (the dock registration does not install it).
   * Global visible rectangles include ancestor clipping: an oversized child is not a larger hit target.
   */
  @Test fun registeredDockTargets() {
    val root=activity.layoutInflater.inflate(R.layout.control_dock,null) as LinearLayout
    val picks=mutableListOf<String>()
    ControlDock.build(root) { picks.add(it) }
    val w=(ControlDock.DP*density).roundToInt();val h=(ControlDock.HEIGHT_DP*density).roundToInt()
    val host=FrameLayout(activity);host.addView(root,FrameLayout.LayoutParams(w,h));activity.setContentView(host)
    renderer.settle(host,w,h)
    val rows=mutableListOf("phase\ttarget\tleft_dp\ttop_dp\tright_dp\tbottom_dp\twidth_dp\theight_dp\tleft_mm\ttop_mm\tright_mm\tbottom_mm\twidth_mm\theight_mm\tdistance_m")
    val targets=all(root).filter { it.isClickable }
    val rects=targets.map { v -> android.graphics.Rect().also { org.junit.Assert.assertTrue(v.getGlobalVisibleRect(it)) } }
    targets.zip(rects).forEach { (v,r) ->
      val x=FloatArray(4) { listOf(r.left,r.top,r.right,r.bottom)[it]/density }
      val width=r.width()/density;val height=r.height()/density
      rows.add(listOf(System.getenv("N_PHASE") ?: "after",v.tag,*x.toTypedArray(),width,height,
        r.left*ControlDock.WIDTH_M/w*1000,r.top*ControlDock.HEIGHT_M/h*1000,
        r.right*ControlDock.WIDTH_M/w*1000,r.bottom*ControlDock.HEIGHT_M/h*1000,
        r.width()*ControlDock.WIDTH_M/w*1000,r.height()*ControlDock.HEIGHT_M/h*1000,ControlDock.DISTANCE_M).joinToString("\t"))
      org.junit.Assert.assertTrue("${v.tag}: $width x $height dp",width>=64f-1/density && height>=64f-1/density)
      // Ancestor/descendant targets deliberately dispatch the same action; neighbours must not overlap.
      v.performClick()
    }
    for(i in 0 until root.childCount) for(j in i+1 until root.childCount) {
      val a=android.graphics.Rect();val b=android.graphics.Rect()
      root.getChildAt(i).getGlobalVisibleRect(a);root.getChildAt(j).getGlobalVisibleRect(b)
      org.junit.Assert.assertFalse("neighbour targets overlap",android.graphics.Rect.intersects(a,b))
    }
    assertEquals(ControlDock.items().flatMap { listOf(it.view,it.view) },picks)
    File(out,"dock_targets.tsv").writeText(rows.joinToString("\n")+"\n")
    picture(root,w,h,File(out,"dock_registered.png"))
  }

  /** One sheet of every control in each state and every status mark (look_nov17 s2), for the design tool. */
  private fun sheet() {
    val ctx=activity
    val sheet=LinearLayout(ctx).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(ctx.getColor(R.color.fs_panel));setPadding(40,40,40,40) }
    fun row(label: String,items: List<Pair<String,View>>) {
      val r=LinearLayout(ctx).apply { gravity=android.view.Gravity.CENTER_VERTICAL }
      r.addView(TextView(ctx).apply { text=label;textSize=14f;setTextColor(Color.WHITE) },LinearLayout.LayoutParams((150*density).toInt(),-2))
      items.forEach { (state,v) ->
        val cell=LinearLayout(ctx).apply { orientation=LinearLayout.VERTICAL;gravity=android.view.Gravity.CENTER_HORIZONTAL }
        cell.addView(v,v.layoutParams ?: LinearLayout.LayoutParams(-2,-2))
        cell.addView(TextView(ctx).apply { text=state;textSize=11f;setTextColor(Color.WHITE) })
        r.addView(cell,LinearLayout.LayoutParams(-2,-2).apply { setMargins(12,12,12,12) })
      }
      sheet.addView(r)
    }
    fun styled(v: View,state: String): View { PanelPolish.apply(v);when(state) {
      "pointed at" -> v.isHovered=true; "pressed" -> v.isPressed=true; "selected","current" -> v.isSelected=true
      "disabled","dim" -> v.isEnabled=false; "on" -> (v as CompoundButton).isChecked=true }
      return v }
    val btnStates=listOf("rest","pointed at","pressed","selected","disabled")
    row("Text button",btnStates.map { it to styled(Button(ctx).apply { text="Read more" },it) })
    row("Filled button",listOf("rest","pointed at","pressed","dim").map { it to styled(Button(ctx).apply { text="Confirm";tag=PanelPolish.PRIMARY },it) })
    row("Icon button",btnStates.map { it to styled(ImageButton(ctx).apply { setImageResource(R.drawable.fs_icon_mic) },it) })
    val switchStates=listOf("off","on","pointed at","pressed")
    row("CheckBox",switchStates.map { it to styled(CheckBox(ctx).apply { text="Listen" },it) })
    row("Switch",switchStates.map { it to styled(Switch(ctx).apply { text="Listen" },it) })
    row("ToggleButton",switchStates.map { it to styled(ToggleButton(ctx).apply { text="Listen" },it) })
    row("Tab",listOf("rest","pointed at","pressed","current").map { it to styled(Button(ctx).apply { text="Voice";tag="settings-tab-voice" },it) })
    row("Surfaces",listOf("inner tile" to R.drawable.fs_tile,"code well" to R.drawable.fs_code_well).map { (k,d) ->
      k to View(ctx).apply { setBackgroundResource(d);layoutParams=LinearLayout.LayoutParams((220*density).toInt(),(96*density).toInt()) } })
    fun mark(d: Int)=ImageView(ctx).apply { setImageResource(d);minimumWidth=(48*density).toInt();minimumHeight=(48*density).toInt();scaleType=ImageView.ScaleType.FIT_CENTER;layoutParams=LinearLayout.LayoutParams((48*density).toInt(),(48*density).toInt()) }
    row("Status",listOf("planned" to R.drawable.fs_status_planned,"building" to R.drawable.fs_status_building,"built" to R.drawable.fs_status_built,
      "needs you" to R.drawable.fs_status_needs_you,"done" to R.drawable.fs_status_done,"error" to R.drawable.fs_status_error,"worker" to R.drawable.fs_status_worker).map { (k,d) -> k to mark(d) })
    val icons=listOf("Fleet" to R.drawable.fs_icon_fleet,"Blueprint" to R.drawable.fs_icon_blueprint,"Code" to R.drawable.fs_icon_code,"Yard" to R.drawable.fs_icon_yard,
      "Settings" to R.drawable.fs_icon_settings,"close" to R.drawable.fs_icon_close,"mic" to R.drawable.fs_icon_mic,"mic muted" to R.drawable.fs_icon_mic_muted,
      "thumb" to R.drawable.fs_icon_thumb,"plus" to R.drawable.fs_icon_plus,"check" to R.drawable.fs_icon_check)
    row("Line icons",icons.map { (k,d) -> k to styled(ImageButton(ctx).apply { setImageResource(d) },"rest") })
    row("pointed at",icons.map { (k,d) -> k to styled(ImageButton(ctx).apply { setImageResource(d) },"pointed at") })
    val w=(1600*density).roundToInt();val h=(1600*density).roundToInt()
    val host=FrameLayout(ctx);host.addView(sheet,FrameLayout.LayoutParams(w,h));activity.setContentView(host)
    renderer.settle(host,w,h)
    picture(sheet,w,h,File(out,"kit_sheet.png"))
  }
}
