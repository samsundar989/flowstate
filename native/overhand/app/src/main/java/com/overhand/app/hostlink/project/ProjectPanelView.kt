package com.overhand.app.hostlink.project

import android.content.res.ColorStateList
import android.graphics.Color
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Draws a [ProjectScreen] into a panel column (#815), on the same grid as the Connect panel: 8 dp spacing, text at
 * least 28 sp, buttons at least 100 dp high. Tags: `project-title`, `project-line-<i>`, `project-<key>` for buttons.
 */
object ProjectPanelView {
  const val W_DP=1000f; const val H_DP=1200f; const val W_M=0.8f; const val H_M=0.96f; const val DIST_M=1.0f
  private val BG=Color.rgb(16,22,30); private val BUTTON=Color.rgb(38,53,70); private val MUTED=Color.rgb(200,210,220)
  private val CHIP=Color.rgb(24,48,72)

  fun render(column: LinearLayout,s: ProjectScreen,action: (String) -> Unit) {
    val ctx=column.context;val dp=ctx.resources.displayMetrics.density
    fun px(v: Int)=(v*dp).roundToInt()
    column.removeAllViews()
    column.orientation=LinearLayout.VERTICAL;column.setBackgroundColor(BG);column.setPadding(px(32),px(32),px(32),px(32))
    column.tag="project-panel-${s.key}"
    fun gap(v: Int)=LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=px(v) }
    fun text(value: String,size: Float,color: Int,tag: String)=TextView(ctx).apply { text=value;textSize=size;setTextColor(color);this.tag=tag }
    column.addView(text(s.title,40f,Color.WHITE,"project-title").apply {
      if(s.key.endsWith("chip")) { setBackgroundColor(CHIP);setPadding(px(16),px(16),px(16),px(16)) } },gap(24))
    // On the chip the title has an inner pad; the lines take the same left inset so their text stays on one edge.
    s.lines.forEachIndexed { i,l -> column.addView(text(l,28f,MUTED,"project-line-$i").apply {
      if(s.key.endsWith("chip")) setPadding(px(16),0,px(16),0) },gap(16)) }
    s.buttons.forEach { (key,label) -> column.addView(Button(ctx).apply {
      text=label;textSize=32f;isAllCaps=false;minHeight=px(100);minimumHeight=px(100);setTextColor(Color.WHITE)
      backgroundTintList=ColorStateList.valueOf(BUTTON);setPadding(px(16),px(8),px(16),px(8));tag="project-$key";setOnClickListener { action(key) }
    },gap(16)) }
  }
}
