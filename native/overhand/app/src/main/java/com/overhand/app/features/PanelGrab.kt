package com.overhand.app.features

import com.overhand.app.hands.HandJoints
import com.overhand.app.hands.Point
import kotlin.math.*

/** look_nov17 s2: dp-sized pill; independent of lane C's generous grab geometry. */
object GrabBarLook {
  const val WIDTH_DP=160.0
  const val HEIGHT_DP=8.0
  const val GAP_DP=24.0
  const val IDLE_ARGB=0x66FFFFFF
  const val CYAN_ARGB=0xFF5CE1E6.toInt()
  data class Size(val w: Double,val h: Double,val gap: Double)
  fun size(panelWidthM: Double,panelWidthDp: Double): Size {
    require(panelWidthM>0 && panelWidthDp>0)
    val m=panelWidthM/panelWidthDp
    return Size(WIDTH_DP*m,HEIGHT_DP*m,GAP_DP*m)
  }
  fun colour(pointed: Boolean,held: Boolean)=if(pointed || held) CYAN_ARGB else IDLE_ARGB
  /** Capsule boundary in dp, two semicircles with a 4 dp radius. */
  fun outline(): List<Pair<Double,Double>> = (0..32).map { i ->
    val a=-PI/2+i*PI/32
    (WIDTH_DP/2-HEIGHT_DP/2+HEIGHT_DP/2*cos(a)) to (HEIGHT_DP/2*sin(a))
  }+(0..32).map { i ->
    val a=PI/2+i*PI/32
    (-WIDTH_DP/2+HEIGHT_DP/2+HEIGHT_DP/2*cos(a)) to (HEIGHT_DP/2*sin(a))
  }
}

/** Replay model for bar-only fixed-size movement. Live grabs are owned by ISDK in GrabFeature. */
class PanelGrab(private val followTimeMs: Double = 40.0, val owner: PinchOwner = PinchOwner()) {
  /** t1282: whether a clickable view lies at panel-local (u, v) metres (right, up from the centre); null: none known. */
  var clickableAt: ((id: String, u: Double, v: Double) -> Boolean)?=null
  /** Dimensions are layout-owned. Extra saved-layout fields remain compatible with recorded fixtures. */
  data class Panel(val id: String, val centre: V3, val yawDeg: Double, val w: Double, val h: Double, val resizable: Boolean=true,
    val baseW: Double=w, val baseH: Double=h) {
    val forward get()=Math.toRadians(yawDeg).let { V3(sin(it),0.0,cos(it)) }
    val right get()=Math.toRadians(yawDeg).let { V3(cos(it),0.0,-sin(it)) }
    /** Visible bar centre, under the bottom edge. */
    val bar get()=centre+V3(0.0,-h/2-BAR_GAP-BAR_H/2,0.0)
  }
  enum class Zone { NONE, BAR, CORNER, FACE, FRAME }
  /** A pointer ray: [o] origin, [d] unit direction. */
  data class Ray(val o: V3, val d: V3)
  data class Hit(val id: String, val zone: Zone, val t: Double, val point: V3, val u: Double, val v: Double, val visible: Boolean=true)
  data class Update(val id: String, val centre: V3, val yawDeg: Double, val w: Double, val h: Double)
  /** [via] "ray" or "near"; [distM] head to window centre; [handReachM] pinch-point travel before the grab; [armM] pinch point to shoulder. */
  data class Event(val kind: String, val id: String, val hand: String, val mode: String, val panel: Update, val movedM: Double, val scale: Double,
    val via: String="ray", val distM: Double=0.0, val handReachM: Double=0.0, val armM: Double=0.0)

  /** [shoulder] is frozen at pinch start: the body does not move during a drag, so the ray pivots about a fixed point. */
  private class Session(val id: String, val hand: String, var mode: String, val start: Panel, val shoulder: V3,
    val r0: Double, val reach0: Double, val offset: V3, val via: String="ray", val anchor0: V3=V3(0.0,0.0,0.0), val minDist: Double=RAY_MIN_DIST_M,
    val reachM: Double=0.0, val armM: Double=0.0) { var last: Update=Update(start.id,start.centre,start.yawDeg,start.w,start.h); var lastTick: Long?=null }

