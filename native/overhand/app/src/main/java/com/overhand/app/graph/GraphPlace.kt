package com.overhand.app.graph

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import kotlin.math.*

/**
 * #1380: where the graph opens. Straight ahead of the head along its forward flattened to the floor, [AHEAD] metres
 * out, [DROP] under the eye line, turned to face the head about the vertical only (no pitch, no roll). A saved place
 * is kept only inside the Yard's rule ([com.overhand.app.graph.site.YardPress.keepSaved]: 2.5 m, 60 degrees).
 * Rotations are (w,x,y,z), the order [GraphDrill.Framing] uses.
 */
object GraphPlace {
  const val AHEAD=1.6
  const val DROP=0.25
  /** The open view fits the page inside +-[FIT_DEG] (GraphLayout.viewingDistance's 60-degree volume). */
  const val FIT_DEG=30.0
  data class Place(val centre: Vec,val q: DoubleArray,val why: String,val dist: Double,val yawOffDeg: Double)

  fun rotate(q: DoubleArray,v: Vec): Vec {
    val (w,x,y,z)=q.toList();val u=Vec(x,y,z)
    val t=u.cross(v)*2.0
    return v+t*w+u.cross(t)
  }
  fun yawQ(yaw: Double)=doubleArrayOf(cos(yaw/2),0.0,sin(yaw/2),0.0)
  /** [forward] flattened to the floor plane; straight ahead (+z) when looking straight up or down. */
  fun flat(forward: Vec): Vec { val f=Vec(forward.x,0.0,forward.z);return if(f.dot(f)<1e-9) Vec(0.0,0.0,1.0) else f.unit() }
  private fun yawOff(head: Vec,forward: Vec,centre: Vec): Double {
    val f=flat(forward);val d=flat(centre-head)
    return Math.toDegrees(acos(f.dot(d).coerceIn(-1.0,1.0)))
  }
  private fun facing(head: Vec,centre: Vec,why: String,forward: Vec): Place {
    val d=flat(centre-head)
    return Place(centre,yawQ(atan2(d.x,d.z)),why,sqrt((centre-head).dot(centre-head)),yawOff(head,forward,centre))
  }
  /** The fresh open place for a head at [head] with rotation [headQ]. */
  fun open(head: Vec,headQ: DoubleArray,why: String="open"): Place {
    val fwd=rotate(headQ,Vec(0.0,0.0,1.0));val f=flat(fwd)
    return facing(head,Vec(head.x+f.x*AHEAD,head.y-DROP,head.z+f.z*AHEAD),why,fwd)
  }
  /** The Yard's rule, reused as is. */
  fun keep(saved: Vec,head: Vec,headQ: DoubleArray)=com.overhand.app.graph.site.YardPress.keepSaved(
    Pose(Vector3(saved.x.toFloat(),saved.y.toFloat(),saved.z.toFloat())),
    Pose(Vector3(head.x.toFloat(),head.y.toFloat(),head.z.toFloat()),Quaternion(headQ[0].toFloat(),headQ[1].toFloat(),headQ[2].toFloat(),headQ[3].toFloat())))
  /** Saved centre [saved] (null on first open): kept, turned upright to face the head, when inside the rule; else re-placed. */
  fun choose(saved: Vec?,head: Vec,headQ: DoubleArray): Place = when {
    saved==null -> open(head,headQ,"open")
    keep(saved,head,headQ) -> facing(head,saved,"saved",rotate(headQ,Vec(0.0,0.0,1.0)))
    else -> open(head,headQ,"moved_away")
  }
  /** Local centre of the page's tiles. */
  fun localCentre(model: GraphModel,page: Int=0): Vec {
    val c=GraphLayout.corners(model,page);if(c.isEmpty()) return Vec(0.0,0.0,0.0)
    return Vec((c.minOf { it.x }+c.maxOf { it.x })/2,(c.minOf { it.y }+c.maxOf { it.y })/2,(c.minOf { it.z }+c.maxOf { it.z })/2)
  }
  /** Zoom (at most 1) that keeps the page inside +-[FIT_DEG] at [dist]. */
  fun fitZoom(model: GraphModel,page: Int,dist: Double): Double {
    val m=localCentre(model,page)
    val r=GraphLayout.corners(model,page).maxOfOrNull { max(abs(it.x-m.x),abs(it.y-m.y)) } ?: return 1.0
    return GraphLod.clampZoom(min(1.0,dist*tan(Math.toRadians(FIT_DEG))/r))
  }
  /** Root translation that puts local point [local] (at [zoom]) on world [centre]. */
  fun rootT(centre: Vec,q: DoubleArray,local: Vec,zoom: Double)=centre-rotate(q,local*zoom)
}

