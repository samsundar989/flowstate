package com.overhand.app.onboarding

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.overhand.app.RecordedFirstCard
import com.overhand.app.hands.ThumbPose
import java.util.WeakHashMap

/** Uses the card's existing ISDK pinch-click controls and the actual classified hand frames. */
class HandsOnboarding private constructor(private val activity: Activity) {
  private val prefs=activity.getSharedPreferences("hands-onboarding-v1",0)
  private var flow: OnboardingFlow?=null
  private var mode=""
  private var root: LinearLayout?=null
  private var overlay: LinearLayout?=null
  private var target: View?=null
  private var pulse: android.animation.ObjectAnimator?=null
  private var syntheticUntil=0L
  private var replayButton: Button?=null
  /** Recorded only: called when the thumb step completes, so the same gesture saves the practice answer. */
  var onPracticeThumb: (() -> Unit)?=null
  var onRehearsalDone: (() -> Unit)?=null
  var onProbeHold: (() -> Unit)?=null
  private fun save() { prefs.edit().putBoolean(mode,true).apply() }
  private fun stopPulse() { pulse?.cancel();target?.alpha=1f;pulse=null }
  private fun sync(recorded: Boolean) {
    val key=if(recorded) "recorded" else "live"
    if(mode!=key) { mode=key;flow=OnboardingFlow(prefs.getBoolean(key,false)) }
  }
  /** True while the Recorded first card should show its single guided layout. Live is never guided here. */
  fun guiding(recorded: Boolean): Boolean { sync(recorded);return recorded && flow?.active==true }
  fun awaitingChoice()=flow?.step==OnboardingFlow.Step.PINCH
  fun rehearsing()=flow?.rehearsal==true
  fun confirmPractice(recorded: Boolean): Boolean {
    if(flow?.confirmPractice(recorded)!=true) return false
    save();dismiss();return true
  }
  fun decorate(column: LinearLayout, recorded: Boolean, compact: Boolean=false) {
    sync(recorded)
    root=column;stopPulse();overlay=null
    if(!tutorialMode || flow?.active!=true) return
    val box=LinearLayout(activity).apply {
      orientation=LinearLayout.VERTICAL;com.overhand.app.windows.PolishRules.labelInset(this)
      background=GradientDrawable().apply { setColor(Color.rgb(20,55,65));setStroke(4,Color.rgb(90,235,215));cornerRadius=16f }
    }
    val pinch=flow!!.step==OnboardingFlow.Step.PINCH
    if(recorded) {
      // One compact instruction; Skip lives in the card's action row so primary controls keep their height.
      val words=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
      words.addView(TextView(activity).apply { text=if(pinch) RecordedFirstCard.CHOOSE else RecordedFirstCard.CONFIRM;textSize=28f;setTextColor(Color.WHITE) })
      if(!compact) words.addView(TextView(activity).apply { text=if(flow!!.rehearsal) OnboardingFlow.REHEARSAL else RecordedFirstCard.PRACTICE;textSize=22f;setTextColor(Color.WHITE) })
      val row=LinearLayout(activity).apply { orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL }
      row.addView(words,LinearLayout.LayoutParams(0,-2,1f))
      row.addView(GestureGuide(activity,pinch),LinearLayout.LayoutParams(96,80))
      box.addView(row)
    } else {
      box.addView(TextView(activity).apply {
        text=if(pinch) "1 / 2 · ${OnboardingFlow.PINCH}" else "2 / 2 · ${OnboardingFlow.THUMB}"
        textSize=28f;setTextColor(Color.WHITE)
      })
      box.addView(GestureGuide(activity,pinch),LinearLayout.LayoutParams(-1,80))
      box.addView(Button(activity).apply { text="Skip";textSize=24f;setOnClickListener { skip() } })
    }
    column.addView(box,1);overlay=box
    fun buttons(v: View): List<Button> = when(v) {
      is Button -> listOf(v)
      is ViewGroup -> (0 until v.childCount).flatMap { buttons(v.getChildAt(it)) }
      else -> emptyList()
    }
    // Nothing is preselected in Recorded, so step 1 pulses the instruction rather than one option.
    target=if(pinch) (if(recorded) box else buttons(column).firstOrNull { it.isSelected } ?: box)
      else if(recorded) buttons(column).firstOrNull { it.tag==com.overhand.app.decisions.QuestionCard.TAG_CONFIRM } ?: box else box
    pulse=android.animation.ObjectAnimator.ofFloat(target,"alpha",1f,0.5f,1f).apply {
      duration=1100;repeatCount=android.animation.ValueAnimator.INFINITE;start()
    }
    box.addOnAttachStateChangeListener(object: View.OnAttachStateChangeListener {
      override fun onViewAttachedToWindow(v: View) {}
      override fun onViewDetachedFromWindow(v: View) { if(overlay===v) stopPulse() }
    })
  }
  /** The tutorial exists only on the Recorded card. In Live it is never drawn, so it must never gate input (t745). */
  private val tutorialMode get()=mode=="recorded"
  fun selected() { if(tutorialMode) flow?.select() }
  fun consume(poses: Map<String,ThumbPose>, aimed: Boolean): Boolean {
    if(SystemClock.elapsedRealtime()<syntheticUntil) return true
    return advance(poses,aimed)
  }
  private fun advance(poses: Map<String,ThumbPose>, aimed: Boolean): Boolean {
    val f=flow ?: return false
    if(!tutorialMode || !f.active) return false
    if(f.frame(poses,aimed,SystemClock.elapsedRealtime())) {
      save();dismiss()
      if(mode=="recorded") { if(f.rehearsal) onRehearsalDone?.invoke() else onPracticeThumb?.invoke() }
      else Toast.makeText(activity,"Practice confirmed. You're ready.",Toast.LENGTH_SHORT).show()
    }
    return true
  }
  fun holdProgress()=if(tutorialMode) flow?.holdProgress(SystemClock.elapsedRealtime()) ?: 0f else 0f
  fun blocksAnswer()=tutorialMode && flow?.active==true
  fun needsIntroduction(recorded: Boolean)=recorded && !prefs.getBoolean("recorded",false)
  private fun dismiss() { stopPulse();overlay?.let { root?.removeView(it) };overlay=null }
  fun skip() { flow?.skip();save();dismiss() }
  fun replay(open: () -> Unit) { flow?.replay();open() }
  fun settings(root: LinearLayout, open: () -> Unit) {
    replayButton=Button(activity).apply { text="Replay hand tutorial";textSize=40f;setOnClickListener { replay(open) } }
    root.addView(replayButton)
  }
  /** Synthetic QA only: click the same native controls; never inject a Live answer. */
  fun debug(intent: android.content.Intent) {
    if(!com.overhand.app.BuildConfig.DEBUG) return
    val command=intent.getStringExtra("onboarding_probe") ?: return
    fun buttons(v: View): List<Button> = when(v) {
      is Button -> listOf(v)
      is ViewGroup -> (0 until v.childCount).flatMap { buttons(v.getChildAt(it)) }
      else -> emptyList()
    }
    fun find(v: View, predicate: (Button) -> Boolean): Button? {
      if(v is Button && predicate(v)) return v
      if(v is ViewGroup) for(i in 0 until v.childCount) find(v.getChildAt(i),predicate)?.let { return it }
      return null
    }
    val options=root?.let { r -> buttons(r).filter { it.tag==OPTION_TAG } }.orEmpty()
    val selection=options.firstOrNull { it.isSelected }
    val before=flow?.step?.name
    when(command) {
      "replay" -> replayButton?.performClick()
      "select" -> options.firstOrNull()?.performClick()
      "next" -> root?.let { find(it) { b -> b.text=="Next decision" || b.text=="Continue" }?.performClick() }
      "history" -> root?.let { find(it) { b -> b.text.contains("Answer history") }?.performClick() }
      "hold_thumb" -> onProbeHold?.invoke()
      // The same button object clicked again at once and after the screen changed: the stale-reference case.
      "repeat_confirm" -> root?.let { find(it) { b -> b.tag==com.overhand.app.decisions.QuestionCard.TAG_CONFIRM } }?.let { b ->
        b.performClick();b.performClick()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ b.performClick() },150)
      }
      "skip" -> root?.let { find(it) { b -> b.text=="Skip" }?.performClick() }
      in (0..9).map { "pick:$it" } -> options.getOrNull(command.substringAfter(':').toInt())?.performClick()
      "thumb" -> if(flow?.step==OnboardingFlow.Step.THUMB) {
        syntheticUntil=SystemClock.elapsedRealtime()+1000
        advance(emptyMap(),true)
        advance(mapOf("right" to ThumbPose.UP),true)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
          advance(mapOf("right" to ThumbPose.UP),true)
        },450)
      }
    }
    activity.openFileOutput("onboarding_probe.json",0).use { it.write(org.json.JSONObject()
      .put("command",command).put("before",before).put("after",flow?.step?.name)
      .put("has_root",root!=null).put("has_selection",selection!=null).toString().toByteArray()) }
  }
  companion object {
    const val OPTION_TAG="card-option"
    private val instances=WeakHashMap<Activity,java.lang.ref.WeakReference<HandsOnboarding>>()
    fun get(activity: Activity): HandsOnboarding = instances[activity]?.get() ?: HandsOnboarding(activity).also {
      instances[activity]=java.lang.ref.WeakReference(it)
    }
  }
}