  private val pinching=mutableMapOf<String,Boolean>()
  private val sessions=mutableMapOf<String,Session>() // by hand
  /** Per-panel bar hover for replay diagnostics. */
  var hover: Map<String,Zone> = emptyMap(); private set
  private val history=mutableMapOf<String,ArrayDeque<Pair<Long,V3>>>()
  /** Per hand: what its ray hit on the previous tick. A pinch acts on what was hovered, not on where the closing pinch jolts the ray. */
  private val prevHit=mutableMapOf<String,Hit?>()
  /** Per hand: the bar its ray lights and since when. */
  private val lit=mutableMapOf<String,Pair<String,Long>>()
  private var frame=0L
  private var head=V3(0.0,0.0,0.0)
  val held get()=sessions.values.map { it.id }.toSet()
  fun holding(id: String)=sessions.values.any { it.id==id }

  /**
   * One tick. [hands] maps "left"/"right" to joints (null when untracked). Returns the panel updates to apply and the
   * grab start/end events to log.
   * #1034: [dock] is the dock row (Settings orb and window targets): a pinch whose ray meets one as it closes is the dock's
   * press and starts no grab, even of a window behind the row.
   */
  fun tick(head: V3, hands: Map<String,HandJoints?>, panels: List<Panel>, pointers: Map<String,Ray?> = emptyMap(), nowMs: Long?=null,
    dock: List<Dock.Target> = emptyList()): Pair<List<Update>,List<Event>> {
    val now=nowMs ?: (frame*FRAME_MS);frame++;this.head=head
    val events=mutableListOf<Event>()
    val byId=panels.associateBy { it.id }
    val hov=mutableMapOf<String,Zone>()
    val rays=hands.mapNotNull { (side,j) -> j?.let { side to (pointers[side] ?: pointer(head,it)) } }.toMap()
    for((side,j) in hands) {
      val h=history.getOrPut(side) { ArrayDeque() }
      if(j==null) { h.clear();continue }
      h.addLast(now to anchor(j));while(h.isNotEmpty() && h.first().first<now-REACH_WINDOW_MS) h.removeFirst()
    }
    // Releases first, so a hand that let go cannot also start on the same tick. Only a pinch's first frame can start.
    val began=mutableSetOf<String>()
    for(side in (hands.keys+sessions.keys).toSet()) {
      val j=hands[side]
      val gap=j?.let { tipGap(it) }
      val was=pinching[side]==true
      val now=if(gap==null) false else if(was) gap<PINCH_OFF_M else gap<PINCH_ON_M
      pinching[side]=now
      if(!was && now) { began.add(side);owner.pinchDown(side) }
      // t1282: release and tracking loss (gap null) both end the pinch's ownership.
      if(was && !now) { sessions.remove(side)?.let { s -> end(s,events) };owner.release(side) }
    }
    for((side,r) in rays) {
      val j=hands.getValue(side)!!
      val hitNow=nearest(r.o,r.d,j,panels)
      // Target lock: the pinch's first frame acts on the previous tick's hit (what was lit) when there was one.
      // A hand touching a bar grabs that bar, whatever its ray (or the lock) points past it at.
      val touch=if(side in began && side !in sessions) anchor(j).let { a -> panels.firstOrNull { dist(a,it.bar)<=NEAR_M }?.let { p -> Hit(p.id,Zone.BAR,dist(a,r.o),p.bar,0.0,-p.h/2-BAR_GAP-BAR_H/2) } } else null
      val hit=touch ?: if(side in began && side !in sessions) (prevHit[side] ?: hitNow)?.takeIf { it.id in byId } else hitNow
      prevHit[side]=hitNow
      val litFrom=lit[side]?.takeIf { it.first==hit?.id }?.second ?: now
      if(hitNow==null || hitNow.zone==Zone.FACE || hitNow.zone==Zone.NONE) lit.remove(side)
      else if(lit[side]?.first!=hitNow.id) lit[side]=hitNow.id to now
      if(hit!=null && hit.zone!=Zone.FACE && hit.zone!=Zone.NONE && sessions.values.none { it.id==hit.id }) { hov[hit.id]=hit.zone }
      val starting=side in began && side !in sessions
      if(!starting || hit==null) continue
      if(Dock.hit(r.o,r.d,dock)!=null) continue
      val p=byId.getValue(hit.id)
      val holder=sessions.values.firstOrNull { it.id==p.id }
      if(holder!=null) continue // A second hand never resizes or steals a panel.
      if(hit.zone!=Zone.BAR) continue
      // t1282: one owner per pinch. A clickable view in the grab zone, or a press already held on this panel, owns it.
      if(!owner.grabDown(side,p.id,clickableAt?.invoke(p.id,hit.u,hit.v)==true,now)) continue
      val shoulder=shoulder(head,j);val a=anchor(j)
      val near=dist(a,p.bar)<=NEAR_M && hit.zone==Zone.BAR
      val mode=if(near) "bar_near" else "bar_ray"
      // Depth is measured from the frozen shoulder along the pointer direction, so the window swings about the body.
      val r0=(hit.point-shoulder).dot(r.d).coerceAtLeast(0.05)
      val reach=history[side]?.filter { it.first>=litFrom }?.maxOfOrNull { dist(it.second,a) } ?: 0.0
      val d0=dist(p.centre,head)
      val s=Session(p.id,side,mode,p,shoulder,r0,dist(a,shoulder),p.centre-(shoulder+r.d*r0),
        via=if(near) "near" else "ray",anchor0=a,minDist=min(RAY_MIN_DIST_M,d0),reachM=reach,armM=dist(a,shoulder))
      sessions[side]=s
      events.add(Event("start",p.id,side,mode,s.last,0.0,1.0,s.via,d0,reach,s.armM))
    }
    for(side in hands.keys) if(side !in rays) { prevHit.remove(side);lit.remove(side) }
    hover=hov
    // Drive held panels.
    val updates=mutableListOf<Update>()
    for((side,s) in sessions) {
      val j=hands[side] ?: continue
      // A platform pointer drives the window directly; the derived one pivots about the shoulder frozen at the start
      // (the per-frame shoulder estimate follows the wrist sideways and would eat part of the drag).
      val o=s.shoulder;val d=pointers[side]?.d ?: (anchor(j)-o).unit()
      val cur=byId[s.id] ?: continue
      val u=if(s.via=="near") {
          // Near grab: the window moves with the hand.
          val c=s.start.centre+(anchor(j)-s.anchor0)
          Update(s.id,c,Comfort.facingYawDeg(head,c),cur.w,cur.h)
        } else {
          val gain=(s.r0/ARM_M).coerceIn(1.0,3.0)
          val r=(s.r0+gain*(dist(anchor(j),o)-s.reach0)).coerceIn(MIN_RAY_M,MAX_RAY_M)
          var c=o+d*r+s.offset
          val dh=dist(c,head)
          if(dh>RAY_MAX_DIST_M || dh<s.minDist) c=head+(c-head)*(dh.coerceIn(s.minDist,RAY_MAX_DIST_M)/dh.coerceAtLeast(1e-6))
          // A short time-based filter damps distance-ray amplification of hand jitter.
          // Only held ray moves are filtered; near moves and release stay direct.
          val dt=(now-(s.lastTick ?: (now-FRAME_MS))).coerceAtLeast(1).toDouble()
          val alpha=dt/(dt+followTimeMs)
          c=s.last.centre+(c-s.last.centre)*alpha
          s.lastTick=now
          Update(s.id,c,Comfort.facingYawDeg(head,c),cur.w,cur.h)
        }
      u.let { s.last=it;updates.add(it) }
    }

    return updates to events
  }

