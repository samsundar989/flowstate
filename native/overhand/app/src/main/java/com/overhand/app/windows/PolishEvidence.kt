package com.overhand.app.windows

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.view.*
import android.widget.*
import org.json.*

/** Debug-only probes. Captures are labelled synthetic; no probe is evidence of tracked hands. */
object PolishEvidence {
  private val extras=mutableMapOf<String,View>()
  private val extraSizes=mutableMapOf<String,Pair<Float,Float>>()
  fun register(name: String,root: View,w: Float,h: Float) { extras[name]=root;extraSizes[name]=w to h }
  /** Registered non-window panels (the card, the graph). */
  val registered: Map<String,View> get()=extras
  private fun descendants(root: View): List<View> = listOf(root)+(if(root is ViewGroup)
    (0 until root.childCount).flatMap { descendants(root.getChildAt(it)) } else emptyList())
  fun handle(activity: Activity,intent: Intent,roots: Map<String,View>,sizes: Map<String,Pair<Float,Float>>,
    content: RecordedContent,preview: (String,String)->Unit) {
    val action=intent.getStringExtra("polish_action") ?: return
    val view=intent.getStringExtra("polish_view") ?: "fleet"
    val root=roots[view] ?: extras[view] ?: return
    val buttons=descendants(root).filterIsInstance<Button>()
    val label=intent.getStringExtra("polish_label")
    val target=buttons.firstOrNull { label==null || it.text.toString()==label }
    when(action) {
      "hover" -> target?.dispatchGenericMotionEvent(MotionEvent.obtain(0,android.os.SystemClock.uptimeMillis(),MotionEvent.ACTION_HOVER_ENTER,10f,10f,0).apply { source=android.view.InputDevice.SOURCE_MOUSE })
      "pageFrames" -> {
        target?.performClick()
        listOf(0L,60L,240L).forEach { delay -> root.postDelayed({
          val image=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888);root.draw(Canvas(image))
          activity.openFileOutput("polish_page_${view}_$delay.png",0).use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()
        },delay) }
      }
      "hoverFrames" -> {
        target?.isHovered=true
        target?.animate()?.scaleX(1.025f)?.scaleY(1.025f)?.setDuration(120)?.start()
        listOf(0L,60L,180L).forEach { delay -> root.postDelayed({
          val image=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
          root.draw(Canvas(image))
          activity.openFileOutput("polish_hover_${view}_$delay.png",0).use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
          image.recycle()
        },delay) }
      }
      "leave" -> target?.dispatchGenericMotionEvent(MotionEvent.obtain(0,android.os.SystemClock.uptimeMillis(),MotionEvent.ACTION_HOVER_EXIT,10f,10f,0).apply { source=android.view.InputDevice.SOURCE_MOUSE })
      "click" -> target?.performClick()
      "workDetails" -> root.findViewWithTag<View>("current-work")?.performClick()
      "scrollDown","scrollUp" -> descendants(root).filterIsInstance<ScrollView>().firstOrNull()?.fullScroll(
        if(action=="scrollDown") View.FOCUS_DOWN else View.FOCUS_UP)
      "long" -> {
        val source=if(view=="settings") activity.assets.open("description.txt").bufferedReader().use { it.readText() }
          else content.projects.map { content.window(view,it.getString("id")) }.flatMap { it.lines() }.maxByOrNull { it.length }.orEmpty()
        activity.openFileOutput("polish_long_$view.txt",0).use { it.write(source.toByteArray()) }
        if(view=="settings") {
          val column=descendants(root).filterIsInstance<LinearLayout>().firstOrNull { it.tag=="settings-content" }
          column?.addView(TextView(activity).apply { text=source;textSize=PolishRules.BODY;setTextColor(Color.WHITE);tag="long-probe" },0)
        } else preview(view,source)
      }
      "measure" -> (roots+extras).forEach { (name,r) ->
        val size=(sizes+extraSizes).getValue(name)
        val all=descendants(r)
        val controls=all.filter { it is Button || it is EditText }
        val origin=IntArray(2);r.getLocationInWindow(origin)
        val metrics=JSONArray()
        controls.forEach { c ->
          val p=IntArray(2);c.getLocationInWindow(p)
          val visible=Rect();val any=c.getLocalVisibleRect(visible)
          metrics.put(JSONObject().put("text",(c as? TextView)?.text?.toString() ?: "Project picker")
            .put("x_px",p[0]-origin[0]).put("y_px",p[1]-origin[1])
            .put("width_px",c.width).put("height_px",c.height)
            .put("width_mm",c.width*size.first*1000/r.width).put("height_mm",c.height*size.second*1000/r.height)
            .put("visible",any).put("fully_visible",any && visible.width()==c.width && visible.height()==c.height)
            .put("lower_half",p[1]-origin[1]>=r.height/2))
        }
        val texts=JSONArray()
        all.filterIsInstance<TextView>().forEach { t ->
          val layout=t.layout
          val p=IntArray(2);t.getLocationInWindow(p)
          val visible=Rect();val any=t.getLocalVisibleRect(visible)
          texts.put(JSONObject().put("text",t.text.toString()).put("sp",t.textSize/t.resources.displayMetrics.scaledDensity)
            .put("x_px",p[0]-origin[0]).put("y_px",p[1]-origin[1])
            .put("fully_visible",any && visible.width()==t.width && visible.height()==t.height)
            .put("scrollable",generateSequence(t.parent) { it.parent }.any { it is ScrollView })
            .put("tag",t.tag).put("height_px",t.height).put("inner_height_px",t.height-t.paddingTop-t.paddingBottom)
            .put("line_bottom_px",if(layout!=null && layout.lineCount>0) layout.getLineBottom(layout.lineCount-1) else 0)
            .put("max_line_width_px",if(layout!=null) (0 until layout.lineCount).maxOfOrNull { layout.getLineMax(it) } ?: 0f else 0f)
            .put("inner_width_px",t.width-t.paddingLeft-t.paddingRight))
        }
        val report=JSONObject().put("view",name).put("width_px",r.width).put("height_px",r.height)
          .put("width_m",size.first).put("height_m",size.second).put("controls",metrics).put("texts",texts)
        activity.openFileOutput("polish_$name.json",0).use { it.write(report.toString(2).toByteArray()) }
        if(r.width>0 && r.height>0) {
          val image=Bitmap.createBitmap(r.width,r.height,Bitmap.Config.ARGB_8888);r.draw(Canvas(image))
          activity.openFileOutput("polish_native_$name.png",0).use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()
        }
      }
    }
  }
}
