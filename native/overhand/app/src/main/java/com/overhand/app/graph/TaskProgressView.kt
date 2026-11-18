package com.overhand.app.graph

import android.animation.ValueAnimator
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.widget.ProgressBar

/** Same bounded 200 ms transition as t361, applied only to directly recorded task counts. */
object TaskProgressView {
  private data class Transition(val target: Int,val animator: ValueAnimator)
  /** Size and weight only, so each surface keeps its own status colours. */
  fun styled(lines: List<ProjectContext.Line>,clean: (String)->String): CharSequence=SpannableStringBuilder().apply {
    lines.forEachIndexed { i,line ->
      if(i>0) append('\n')
      val start=length;append(clean(line.text))
      if(line.scale!=1f) setSpan(RelativeSizeSpan(line.scale),start,length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      if(line.bold) setSpan(StyleSpan(Typeface.BOLD),start,length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
  }
  fun update(bar: ProgressBar,fraction: Double) {
    val target=(fraction*1000).toInt().coerceIn(0,1000)
    val previous=bar.tag as? Transition
    if(previous?.target==target) return
    previous?.animator?.cancel()
    bar.max=1000
    val from=bar.progress.toFloat()
    val animator=ValueAnimator.ofFloat(0f,1f).apply {
      duration=TaskReplay.DURATION
      interpolator=android.view.animation.LinearInterpolator()
      addUpdateListener { bar.progress=TaskReplay.ease(from,target.toFloat(),0,(it.animatedFraction*TaskReplay.DURATION).toLong()).toInt() }
    }
    bar.tag=Transition(target,animator);animator.start()
  }
}
