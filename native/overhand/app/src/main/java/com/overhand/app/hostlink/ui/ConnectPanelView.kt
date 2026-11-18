package com.overhand.app.hostlink.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.overhand.app.hostlink.Endpoint
import kotlin.math.roundToInt

/**
 * Draws a [ConnectScreen] into a panel column (#814). Spacing is on the 8 dp grid, text is at least 28 sp and every
 * button at least 100 dp high, so the panel passes the layout lint at its size ([W_DP] x [H_DP], [W_M] m wide, [DIST_M] m away).
 * Every view carries a tag: `connect-<key>` for buttons, `connect-host-<i>` for hosts, `connect-key-<d>` for the keypad.
 */
object ConnectPanelView {
  const val W_DP=1000f; const val H_DP=1200f; const val W_M=0.8f; const val H_M=0.96f; const val DIST_M=1.0f
  private val BG=Color.rgb(16,22,30); private val BUTTON=Color.rgb(38,53,70); private val MUTED=Color.rgb(200,210,220)

  interface Actions { fun action(key: String); fun host(ep: Endpoint); fun key(d: String); fun char(c: String) {} }

  fun render(column: LinearLayout,s: ConnectScreen,a: Actions) {
    val ctx=column.context;val dp=ctx.resources.displayMetrics.density
    fun px(v: Int)=(v*dp).roundToInt()
    column.removeAllViews()
    column.orientation=LinearLayout.VERTICAL;column.setBackgroundColor(BG);column.setPadding(px(32),px(32),px(32),px(32))
    column.tag="connect-panel-${s.key}"
    fun gap(v: Int)=LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=px(v) }
    fun text(value: String,size: Float,color: Int=Color.WHITE,tag: String)=TextView(ctx).apply {
      text=value;textSize=size;setTextColor(color);this.tag=tag }
    fun button(label: String,tag: String,click: () -> Unit)=Button(ctx).apply {
      text=label;textSize=32f;isAllCaps=false;minHeight=px(100);minimumHeight=px(100);setTextColor(Color.WHITE)
      backgroundTintList=ColorStateList.valueOf(BUTTON);setPadding(px(16),px(8),px(16),px(8));this.tag=tag;setOnClickListener { click() } }
    column.addView(text(s.title,40f,tag="connect-title"),gap(24))
    s.lines.forEachIndexed { i,l -> column.addView(text(l,28f,MUTED,"connect-line-$i"),gap(16)) }
    s.digits?.let { column.addView(text(it,96f,tag="connect-digits").apply { gravity=Gravity.CENTER;letterSpacing=0.1f },gap(32)) }
    s.hosts.forEachIndexed { i,ep -> column.addView(button(ep.name,"connect-host-$i") { a.host(ep) },gap(16)) }
    s.keypad?.let { typed ->
      column.addView(text(typed.padEnd(6,'_').chunked(3).joinToString(" ") { it.toList().joinToString(" ") },56f,tag="connect-typed")
        .apply { gravity=Gravity.CENTER },gap(16))
      listOf(listOf("1","2","3"),listOf("4","5","6"),listOf("7","8","9"),listOf("del","0","")).forEach { row ->
        val line=LinearLayout(ctx).apply { orientation=LinearLayout.HORIZONTAL }
        row.forEachIndexed { j,d ->
          val lp=LinearLayout.LayoutParams(0,px(100),1f).apply { if(j>0) marginStart=px(16) }
          if(d.isEmpty()) line.addView(android.view.View(ctx),lp)
          else line.addView(button(if(d=="del") "Delete" else d,"connect-key-$d") { a.key(d) },lp)
        }
        column.addView(line,gap(16))
      }
    }
    s.keyboard?.let { typed ->
      // Wraps rather than single-line + ellipsis: the whole address stays visible (#960 layout lint: text_clipped).
      column.addView(text(typed.ifEmpty { "_" },40f,tag="connect-address").apply { gravity=Gravity.CENTER },gap(16))
      ConnectScreens.KEYBOARD.forEach { row ->
        val line=LinearLayout(ctx).apply { orientation=LinearLayout.HORIZONTAL }
        row.forEachIndexed { j,c ->
          val lp=LinearLayout.LayoutParams(0,px(100),1f).apply { if(j>0) marginStart=px(8) }
          line.addView(button(if(c=="del") "Del" else c,"connect-char-$c") { a.char(c) }.apply { setPadding(0,0,0,0) },lp)
        }
        column.addView(line,gap(8))
      }
      // No wrap_content spacer View here: it took all remaining height and left Connect/Back 0 px high (#960 lint).
      column.addView(android.view.View(ctx),LinearLayout.LayoutParams(-1,px(8)))
    }
    s.buttons.forEach { (key,label) -> column.addView(button(label,"connect-$key") { a.action(key) },gap(16)) }
  }
}
