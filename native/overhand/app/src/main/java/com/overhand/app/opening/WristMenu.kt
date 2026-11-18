package com.overhand.app.opening

import com.overhand.app.hands.HandJoints
import com.overhand.app.hands.Point
import kotlin.math.acos
import kotlin.math.exp

/**
 * #1236: the control menu on the wrist. Pure rules (no Android types), driven on every rendered frame by
 * OpeningFeature and replayed on developer's recorded hands by WristMenuReplayTest.
 *
 * Built to the hand-constraint palm-up pattern (MRTK HandConstraintPalmUp: palm facing the camera, flat hand,
 * gaze on the hand, hysteresis between show and keep; the platform's own palm menu: hand-locked, pressed by the other
 * hand). The menu has no world pose of its own: every pose it gets is [target] of its hand on that frame, lightly smoothed.
 *
 * Opens on purpose: palm up and turned toward the face, the hand open and raised (not resting on the lap: Nov 15 his
 * hands rested palm up 0.40-0.44 m below the head for minutes), the hand within [SHOW_GAZE_DEG] of head forward, held
 * [DWELL_MS]; never while either hand pinches or a window is held. Leaves at once: [HIDE_MS] after the keep test fails.
 */
class WristMenu {
  companion object {
    /** Show thresholds; the keep thresholds below are looser (hysteresis, no flicker at the edge). */
    const val SHOW_PALM_UP=0.20
    const val KEEP_PALM_UP=0.10
    /** Palm normal toward the eyes: -dot(normal, head-to-palm) (1 = palm faces the face square on). */
    const val SHOW_FACING=0.8191520443 // cos 35 degrees
    const val KEEP_FACING=0.6427876097 // cos 50 degrees
    /** Angle between head forward and the head-to-palm line. */
    const val SHOW_GAZE_DEG=50.0
    const val KEEP_GAZE_DEG=50.0
    /** A raised hand: wrist at most this far below the head to show (his lap: 0.40-0.44 m), [Opening.MENU_DROP_M] to keep. */
    const val SHOW_DROP_M=0.35
    /** All four fingers: tip-base / bone length. Recorded quiet jitter reaches .93658 on an open hand. */
    const val OPEN_STRAIGHT=0.936
    /** Recorded open thumbs reach .80329 under measured jitter; keep the abduction boundary just beyond it. */
    const val THUMB_ALONG_MAX=0.804
    const val DWELL_MS=250L
    const val QUIET_MS=600L
    const val STILL_M=0.025
    const val STILL_DOT=0.984807753 // 10 degrees
    const val HIDE_MS=100L
    /** Smoothing time constant: at 72 Hz about 60 % of the step each frame, under 50 ms to settle. */
    const val SMOOTH_TAU_MS=12.0
    /** Panel centre from the wrist joint: along the palm normal, and back toward the forearm (away from the fingertips). */
    const val LIFT_M=0.14
    const val BACK_M=0.0
    /** Panel size, metres: 2 columns by 2 rows of 92 x 44 mm targets with 8 mm gaps. */
    const val PANEL_W=0.208f
    const val PANEL_H=0.112f
  }

  /** [p] head position, [fwd] unit head forward. */
  data class Head(val p: Point,val fwd: Point)
  /** [centre] the panel centre; [facing] unit direction from the head to the panel (the panel's +z, as main's yaw used). */
  data class Place(val centre: Point,val facing: Point)
  /** What the replay records per gate decision (for the per-show TSV). */
  data class Reading(val palmUp: Double,val facing: Double,val gazeDeg: Double,val straight: Double,val drop: Double)

  var owner: String?=null; private set
  val showing get()=owner!=null
  var place: Place?=null; private set
  /** True when [place] was set from the owner hand on this tick: the only time the feature writes a pose. */
  var fresh=false; private set
  /** The owner hand's raw target this frame (what [place] follows). */
  var target: Place?=null; private set
  /** The reading of the owner hand when it was shown. */
  var shownWith: Reading?=null; private set
  private val dwellFrom=mutableMapOf<String,Long?>("left" to null,"right" to null)
  private val pinch=mutableMapOf("left" to false,"right" to false)
  private var failingSince: Long?=null
  private var lastT=0L
  /** After a press or a busy hide, no show until no hand passes the show test (the palm must turn away first). */
  private var latched=false
  private var quietUntil=Long.MIN_VALUE
  private val anchors=mutableMapOf<String,Pair<Point,Point>>()

  private fun add(a: Point,b: Point,k: Double)=Point(a.x+b.x*k,a.y+b.y*k,a.z+b.z*k)
  private fun lerp(a: Point,b: Point,k: Double)=Point(a.x+(b.x-a.x)*k,a.y+(b.y-a.y)*k,a.z+(b.z-a.z)*k)

  fun straightness(h: HandJoints): Double = h.fingers.map { f ->
    val bones=(f.pip-f.base).length()+(f.dip-f.pip).length()+(f.tip-f.dip).length()
    (f.tip-f.base).length()/bones.coerceAtLeast(1e-6)
  }.minOrNull() ?: 0.0

  /** All fingers extended along the palm, thumb abducted, with no pinch forming. */
  fun openShape(h: HandJoints): Boolean {
    val along=(h.fingers[1].base-h.wrist).unit()
    val thumb=h.thumb
    val bones=(thumb.pip-thumb.base).length()+(thumb.dip-thumb.pip).length()+(thumb.tip-thumb.dip).length()
    return straightness(h)>=OPEN_STRAIGHT && h.fingers.all { (it.tip-it.base).unit().dot(along)>0.70 } &&
      (thumb.tip-thumb.base).length()/bones.coerceAtLeast(1e-6)>0.90 &&
      (thumb.tip-h.fingers[0].base).length()>0.045 && Opening.pinchDistance(h)>0.065 &&
      (thumb.tip-thumb.base).unit().dot(along)<THUMB_ALONG_MAX
  }

