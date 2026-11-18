package com.overhand.app.windows

import android.content.Context
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

/**
 * In-panel single choice: one button per option, the chosen one lit (state_selected, see [Theme.control]). It never
 * opens a popup window: a panel's window context cannot host one (Spinner's dialog crashed the app on the headset,
 * Nov 13 00:12). #880: the chips sit on ONE horizontally scrolled row ([view]), so the list above keeps the height a
 * second row of chips took; [row] (tag "chip-row") holds the buttons. [perRow] = 0 asks for that row; the default keeps wrapped rows of [perRow] (graph, Settings).
 */
class ChipRow(context: Context,labels: List<String>,selected: Int,perRow: Int=3,private val onPick: (Int)->Unit) {
  val row=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;tag="chip-row" }
  val view: android.view.View
  val chips: List<Button>
  var selected=selected.coerceIn(0,(labels.size-1).coerceAtLeast(0)); private set
  init {
    chips=labels.mapIndexed { i,label -> Button(context).apply {
      text=label;contentDescription=label;tag="chip:$i"
      setOnClickListener { if(i!=this@ChipRow.selected) { select(i);onPick(i) } }
    } }
    if(perRow>0) {
      chips.chunked(perRow).forEach { group ->
        val line=LinearLayout(context)
        group.forEach { line.addView(it,LinearLayout.LayoutParams(0,-2,1f)) }
        row.addView(line,LinearLayout.LayoutParams(-1,-2))
      }
      view=row
    } else {
      val line=LinearLayout(context)
      chips.forEach { line.addView(it,LinearLayout.LayoutParams(-2,-2)) }
      row.addView(line,LinearLayout.LayoutParams(-2,-2))
      view=HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled=true;isFillViewport=true
        addView(row,android.view.ViewGroup.LayoutParams(-2,-2))
      }
    }
    select(this.selected)
  }
  /** Shows [index] as chosen without calling back (for state that changed elsewhere), scrolled into view. */
  fun select(index: Int) {
    if(index !in chips.indices) return
    selected=index;chips.forEachIndexed { j,b -> b.isSelected=j==index }
    (view as? HorizontalScrollView)?.let { s -> s.post { chips[index].let { c -> s.smoothScrollTo((c.left-s.width/2+c.width/2).coerceAtLeast(0),0) } } }
  }
}
