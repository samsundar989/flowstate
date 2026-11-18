package com.overhand.app.windows

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * #1371 Code window, built to `docs/design/reference/flowstate_look_v1/Code.dc.html` (look_nov17 s5 Code).
 * Left: changed files, newest first (76 dp rows, green "+n"; the open one has a cyan edge on cyan 16%).
 * Right: a black 35% well; header = worker hexagon + "Writing now" only while a worker writes that file, else the
 * change's short subject; the diff in mono 26 dp: added lines on green 16% with a green plus, removed lines dimmed and
 * struck through. Red is never used. A dot after the count follows the newest change; pinching a file pins it.
 */
class CodeView(context: Context,private val name: (String)->String) {
  /* look E2: values from Code.dc.html inline CSS and look_nov17 s2 (local constants; Theme.kt is lane K1's). */
  companion object {
    const val TEXT=0xFFF2F6FA.toInt()          // s2 Text #F2F6FA
    const val CAPTION=0xB3FFFFFF.toInt()       // s2 Caption: white at 70%
    const val CYAN=0xFF5CE1E6.toInt()          // s2 Cyan
    const val CYAN_16=0x295CE1E6               // s2 selected fill: cyan at 16% (.16*255=41=0x29)
    const val GREEN=0xFF45D99A.toInt()         // s2 Green
    const val GREEN_16=0x2945D99A              // Code.dc.html .a background rgba(69,217,154,.16)
    const val WELL=0x59000000                  // s2 Code well: black at 35% (.35*255=89=0x59)
    const val WELL_EDGE=0x1FFFFFFF             // Code.dc.html well border rgba(255,255,255,.12)
    const val STRIKE=0x73FFFFFF                // Code.dc.html .r text-decoration-color rgba(255,255,255,.45)
    const val ON_CYAN=0xFF06222A.toInt()       // s2 text on cyan
    const val ROW_DP=76f; const val LIST_DP=380f; const val GAP_DP=28f; const val LINE_DP=44f
    const val NAME_SP=30f; const val COUNT_SP=24f; const val MONO_SP=26f
    const val TAG_ROW="code-file"; const val TAG_LINE="code-line"; const val TAG_LIVE="code-live"; const val TAG_HEADER="code-header"
    const val EMPTY="No changes yet"
    const val LOADING="Loading changes"
    /** #1385: at most this many line views for the open file, then one "N more lines" row. */
    const val MAX_LINES=200
  }
  private val d=context.resources.displayMetrics.density
  private fun dp(v: Float)=(v*d).toInt()
  private val medium=Typeface.create(Typeface.DEFAULT,500,false)
  private val semibold=Typeface.create(Typeface.DEFAULT,600,false)
  private val mono=Typeface.create(Typeface.MONOSPACE,500,false)
  private fun text(c: Context,value: String,sp: Float,color: Int,face: Typeface)=TextView(c).apply {
    text=value;setTextSize(TypedValue.COMPLEX_UNIT_DIP,sp);setTextColor(color);typeface=face;includeFontPadding=false }
  private fun round(fill: Int,edge: Int,r: Float)=GradientDrawable().apply { setColor(fill);cornerRadius=r*d;setStroke(dp(2f),edge) }

