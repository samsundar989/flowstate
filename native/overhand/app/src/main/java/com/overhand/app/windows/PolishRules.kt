package com.overhand.app.windows

/** Display-only rules. Wire values, recorded data and answer payloads stay intact. */
object PolishRules {
  const val CAPTION=22f
  const val BODY=28f
  const val CONTROL=32f
  const val TITLE=36f
  const val TARGET_DP=128
  const val GAP_DP=32
  /** The near panels' gap (decision card, graph; #911): each control keeps half of it on every side, 8 dp, on the grid. */
  const val NEAR_GAP_DP=16
  /** The one label inset (#816): every text line and control starts 16 dp in, 8 dp above and below (8 dp grid). */
  /** The six windows' targets: 3 degrees at 1.6 m is 89 dp (1280 dp on 1.2 m), on the grid 96; gaps are the 16 dp
   *  HIT_GAP token (15 mm there, over the 12 mm floor). Near panels keep [TARGET_DP] and [GAP_DP]. */
  const val WINDOW_TARGET_DP=96
  const val WINDOW_GAP_DP=16
  const val INSET_DP=16
  const val INSET_V_DP=8
  /** #911: the label inset in px at the view's density (16 and 8 dp), so text boxes stay on the 8 dp grid. */
  fun labelInset(view: android.view.View) {
    val d=view.resources.displayMetrics.density;val h=Math.round(INSET_DP*d);val v=Math.round(INSET_V_DP*d)
    view.setPadding(h,v,h,v)
  }
  fun status(value: String)=com.overhand.app.FirstMinutes.statusWord(value)
  /** Only status prefixes are translated; quoted prose and wire data remain verbatim. */
  fun rowTitle(value: String)=Regex("(#\\d+ )success:",RegexOption.IGNORE_CASE).replace(value,"$1done:")
  fun graphLabel(name: String,wire: String)=status(wire).padEnd(9)+name
  fun size(original: Float)=NEAR.size(original)
  /**
   * The one type scale (#816): every view's size snaps to caption, body or title; controls are [control].
   * [NEAR] is the near panels' (decision card, graph, palm menu) original ramp. [at] derives a panel's scale from
   * the Flowstate degree tokens, so each step clears the 1 degree floor at that panel's distance.
   */
  class TypeScale(val caption: Float,val body: Float,val title: Float,val control: Float) {
    fun size(original: Float)=when {
      original<=24f -> caption
      original<34f -> body
      else -> title
    }
  }
  val NEAR=TypeScale(CAPTION,BODY,TITLE,CONTROL)
  fun at(g: com.overhand.app.design.FlowstateTokens.PanelGeometry): TypeScale {
    fun sp(deg: Float)=kotlin.math.ceil(g.dpForDegrees(deg))
    val body=sp(com.overhand.app.design.FlowstateTokens.TYPE_BODY_DEG)
    return TypeScale(sp(com.overhand.app.design.FlowstateTokens.TYPE_CAPTION_DEG),body,
      sp(com.overhand.app.design.FlowstateTokens.TYPE_HEADING_DEG),body)
  }
  /** The five windows, from their real placement (#861: 1280 dp on 0.84 m, farthest at 1.12 m): caption 30, body and controls 36, title 43. */
  val WINDOW by lazy { floor(com.overhand.app.design.FlowstateTokens.WINDOW) }
  /**
   * #861 one angle: caption is the smallest sp whose em is at least 1.0 deg (the lint's floor) at [g]'s distance (the
   * farthest default window) and whose x-height is at least [MIN_XHEIGHT_DEG] after one push-away step; body and title
   * keep the token steps above it (x1.19, x1.43).
   */
  fun floor(g: com.overhand.app.design.FlowstateTokens.PanelGeometry): TypeScale {
    val d=g.distanceM.toDouble()
    fun size(deg: Double,at: Double)=2*at*kotlin.math.tan(Math.toRadians(deg/2))
    val em=maxOf(size(1.0,d),size(MIN_XHEIGHT_DEG,pushed(d))/X_HEIGHT_EM)
    val caption=kotlin.math.ceil(em*g.dpPerMetre).toFloat()
    val t=com.overhand.app.design.FlowstateTokens
    val body=kotlin.math.ceil(caption*t.TYPE_BODY_DEG/t.TYPE_CAPTION_DEG)
    return TypeScale(caption,body,kotlin.math.ceil(caption*t.TYPE_HEADING_DEG/t.TYPE_CAPTION_DEG),body)
  }
  /** x-height over em of the panels' font (Roboto 1082/2048; the capture measures 0.538). */
  const val X_HEIGHT_EM=0.528
  /** Settings, from its real placement (#861: 853 dp on 0.6 m at 1.12 m): caption and controls 28, body 34, title 40. */
  val SETTINGS by lazy { floor(com.overhand.app.design.FlowstateTokens.SETTINGS).let { TypeScale(it.caption,it.body,it.title,it.caption) } }
  /** Settings' width in dp: square dp on its 0.6 x 0.5625 m quad (853 x 800), like the windows (docs/design/windows.md). */
  const val SETTINGS_W_DP=853f
  /** Settings' targets: 3 degrees at its real geometry, up to the 8 dp grid (83.4 dp -> 88 since #799). */
  val SETTINGS_TARGET_DP by lazy { targetDp(com.overhand.app.design.FlowstateTokens.SETTINGS) }
  /** One push-away step (#861): GrabReplay's 22 cm hand stroke, outward, at PanelGrab's depth gain r0/ARM_M (1..3).
   *  The farthest default window (1.12 m) lands at 1.61 m. */
  const val PUSH_STROKE_M=0.22
  fun pushed(d: Double)=d+(d/com.overhand.app.features.PanelGrab.ARM_M).coerceIn(1.0,3.0)*PUSH_STROKE_M
  /** Legibility minimum (#861): x-height of at least 0.35 deg, Microsoft Mixed Reality "Text in Unity" (minimum
   *  legible 0.35-0.4 deg; comfortable 0.6-0.75 deg), applied to the x-height (stricter than to the font height),
   *  held after one push-away step from the farthest default window. */
  const val MIN_XHEIGHT_DEG=0.35
  /** A 3 degree target on [g], rounded up to the 8 dp grid. */
  fun targetDp(g: com.overhand.app.design.FlowstateTokens.PanelGeometry)=(kotlin.math.ceil(g.dpForDegrees(3f)/8f)*8f).toInt()
  /** Prefer complete blank-line-separated rows. Oversized rows still use whole lines. */
  fun rowPages(text: String,lineEnds: List<Int>,lineBottoms: List<Int>,height: Int): List<IntRange> {
    require(lineEnds.size==lineBottoms.size)
    val out=mutableListOf<IntRange>();var start=0;var line=0
    while(line<lineEnds.size) {
      val top=if(line==0) 0 else lineBottoms[line-1]
      var end=line
      while(end+1<lineEnds.size && lineBottoms[end+1]-top<=height) end++
      val limit=lineEnds[end]
      // #904: from limit-1, so a row whose last line is the last that fits (its "\n" then the blank line's) stays here.
      val split=text.lastIndexOf("\n\n",(limit-1).coerceAtLeast(0))
      val boundary=if(split<0) -1 else split+2
      // #861: a row taller than a whole page is cut across pages anyway, so do not leave this page half empty for it.
      val oversized=boundary>start && run {
        val next=text.indexOf("\n\n",boundary).let { if(it<0) text.length else it+2 }
        val first=lineEnds.indexOfFirst { it>boundary }
        val last=lineEnds.indexOfFirst { it>=next }
        first>=0 && last>=0 && lineBottoms[last]-(if(first==0) 0 else lineBottoms[first-1])>height
      }
      // #880: nor end it on a row boundary that leaves the page less than half full (blueprint's page 1 was its header
      // alone, under a component block a little taller than the rest of the page): cut at a whole line instead.
      val sparse=boundary>start && lineEnds.indexOfFirst { it>=boundary }.let { b -> b>=0 && (lineBottoms[b]-top)*2<height }
      val stop=if(limit<text.length && boundary>start && !oversized && !sparse) boundary else limit
      out.add(start until stop);start=stop
      line=lineEnds.indexOfFirst { it>start }.let { if(it<0) lineEnds.size else it }
    }
    return out.ifEmpty { listOf(0 until 0) }
  }
  /** Whole-line page boundaries, including the final newline; never discard characters. */
  fun pages(lineEnds: List<Int>,lineBottoms: List<Int>,height: Int): List<IntRange> {
    require(lineEnds.size==lineBottoms.size)
    val out=mutableListOf<IntRange>();var start=0;var top=0
    for(i in lineEnds.indices) {
      if(lineBottoms[i]-top>height && i>0 && lineEnds[i-1]>start) {
        out.add(start until lineEnds[i-1]);start=lineEnds[i-1];top=lineBottoms[i-1]
      }
    }
    if(lineEnds.isNotEmpty() && lineEnds.last()>start) out.add(start until lineEnds.last())
    return out.ifEmpty { listOf(0 until 0) }
  }
}

/** Tracking loss never counts as a release. One accepted activation per 500 ms across hands. */
class RepeatInputGate(private val cooldown: Long=500) {
  private val held=mutableSetOf<String>()
  private var last: Long?=null
  fun update(hand: String,down: Boolean,tracked: Boolean,now: Long): Boolean {
    if(!tracked) { held.add(hand);return false }
    if(!down) { held.remove(hand);return false }
    if(!held.add(hand)) return false
    val previous=last
    if(previous!=null && now-previous<cooldown) return false
    last=now;return true
  }
}
