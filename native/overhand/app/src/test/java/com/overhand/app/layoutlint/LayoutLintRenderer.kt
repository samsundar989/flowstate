package com.overhand.app.layoutlint

import android.app.Activity
import android.graphics.*
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.overhand.app.R
import java.io.File
import java.time.Duration
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.Shadows.shadowOf

/** Physical facts the lint needs: panel metres, dp size and the default viewing distance. */
data class Panel(val name: String,val wDp: Float,val hDp: Float,val wM: Float,val hM: Float,val distanceM: Float,
  val source: String,val build: (LinearLayout) -> Unit) {
  /**
   * #928: a window read at a glance, never scrolled (the decision card). Its scroll panes excuse nothing: text the
   * pane's viewport cuts counts as clipped, the way the wearer sees it.
   */
  var wholeText=false
}

/**
 * Capture entry point (#807). A panel registered here is rendered by LayoutLintCapture with every other window
 * and gated by tools/layout_lint/baseline.json once it is in the baseline.
 *
 * Compose panels (#772) register a builder that adds their ComposeView to the panel column, for example
 *   Panel("my_panel",1000f,720f,1f,0.72f,1.2f,"MyPanel.kt") { column ->
 *     column.addView(ComposeView(column.context).apply { setContent { MyPanel() } },LinearLayout.LayoutParams(-1,-1)) }
 * The renderer dumps Android views. A ComposeView is one opaque view to it until #772 adds a semantics-tree dump
 * (compose-ui-test is not on main yet), so register Compose text as views or extend [LayoutLintRenderer.dump].
 * LayoutLintPanelsTest is the worked example.
 */
object LayoutLintPanels {
  val panels: List<Panel> = listOf<Panel>(
    // #772: one Panel(...) per Compose window.
  )+connect()+project()+preview()+bfn()

  /** #1008: Recorded mode's build-from-nothing replay window at six points of the bundled run. */
  private fun bfn(): List<Panel> {
    val v=com.overhand.app.hostlink.project.ProjectPanelView
    return com.overhand.app.hostlink.recorded.RecordedBuildReplayTest.screens().map { (k,sc) ->
      Panel("bfn_$k",v.W_DP,v.H_DP,v.W_M,v.H_M,v.DIST_M,"hostlink/recorded/RecordedBuildFeature.kt") { column -> v.render(column,sc) {} }
    }
  }

  /** #973: the Preview window (loading, ready, refused) and the spoken-change chip on the project window. */
  private fun preview(): List<Panel> {
    val v=com.overhand.app.windows.PreviewPanelView
    val pv=com.overhand.app.hostlink.project.ProjectPanelView
    return com.overhand.app.hostlink.project.PreviewFetchTest.states().map { (k,st) ->
      Panel("preview_$k",v.W_DP,v.H_DP,v.W_M,v.H_M,v.DIST_M,"windows/PreviewPanelView.kt") { column ->
        // A WebView draws nothing under Robolectric: the lint sees a plain view in its place, at its size.
        val parts=v.build(column,android.view.View(column.context))
        v.show(parts,st.first,st.second,k!="loading")
      }
    }+com.overhand.app.hostlink.project.FollowUpFlowTest.screens().map { (k,sc) ->
      Panel("project_$k",pv.W_DP,pv.H_DP,pv.W_M,pv.H_M,pv.DIST_M,"hostlink/project/ProjectPanelView.kt") { column -> pv.render(column,sc) {} }
    }
  }

  /** #815: the project window (new project chip, progress, question, finished) in every state it can show. */
  private fun project(): List<Panel> {
    val v=com.overhand.app.hostlink.project.ProjectPanelView
    return com.overhand.app.hostlink.project.NewProjectFlowTest.all().map { (k,sc) ->
      Panel("project_$k",v.W_DP,v.H_DP,v.W_M,v.H_M,v.DIST_M,"hostlink/project/ProjectPanelView.kt") { column -> v.render(column,sc) {} }
    }
  }

  /** #814: the Connect panel in every state it can show. */
  private fun connect(): List<Panel> {
    val none=object: com.overhand.app.hostlink.ui.ConnectPanelView.Actions {
      override fun action(key: String) {}; override fun host(ep: com.overhand.app.hostlink.Endpoint) {}; override fun key(d: String) {} }
    val v=com.overhand.app.hostlink.ui.ConnectPanelView
    return com.overhand.app.hostlink.ui.ConnectScreenTest.all().map { (k,sc) ->
      Panel("connect_$k",v.W_DP,v.H_DP,v.W_M,v.H_M,v.DIST_M,"hostlink/ui/ConnectPanelView.kt") { column -> v.render(column,sc,none) }
    }
  }
}