  fun reading(h: HandJoints,left: Boolean,head: Head): Reading {
    val n=Opening.palmNormal(h,left)
    val c=Opening.palmCentre(h)
    val d=(c-head.p).unit()
    val gaze=Math.toDegrees(acos(d.dot(head.fwd.unit()).coerceIn(-1.0,1.0)))
    return Reading(n.y,-n.dot(d),gaze,straightness(h),h.wrist.y-head.p.y)
  }

  /** Where the menu goes on this hand: beside the wrist on the palm side, facing the head. */
  fun target(h: HandJoints,left: Boolean,head: Head): Place {
    val n=Opening.palmNormal(h,left)
    val along=(h.fingers[1].base-h.wrist).unit()
    // Offset toward the fingertips as well as above the palm, away from the system's palm icon.
    val c=add(add(Opening.palmCentre(h),n,LIFT_M),along,0.065)
    return Place(c,(c-head.p).unit())
  }

  private fun canShow(r: Reading,h: HandJoints,head: Head)=Opening.plausible(h.wrist,head.p) &&
    r.palmUp>SHOW_PALM_UP && r.facing>SHOW_FACING && r.gazeDeg<SHOW_GAZE_DEG && openShape(h) &&
    r.drop in -SHOW_DROP_M..-0.06 && (Opening.palmCentre(h)-head.p).unit().y<head.fwd.unit().y
  /** The keep test alone (public for the replay's linger measure). */
  fun keeps(h: HandJoints,left: Boolean,head: Head)=canKeep(reading(h,left,head),h,head)
  private fun canKeep(r: Reading,h: HandJoints,head: Head)=Opening.plausible(h.wrist,head.p) &&
    r.palmUp>KEEP_PALM_UP && r.facing>KEEP_FACING && r.gazeDeg<KEEP_GAZE_DEG && r.drop>-Opening.MENU_DROP_M

  /** Events of one tick: "show:<left|right>" or "hide:<why>". [holding] is true while any window is held. */
  fun tick(t: Long,head: Head,hands: Map<String,HandJoints?>,holding: Boolean,rayTargets: Set<String> = emptySet()): List<String> {
    val ev=mutableListOf<String>()
    val dt=(t-lastT).coerceAtLeast(0L);lastT=t;fresh=false
    listOf("left","right").forEach { s -> pinch[s]=Opening.pinching(hands[s],pinch.getValue(s)) }
    val busy=holding || pinch.values.any { it }
    if(busy) quietUntil=t+QUIET_MS
    rayTargets.forEach { s -> blockedUntil[s]=t+QUIET_MS }
    owner?.let { o ->
      val h=hands[o]
      // The menu hand pinching is the headset's own gesture; a held window means the hands are busy. The other
      // hand pinching is the press itself and keeps the menu.
      val why=when {
        busy || o in rayTargets -> "busy"
        h==null -> "hand_lost"
        !canKeep(reading(h,o=="left",head),h,head) -> "palm_away"
        else -> null
      }
      if(why==null) {
        failingSince=null
        val tg=target(h!!,o=="left",head);target=tg
        val k=1.0-exp(-dt/SMOOTH_TAU_MS)
        place=place?.let { Place(lerp(it.centre,tg.centre,k),lerp(it.facing,tg.facing,k).unit()) } ?: tg;fresh=true
      } else if(why=="busy" || why=="hand_lost") { hide(why);if(why=="busy") latched=true;ev.add("hide:$why") }
      else {
        val since=failingSince ?: t.also { failingSince=it }
        if(t-since>=HIDE_MS) { hide(why);ev.add("hide:$why") }
        else if(h!=null) { // still visible inside the grace: keep following the hand, never a world pose
          val tg=target(h,o=="left",head);target=tg;place=Place(lerp(place!!.centre,tg.centre,1.0-exp(-dt/SMOOTH_TAU_MS)),tg.facing);fresh=true
        }
      }
    }
    var anyShowable=false
    listOf("left","right").forEach { s ->
      val h=hands[s]
      val r=h?.let { reading(it,s=="left",head) }
      val ok=h!=null && r!=null && canShow(r,h,head)
      if(ok) anyShowable=true
      val c=h?.let(Opening::palmCentre)
      val n=h?.let { Opening.palmNormal(it,s=="left") }
      val anchor=anchors[s]
      val moved=c!=null && n!=null && anchor!=null && ((c-anchor.first).length()>STILL_M || n.dot(anchor.second)<STILL_DOT)
      if(!ok || busy || t<quietUntil || t<(blockedUntil[s] ?: Long.MIN_VALUE)) { dwellFrom[s]=null;anchors.remove(s);return@forEach }
      if(moved) { dwellFrom[s]=null;anchors.remove(s) }
      if(s !in anchors) anchors[s]=c!! to n!!
      else anchors[s]=anchors.getValue(s).first to n!!
      val from=dwellFrom[s] ?: t.also { dwellFrom[s]=it }
      // Both palms up: the first one keeps the menu.
      if(owner==null && !latched && t-from>=DWELL_MS) {
        owner=s;shownWith=r;failingSince=null
        val tg=target(h!!,s=="left",head);target=tg;place=tg;fresh=true
        ev.add("show:$s")
      }
    }
    if(!anyShowable) latched=false
    return ev
  }

  private val blockedUntil=mutableMapOf<String,Long>()

  /** Hide now (a press, or busy); the palm must turn away before it shows again. */
  fun hide(why: String) {
    if(owner==null) return
    owner=null;place=null;target=null;failingSince=null;fresh=false
    if(why=="pressed" || why=="busy") latched=true
  }
}
