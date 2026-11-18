package com.overhand.app.onboarding

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Small drawn ghost hand: thumb/index close for pinch, raised thumb for confirmation. */
class GestureGuide(context: Context, private val pinch: Boolean, private val compact: Boolean=false): View(context) {
  private val ink=Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color=Color.rgb(130,255,230);strokeWidth=5f;style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND
  }
  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    canvas.save()
    // compact (#1033, the card's gesture hint): the 70x82 drawing fitted and centred in a small icon box.
    if(compact) { val k=minOf(width/80f,height/86f);canvas.translate(width/2f-52f*k,height/2f-39f*k);canvas.scale(k,k) }
    else { canvas.translate(width/2f-60f,4f);canvas.scale(0.85f,0.85f) }
    val p=Path()
    if(pinch) {
      p.moveTo(35f,80f);p.lineTo(20f,54f);p.lineTo(25f,26f);p.cubicTo(28f,8f,55f,8f,60f,27f)
      p.lineTo(53f,40f);p.lineTo(40f,39f);p.lineTo(51f,28f)
      p.moveTo(53f,40f);p.lineTo(78f,40f);p.lineTo(86f,65f);p.lineTo(72f,80f)
      canvas.drawCircle(59f,26f,13f,ink)
    } else {
      p.moveTo(30f,80f);p.lineTo(17f,48f);p.lineTo(20f,10f);p.cubicTo(20f,-2f,34f,-2f,34f,10f)
      p.lineTo(34f,36f);p.lineTo(77f,36f);p.lineTo(84f,45f);p.lineTo(80f,73f);p.lineTo(70f,80f)
      for(y in listOf(46f,56f,66f)) canvas.drawLine(50f,y,79f,y,ink)
    }
    canvas.drawPath(p,ink);canvas.restore()
    contentDescription=if(pinch) "Ghost hand pinching thumb and index finger" else "Ghost hand holding thumbs up"
  }
}