  val view=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;tag="code-view" }
  private val caption=text(context,"",NAME_SP,CAPTION,medium)
  private val live=View(context).apply { tag=TAG_LIVE;contentDescription="Follow newest" }
  private val list=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
  private val well=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL
    background=round(WELL,WELL_EDGE,20f);setPadding(dp(8f),dp(22f),dp(8f),dp(22f)) }
  private val header=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL;tag=TAG_HEADER;setPadding(dp(18f),0,dp(18f),dp(14f)) }
  private val lines=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
  private val empty=text(context,EMPTY,NAME_SP,CAPTION,medium).apply { gravity=Gravity.CENTER }
  private val body=LinearLayout(context)

  var project="";private set
  var rows: List<CodeDiffs.Row> = emptyList();private set
  var following=true;private set
  var selected: String?=null;private set
  /** The path a worker is writing now (live mode only); recorded commits are finished work. */
  var writing: String?=null

  init {
    val top=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
    top.addView(caption,LinearLayout.LayoutParams(0,-2,1f))
    top.addView(live,LinearLayout.LayoutParams(dp(64f),dp(64f)))  // s2 Target: 64 dp minimum
    live.setOnClickListener { follow() }
    view.addView(top,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12f) })
    val listScroll=ScrollView(context).apply { isVerticalScrollBarEnabled=false;addView(list) }
    body.addView(listScroll,LinearLayout.LayoutParams(dp(LIST_DP),-1).apply { rightMargin=dp(GAP_DP) })
    // Keep the 26 dp mono lines intact; long lines remain reachable sideways.
    val horizontal=HorizontalScrollView(context).apply { isFillViewport=true;addView(lines) }
    val diffScroll=ScrollView(context).apply { isVerticalScrollBarEnabled=false;addView(horizontal) }
    well.addView(header,LinearLayout.LayoutParams(-1,-2))
    well.addView(diffScroll,LinearLayout.LayoutParams(-1,0,1f))
    body.addView(well,LinearLayout.LayoutParams(0,-1,1f))
    view.addView(body,LinearLayout.LayoutParams(-1,0,1f))
    view.addView(empty,LinearLayout.LayoutParams(-1,0,1f))
  }

  /** Show [all] for [project] (newest file first), filtered to [module] when given. Rows are already gated. */
  fun show(project: String,all: List<CodeDiffs.Row>,module: String?=null) {
    this.project=project
    rows=CodeDiffs.files(all,module)
    if(following || rows.none { it.path==selected }) selected=rows.firstOrNull()?.path
    render()
  }
  /** #1385: before the recording's changes are loaded (off the main thread): the caption and one line. */
  fun loading() { loadingShown=true;drawn=null
    body.visibility=View.GONE;live.visibility=View.GONE;empty.visibility=View.VISIBLE;empty.text=LOADING;caption.text="" }
  private var loadingShown=false
  fun pin(path: String) { if(rows.none { it.path==path }) return;following=false;selected=path;render()
    Log.i("Overhand","code event=pin target=$path") }
  fun follow() { following=true;selected=rows.firstOrNull()?.path;render();Log.i("Overhand","code event=follow target=${selected.orEmpty()}") }

  /** What the last render drew: rows by identity (they come from one immutable cache), selection and writer. */
  private var drawn: List<Any?>?=null
  private var listed: List<CodeDiffs.Row> = emptyList()
  private val generations=mutableMapOf<LinearLayout,Int>()
  var pendingSlices=0;private set
  /** Build at most 8 ms per frame; stale selections cannot append to their replacements. */
  private fun sliced(parent: LinearLayout,count: Int,add: (Int)->Unit) {
    val generation=(generations[parent] ?: 0)+1;generations[parent]=generation
    if(!view.isAttachedToWindow) { repeat(count,add);return }
    var i=0
    fun slice() {
      if(generations[parent]!=generation) { pendingSlices--;return }
      val start=System.nanoTime()
      do { add(i++) } while(i<count && System.nanoTime()-start<8_000_000)
      if(i<count) parent.postOnAnimation { slice() } else pendingSlices--
    }
    if(count>0) { pendingSlices++;slice() }
  }
  private var opened: Pair<CodeDiffs.Row,Boolean>?=null
  private fun same(a: List<CodeDiffs.Row>,b: List<CodeDiffs.Row>)=a.size==b.size && a.indices.all { a[it]===b[it] }
  /** Renders so far (tests read it); each logs `code event=render ms=<n> views=<n>`. */
  var renders=0;private set

  private fun render() {
    val key=listOf(project,selected,following,writing)
    if(!loadingShown && drawn==key && same(listed,rows)) return  // nothing it draws has changed
    val t0=System.nanoTime();val before=built
    loadingShown=false;drawn=key
    val has=rows.isNotEmpty()
    body.visibility=if(has) View.VISIBLE else View.GONE
    empty.visibility=if(has) View.GONE else View.VISIBLE
    empty.text=EMPTY
    caption.text=if(has) name("$project · ${rows.size} ${if(rows.size==1) "file" else "files"} changed") else ""  // empty state: "No changes yet" and no other words
    live.visibility=if(has) View.VISIBLE else View.GONE
    live.background=Dot(following)
    if(same(listed,rows)) (0 until list.childCount).forEach { i -> mark(list.getChildAt(i),rows[i].path==selected) }
    else { list.removeAllViews();listed=rows
      sliced(list,rows.size) { i -> list.addView(fileRow(rows[i]),LinearLayout.LayoutParams(-1,dp(ROW_DP)).apply { bottomMargin=dp(8f) }) } }
    val open=rows.firstOrNull { it.path==selected }
    val isWriting=open!=null && writing==open.path && open.worker!=null
    if(open==null) { generations[lines]=(generations[lines] ?: 0)+1;lines.removeAllViews();header.removeAllViews();opened=null }
    else if(opened?.first!==open || opened?.second!=isWriting) {
      opened=open to isWriting
      lines.removeAllViews();header.removeAllViews()
      if(isWriting) header.addView(Hexagon(view.context,open.worker!!),LinearLayout.LayoutParams(dp(40f),dp(44f)).apply { rightMargin=dp(14f) })
      header.addView(text(view.context,CodeDiffs.header(open,isWriting),NAME_SP,TEXT,medium).apply {
        maxLines=1;ellipsize=TextUtils.TruncateAt.END })
      val count=minOf(open.lines.size,MAX_LINES)
      sliced(lines,count+if(open.lines.size>MAX_LINES) 1 else 0) { i ->
        lines.addView(diffLine(if(i<count) open.lines[i] else CodeDiffs.Line(CodeDiffs.Kind.HUNK,"${open.lines.size-MAX_LINES} more lines")),
          LinearLayout.LayoutParams(-1,dp(LINE_DP)))
      }
    }
    renders++
    Log.i("Overhand","code event=render ms=${(System.nanoTime()-t0)/1_000_000} views=${built-before}")
  }
  private fun mark(row: View,on: Boolean) { row.background=round(if(on) CYAN_16 else Color.TRANSPARENT,if(on) CYAN else Color.TRANSPARENT,20f) }

  /** #1385: file and line views built so far (the open-path budget test reads it). */
  var built=0;private set
  private fun fileRow(r: CodeDiffs.Row)=LinearLayout(view.context).apply {
    built++
    tag=TAG_ROW;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(22f),0,dp(22f),0);isClickable=true
    mark(this,r.path==selected)
    contentDescription=r.path
    addView(text(context,r.name,NAME_SP,TEXT,medium).apply { maxLines=1;ellipsize=TextUtils.TruncateAt.MIDDLE },
      LinearLayout.LayoutParams(0,-2,1f).apply { rightMargin=dp(12f) })
    addView(text(context,"+${r.added}",COUNT_SP,GREEN,mono))
    if(r.removed>0) addView(text(context," -${r.removed}",COUNT_SP,CAPTION,mono))
    // Mixed mono/name fonts have different ascents; preserve the centred row and share its text baseline.
    addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ ->
      val first=getChildAt(0) as TextView
      for(i in 1 until childCount) (getChildAt(i) as TextView).let { it.offsetTopAndBottom(first.top+first.baseline-it.top-it.baseline) }
    }
    setOnClickListener { pin(r.path) }
  }

  /** One diff line: the three treatments of Code.dc.html (.l, .l.a, .r). Hunk headers read as caption context. */
  internal fun diffLine(l: CodeDiffs.Line)=LinearLayout(view.context).apply {
    built++
    tag=TAG_LINE;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(18f),0,dp(18f),0)
    val glyph=text(context,when(l.kind) { CodeDiffs.Kind.ADD -> "+";CodeDiffs.Kind.DEL -> "-";else -> " " },MONO_SP,
      if(l.kind==CodeDiffs.Kind.ADD) GREEN else CAPTION,mono)
    val body=text(context,l.text,MONO_SP,if(l.kind==CodeDiffs.Kind.ADD || l.kind==CodeDiffs.Kind.CTX) TEXT else CAPTION,mono).apply {
      maxLines=1;ellipsize=null;setHorizontallyScrolling(true)
      if(l.kind==CodeDiffs.Kind.DEL) paintFlags=paintFlags or Paint.STRIKE_THRU_TEXT_FLAG }
    if(l.kind==CodeDiffs.Kind.ADD) setBackgroundColor(GREEN_16)
    addView(glyph,LinearLayout.LayoutParams(dp(18f),-2).apply { rightMargin=dp(16f) })
    addView(body,LinearLayout.LayoutParams(0,-2,1f))
  }

  /** The follow dot: solid cyan while following the newest change, a cyan ring when a file is pinned. */
  private inner class Dot(val on: Boolean): android.graphics.drawable.Drawable() {
    private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=CYAN;strokeWidth=2f*d }
    override fun draw(c: Canvas) { val r=10f*d;p.style=if(on) Paint.Style.FILL else Paint.Style.STROKE
      c.drawCircle(bounds.exactCenterX(),bounds.exactCenterY(),r,p) }
    override fun setAlpha(a: Int) {}; override fun setColorFilter(f: android.graphics.ColorFilter?) {}
    @Deprecated("") override fun getOpacity()=android.graphics.PixelFormat.TRANSLUCENT
  }
  /** s2 Worker: a cyan hexagon holding its slot number in dark text (Code.dc.html: 40 x 44, 600 20px). */
  private class Hexagon(c: Context,val slot: Int): View(c) {
    private val fill=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=CYAN }
    private val ink=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=ON_CYAN;textAlign=Paint.Align.CENTER
      typeface=Typeface.create(Typeface.DEFAULT,600,false);textSize=20f*c.resources.displayMetrics.density }
    override fun onDraw(c: Canvas) { val w=width.toFloat();val h=height.toFloat()
      c.drawPath(Path().apply { moveTo(w/2,0f);lineTo(w,h*.25f);lineTo(w,h*.75f);lineTo(w/2,h);lineTo(0f,h*.75f);lineTo(0f,h*.25f);close() },fill)
      c.drawText("$slot",w/2,h/2-(ink.ascent()+ink.descent())/2,ink) }
  }
}
