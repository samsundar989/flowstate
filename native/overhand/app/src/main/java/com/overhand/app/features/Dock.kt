package com.overhand.app.features

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Window menu items and the calm-start policy. The only menu surface is ControlDock. */
object Dock {
  const val START_CALM="start-calm"
  data class Item(val view: String,val label: String)
  val ITEMS=listOf(Item("fleet","Fleet"),Item("blueprint","Blueprint"),Item("code","Code"),Item("build","Yard"))
  fun calm(pref: Boolean)=pref
  /** Windows closed at put-on: every dock window when calm, none otherwise (the pre-#1034 layout). */
  fun closedAtPutOn(calm: Boolean): Set<String> = if(calm) ITEMS.map { it.view }.toSet() else emptySet()
  /** What the wearer sees at put-on: the site, the windows not closed (Settings starts closed), the question if any. */
  fun putOnViews(calm: Boolean,question: Boolean): List<String> =
    listOf("site")+WindowLayout.defaultSpecs.map { it.view }.filter { it!=SettingsPanel.VIEW && it !in closedAtPutOn(calm) }+
      (if(question) listOf("decision") else emptyList())

  private fun right(yawDeg: Double)=Math.toRadians(yawDeg).let { V3(cos(it),0.0,-sin(it)) }
  data class Target(val id: String,val centre: V3,val yawDeg: Double,val w: Double,val h: Double)
  /** The target a ray from [o] along unit [d] meets first, or null (a gap between targets is not a press). */
  fun hit(o: V3,d: V3,targets: List<Target>): Target? = targets.mapNotNull { t ->
    val n=Math.toRadians(t.yawDeg).let { V3(sin(it),0.0,cos(it)) };val den=d.dot(n)
    if(abs(den)<1e-6) return@mapNotNull null
    val k=(t.centre-o).dot(n)/den;if(k<=0) return@mapNotNull null
    val q=o+d*k-t.centre
    if(abs(q.dot(right(t.yawDeg)))<=t.w/2 && abs(q.y)<=t.h/2) k to t else null
  }.minByOrNull { it.first }?.second

  /** A row target acts when the pinch closes on it, not on release: as his fingers open his hand drops and turns, and
   *  the ray at release lands 0.1 to 2 m from the target he pressed (the replay's "offsets"), off any button. Taps from
   *  accessibility or a debug press still arrive as a click. */
  fun pressOnClose(v: android.view.View,act: () -> Unit) {
    v.setOnTouchListener { view,e -> if(e.actionMasked==android.view.MotionEvent.ACTION_DOWN) { view.isPressed=true;act() }
      else if(e.actionMasked==android.view.MotionEvent.ACTION_UP || e.actionMasked==android.view.MotionEvent.ACTION_CANCEL) view.isPressed=false
      true }
    v.setOnClickListener { act() }
  }

  /** One pinch is one toggle: a second press within [SettingsPanel.GUARD_MS] is ignored (the orb's rule). */
  class Guard { private var last=Long.MIN_VALUE/2
    fun accept(nowMs: Long): Boolean { if(nowMs-last<SettingsPanel.GUARD_MS) return false;last=nowMs;return true } }
}
