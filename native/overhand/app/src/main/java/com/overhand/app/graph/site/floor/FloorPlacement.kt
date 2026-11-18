package com.overhand.app.graph.site.floor

import com.overhand.app.graph.site.SiteGeometry
import com.overhand.app.graph.site.SitePutOn
import kotlin.math.*

/**
 * #1238 the build site standing on the real floor in passthrough: where its root goes, at what uniform scale, and the
 * grounding shapes (plot outline, a soft dark patch under each building) as plain data. World metres, +y up, the scene's
 * LOCAL_FLOOR space. No SDK or Android types, so every rule runs on the JVM ([FloorPlacementTest]).
 *
 * Rules (numbers in the constants below): the site is a floor model the wearer looks down on. Tallest thing at least
 * [EYE_MARGIN] below the eye; near edge at least [NEAR_EDGE] from the feet; centre [PREFERRED_MIN]..[PREFERRED_MAX] ahead
 * when the room allows, closer (never under the near-edge rule) only when the room is too small; it shrinks as a whole
 * (down to [MIN_SCALE]) and turns away from the gaze only when it must; it never crosses a wall ([WALL_CLEAR]) or a piece
 * of furniture ([FURNITURE_CLEAR]). No fit at all gives [Source.NONE]: the caller keeps today's table-height site.
 */
object FloorPlacement {
  const val DEFAULT_SCALE = 0.8
  const val MIN_SCALE = 0.3
  const val SCALE_STEP = 0.05
  const val NEAR_EDGE = 0.8
  const val PREFERRED_MIN = 1.5
  const val PREFERRED_MAX = 2.0
  const val PREFERRED = 1.7
  const val EYE_MARGIN = 0.3
  const val WALL_CLEAR = 0.10
  const val FURNITURE_CLEAR = 0.10
  /** Lower than this above the floor (a rug, a threshold) is walked on, not an obstacle. */
  const val FURNITURE_MIN_H = 0.05
  /** Table metres: the slab is drawn from -0.03 to 0 ([com.overhand.app.graph.site.SiteMesh] table box), the rim 0.012 out. */
  const val SLAB = 0.03
  const val RIM = 0.012
  /** World metres of label overhang beyond the rim (as [SitePutOn.siteBox]). */
  const val LABEL_MARGIN = 0.02
  const val OUTLINE_LIFT = 0.002
  const val SHADOW_LIFT = 0.003
  /** Table metres each shadow reaches beyond its building's footprint. */
  const val SHADOW_GROW = 0.02
  /** Degrees from the gaze, in order of preference. */
  val HEADINGS = listOf(0.0,20.0,-20.0,40.0,-40.0,60.0,-60.0,90.0,-90.0,120.0,-120.0,150.0,-150.0,180.0)

  data class V2(val x: Double, val z: Double)
  data class V3(val x: Double, val y: Double, val z: Double)
  /** Head position and the gaze direction on the floor (any length; pitch ignored). */
  data class Head(val x: Double, val y: Double, val z: Double, val forwardX: Double, val forwardZ: Double)
  /** A convex footprint on the floor (world x,z) and the height of its top (world y). */
  data class Furniture(val footprint: List<V2>, val top: Double)
  /** Room scan data, when the wearer allowed room access: floor height, floor outline (any simple polygon), furniture. */
  data class Room(val floorY: Double? = null, val outline: List<V2> = emptyList(), val furniture: List<Furniture> = emptyList())
  /** A building footprint in table metres (site-local x right, z away from the wearer). */
  data class Rect(val x0: Double, val z0: Double, val x1: Double, val z1: Double)
  enum class Source { ROOM, FLOOR_ORIGIN, NONE }
  data class Shape(val kind: String, val corners: List<V3>)
  data class Result(val source: Source, val floorY: Double, val centre: V3, val yawDeg: Double, val scale: Double,
    /** World half-width of everything drawn on the floor (square). */
    val half: Double, val rootY: Double, val topY: Double, val headingDeg: Double, val distance: Double,
    val shapes: List<Shape>, val reason: String) {
    val forwardX get() = sin(Math.toRadians(yawDeg))
    val forwardZ get() = cos(Math.toRadians(yawDeg))
    /** The four floor corners of the site square, world. */
    fun corners(grow: Double = 0.0) = square(centre.x,centre.z,yawDeg,half+grow)
  }

  /** The whole table as one building: the default shadow when the caller passes no footprints. */
  val WHOLE = listOf(Rect(-SiteGeometry.TABLE/2,-SiteGeometry.TABLE/2,SiteGeometry.TABLE/2,SiteGeometry.TABLE/2))

  fun halfOf(scale: Double) = (SiteGeometry.TABLE/2+RIM)*scale+LABEL_MARGIN
  /** The largest scale whose tallest point stays [EYE_MARGIN] below the eye. */
  fun eyeCap(eyeY: Double, floorY: Double) = (eyeY-floorY-EYE_MARGIN)/(SitePutOn.TOP+SLAB)