/**
 * #1380: the part level is framed by visual angle, not a zoom number. The picked box and its children (the parts that
 * build on it) are [TARGET_DEG] wide seen from the eye, the group sits [LEFT_DEG] left of centre so the files card fits
 * on its right, and no box of the page is nearer than [NEAR_M].
 */
object GraphFrame {
  const val MIN_DEG=25.0
  const val MAX_DEG=40.0
  const val TARGET_DEG=32.0
  const val NEAR_M=0.9
  const val LEFT_DEG=13.0
  /** Distance to the graph plane the part level starts from: comfortable, well clear of [NEAR_M]. */
  const val DIST_M=1.4
  data class Result(val framing: GraphDrill.Framing,val dist: Double,val widthDeg: Double,val nearest: Double)

  fun group(model: GraphModel,id: String): List<GraphNode> {
    val kids=model.edges.filter { it.from==id }.map { it.to }.toSet()
    return model.nodes.filter { it.id==id || it.id in kids }
  }
  private fun boxCorners(n: GraphNode)=listOf(-1,1).flatMap { x -> listOf(-1,1).flatMap { y -> listOf(-1,1).map { z ->
    Vec(n.x+x*GraphLayout.WIDTH/2,n.y+y*GraphLayout.HEIGHT/2,n.z+z*GraphLayout.DEPTH/2) } } }
  /** Horizontal visual angle of [nodes] and the nearest distance of [all], for a root at [t],[q],[zoom] seen from [eye]. */
  fun measure(nodes: List<GraphNode>,all: List<GraphNode>,eye: Vec,t: Vec,q: DoubleArray,zoom: Double): Pair<Double,Double> {
    val yaw=yawOnly(q);val fwd=GraphPlace.rotate(yaw,Vec(0.0,0.0,1.0));val right=GraphPlace.rotate(yaw,Vec(1.0,0.0,0.0))
    fun world(c: Vec)=t+GraphPlace.rotate(q,c*zoom)
    val a=nodes.flatMap(::boxCorners).map { val v=world(it)-eye;Math.toDegrees(atan2(v.dot(right),v.dot(fwd))) }
    val near=all.flatMap(::boxCorners).minOf { val v=world(it)-eye;sqrt(v.dot(v)) }
    return (a.max()-a.min()) to near
  }
  fun yawOnly(q: DoubleArray): DoubleArray { val f=GraphPlace.flat(GraphPlace.rotate(q,Vec(0.0,0.0,1.0)));return GraphPlace.yawQ(atan2(f.x,f.z)) }
  /** The framing that shows [id]'s group at [TARGET_DEG] from [eye], the root turned upright from [rootQ]. */
  fun frame(model: GraphModel,page: Int,id: String,eye: Vec,rootQ: DoubleArray): Result? {
    val g=group(model,id);if(g.isEmpty()) return null
    val all=model.nodes.drop(page*GraphLayout.PAGE_SIZE).take(GraphLayout.PAGE_SIZE).ifEmpty { g }
    val q=yawOnly(rootQ);val fwd=GraphPlace.rotate(q,Vec(0.0,0.0,1.0));val right=GraphPlace.rotate(q,Vec(1.0,0.0,0.0))
    val c=g.flatMap(::boxCorners);val local=Vec((c.minOf { it.x }+c.maxOf { it.x })/2,(c.minOf { it.y }+c.maxOf { it.y })/2,(c.minOf { it.z }+c.maxOf { it.z })/2)
    fun at(d: Double,s: Double): Pair<Vec,Pair<Double,Double>> {
      val p=eye+fwd*d-right*(d*tan(Math.toRadians(LEFT_DEG)));val t=GraphPlace.rootT(p,q,local,s)
      return t to measure(g,all,eye,t,q,s)
    }
    fun solveZoom(d: Double): Double { var lo=GraphLod.MIN_ZOOM;var hi=GraphLod.MAX_ZOOM
      repeat(50) { val m=(lo+hi)/2;if(at(d,m).second.first<TARGET_DEG) lo=m else hi=m };return (lo+hi)/2 }
    var d=DIST_M;var s=solveZoom(d)
    // A group too small even at the largest zoom comes nearer; one too wide at the smallest goes farther.
    if(at(d,s).second.first !in MIN_DEG..MAX_DEG) { var lo=NEAR_M;var hi=8.0
      repeat(50) { val m=(lo+hi)/2;if(at(m,s).second.first>TARGET_DEG) lo=m else hi=m };d=(lo+hi)/2 }
    // Boxes nearer than NEAR_M (depth or a group far off-centre): push the whole frame back and re-solve.
    repeat(4) { val near=at(d,s).second.second;if(near<NEAR_M+0.02) { d+=NEAR_M+0.05-near;s=solveZoom(d) } }
    val (t,m)=at(d,s)
    val pc=t+GraphPlace.rotate(q,local*s)-eye
    return Result(GraphDrill.Framing(t,q,s),sqrt(pc.dot(pc)),m.first,m.second)
  }
}
