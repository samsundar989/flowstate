package com.overhand.app.decisions

import android.view.View
import android.view.ViewTreeObserver
import android.text.Layout
import android.text.StaticLayout
import android.widget.Button
import android.widget.TextView

/** Measures the styled, visible text area. Each page carries a contiguous source slice. */
class ReadingPages(private val body: TextView,private val title: TextView,
  private val previous: Button,private val next: Button,
  private val fields: List<Pair<String,String>>,initial: Int,private val clicked: (String)->Unit={},private val changed: (Int)->Unit): ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener, View.OnLayoutChangeListener {
  private data class Page(val field: Int,val range: IntRange)
  private var pages=emptyList<Page>()
  private var page=initial
  private var measured=""
  init {
    previous.isEnabled=false;next.isEnabled=false
    previous.setOnClickListener { if(page>0) { clicked("read_back");page--;show() } }
    next.setOnClickListener { if(page<pages.lastIndex) { clicked("read_more");page++;show() } }
  }
  private var observer: ViewTreeObserver?=null
  private var disposed=false
  fun bind() {
    body.addOnAttachStateChangeListener(this)
    body.addOnLayoutChangeListener(this)
    if(body.isAttachedToWindow) onViewAttachedToWindow(body)
  }
  override fun onViewAttachedToWindow(v: View) {
    if(disposed || observer!=null) return
    observer=body.viewTreeObserver.also { it.addOnPreDrawListener(this) }
    layout()
  }
  private fun unregister() {
    observer?.let { if(it.isAlive) it.removeOnPreDrawListener(this) };observer=null
  }
  override fun onViewDetachedFromWindow(v: View) { unregister() }
  override fun onPreDraw(): Boolean { if(!disposed && body.isAttachedToWindow) layout();return true }
  override fun onLayoutChange(v: View,l: Int,t: Int,r: Int,b: Int,ol: Int,ot: Int,or: Int,ob: Int) {
    if(!disposed && body.isAttachedToWindow) layout()
  }
  fun dispose() {
    if(disposed) return
    disposed=true;unregister()
    body.removeOnAttachStateChangeListener(this);body.removeOnLayoutChangeListener(this)
    previous.setOnClickListener(null);next.setOnClickListener(null)
  }
  internal var layoutCalls=0; private set
  fun layout() {
    layoutCalls++
    val w=body.width-body.compoundPaddingLeft-body.compoundPaddingRight
    val h=body.height-body.compoundPaddingTop-body.compoundPaddingBottom
    if(w<=0 || h<body.lineHeight) return
    val key="$w/$h/${body.textSize}/${body.typeface}"
    if(key==measured) return
    measured=key
    fun measure(text: String)=StaticLayout.Builder.obtain(text,0,text.length,body.paint,w)
      .setIncludePad(body.includeFontPadding).setLineSpacing(body.lineSpacingExtra,body.lineSpacingMultiplier)
      .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE).build()
    pages=fields.flatMapIndexed { i,field ->
      val text=field.second
      val result=mutableListOf<Page>();var start=0
      while(start<text.length) {
        val remaining=text.substring(start);val lines=measure(remaining)
        var end=0
        // Measure the actual substring again: font padding and a terminal newline change its height.
        fun fits(value: String): Boolean {
          val layout=measure(value)
          return layout.height<=h && (0 until layout.lineCount).all { layout.getLineLeft(it)>=0 && layout.getLineRight(it)<=w }
        }
        for(line in 0 until lines.lineCount) {
          val candidate=lines.getLineEnd(line)
          val value=remaining.substring(0,candidate)
          if(measure(value).height>h) break
          if(fits(value)) end=candidate
          else {
            // A terminal wrap-space can overhang even when the word fits. Carry it onto the next page;
            // it remains rendered and reconstructable instead of silently trimming source characters.
            val trimmed=value.trimEnd().length
            if(trimmed>0 && fits(value.substring(0,trimmed))) end=trimmed
          }
        }
        if(end==0) { measured="";return } // Wait for a viable, fully styled reading area.
        result.add(Page(i,start until start+end));start+=end
      }
      result.ifEmpty { listOf(Page(i,0 until 0)) }
    }
    page=page.coerceIn(0,(pages.size-1).coerceAtLeast(0));show()
  }
  private fun show() {
    val p=pages.getOrNull(page) ?: return
    title.text="${fields[p.field].first} · ${page+1}/${pages.size}"
    title.contentDescription=title.text
    body.text=fields[p.field].second.substring(p.range)
    body.contentDescription="${p.field}:${p.range.first}:${p.range.last+1}"
    previous.isEnabled=page>0;next.isEnabled=page<pages.lastIndex
    changed(page)
  }
}