  fun place(head: Head, floorY: Double = 0.0, room: Room? = null, buildings: List<Rect> = WHOLE): Result {
    val floor = room?.floorY ?: floorY
    val f = hypot(head.forwardX,head.forwardZ)
    val gaze = if(f<1e-6 || !f.isFinite()) 0.0 else Math.toDegrees(atan2(head.forwardX,head.forwardZ))
    val cap = min(DEFAULT_SCALE,eyeCap(head.y,floor))
    if(!head.x.isFinite() || !head.y.isFinite() || !head.z.isFinite() || cap<MIN_SCALE)
      return none(floor,gaze,"head %.2f m above the floor is too low for a floor model".format(head.y-floor))
    val hasRoom = room!=null && (room.outline.size>=3 || room.furniture.isNotEmpty())
    var best: Result? = null; var bestScore = Double.NEGATIVE_INFINITY
    val scales = generateSequence(cap) { it-SCALE_STEP }.takeWhile { it>=MIN_SCALE-1e-9 }.toList()
    for(s in scales) for(h in HEADINGS) {
      val half = halfOf(s)
      val nearest = NEAR_EDGE+half
      val dists = ((0..20).map { nearest+it*0.05 }+PREFERRED)
        .filter { it>=nearest-1e-9 && it<=PREFERRED_MAX+1e-9 }.distinct()
      for(d in dists) {
        val score = s/DEFAULT_SCALE-0.004*abs(h)-0.3*max(0.0,PREFERRED_MIN-d)-0.05*abs(d-PREFERRED)
        if(score<=bestScore) continue
        val yaw = gaze+h
        val cx = head.x+d*sin(Math.toRadians(yaw)); val cz = head.z+d*cos(Math.toRadians(yaw))
        if(hasRoom && !clear(cx,cz,yaw,half,room!!,floor)) continue
        best = build(if(hasRoom) Source.ROOM else Source.FLOOR_ORIGIN,floor,cx,cz,yaw,s,h,d,buildings,
          if(hasRoom) "room data" else "floor origin, no room data")
        bestScore = score
      }
    }
    return best ?: none(floor,gaze,"no spot clear of walls and furniture down to scale $MIN_SCALE")
  }

  private fun none(floor: Double, gaze: Double, why: String) =
    Result(Source.NONE,floor,V3(Double.NaN,floor,Double.NaN),gaze,0.0,0.0,floor,floor,0.0,0.0,emptyList(),why)

  private fun build(source: Source, floor: Double, cx: Double, cz: Double, yaw: Double, s: Double, h: Double, d: Double,
    buildings: List<Rect>, why: String): Result {
    val half = halfOf(s)
    val outline = Shape("outline",square(cx,cz,yaw,half).map { V3(it.x,floor+OUTLINE_LIFT,it.z) })
    val lim = SiteGeometry.TABLE/2
    val shadows = buildings.map { b ->
      val x0 = max(-lim,b.x0-SHADOW_GROW); val x1 = min(lim,b.x1+SHADOW_GROW)
      val z0 = max(-lim,b.z0-SHADOW_GROW); val z1 = min(lim,b.z1+SHADOW_GROW)
      Shape("shadow",listOf(V2(x0,z0),V2(x1,z0),V2(x1,z1),V2(x0,z1)).map { p ->
        val w = local(cx,cz,yaw,p.x*s,p.z*s); V3(w.x,floor+SHADOW_LIFT,w.z) })
    }
    val rootY = floor+SLAB*s
    return Result(source,floor,V3(cx,floor,cz),yaw,s,half,rootY,rootY+SitePutOn.TOP*s,h,d,listOf(outline)+shadows,why)
  }

  /** Rebuild grounding for a changed picture without searching or moving the site's cached world pose. */
  fun grounding(r: Result,buildings: List<Rect>): List<Shape> = if(r.source==Source.NONE) emptyList() else
    build(r.source,r.floorY,r.centre.x,r.centre.z,r.yawDeg,r.scale,r.headingDeg,r.distance,buildings,r.reason).shapes

  // ---- checks (also what the tests measure) ----

  /** Horizontal distance from the wearer's feet to the nearest part of anything the site draws. */
  fun nearestToFeet(r: Result, head: Head): Double {
    val ps = r.shapes.flatMap { it.corners }.map { V2(it.x,it.z) }+r.corners()
    return distanceToPolygon(V2(head.x,head.z),hull(ps))
  }
  fun overlapsWearer(r: Result, head: Head) = r.source!=Source.NONE && nearestToFeet(r,head)<NEAR_EDGE-1e-9
  fun belowFloor(r: Result) = r.source!=Source.NONE &&
    (r.rootY-SLAB*r.scale<r.floorY-1e-9 || r.shapes.any { s -> s.corners.any { it.y<r.floorY-1e-9 } })
  fun aboveEye(r: Result, head: Head) = r.source!=Source.NONE && r.topY>head.y-1e-9
  fun insideRoom(r: Result, room: Room) = r.source==Source.NONE || clear(r.centre.x,r.centre.z,r.yawDeg,r.half,room,r.floorY,0.0)