  private fun end(s: Session,events: MutableList<Event>) {
    val moved=dist(s.last.centre,s.start.centre)
    events.add(Event("end",s.id,s.hand,s.mode,s.last,moved,s.last.w/s.start.w,s.via,dist(s.last.centre,head),s.reachM,s.armM))
  }
  /** The nearest grab target along the ray (or touching the anchor, for near grabs). */
  fun nearest(o: V3,d: V3,j: HandJoints?,panels: List<Panel>): Hit? {
    var best: Hit?=null
    for(p in panels) {
      val h=zone(o,d,p) ?: continue
      if(best==null || score(h)<score(best)) best=h
    }
    if(j!=null && (best==null || best.zone==Zone.FACE || best.zone==Zone.NONE)) {
      val a=anchor(j)
      panels.firstOrNull { dist(a,it.bar)<=NEAR_M }?.let { p -> return Hit(p.id,Zone.BAR,dist(a,o),p.bar,0.0,-p.h/2-BAR_GAP-BAR_H/2) }
    }
    return best
  }
  /**
   * Depth plus penalties: a hit in an invisible margin (outside the panel and its visible bar) yields to a visible
   * target up to [MARGIN_YIELD_M] behind it (aiming at Fleet's inner corner crosses Chat's outer corner margin first),
   * and a face yields to a handle up to 5 cm behind it.
   */
  private fun score(h: Hit)=h.t+(if(h.visible) 0.0 else MARGIN_YIELD_M)+(if(h.zone==Zone.FACE) 0.05 else 0.0)
  fun zone(o: V3,d: V3,p: Panel): Hit? {
    val (t,pt)=planeHit(o,d,p) ?: return null
    val q=pt-p.centre;val u=q.dot(p.right);val v=q.y
    val au=abs(u);val av=abs(v)
    val zone=when {
      au<=p.w/2 && av<=p.h/2 -> Zone.FACE
      au<=BAR_HIT_W/2 && abs(v-(-p.h/2-BAR_GAP-BAR_H/2))<=BAR_HIT_H/2 -> Zone.BAR
      else -> return null
    }
    val barTop=-p.h/2-BAR_GAP
    val visible=(au<=p.w/2+VIS_TOL && av<=p.h/2+VIS_TOL) || (au<=BAR_W/2+VIS_TOL && v in (barTop-BAR_H-VIS_TOL)..(barTop+VIS_TOL))
    return Hit(p.id,zone,t,pt,u,v,visible)
  }
  private fun planeHit(o: V3,d: V3,p: Panel): Pair<Double,V3>? {
    val n=p.forward;val den=d.dot(n)
    if(abs(den)<0.05) return null
    val t=(p.centre-o).dot(n)/den
    if(t<=0.02) return null
    return t to (o+d*t)
  }

