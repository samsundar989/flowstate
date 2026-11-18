package com.overhand.app.mr

import kotlin.math.*

/** Metres in LOCAL_FLOOR. No Android/SDK dependency: the geometry is independently testable. */
object DeskPlacement {
  data class Point(val x: Float, val y: Float, val z: Float) {
    operator fun plus(b: Point) = Point(x+b.x,y+b.y,z+b.z)
    operator fun minus(b: Point) = Point(x-b.x,y-b.y,z-b.z)
    operator fun times(s: Float) = Point(x*s,y*s,z*s)
    fun finite() = x.isFinite() && y.isFinite() && z.isFinite()
    fun distance(b: Point) = sqrt((x-b.x).pow(2)+(y-b.y).pow(2)+(z-b.z).pow(2))
  }
  data class Placement(val position: Point, val yaw: Float)
  data class Frame(val far: Point, val near: Point, val forward: Point, val eyeHeight: Float,
    val desk: Boolean = true) {
    private val right get() = Point(forward.z,0f,-forward.x)
    private fun facing(p: Point, eye: Point) = Placement(p,
      Math.toDegrees(atan2(p.x-eye.x,p.z-eye.z).toDouble()).toFloat())
    fun window(x: Float, y: Float, depth: Float, eye: Point): Placement {
      // Two virtual-room rows would intersect a real desk. Spread the five dashboard
      // windows around its far edge at eye height; Settings sits above the centre.
      val angle=if(y>0.5f) 0f else atan2(x,depth)
      val height = max(eyeHeight,card(eye).position.y+0.9f)+(if(y>0.5f) 0.95f else 0f)
      val delta=far-eye
      val radius=sqrt(delta.x*delta.x+delta.z*delta.z).coerceAtLeast(1.4f)

      val p=far+right*(radius*sin(angle))+forward*(radius*(cos(angle)-1f))
      return facing(Point(p.x,height,p.z),eye)
    }
    fun card(eye: Point): Placement {
      val p=if(desk) near+forward*0.18f else eye+forward*0.85f
      return facing(Point(p.x,if(desk) near.y+0.40f else eye.y-0.2f,p.z),eye)
    }
    fun station(index: Int, count: Int, eye: Point): Placement {
      require(count>0 && index in 0 until count)
      val angle=Math.toRadians(((index-(count-1)/2f)*min(12f,72f/max(1,count-1))).toDouble()).toFloat()
      val p=far+right*(3.2f*sin(angle))+forward*(3.2f*(cos(angle)-1f))
      return facing(Point(p.x,if(desk) far.y+0.15f else eyeHeight-0.4f,p.z),eye)
    }
  }

  /** Four ordered tabletop corners, derived from MRUK bounds in world space. */
  fun desk(corners: List<Point>, eye: Point): Frame? {
    if(corners.size!=4 || !eye.finite() || corners.any { !it.finite() }) return null
    val top=corners.map { it.y }.average().toFloat()
    if(top !in 0.35f..1.5f || corners.any { abs(it.y-top)>0.08f }) return null
    val center=corners.reduce { a,b -> a+b }*0.25f
    val sorted=corners.sortedBy { atan2(it.z-center.z,it.x-center.x) }
    val edges=sorted.indices.map { (sorted[it]+sorted[(it+1)%4])*0.5f }
    val nearest=edges.indices.minBy { edges[it].distance(eye) }
    val near=edges[nearest]; val far=edges[(nearest+2)%4]
    val d=far-near; val length=sqrt(d.x*d.x+d.z*d.z)
    if(length<0.25f || length>3f || center.distance(eye)>4f) return null
    return Frame(far,near,Point(d.x/length,0f,d.z/length),max(eye.y,top+0.45f))
  }

  fun wall(center: Point, normal: Point, eye: Point): Frame? {
    if(!center.finite() || !normal.finite() || !eye.finite() || center.distance(eye)>4f) return null
    val length=sqrt(normal.x*normal.x+normal.z*normal.z)
    if(length<0.9f || abs(normal.y)>0.15f) return null
    var toward=Point(normal.x/length,0f,normal.z/length)
    val toEye=eye-center
    if(toward.x*toEye.x+toward.z*toEye.z<0f) toward=toward*-1f
    val p=center+toward*0.06f
    return Frame(p,p,toward*-1f,eye.y,false)
  }

  /** Surface fallback is independent of passthrough: a room scan is optional. */
  fun fallback(enabled: Boolean, permission: Boolean, timedOut: Boolean, surface: Frame?) =
    !enabled || !permission || timedOut || surface==null
}
