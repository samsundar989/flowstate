package com.overhand.app.windows

import android.graphics.Color
import android.graphics.Typeface
import com.overhand.app.R
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import kotlin.math.roundToInt

/** Android views receive ISDK's normal hover/press events. Keep SDK touch limiting and cursor. */
object PanelPolish {
  private val ui by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
  private val lifetimes=java.util.WeakHashMap<View,Int>()
  /** Animate the whole SDK surface, including its backplate, rather than only page text. */
  fun lifetime(root: View,show: Boolean,initial: Boolean=false,completed: () -> Unit={}) {
    val generation=(lifetimes[root] ?: 0)+1;lifetimes[root]=generation
    var finished=false
    fun finish() {
      if(finished || lifetimes[root]!=generation) return
      finished=true;root.alpha=if(show) 1f else 0f
      root.scaleX=if(show) 1f else 0.975f;root.scaleY=root.scaleX
      completed()
    }
    root.animate().cancel()
    if(initial) { root.alpha=0f;root.scaleX=0.975f;root.scaleY=0.975f }
    root.animate().alpha(if(show) 1f else 0f).scaleX(if(show) 1f else 0.975f)
      .scaleY(if(show) 1f else 0.975f).setDuration(PanelTransition.DURATION)
      .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator()).withEndAction { finish() }.start()
    ui.postDelayed({
      if(!finished && lifetimes[root]==generation) { root.animate().cancel();finish() }
    },PanelTransition.DURATION+100)
  }
  private val styled=java.util.WeakHashMap<View,Boolean>()
  private val graphGates=java.util.WeakHashMap<Any,RepeatInputGate>()
  fun graphEdge(owner: Any,left: Boolean,down: Boolean,tracked: Boolean,now: Long)=
    graphGates.getOrPut(owner) { RepeatInputGate() }.update(left.toString(),down,tracked,now)
  fun install(root: View,targetDp: Int=PolishRules.TARGET_DP,gapDp: Int=PolishRules.GAP_DP,type: PolishRules.TypeScale=PolishRules.NEAR) {
    root.viewTreeObserver.addOnGlobalLayoutListener { apply(root,targetDp,gapDp,type) }
    apply(root,targetDp,gapDp,type)
  }
  fun apply(view: View,targetDp: Int=PolishRules.TARGET_DP,gapDp: Int=PolishRules.GAP_DP,type: PolishRules.TypeScale=PolishRules.NEAR)=
    walk(view,view)
  /**
   * look_nov17 s2 (#1372, lane K2): every control becomes its kit kind. Button: text button; the one Button tagged
   * [PRIMARY] (or with R.id.fs_kind preset to "primary"): filled button; CheckBox, Switch, ToggleButton: the switch;
   * ImageButton: icon button; Settings tabs: tab. Type: Regular 30 dp medium, Large 40 dp semibold, code mono 26;
   * sentence case; targets at least 64 dp, controls 72 dp, 16 dp gap. The older per-panel targets and type ramps
   * (TypeScale, 96/128 dp targets, hover scale) are replaced where they disagree with the spec.
   */
  private fun walk(view: View,root: View) {
    if(view.tag==com.overhand.app.decisions.QuestionCard.TAG_CARD) return // Card owns its spec colours, type and targets.
    if(styled.put(view,true)==null) kit(view,root)
    // A wrapping Settings page can constrain its last row below the View's suggested minimum.
    // Enforce only a measured undershoot; let all larger/multiline controls keep their natural size.
    if(view.isClickable && view.height>0) {
      val d=view.resources.displayMetrics.density
      val h=((if(kind(view)==Kind.SWITCH || kind(view)==null) TARGET_MIN_DP else CONTROL_DP)*d).roundToInt()
      val w=(TARGET_MIN_DP*d).roundToInt()
      view.layoutParams?.let { p ->
        var changed=false
        if(view.height<h && p.height!=h) { p.height=h;changed=true }
        if(view.width in 1 until w && p.width!=w) { p.width=w;changed=true }
        if(changed) view.layoutParams=p
      }
    }
    if(view is ViewGroup) for(i in 0 until view.childCount) walk(view.getChildAt(i),root)
  }
  const val PRIMARY="fs_primary"
  /** Kit kinds, recorded on each control under R.id.fs_kind (KitPolishTest reads them). */
  enum class Kind { TEXT,FILLED,SWITCH,ICON,TAB }
  private val primaries=java.util.WeakHashMap<View,View>()
  fun kind(v: View)=v.getTag(R.id.fs_kind) as? Kind
  private fun kit(view: View,root: View) {
    val res=view.resources;val density=res.displayMetrics.density
    fun px(dp: Int)=(dp*density).roundToInt()
    if(view is TextView) {
      val sp=view.textSize/res.displayMetrics.scaledDensity
      val mono=view.typeface?.let { it==Typeface.MONOSPACE || it.systemFontFamilyName=="monospace" } ?: false
      val large=view !is Button && view !is EditText && sp>=TITLE_FROM_SP
      // look_nov17 s2 Type: Large 40 semibold (titles, numbers), Regular 30 medium (labels, captions), code mono 26.
      view.textSize=when { mono -> maxOf(sp,CODE_SP);large -> LARGE_SP;else -> REGULAR_SP }
      if(!mono) view.typeface=Typeface.create(Typeface.create("sans-serif",Typeface.NORMAL),if(large) 600 else 500,false)
      view.isAllCaps=false
      if(view !is Button) view.setTextColor(res.getColor(R.color.fs_text,null))
      view.includeFontPadding=true
      if(view !is EditText) view.breakStrategy=android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE
    }
    if(Theme.ENABLED && view is android.webkit.WebView) view.setBackgroundColor(Color.TRANSPARENT)
    if(Theme.ENABLED && view is EditText) view.background=Theme.control(density)
    val kind=when {
      view is CompoundButton -> Kind.SWITCH
      view is Button && (view.getTag(R.id.fs_kind)=="primary" || view.tag==PRIMARY) ->
        if(primaries.putIfAbsent(root,view)==null) Kind.FILLED
        else { android.util.Log.w("Overhand","polish event=extra_primary panel=${root.javaClass.simpleName} text=${view.text}");Kind.TEXT }
      view is Button && (view.tag as? String)?.startsWith("settings-tab-")==true -> Kind.TAB
      view is Button -> Kind.TEXT
      view is ImageButton -> Kind.ICON
      else -> null
    }
    if(kind!=null) {
      view.setTag(R.id.fs_kind,kind)
      view.backgroundTintList=null
      when(kind) {
        Kind.SWITCH -> {
          val b=view as CompoundButton
          b.background=null
          if(b is Switch) { b.thumbDrawable=res.getDrawable(R.drawable.fs_switch_thumb,null);b.trackDrawable=res.getDrawable(R.drawable.fs_switch_track,null);b.thumbTintList=null;b.trackTintList=null }
          else {
            // The label first, the switch at the row's end (Dock.dc.html .row: label plus switch).
            b.buttonDrawable=null;b.buttonTintList=null
            if(b is ToggleButton) { b.textOn=b.text;b.textOff=b.text }
            b.setCompoundDrawablesRelativeWithIntrinsicBounds(null,null,res.getDrawable(R.drawable.fs_switch,null),null)
            b.compoundDrawablePadding=px(GAP_DP)
            b.gravity=android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
          }
          b.setTextColor(res.getColor(R.color.fs_text,null))
          b.setPadding(px(INSET_H_DP),0,px(INSET_H_DP),0)
          b.minimumHeight=px(TARGET_MIN_DP);b.minimumWidth=px(TARGET_MIN_DP)
        }
        Kind.ICON -> {
          view.background=res.getDrawable(R.drawable.fs_icon_button,null)
          (view as ImageButton).imageTintList=res.getColorStateList(R.color.fs_icon_tint,null)
          view.minimumHeight=px(CONTROL_DP);view.minimumWidth=px(CONTROL_DP)
        }
        else -> {
          val b=view as Button
          b.background=res.getDrawable(when(kind) { Kind.FILLED -> R.drawable.fs_filled_button;Kind.TAB -> R.drawable.fs_tab;else -> R.drawable.fs_text_button },null)
          b.setTextColor(res.getColorStateList(if(kind==Kind.FILLED) R.color.fs_text_on_filled else R.color.fs_text_on_button,null))
          if(kind==Kind.FILLED) b.typeface=Typeface.create(Typeface.create("sans-serif",Typeface.NORMAL),600,false)
          b.stateListAnimator=null
          b.setPadding(px(INSET_H_DP),px(INSET_V_DP),px(INSET_H_DP),px(INSET_V_DP))
          b.minimumHeight=px(CONTROL_DP);b.minimumWidth=px(CONTROL_DP)
        }
      }
      (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
        val gap=px(GAP_DP)/2
        it.setMargins(gap,gap,gap,gap)
        if(it.height>0) it.height=maxOf(it.height,view.minimumHeight)
        view.layoutParams=it
      }
      if(view is Button) {
        val gate=RepeatInputGate();var suppress=false
        view.setOnTouchListener { _,event ->
          when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> suppress=!gate.update("touch",true,true,event.eventTime)
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> gate.update("touch",false,true,event.eventTime)
          }
          suppress
        }
      }
    } else if(view is EditText) {
      view.setPadding(px(PolishRules.INSET_DP),px(PolishRules.INSET_V_DP),px(PolishRules.INSET_DP),px(PolishRules.INSET_V_DP))
      view.minimumHeight=px(CONTROL_DP)
      (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { it.setMargins(0,px(GAP_DP)/2,0,px(GAP_DP)/2);view.layoutParams=it }
    } else if(view.isClickable && view !is ViewGroup) {
      view.minimumHeight=maxOf(view.minimumHeight,px(TARGET_MIN_DP));view.minimumWidth=maxOf(view.minimumWidth,px(TARGET_MIN_DP))
    }
  }
  /** look_nov17 s2 Type and Controls (sizes in dp on a 1280 dp window). */
  const val REGULAR_SP=30f
  const val LARGE_SP=40f
  const val CODE_SP=26f
  /** A view whose own size was at least this was a title or number before the kit (the old title step, 34+). */
  const val TITLE_FROM_SP=34f
  const val CONTROL_DP=72
  const val TARGET_MIN_DP=64
  const val GAP_DP=16
  /** Question.dc.html .opt: padding 0 20px. */
  const val INSET_H_DP=20
  const val INSET_V_DP=8
}

/** Reversible lifetime curve. ECS keeps a closing surface alive until its fade is complete. */
class PanelTransition {
  @Volatile var showing=false; private set
  private var start=0L
  private var from=0f
  fun alpha(now: Long): Float {
    val t=((now-start).toFloat()/DURATION).coerceIn(0f,1f)
    val ease=(1-kotlin.math.cos(t*Math.PI)).toFloat()/2
    return from+((if(showing) 1f else 0f)-from)*ease
  }
  fun scale(now: Long)=0.975f+0.025f*alpha(now)
  fun target(show: Boolean,now: Long): Boolean {
    if(show==showing) return false
    from=alpha(now);start=now;showing=show;return true
  }
  companion object { const val DURATION=200L }
}