  companion object {
    /** Carry only the native bar's translation. Panel size and all other layout state remain fixed. */
    fun carried(panel: Panel,barStart: V3,barNow: V3)=panel.centre+(barNow-barStart)

    /** #1020, fitted to developer's 25 labelled presses (developer_pinch_labels.tsv, runs/pinch_t1020/sweep.md): 17/21 mm
     *  turns 24 of them into one timely press with 0 false; 15/30 mm gave 21. 18 mm reads his thumbs-up lead-ins (17.8 mm)
     *  as presses, and his weakest meant press closes to 17.0 mm: there is under 1 mm of margin either side. */
    const val PINCH_ON_M=0.017
    const val PINCH_OFF_M=0.021
    const val NEAR_M=0.10
    const val ARM_M=0.5
    const val MIN_RAY_M=0.25
    const val MAX_RAY_M=6.0
    /** Replay hit geometry matches the native bar mesh. */
    const val BAR_W=0.40
    const val BAR_H=0.035
    const val BAR_GAP=0.03
    const val BAR_HIT_W=BAR_W
    const val BAR_HIT_H=BAR_H
    const val VIS_TOL=0.02
    const val MARGIN_YIELD_M=0.30
    const val MIN_W=0.5; const val MAX_W=2.4; const val MIN_H=0.35; const val MAX_H=1.5
    /** t1234 (worn nov15d: two corner grabs, one a 59 ms tap, stretched the voice window to 2.40 x 1.15 m): a window's
     *  size stays within this range of its default, however many grabs compound. */
    const val SCALE_MIN=0.75; const val SCALE_MAX=1.5
    const val SHOULDER_DROP=0.20
    /** A ray-held window stays this far from the head (a window already nearer keeps its own distance as the floor). */
    const val RAY_MIN_DIST_M=0.8
    const val RAY_MAX_DIST_M=4.0
    const val REACH_WINDOW_MS=1000L
    const val FRAME_MS=14L
    const val SHOULDER_SIDE=0.18

    fun tipGap(j: HandJoints)=dist(j.thumb.tip,j.fingers[0].tip)
    fun anchor(j: HandJoints): V3 { val a=j.thumb.pip;val b=j.fingers[0].base;return V3((a.x+b.x)/2,(a.y+b.y)/2,(a.z+b.z)/2) }
    /** Shoulder below the head, offset toward the hand's side (side-agnostic: taken from where the hand is). */
    fun shoulder(head: V3,j: HandJoints): V3 {
      val w=j.wrist;val lateral=V3(w.x-head.x,0.0,w.z-head.z)
      val l=lateral.length()
      val side=if(l<1e-6) V3(0.0,0.0,0.0) else lateral*(min(l,SHOULDER_SIDE)/l)
      return V3(head.x,head.y-SHOULDER_DROP,head.z)+side
    }
    fun ray(head: V3,j: HandJoints): Pair<V3,V3> { val o=shoulder(head,j);return o to (anchor(j)-o).unit() }
    /**
     * Legacy replay-only ray; recordings do not carry the system pointer pose:
     * origin at the pinch point, direction from the estimated shoulder through it.
     */
    fun pointer(head: V3,j: HandJoints): Ray { val o=shoulder(head,j);val a=anchor(j);return Ray(a,(a-o).unit()) }
    fun dist(a: V3,b: V3)=(a-b).length()
    fun dist(a: Point,b: Point)=(a-b).length()
  }
}

internal operator fun V3.times(k: Double)=V3(x*k,y*k,z*k)
internal fun V3.dot(o: V3)=x*o.x+y*o.y+z*o.z
internal fun V3.unit(): V3 { val l=length();return if(l<1e-9) V3(0.0,0.0,1.0) else V3(x/l,y/l,z/l) }
