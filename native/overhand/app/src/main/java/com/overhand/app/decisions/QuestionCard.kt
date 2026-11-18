package com.overhand.app.decisions

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.overhand.app.R
import com.overhand.app.windows.PanelPolish

/** #1373 (look_nov17 s5, Question.dc.html): the question card's three states. */
enum class CardState { ASK, PICKED, RECEIPT }

/**
 * One card, three states. DecisionCards keeps every rule (select, confirm, thumb hold, receipt, next); this only
 * names the state and draws it. Values are look_nov17 s2 and the inline CSS of Question.dc.html; the drawables are
 * local (`fsCard*`) until K2's `fs_*` drawables land on main, and are named so they can be swapped one for one.
 */
object QuestionCard {
  fun state(selected: Int,options: Int,receipt: Boolean)=when {
    receipt -> CardState.RECEIPT
    selected in 0 until options -> CardState.PICKED
    else -> CardState.ASK
  }
  /** "the dim Confirm teaches pick first" (s3): not pressable in Ask. */
  fun confirmEnabled(s: CardState)=s==CardState.PICKED
  fun stateLine(from: CardState?,to: String,why: String)="card event=state from=${from?.name?.lowercase() ?: "none"} to=$to why=$why"
  /** Why a receipt came: the Confirm press posts as "pinch", the thumb hold as its pose. */
  fun whyOf(input: String)=if(input=="pinch") "confirm" else "thumb"

  /**
   * The close fix (q2 wear log L729-732). A panel stays visible while it fades out; for the card that fade
   * re-showed it 1 ms after Close hid it. The card hides at once and stays hidden until it is shown again.
   */
  fun entityVisible(show: Boolean,fadedOut: Boolean,card: Boolean)=show || (!card && !fadedOut)

  // The card's own words (s3: under 25 words). The question and options are the recorded day's.
  const val CONFIRM="Confirm"
  const val MORE="More"
  const val RECEIVED="Answer received"
  const val YOU_SAID="You said"
  const val SAVED_AS="Saved as"
  const val NEXT="Next"
  const val PRACTICE="Practice, on this headset"
  val OWN_WORDS=listOf(CONFIRM,MORE,RECEIVED,YOU_SAID,SAVED_AS,NEXT,PRACTICE)

  // look_nov17 s2 tokens, local until Theme.kt carries them.
  const val FILL=0xA60C121A.toInt()          // panel fill #0C121A at 65%
  const val EDGE=0x26FFFFFF                  // white at 15%
  const val CONTROL_EDGE=0x38FFFFFF          // white at 22% (s2 Controls)
  const val RING_EDGE=0x8CFFFFFF.toInt()     // .dot border rgba(255,255,255,.55)
  const val TEXT=0xFFF2F6FA.toInt()
  const val CAPTION=0xB3FFFFFF.toInt()       // white at 70%
  const val CYAN=0xFF5CE1E6.toInt()
  const val CYAN_16=0x295CE1E6               // cyan at 16% (selected)
  const val AMBER=0xFFFFB547.toInt()
  const val GREEN=0xFF45D99A.toInt()
  const val ON_CYAN=0xFF06222A.toInt()
  const val EDGE_DP=2f; const val RADIUS_PANEL_DP=32f; const val CONTROL_DP=72; const val GAP_DP=16
  const val LARGE_SP=40f; const val REGULAR_SP=30f; const val DIM=0.45f  // .opt Confirm opacity:.45 in Ask

  fun fsCardPanel(d: Float,s: CardState)=GradientDrawable().apply {
    setColor(FILL);com.overhand.app.windows.Theme.trackFill(this);cornerRadius=RADIUS_PANEL_DP*d
    setStroke((EDGE_DP*d).toInt().coerceAtLeast(2),if(s==CardState.RECEIPT) GREEN else AMBER)
  }
  fun fsPill(d: Float,edge: Int,fill: Int=Color.TRANSPARENT)=GradientDrawable().apply {
    setColor(fill);cornerRadius=999f*d;setStroke((EDGE_DP*d).toInt().coerceAtLeast(2),edge)
  }
  fun fsRing(d: Float,filled: Boolean)=GradientDrawable().apply {
    shape=GradientDrawable.OVAL;setColor(if(filled) CYAN else Color.TRANSPARENT)
    setStroke((EDGE_DP*d).toInt().coerceAtLeast(2),if(filled) CYAN else RING_EDGE)
  }

