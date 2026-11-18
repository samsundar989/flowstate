package com.overhand.app.decisions

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout

/**
 * #800: no silent pinch on a card. Every touch the panel receives leaves two trace lines,
 *   `OverhandTouch: down panel=<id> x=<px> y=<px> view=<control or none>`
 *   `OverhandTouch: up panel=<id> x=<px> y=<px> view=<control or none> outcome=<select|confirm|next|…|none:<reason>>`
 * and a pinch that did nothing draws a brief ring where it landed. Controls say what a pinch did with [report];
 * a pinch no control reported is `none:miss` (no control under it), `none:cancel` (the press was cancelled, e.g. it
 * slid off the control) or `none:unhandled`.
 *
 * A rebuilt card keeps this by putting its content inside a [Layout] and calling [report] from each handler.
 */
class CardTouchTrace(val panel: String,private val owner: com.overhand.app.features.PinchOwner=com.overhand.app.features.PinchOwner.shared) {
  private var layout: Layout?=null
  /** t1282: a clickable view (or Compose node) at fractions (0..1) of the panel, for the grab path's priority check. */
  fun clickableAt(fx: Double,fy: Double): Boolean {
    val l=layout ?: return false
    if(fx !in 0.0..1.0 || fy !in 0.0..1.0 || l.width<=0 || l.height<=0) return false
    val x=(fx*l.width).toFloat();val y=(fy*l.height).toFloat()
    return hit(l,x,y)!=null || namer?.invoke(x,y)!=null
  }
  private class Gesture(val x: Float,val y: Float,val view: String) { var outcome: String?=null }
  private var gesture: Gesture?=null
  /** #874: names the target under (x, y) when no clickable View is there (a Compose card's semantics node). */
  var namer: ((Float,Float) -> String?)?=null
  /** What the current pinch did. The last report wins, so a later block ("none:busy") overrides an earlier intent. */
  fun report(outcome: String) {
    val g=gesture
    if(g==null) { Log.i(TAG,"click panel=$panel outcome=$outcome source=no_touch");return }
    g.outcome=outcome
  }
  /** The card's content root: traces each touch and draws the "nothing here" ring. */
  inner class Layout(context: Context): LinearLayout(context) {
    private val ring=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE;strokeWidth=8f }
    private var pulseX=0f;private var pulseY=0f;private var pulseAt=-1L
    init { orientation=VERTICAL;tag="card-touch-trace";setWillNotDraw(false);this@CardTouchTrace.layout=this }
    /** t1282: this gesture belongs to a grab of the same pinch; none of it reaches the card. */
    private var swallowed=false
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
      val end=ev.actionMasked==MotionEvent.ACTION_UP || ev.actionMasked==MotionEvent.ACTION_CANCEL
      if(ev.actionMasked==MotionEvent.ACTION_DOWN) {
        val v=hit(this,ev.x,ev.y)
        val named=v?.let(::name) ?: namer?.invoke(ev.x,ev.y)
        swallowed=!owner.pressDown(panel,named!=null,ev.eventTime)
        if(swallowed) { Log.i(TAG,"down panel=$panel x=${ev.x.toInt()} y=${ev.y.toInt()} view=${named ?: "none"} owner=grab");return true }
        gesture=Gesture(ev.x,ev.y,named ?: "none")
        Log.i(TAG,"down panel=$panel x=${ev.x.toInt()} y=${ev.y.toInt()} view=${gesture!!.view}")
      } else if(swallowed) {
        if(end) { swallowed=false;Log.i(TAG,"up panel=$panel x=${ev.x.toInt()} y=${ev.y.toInt()} outcome=none:grab") }
        return true
      }
      val handled=super.dispatchTouchEvent(ev)
      if(end) {
        owner.pressUp(panel)
        val g=gesture;val cancelled=ev.actionMasked==MotionEvent.ACTION_CANCEL;val x=ev.x;val y=ev.y
        // A click runs from a post queued during this UP, so the outcome is read after it.
        if(g!=null) post { finish(g,cancelled,x,y) }
      }
      return handled
    }
    private fun finish(g: Gesture,cancelled: Boolean,x: Float,y: Float) {
      if(gesture===g) gesture=null
      val outcome=g.outcome ?: "none:"+when { g.view=="none" -> "miss";cancelled -> "cancel";else -> "unhandled" }
      Log.i(TAG,"up panel=$panel x=${x.toInt()} y=${y.toInt()} view=${g.view} outcome=$outcome")
      if(outcome.startsWith("none")) pulse(g.x,g.y)
    }
    /** The "nothing here" response: an amber ring at the pinch that fades over [PULSE_MS]. */
    fun pulse(x: Float,y: Float) { pulseX=x;pulseY=y;pulseAt=SystemClock.uptimeMillis();invalidate() }
    override fun dispatchDraw(canvas: Canvas) {
      super.dispatchDraw(canvas)
      if(pulseAt<0) return
      val t=(SystemClock.uptimeMillis()-pulseAt).toFloat()/PULSE_MS
      if(t>=1f) { pulseAt=-1;return }
      ring.color=Color.argb((255*(1f-t)).toInt(),255,190,70)
      canvas.drawCircle(pulseX,pulseY,36f+48f*t,ring)
      postInvalidateOnAnimation()
    }
  }
  companion object {
    const val TAG="OverhandTouch"
    const val PULSE_MS=1400L
    /** The deepest shown, clickable view under (x, y) in [v]'s coordinates. */
    fun hit(v: View,x: Float,y: Float): View? {
      if(v.visibility!=View.VISIBLE) return null
      if(v is ViewGroup) for(i in v.childCount-1 downTo 0) {
        val c=v.getChildAt(i);val cx=x+v.scrollX-c.left;val cy=y+v.scrollY-c.top
        if(cx>=0 && cy>=0 && cx<c.width && cy<c.height) hit(c,cx,cy)?.let { return it }
      }
      return v.takeIf { it.isClickable }
    }
    /** Short names for the trace: option:A, confirm_selected_option, next_decision, close, … */
    fun name(v: View): String {
      val text=(v as? Button)?.text?.toString().orEmpty()
      if(v.tag==com.overhand.app.onboarding.HandsOnboarding.OPTION_TAG) return "option:"+text.substringBefore(':').trim().take(12)
      if(text.isNotBlank()) return slug(text)
      return (v.tag as? String) ?: v.javaClass.simpleName
    }
    fun slug(s: String)=s.lowercase().replace(Regex("[^a-z0-9]+"),"_").trim('_').take(28)
  }
}
