package com.overhand.app.mr

import com.overhand.app.graph.site.floor.FloorPlacement as F

/** MRUK boundary points and all eight volume corners, already transformed to LOCAL_FLOOR metres.
 * No label whitelist: every supplied volume can obstruct the model, including OTHER and STORAGE. */
object FloorRoomAdapter {
  data class Anchor(val floor: List<F.V3> = emptyList(),val volume: List<F.V3> = emptyList())
  fun room(anchors: List<Anchor>,x: Double,z: Double): F.Room? {
    val floors=anchors.map { it.floor }.filter { it.size>=3 && it.all(::finite) }
    val floor=floors.firstOrNull { contains(F.V2(x,z),it.map { p -> F.V2(p.x,p.z) }) } ?: return null
    // A volume's horizontal convex hull is conservative even when the anchor is rotated/tilted.
    val furniture=anchors.filter { it.volume.size>=4 && it.volume.all(::finite) }.map { a ->
      F.Furniture(hull(a.volume.map { F.V2(it.x,it.z) }),a.volume.maxOf { it.y }) }
    return F.Room(floor.map { it.y }.average(),floor.map { F.V2(it.x,it.z) },furniture)
  }
  private fun finite(p: F.V3)=p.x.isFinite() && p.y.isFinite() && p.z.isFinite()
  fun contains(p: F.V2,poly: List<F.V2>): Boolean {
    var inside=false;var j=poly.lastIndex
    for(i in poly.indices) { val a=poly[i];val b=poly[j]
      if((a.z>p.z)!=(b.z>p.z) && p.x<(b.x-a.x)*(p.z-a.z)/(b.z-a.z)+a.x) inside=!inside
      j=i }
    return inside
  }
  private fun hull(points: List<F.V2>): List<F.V2> {
    val sorted=points.distinct().sortedWith(compareBy({ it.x },{ it.z }))
    if(sorted.size<3) return sorted
    fun chain(ps: List<F.V2>): List<F.V2> { val h=ArrayList<F.V2>()
      for(p in ps) { while(h.size>=2) { val a=h[h.size-2];val b=h.last()
        if((b.x-a.x)*(p.z-a.z)-(b.z-a.z)*(p.x-a.x)>0) break
        h.removeAt(h.lastIndex) };h.add(p) };return h.dropLast(1) }
    return chain(sorted)+chain(sorted.reversed())
  }
}
