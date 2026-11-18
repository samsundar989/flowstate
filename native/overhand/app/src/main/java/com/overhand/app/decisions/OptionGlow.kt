package com.overhand.app.decisions

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button

/** Selection is shown by light, never by a text label: a bright rim and lifted fill when selected; hover keeps the shared softer panel hover (lighter fill, slight scale). */
object OptionGlow {
  val RIM=Color.rgb(90,235,215)
  /** Foreground, not background: PanelPolish restyles every button background (and its hover) after layout. */
  fun apply(b: Button,selected: Boolean) {
    b.isSelected=selected
    b.foreground=if(selected) GradientDrawable().apply {
      setColor(Color.argb(70,90,235,215));setStroke(8,RIM);cornerRadius=24f
    } else null
  }
}

/** Fills clockwise while a thumbs-up is held; full means it registered. */
class HoldRing(context: Context): View(context) {
  var progress=0f
    set(value) { val v=value.coerceIn(0f,1f);if(v!=field) { field=v;invalidate() } }
  private val track=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE;strokeWidth=10f;color=Color.argb(90,255,255,255) }
  private val fill=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE;strokeWidth=12f;strokeCap=Paint.Cap.ROUND;color=OptionGlow.RIM }
  /** #800: the letter of the option a thumbs-up would save, inside the ring; bright while it fills. */
  var label=""
    set(value) { if(value!=field) { field=value;invalidate() } }
  private val letter=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE;textAlign=Paint.Align.CENTER;isFakeBoldText=true }
  private val box=RectF()
  init { tag="card-hold-ring" }
  override fun onDraw(canvas: Canvas) {
    val pad=10f;box.set(pad,pad,width-pad,height-pad)
    canvas.drawOval(box,track)
    if(progress>0f) canvas.drawArc(box,-90f,360f*progress,false,fill)
    if(tag==QuestionCard.TAG_THUMB) {
      canvas.save();canvas.scale(width/72f,height/72f)
      val pen=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=QuestionCard.CYAN;style=Paint.Style.STROKE;strokeWidth=2f;strokeJoin=Paint.Join.ROUND }
      canvas.drawPath(Path().apply { moveTo(22f,34f);lineTo(31f,34f);lineTo(37f,20f);lineTo(42f,20f);lineTo(42f,32f);lineTo(51f,32f);lineTo(48f,51f);lineTo(31f,51f);lineTo(31f,34f);moveTo(22f,34f);lineTo(22f,51f);lineTo(28f,51f);lineTo(28f,34f) },pen)
      canvas.restore()
    } else if(label.isNotEmpty()) {
      letter.textSize=(height-4*pad)*0.6f;letter.alpha=if(progress>0f) 255 else 140
      canvas.drawText(label,width/2f,height/2f-(letter.descent()+letter.ascent())/2,letter)
    }
  }
}