/**
 * Renders a [Panel] at its registered pixel size (dp at 288 dpi, 1 dp = 1.8 px) with Robolectric native graphics
 * and writes `<window>.png` + `<window>.json` (every element's bounds, text lines, colours, padding) into [out].
 */
class LayoutLintRenderer(private val activity: Activity,val out: File) {
  init { out.mkdirs() }
  val density get()=activity.resources.displayMetrics.density

  fun inflate(): LinearLayout {
    val frame=FrameLayout(activity)
    activity.layoutInflater.inflate(R.layout.web_panel,frame,true)
    return (frame.getChildAt(0) as LinearLayout).also { frame.removeView(it) }
  }

  fun settle(root: View,w: Int,h: Int) {
    repeat(4) {
      root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY))
      root.layout(0,0,w,h)
      Thread.sleep(30)
      shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
    }
  }

  fun capture(p: Panel): JSONObject {
    val w=(p.wDp*density).roundToInt();val h=(p.hDp*density).roundToInt()
    val column=inflate()
    val host=FrameLayout(activity);host.addView(column,FrameLayout.LayoutParams(w,h))
    activity.setContentView(host)       // attached, so View.post (pagination) runs as on the headset
    p.build(column)
    settle(host,w,h)
    val first=captureExisting(p,column)
    fun buttons(v: View): List<Button> = (if(v is Button) listOf(v) else emptyList())+
      (if(v is ViewGroup) (0 until v.childCount).flatMap { buttons(v.getChildAt(it)) } else emptyList())
    var n=1
    while(buttons(column).firstOrNull { it.text=="Read more" }?.isEnabled==true) {
      check(n<500) { "unbounded page controls" }
      buttons(column).first { it.text=="Read more" }.performClick()
      captureExisting(p.copy(name="${p.name}_page${n++}").apply { wholeText=p.wholeText },column)
    }
    while(buttons(column).firstOrNull { it.text=="Read back" }?.isEnabled==true)
      buttons(column).first { it.text=="Read back" }.performClick()
    if(p.name=="chat") {
      val web=column.findViewWithTag<android.webkit.WebView>(com.overhand.app.windows.LookK8.WEB_TAG)
      check(web.isShown) { "briefing must be the first view" }
      captureExisting(p.copy(name="chat_briefing"),column)
      val doc=File(out,"briefing.html")
      doc.writeText(shadowOf(web).lastLoadDataWithBaseURL.data)
      val config=File(out,"briefing-config.txt")
      // Robolectric's WebView shadow measures 0x0 for a weighted child. All native siblings
      // are GONE on the briefing, so its actual viewport is the full panel column.
      check((0 until column.childCount).map { column.getChildAt(it) }.all { it===web || it.visibility==View.GONE })
      config.writeText("${w/density}\t${h/density}\t$density\t${p.wM}\t${p.distanceM}\t${p.wDp}")
      val log=File(out,"briefing-render.log")
      val helper=generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .map { File(it,"runs/fix17/M/briefing.mjs") }.first { it.isFile }
      val process=ProcessBuilder("node",helper.absolutePath,out.absolutePath)
        .redirectErrorStream(true).redirectOutput(log).start()
      check(process.waitFor()==0) { "briefing Chromium capture failed: ${log.readText()}" }
      val bridge=shadowOf(web).getJavascriptInterface("AfK8")!!
      bridge.javaClass.getMethod("act",String::class.java,String::class.java).apply { isAccessible=true }
        .invoke(bridge,"history","")
      settle(column,w,h)
      check(!web.isShown) { "History must hide briefing" }
      val coverage=mutableListOf("chat_briefing", "chat_briefing_web")
      fun recordedPages(prefix: String) {
        var page=0
        val seen=mutableSetOf<String>()
        do {
          settle(column,w,h)
          val text=column.findViewWithTag<TextView>("recorded-page")
          check(text.isShown && text.text.isNotBlank()) { "History page must be visible and populated" }
          check(seen.add(text.text.toString())) { "History did not advance" }
          val name="${prefix}_page${page++}"
          captureExisting(p.copy(name=name).apply { wholeText=true },column)
          coverage.add(name)
          val next=buttons(column).first { it.text=="Next" && it.isShown }
          if(!next.isEnabled) break
          check(page<500) { "unbounded History page controls" };next.performClick()
        } while(true)
      }
      recordedPages("chat_reading")
      column.findViewWithTag<View>("current-work").also { check(it.isShown) }.performClick()
      recordedPages("chat_current_work")
      column.findViewWithTag<View>(com.overhand.app.windows.LookK8.BACK_TAG).performClick()
      settle(column,w,h)
      check(web.isShown && !column.findViewWithTag<View>("recorded-page").isShown)
      captureExisting(p.copy(name="chat_briefing_return"),column)
      coverage.add("chat_briefing_return")
      fun covered(names: List<String>)=names.containsAll(listOf("chat_briefing","chat_briefing_web","chat_briefing_return")) &&
        names.any { it.startsWith("chat_reading_page") } && names.any { it.startsWith("chat_current_work_page") } &&
        names.all { File(out,"$it.json").isFile && File(out,"$it.png").isFile }
      check(covered(coverage)) { "incomplete briefing/History coverage" }
      val noBriefing=covered(coverage.filterNot { it.startsWith("chat_briefing") })
      val noHistory=covered(coverage.filterNot { it.startsWith("chat_reading") || it.startsWith("chat_current_work") })
      check(!noBriefing && !noHistory) { "planted omissions were not detected" }
      File(out,"chat_coverage.txt").writeText(coverage.joinToString("\n")+"\nfull=PASS\nomit_briefing=FAIL\nomit_history=FAIL\n")
    }
    return first
  }

  fun captureExisting(p: Panel,column: LinearLayout): JSONObject {
    val w=(p.wDp*density).roundToInt();val h=(p.hDp*density).roundToInt()
    settle(column,w,h)
    val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
    val canvas=Canvas(bitmap);canvas.drawColor(Color.BLACK)   // the panel quad over a dark room
    column.draw(canvas)
    File(out,"${p.name}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    bitmap.recycle()
    val elements=JSONArray()
    dump(column,-1,0f,0f,Rect(0,0,w,h),elements,p.wholeText)
    val meta=JSONObject().put("window",p.name).put("kind","android-view").put("source",p.source)
      .put("width_px",w).put("height_px",h).put("width_dp",p.wDp.toDouble()).put("height_dp",p.hDp.toDouble())
      .put("density",density.toDouble()).put("width_m",p.wM.toDouble()).put("height_m",p.hM.toDouble())
      .put("m_per_px",p.wM.toDouble()/w).put("distance_m",p.distanceM.toDouble()).put("grid",true)
      .put("push_distance_m",com.overhand.app.windows.PolishRules.pushed(p.distanceM.toDouble()))
      .put("elements",elements).put("strings",JSONArray(strings(column).distinct()))
    File(out,"${p.name}.json").writeText(meta.toString(1))
    return meta
  }

  private fun name(v: View,index: Int): String {
    val tag=(v.tag as? String)?.let { "[$it]" } ?: ""
    val id=if(v.id!=View.NO_ID) runCatching { "#"+v.resources.getResourceEntryName(v.id) }.getOrDefault("") else ""
    val text=(v as? TextView)?.text?.toString()?.replace('\n',' ')?.take(40)?.let { "'$it'" } ?: ""
    return "${v.javaClass.simpleName}$id$tag$text@$index"
  }

  private fun dump(v: View,parent: Int,px: Float,py: Float,clip: Rect,out: JSONArray,whole: Boolean=false) {
    if(v.visibility!=View.VISIBLE) return
    val index=out.length()
    val x=px+v.left+v.translationX;val y=py+v.top+v.translationY
    val r=Rect(x.roundToInt(),y.roundToInt(),(x+v.width).roundToInt(),(y+v.height).roundToInt())
    val shown=Rect(r);val visible=shown.intersect(clip)
    val o=JSONObject().put("index",index).put("parent",parent).put("name",name(v,index)).put("class",v.javaClass.name)
      .put("tag",(v.tag as? String) ?: JSONObject.NULL).put("rect",JSONArray(listOf(r.left,r.top,r.right,r.bottom)))
      .put("visible_rect",if(visible) JSONArray(listOf(shown.left,shown.top,shown.right,shown.bottom)) else JSONObject.NULL)
      .put("padding",JSONArray(listOf(v.paddingLeft,v.paddingTop,v.paddingRight,v.paddingBottom)))
      .put("clickable",v.isClickable && v.isEnabled).put("alpha",v.alpha.toDouble())
      .put("scroll_container",!whole && (v is ScrollView || v is HorizontalScrollView || v is AdapterView<*>))
    (v.layoutParams as? ViewGroup.MarginLayoutParams)?.let { o.put("margin",JSONArray(listOf(it.leftMargin,it.topMargin,it.rightMargin,it.bottomMargin))) }
    (v as? LinearLayout)?.let { o.put("orientation",if(it.orientation==LinearLayout.VERTICAL) "vertical" else "horizontal") }
    if(v is TextView) text(v,x,y,o,if(whole && visible) shown.bottom else null)
    // A container that is not shown (0 px, or past the panel edge) is not descended into; record the text it
    // hides so the lint can still say what the wearer lost (#807: the graph detail's 0 px ScrollView).
    if(v is ViewGroup && !visible) hiddenText(v).takeIf { it.isNotBlank() }?.let { o.put("hidden_text",it) }
    out.put(o)
    if(v is ViewGroup && visible) {
      val childClip=if(v.clipChildren) shown else clip
      for(i in 0 until v.childCount) dump(v.getChildAt(i),index,x-v.scrollX,y-v.scrollY,childClip,out,whole)
    }
  }

  /**
   * #879 brand gate: every string this window can show in this state, visible or not (a GONE view can be shown by
   * the next tap): TextView text and hint, any view's content description and tooltip. tools/brand_gate scans these.
   */
  private fun strings(v: View): List<String> {
    val own=listOfNotNull((v as? TextView)?.text?.toString(),(v as? TextView)?.hint?.toString(),
      v.contentDescription?.toString(),v.tooltipText?.toString()).filter { it.isNotBlank() }
    return own+if(v is ViewGroup) (0 until v.childCount).flatMap { strings(v.getChildAt(it)) } else emptyList()
  }

  private fun hiddenText(g: ViewGroup): String=(0 until g.childCount).map { g.getChildAt(it) }
    .filter { it.visibility==View.VISIBLE }
    .joinToString(" ") { c -> if(c is TextView) c.text.toString() else if(c is ViewGroup) hiddenText(c) else "" }.trim()

  private fun text(v: TextView,x: Float,y: Float,o: JSONObject,viewportBottom: Int?=null) {
    val s=v.text.toString()
    o.put("text",s).put("text_size_px",v.textSize.toDouble()).put("text_color",String.format("#%08X",v.currentTextColor))
      .put("gravity_h",when(v.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) { Gravity.CENTER_HORIZONTAL -> "center"; Gravity.RIGHT,Gravity.END -> "end"; else -> "start" })
      .put("button",v is Button).put("max_lines",v.maxLines)
    // #861: the measured x-height of this font at this size, and the smallest size a span gives any of its text
    // (RelativeSizeSpan / AbsoluteSizeSpan), so the lint sees the size the wearer actually reads.
    val xb=Rect();v.paint.getTextBounds("x",0,1,xb)
    o.put("x_height_px",xb.height().toDouble())
    (v.text as? android.text.Spanned)?.let { sp ->
      val scales=sp.getSpans(0,sp.length,android.text.style.RelativeSizeSpan::class.java).map { it.sizeChange.toDouble() } +
        sp.getSpans(0,sp.length,android.text.style.AbsoluteSizeSpan::class.java).map {
          (if(it.dip) it.size*v.resources.displayMetrics.density else it.size.toFloat()).toDouble()/v.textSize }
      scales.minOrNull()?.let { o.put("min_span_scale",it) }
    }
    val layout=v.layout ?: return
    val innerTop=y+v.totalPaddingTop;val innerLeft=x+v.totalPaddingLeft
    // #928: in a whole-text window the text box ends where the panel stops showing it (a scroll viewport's edge).
    val innerH=(v.height-v.totalPaddingTop-v.totalPaddingBottom).let { h -> viewportBottom?.let { minOf(h.toFloat(),it-innerTop).toInt() } ?: h }
    val innerW=v.width-v.totalPaddingLeft-v.totalPaddingRight
    val lines=JSONArray()
    for(i in 0 until layout.lineCount) {
      val start=layout.getLineStart(i);val end=layout.getLineEnd(i)
      lines.put(JSONObject().put("start",start).put("end",end).put("text",s.substring(start.coerceAtMost(s.length),end.coerceAtMost(s.length)))
        .put("left",(innerLeft+layout.getLineLeft(i)).toDouble()).put("right",(innerLeft+layout.getLineRight(i)).toDouble())
        .put("top",(innerTop+layout.getLineTop(i)).toDouble()).put("bottom",(innerTop+layout.getLineBottom(i)).toDouble())
        .put("baseline",(innerTop+layout.getLineBaseline(i)).toDouble()).put("ellipsis",layout.getEllipsisCount(i)))
    }
    o.put("inner",JSONArray(listOf(innerLeft.toDouble(),innerTop.toDouble(),(innerLeft+innerW).toDouble(),(innerTop+innerH).toDouble())))
      .put("layout_height",layout.height).put("lines",lines)
  }
}