  /** Planted control: the site centred on the wearer's feet. The overlap check must reject it. */
  fun centredOnWearer(head: Head, floorY: Double = 0.0): Result {
    val yaw = Math.toDegrees(atan2(head.forwardX,head.forwardZ))
    return build(Source.FLOOR_ORIGIN,floorY,head.x,head.z,yaw,DEFAULT_SCALE,0.0,0.0,WHOLE,"planted: centred on wearer")
  }

  // ---- geometry ----

  private fun clear(cx: Double, cz: Double, yaw: Double, half: Double, room: Room, floor: Double,
    margin: Double = 1.0): Boolean {
    if(room.outline.size>=3) {
      val sq = square(cx,cz,yaw,half+WALL_CLEAR*margin)
      if(sq.any { !inside(it,room.outline) }) return false
      for(i in sq.indices) for(j in room.outline.indices)
        if(cross(sq[i],sq[(i+1)%4],room.outline[j],room.outline[(j+1)%room.outline.size])) return false
    }
    val sq = square(cx,cz,yaw,half+FURNITURE_CLEAR*margin)
    return room.furniture.none { it.top-floor>FURNITURE_MIN_H && it.footprint.size>=3 && overlapConvex(sq,it.footprint) }
  }

  private fun local(cx: Double, cz: Double, yaw: Double, lx: Double, lz: Double): V2 {
    val r = Math.toRadians(yaw); val c = cos(r); val s = sin(r)
    // Site +z is the yaw direction (away from the wearer), +x is to its right.
    return V2(cx+lx*c+lz*s,cz-lx*s+lz*c)
  }
  private fun square(cx: Double, cz: Double, yaw: Double, h: Double) =
    listOf(local(cx,cz,yaw,-h,-h),local(cx,cz,yaw,h,-h),local(cx,cz,yaw,h,h),local(cx,cz,yaw,-h,h))

  private fun inside(p: V2, poly: List<V2>): Boolean {
    var c = false; var j = poly.size-1
    for(i in poly.indices) { val a = poly[i]; val b = poly[j]
      if((a.z>p.z)!=(b.z>p.z) && p.x<(b.x-a.x)*(p.z-a.z)/(b.z-a.z)+a.x) c = !c; j = i }
    return c
  }
  private fun orient(a: V2, b: V2, c: V2) = (b.x-a.x)*(c.z-a.z)-(b.z-a.z)*(c.x-a.x)
  private fun cross(a: V2, b: V2, c: V2, d: V2): Boolean {
    val d1 = orient(c,d,a); val d2 = orient(c,d,b); val d3 = orient(a,b,c); val d4 = orient(a,b,d)
    return (d1>0)!=(d2>0) && (d3>0)!=(d4>0) && d1!=0.0 && d2!=0.0 && d3!=0.0 && d4!=0.0
  }
  /** Separating axis test for two convex polygons. */
  private fun overlapConvex(a: List<V2>, b: List<V2>): Boolean {
    for(poly in listOf(a,b)) for(i in poly.indices) {
      val p = poly[i]; val q = poly[(i+1)%poly.size]; val nx = -(q.z-p.z); val nz = q.x-p.x
      val pa = a.map { it.x*nx+it.z*nz }; val pb = b.map { it.x*nx+it.z*nz }
      if(pa.max()<=pb.min() || pb.max()<=pa.min()) return false
    }
    return true
  }
  private fun hull(ps: List<V2>): List<V2> {
    val s = ps.distinct().sortedWith(compareBy({ it.x },{ it.z })); if(s.size<3) return s
    fun chain(list: List<V2>): List<V2> { val h = ArrayList<V2>()
      for(p in list) { while(h.size>=2 && orient(h[h.size-2],h[h.size-1],p)<=0) h.removeAt(h.size-1); h.add(p) }
      return h.dropLast(1) }
    return chain(s)+chain(s.reversed())
  }
  private fun distanceToPolygon(p: V2, poly: List<V2>): Double {
    if(poly.size>=3 && inside(p,poly)) return 0.0
    return poly.indices.minOf { i -> val a = poly[i]; val b = poly[(i+1)%poly.size]
      val dx = b.x-a.x; val dz = b.z-a.z; val l = dx*dx+dz*dz
      val t = if(l<1e-12) 0.0 else (((p.x-a.x)*dx+(p.z-a.z)*dz)/l).coerceIn(0.0,1.0)
      hypot(p.x-(a.x+t*dx),p.z-(a.z+t*dz)) }
  }
}