  class Model(val state: CardState,val project: String,val question: String,val why: String?,
    val options: List<String>,val selected: Int,val youSaid: String?,val savedAs: String?)
  class Handlers(val select: (Int) -> Unit,val confirm: () -> Unit,val more: () -> Unit,val next: () -> Unit,
    val button: (String,() -> Unit) -> Button)

  /** Draws [m] into [column] (cleared). Returns the hold ring that sits in Confirm, if any, for the thumb hold. */
  fun draw(activity: Activity,column: LinearLayout,m: Model,on: Handlers): HoldRing? {
    val d=activity.resources.displayMetrics.density
    val px={ dp: Int -> (dp*d).toInt() }
    column.removeAllViews()
    column.background=fsCardPanel(d,m.state);column.tag=TAG_CARD
    column.setPadding(px(28),px(28),px(28),px(28))
    fun text(v: String,sp: Float,color: Int,bold: Boolean=false)=TextView(activity).apply {
      text=v;textSize=sp;setTextColor(color);typeface=Typeface.create("sans-serif",if(bold) Typeface.BOLD else Typeface.NORMAL)
    }
    // Header: amber triangle and project name (Ask, Picked); green check and "Answer received" (Receipt).
    val receipt=m.state==CardState.RECEIPT
    column.addView(LinearLayout(activity).apply {
      gravity=Gravity.CENTER_VERTICAL;tag=TAG_HEADER
      addView(StatusMark(activity,receipt).apply { tag=TAG_GLYPH },LinearLayout.LayoutParams(px(30),px(30)))
      addView(text(if(receipt) RECEIVED else m.project,REGULAR_SP,if(receipt) GREEN else AMBER).apply { setPadding(px(10),0,0,0) })
    })
    val body=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;if(receipt) tag="card-saved" }
    val scroll=ScrollView(activity).apply { addView(body) }
    column.addView(scroll,LinearLayout.LayoutParams(-1,0,1f).apply { topMargin=px(14) })
    val row=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL;tag=TAG_ACTIONS }
    column.addView(row,LinearLayout.LayoutParams(-1,px(CONTROL_DP)).apply { topMargin=px(GAP_DP) })
    if(receipt) {
      fun fact(label: String,value: String?,tag: String) {
        body.addView(text(label,REGULAR_SP,CAPTION))
        body.addView(text(value.orEmpty(),LARGE_SP,TEXT,true).apply { this.tag=tag;setPadding(0,0,0,px(GAP_DP)) })
      }
      fact(YOU_SAID,m.youSaid,TAG_YOU_SAID);fact(SAVED_AS,m.savedAs,TAG_SAVED_AS)
      row.addView(on.button(NEXT,on.next).apply { styleText(this,d);tag=TAG_NEXT },LinearLayout.LayoutParams(0,-1,1f))
      return null
    }
    body.addView(text(m.question,LARGE_SP,TEXT,true).apply { tag=TAG_QUESTION })
    m.why?.takeIf { it.isNotBlank() }?.let { body.addView(text(it,REGULAR_SP,CAPTION).apply { maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END }) }
    m.options.forEachIndexed { i,label ->
      val picked=i==m.selected
      // An option pill: a ring on the left, then the label; the pick has a cyan edge, cyan 16% fill and a filled ring.
      val pill=LinearLayout(activity).apply {
        gravity=Gravity.CENTER_VERTICAL;setPadding(px(20),0,px(20),0);tag="qcard-option-pill"
        // The wrapper owns the option edge/ring; duplicate child state gives it kit hover/press feedback.
        background=activity.getDrawable(R.drawable.fs_text_button);isSelected=picked;setAddStatesFromChildren(true)
        addView(View(activity).apply { background=fsRing(d,picked);tag=if(picked) TAG_RING_ON else TAG_RING },LinearLayout.LayoutParams(px(20),px(20)))
        val b=on.button(label) { on.select(i) }.apply {
          background=null;textSize=REGULAR_SP;setTextColor(TEXT);isAllCaps=false;gravity=Gravity.CENTER_VERTICAL or Gravity.START
          tag=com.overhand.app.onboarding.HandsOnboarding.OPTION_TAG;isSelected=picked;minHeight=px(CONTROL_DP);setPadding(0,px(8),0,px(8));minimumWidth=0;stateListAnimator=null;backgroundTintList=null
          setTag(R.id.fs_kind,PanelPolish.Kind.TEXT)
        }
        addView(b,LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=px(12) })
        setOnClickListener { b.performClick() }
      }
      body.addView(pill,LinearLayout.LayoutParams(-1,-2).apply { topMargin=px(GAP_DP) })
    }
    val on2=confirmEnabled(m.state)
    val confirm=on.button(CONFIRM,on.confirm).apply {
      styleText(this,d);tag=TAG_CONFIRM;isAllCaps=false;textSize=REGULAR_SP;setTextColor(ON_CYAN);typeface=Typeface.DEFAULT_BOLD
      background=activity.getDrawable(R.drawable.fs_filled_button);setTag(R.id.fs_kind,PanelPolish.Kind.FILLED)
      isEnabled=on2;alpha=if(on2) 1f else DIM
    }
    val confirmCell=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL }
    confirmCell.addView(confirm,LinearLayout.LayoutParams(0,-1,1f))
    // Picked: Confirm shows the thumb icon, which fills as the thumbs-up hold runs (s3 teaches with the icon).
    val ring=if(on2) HoldRing(activity).also { it.tag=TAG_THUMB;confirmCell.addView(it,LinearLayout.LayoutParams(px(CONTROL_DP),px(CONTROL_DP))) } else null
    row.addView(confirmCell,LinearLayout.LayoutParams(0,-1,1f))
    row.addView(View(activity),LinearLayout.LayoutParams(px(12),1))
    row.addView(on.button(MORE,on.more).apply { styleText(this,d);tag=TAG_MORE },LinearLayout.LayoutParams(0,-1,1f))
    return ring
  }
  private fun styleText(b: Button,d: Float) { b.setPadding((16*d).toInt(),0,(16*d).toInt(),0);b.minimumHeight=0;b.minimumWidth=0;b.stateListAnimator=null;b.backgroundTintList=null; b.isAllCaps=false;b.textSize=REGULAR_SP;b.setTextColor(TEXT);b.background=b.context.getDrawable(R.drawable.fs_text_button);b.setTag(R.id.fs_kind,PanelPolish.Kind.TEXT) }

  private class StatusMark(activity: Activity,val receipt: Boolean): View(activity) {
    override fun onDraw(c: android.graphics.Canvas) {
      val p=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color=if(receipt) GREEN else AMBER;style=android.graphics.Paint.Style.STROKE;strokeWidth=2f;strokeJoin=android.graphics.Paint.Join.ROUND }
      c.save();c.scale(width/30f,height/30f)
      if(receipt) { c.drawCircle(15f,15f,12f,p);c.drawPath(android.graphics.Path().apply { moveTo(8f,15f);lineTo(13f,20f);lineTo(22f,10f) },p) }
      else c.drawPath(android.graphics.Path().apply { moveTo(15f,3f);lineTo(28f,26f);lineTo(2f,26f);close() },p)
      c.restore()
    }
  }
  const val TAG_CARD="qcard";const val TAG_HEADER="qcard-header";const val TAG_GLYPH="qcard-glyph";const val TAG_ACTIONS="qcard-actions"
  const val TAG_QUESTION="qcard-question";const val TAG_OPTION="card-option";const val TAG_RING="qcard-ring";const val TAG_RING_ON="qcard-ring-on"
  const val TAG_CONFIRM="qcard-confirm";const val TAG_THUMB="qcard-thumb";const val TAG_MORE="qcard-more";const val TAG_NEXT="qcard-next"
  const val TAG_YOU_SAID="qcard-you-said";const val TAG_SAVED_AS="qcard-saved-as"
}
