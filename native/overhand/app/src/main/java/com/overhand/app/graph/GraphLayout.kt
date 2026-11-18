package com.overhand.app.graph

import kotlin.math.*
import java.io.File

data class Component(val id: String, val after: List<String>)
data class GraphNode(val id: String, val layer: Int, val x: Double, val y: Double, val z: Double)
data class GraphEdge(val from: String, val to: String)
data class GraphModel(val nodes: List<GraphNode>, val edges: List<GraphEdge>)
data class Vec(val x: Double, val y: Double, val z: Double) {
  operator fun minus(b: Vec)=Vec(x-b.x,y-b.y,z-b.z)
  operator fun plus(b: Vec)=Vec(x+b.x,y+b.y,z+b.z)
  operator fun times(s: Double)=Vec(x*s,y*s,z*s)
  fun dot(b: Vec)=x*b.x+y*b.y+z*b.z
  fun cross(b: Vec)=Vec(y*b.z-z*b.y,z*b.x-x*b.z,x*b.y-y*b.x)
  fun unit(): Vec { val length=sqrt(dot(this));require(length>1e-9);return this*(1.0/length) }
}

/** Stable longest-path layers with barycentric ordering. Geometry and hit testing share metre units. */
object GraphLayout {
  const val WIDTH=0.44
  const val HEIGHT=0.31
  const val DEPTH=0.035
  const val PAGE_SIZE=32
  data class LabelFrame(val right: Vec,val up: Vec,val back: Vec)
  /** Text3D's readable face is local -Z. Project camera-up, never flip glyph X. */
  fun labelFrame(center: Vec,viewer: Vec,cameraUp: Vec): LabelFrame {
    val back=(center-viewer).unit()
    val projected=cameraUp-back*cameraUp.dot(back)
    val up=if(projected.dot(projected)>1e-12) projected.unit()
      else (Vec(0.0,0.0,1.0)-back*back.z).unit()
    return LabelFrame(up.cross(back).unit(),up,back)
  }
  /** All eight solid corners, not just centres, for framing and regression checks. */
  fun corners(model: GraphModel,page: Int=0)=model.nodes.drop(page*PAGE_SIZE).take(PAGE_SIZE).flatMap { n ->
    listOf(-1,1).flatMap { x -> listOf(-1,1).flatMap { y -> listOf(-1,1).map { z ->
      Vec(n.x+x*WIDTH/2,n.y+y*HEIGHT/2,n.z+z*DEPTH/2)
    } } }
  }
  // 60-degree safe view volume, plus 40 cm head movement and 20 cm depth margin.
  fun viewingDistance(model: GraphModel,page: Int=0): Double {
    val radius=corners(model,page).maxOfOrNull { max(abs(it.x),abs(it.y)) } ?: 0.0
    return max(1.5,(radius+0.4)/tan(PI/6)+0.2)
  }
  fun build(input: List<Component>): GraphModel {
    require(input.map { it.id }.distinct().size==input.size) { "Duplicate component IDs" }
    val cs=input.sortedBy { it.id }
    val ids=cs.map { it.id }.toSet()
    require(cs.all { c -> c.after.all { it in ids } }) { "Unknown dependency" }
    val edges=cs.flatMap { c -> c.after.distinct().sorted().map { GraphEdge(it,c.id) } }
    val layers=mutableMapOf<String,Int>()
    val pending=cs.toMutableList()
    while(pending.isNotEmpty()) {
      val ready=pending.filter { c -> c.after.all { it in layers } }
      // Preserve every node and edge even for a cyclic plan; unresolved nodes share a final tier.
      if(ready.isEmpty()) { val tier=(layers.values.maxOrNull() ?: -1)+1;pending.forEach { layers[it.id]=tier };break }
      ready.forEach { c -> layers[c.id]=(c.after.maxOfOrNull { layers.getValue(it) } ?: -1)+1 }
      pending.removeAll(ready.toSet())
    }
    val groups=cs.groupBy { layers.getValue(it.id) }.toSortedMap().mapValues { (_,v) -> v.map { it.id }.toMutableList() }
    repeat(4) { pass ->
      val ranks=groups.values.flatMap { v -> v.mapIndexed { i,id -> id to i.toDouble() } }.toMap()
      val order=if(pass%2==0) groups.keys.toList() else groups.keys.reversed()
      order.forEach { layer ->
        groups.getValue(layer).sortWith(compareBy<String> { id ->
          val neighbours=edges.filter { if(pass%2==0) it.to==id else it.from==id }.map { if(pass%2==0) it.from else it.to }
          if(neighbours.isEmpty()) ranks.getValue(id) else neighbours.map { ranks.getValue(it) }.average()
        }.thenBy { it })
      }
    }
    val maxLayer=layers.values.maxOrNull() ?: 0
    val nodes=groups.flatMap { (layer,rows) -> rows.mapIndexed { row,id ->
      GraphNode(id,layer,(layer-maxLayer/2.0)*0.54,((rows.size-1)/2.0-row)*0.36,-0.025*(layer%2))
    } }
    return GraphModel(nodes,edges)
  }

  /** Slab ray/AABB intersection; graph-local ray after inverse root pose, nearest positive hit.
   *  #999: [minHalf] widens each box's half width and height to at least that (graph-local metres), so every node
   *  stays at least GraphDrill.NODE_MIN_DEG wide however far or small it is drawn. */
  fun hit(model: GraphModel, origin: Vec, direction: Vec, page: Int=0, minHalf: Double=0.0): String? {
    if(listOf(origin.x,origin.y,origin.z,direction.x,direction.y,direction.z).any { !it.isFinite() } ||
      direction.x*direction.x+direction.y*direction.y+direction.z*direction.z<1e-12) return null
    var best=Double.POSITIVE_INFINITY
    var result: String?=null
    model.nodes.drop(page*PAGE_SIZE).take(PAGE_SIZE).forEach { n ->
      var near=0.0;var far=Double.POSITIVE_INFINITY
      val o=doubleArrayOf(origin.x,origin.y,origin.z);val d=doubleArrayOf(direction.x,direction.y,direction.z)
      val c=doubleArrayOf(n.x,n.y,n.z);val h=doubleArrayOf(max(WIDTH/2,minHalf),max(HEIGHT/2,minHalf),DEPTH/2)
      for(i in 0..2) {
        if(abs(d[i])<1e-10) { if(abs(o[i]-c[i])>h[i]) far=-1.0 }
        else { val a=(c[i]-h[i]-o[i])/d[i];val b=(c[i]+h[i]-o[i])/d[i];near=max(near,min(a,b));far=min(far,max(a,b)) }
      }
      if(far>=near && near<best) { best=near;result=n.id }
    }
    return result
  }

  /** JVM verification bridge uses this exact production layout, never a Python reimplementation. */
  @JvmStatic fun main(args: Array<String>) {
    val input=File(args[0]).readLines().filter { it.isNotBlank() }.map { line ->
      val p=line.split('\t');Component(p[0],p.drop(1).filter { it.isNotBlank() })
    }
    val model=build(input)
    model.nodes.forEach { println("N\t${it.id}\t${it.layer}\t${it.x}\t${it.y}\t${it.z}") }
    model.edges.forEach { println("E\t${it.from}\t${it.to}") }
  }
}
