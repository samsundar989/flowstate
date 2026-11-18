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
class LegacyWristMenu {
  companion object {
    /** Show thresholds; the keep thresholds below are looser (hysteresis, no flicker at the edge). */
    const val SHOW_PALM_UP=Opening.PALM_UP
    const val KEEP_PALM_UP=Opening.PALM_KEEP
    /** Palm normal toward the eyes: -dot(normal, head-to-palm) (1 = palm faces the face square on). */
    const val SHOW_FACING=0.45
    const val KEEP_FACING=0.20
    /** Angle between head forward and the head-to-palm line. */
    const val SHOW_GAZE_DEG=35.0
    const val KEEP_GAZE_DEG=50.0
    /** A raised hand: wrist at most this far below the head to show (his lap: 0.40-0.44 m), [Opening.MENU_DROP_M] to keep. */
    const val SHOW_DROP_M=0.35
    /** Open hand: the second-least straight finger (index..little) is at least this straight (tip-base / bone length). */
    const val OPEN_STRAIGHT=0.85
    const val DWELL_MS=300L
    const val HIDE_MS=100L
    /** Smoothing time constant: at 72 Hz about 60 % of the step each frame, under 50 ms to settle. */
    const val SMOOTH_TAU_MS=12.0
    /** Panel centre from the wrist joint: along the palm normal, and back toward the forearm (away from the fingertips). */
    const val LIFT_M=0.08
    const val BACK_M=0.03
    /** Panel size, metres: 2 columns by 2 rows of 92 x 44 mm targets with 8 mm gaps. */
    const val PANEL_W=0.20f
    const val PANEL_H=0.104f
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

  private fun add(a: Point,b: Point,k: Double)=Point(a.x+b.x*k,a.y+b.y*k,a.z+b.z*k)
  private fun lerp(a: Point,b: Point,k: Double)=Point(a.x+(b.x-a.x)*k,a.y+(b.y-a.y)*k,a.z+(b.z-a.z)*k)

  fun straightness(h: HandJoints): Double = h.fingers.map { f ->
    val bones=(f.pip-f.base).length()+(f.dip-f.pip).length()+(f.tip-f.dip).length()
    (f.tip-f.base).length()/bones.coerceAtLeast(1e-6)
  }.sorted()[1]

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
    val c=add(add(h.wrist,n,LIFT_M),along,-BACK_M)
    return Place(c,(c-head.p).unit())
  }

  private fun canShow(r: Reading,h: HandJoints,head: Head)=Opening.plausible(h.wrist,head.p) &&
    r.palmUp>SHOW_PALM_UP && r.facing>SHOW_FACING && r.gazeDeg<SHOW_GAZE_DEG && r.straight>=OPEN_STRAIGHT && r.drop>-SHOW_DROP_M
  /** The keep test alone (public for the replay's linger measure). */
  fun keeps(h: HandJoints,left: Boolean,head: Head)=canKeep(reading(h,left,head),h,head)
  private fun canKeep(r: Reading,h: HandJoints,head: Head)=Opening.plausible(h.wrist,head.p) &&
    r.palmUp>KEEP_PALM_UP && r.facing>KEEP_FACING && r.gazeDeg<KEEP_GAZE_DEG && r.drop>-Opening.MENU_DROP_M

  /** Events of one tick: "show:<left|right>" or "hide:<why>". [holding] is true while any window is held. */
  fun tick(t: Long,head: Head,hands: Map<String,HandJoints?>,holding: Boolean): List<String> {
    val ev=mutableListOf<String>()
    val dt=(t-lastT).coerceAtLeast(0L);lastT=t;fresh=false
    listOf("left","right").forEach { s -> pinch[s]=Opening.pinching(hands[s],pinch.getValue(s)) }
    val busy=holding || pinch.values.any { it }
    owner?.let { o ->
      val h=hands[o]
      // The menu hand pinching is the headset's own gesture; a held window means the hands are busy. The other
      // hand pinching is the press itself and keeps the menu.
      val why=when {
        holding || pinch.getValue(o) -> "busy"
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
      if(!ok || busy) { dwellFrom[s]=null;return@forEach }
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

  /** Hide now (a press, or busy); the palm must turn away before it shows again. */
  fun hide(why: String) {
    if(owner==null) return
    owner=null;place=null;target=null;failingSince=null;fresh=false
    if(why=="pressed" || why=="busy") latched=true
  }
}

