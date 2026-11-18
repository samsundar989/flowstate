package com.overhand.app.graph

import org.json.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** One unlit, vertex-coloured GLB for all node solids, directed edges and the grab rail. */
object GraphMesh {
  /** t775 tile body: the design panel grey, so white labels keep contrast whatever the state. */
  val TILE=floatArrayOf(.16f,.18f,.21f,1f)
  /**
   * [nodes] (t775) switches a node from a state-filled box to a dark tile with a state ring and a type icon: planned
   * dashed, building and built solid, verified double, failed solid red with a gap (state is not colour alone).
   */
  fun write(file: File, model: GraphModel, page: Int, colours: Map<String,FloatArray>, edgeColours: Map<String,FloatArray> = emptyMap(),
    nodes: Map<String,GraphLod.NodeInfo>? = null, dim: Set<String> = emptySet()) {
    val vertices=mutableListOf<Float>();val coloursOut=mutableListOf<Float>();val indices=mutableListOf<Int>()
    fun solid(points: List<Vec>, colour: FloatArray) {
      val offset=vertices.size/3
      // Spatial SDK's glTF importer applies a Y half-turn. Convert the native
      // layout frame here so solids, Text3D labels and ray hits share coordinates.
      points.forEach { vertices.addAll(listOf(-it.x.toFloat(),it.y.toFloat(),-it.z.toFloat()));coloursOut.addAll(colour.toList()) }
      listOf(0,2,1,0,3,2,4,5,6,4,6,7,0,1,5,0,5,4,2,3,7,2,7,6,0,4,7,0,7,3,1,2,6,1,6,5).forEach { indices.add(offset+it) }
    }
    fun box(x: Double,y: Double,z: Double,w: Double,h: Double,d: Double,c: FloatArray) {
      solid(listOf(Vec(x-w/2,y-h/2,z-d/2),Vec(x+w/2,y-h/2,z-d/2),Vec(x+w/2,y+h/2,z-d/2),Vec(x-w/2,y+h/2,z-d/2),
        Vec(x-w/2,y-h/2,z+d/2),Vec(x+w/2,y-h/2,z+d/2),Vec(x+w/2,y+h/2,z+d/2),Vec(x-w/2,y+h/2,z+d/2)),c)
    }
    fun rod(a: Vec,b: Vec,width: Double,c: FloatArray) {
      val dx=b.x-a.x;val dy=b.y-a.y;val length=hypot(dx,dy).coerceAtLeast(1e-6)
      val nx=-dy/length*width/2;val ny=dx/length*width/2;val z=width/2
      solid(listOf(Vec(a.x+nx,a.y+ny,a.z-z),Vec(b.x+nx,b.y+ny,b.z-z),Vec(b.x-nx,b.y-ny,b.z-z),Vec(a.x-nx,a.y-ny,a.z-z),
        Vec(a.x+nx,a.y+ny,a.z+z),Vec(b.x+nx,b.y+ny,b.z+z),Vec(b.x-nx,b.y-ny,b.z+z),Vec(a.x-nx,a.y-ny,a.z+z)),c)
    }
    val visible=model.nodes.drop(page*GraphLayout.PAGE_SIZE).take(GraphLayout.PAGE_SIZE)
    val byId=visible.associateBy { it.id };val edgeColour=floatArrayOf(0.28f,0.66f,0.78f,1f)
    model.edges.forEach { e ->
      val a=byId[e.from];val b=byId[e.to]
      if(a!=null && b!=null) {
        val edgeColour=edgeColours[e.from] ?: edgeColour
        // A dedicated depth lane keeps crossing edges off the label surfaces.
        val (start,p,q,end)=GraphLod.edgePath(a,b,model.edges.indexOf(e))
        val w=if(nodes!=null) 0.009 else 0.006
        rod(start,p,w,edgeColour);rod(p,q,w,edgeColour);rod(q,end,w,edgeColour)
        rod(Vec(end.x-0.025,end.y+0.018,end.z),end,0.008,edgeColour)
        rod(Vec(end.x-0.025,end.y-0.018,end.z),end,0.008,edgeColour)
      }
    }
    visible.forEach { n ->
      val info=nodes?.get(n.id)
      // #999: at the part level every other node is drawn at 35 % so the focused part stands out.
      fun dimmed(c: FloatArray)=if(n.id in dim) floatArrayOf(c[0]*.35f,c[1]*.35f,c[2]*.35f,c[3]) else c
      if(info==null) { box(n.x,n.y,n.z,GraphLayout.WIDTH,GraphLayout.HEIGHT,GraphLayout.DEPTH,colours[n.id] ?: floatArrayOf(.2f,.3f,.4f,1f));return@forEach }
      box(n.x,n.y,n.z,GraphLayout.WIDTH,GraphLayout.HEIGHT,GraphLayout.DEPTH,dimmed(TILE))
      val ring=dimmed(colours[n.id] ?: GraphLod.ringColour(info.state))
      val cx=n.x+GraphLod.RING_X;val cy=n.y+GraphLod.RING_Y;val z=n.z-GraphLayout.DEPTH/2-0.004
      ringSegments(info.state).forEach { (a0,a1,r) -> rod(Vec(cx+r*cos(a0),cy+r*sin(a0),z),Vec(cx+r*cos(a1),cy+r*sin(a1),z),0.008,ring) }
      val k=GraphLod.RING_R*0.5
      GraphLabels.icon(info.kind).forEach { s -> rod(Vec(cx+s[0]*k,cy+s[1]*k,z),Vec(cx+s[2]*k,cy+s[3]*k,z),0.006,dimmed(floatArrayOf(.95f,.96f,.97f,1f))) }
    }
    val bottom=(visible.minOfOrNull { it.y } ?: 0.0)-0.30
    box(0.0,bottom,0.0,0.65,0.065,0.065,floatArrayOf(.3f,.5f,.6f,1f))
    write(file,vertices,coloursOut,indices)
  }
  /** Ring as straight segments (start angle, end angle, radius): the state's shape. Shared with the render. */
  fun ringSegments(state: String): List<Triple<Double,Double,Double>> {
    val n=20;val r=GraphLod.RING_R;val step=2*PI/n
    val solid=(0 until n).map { Triple(it*step,(it+1)*step,r) }
    return when(GraphLod.state(state)) {
      "planned" -> solid.filterIndexed { i,_ -> i%2==0 }
      "verified" -> solid+(0 until n).map { Triple(it*step,(it+1)*step,r*0.78) }
      "failed" -> solid.filterIndexed { i,_ -> i!=0 && i!=n-1 && i!=n/2 && i!=n/2-1 }
      else -> solid
    }
  }
  private fun write(file: File,vertices: List<Float>,coloursOut: List<Float>,indices: List<Int>) {
    val posBytes=vertices.size*4;val colourBytes=coloursOut.size*4;val indexBytes=indices.size*4
    val binary=ByteBuffer.allocate(posBytes+colourBytes+indexBytes).order(ByteOrder.LITTLE_ENDIAN)
    vertices.forEach { binary.putFloat(it) };coloursOut.forEach { binary.putFloat(it) };indices.forEach { binary.putInt(it) }
    val min=(0..2).map { axis -> vertices.filterIndexed { i,_ -> i%3==axis }.minOrNull() ?: 0f }
    val max=(0..2).map { axis -> vertices.filterIndexed { i,_ -> i%3==axis }.maxOrNull() ?: 0f }
    val views=JSONArray().put(JSONObject().put("buffer",0).put("byteOffset",0).put("byteLength",posBytes))
      .put(JSONObject().put("buffer",0).put("byteOffset",posBytes).put("byteLength",colourBytes))
      .put(JSONObject().put("buffer",0).put("byteOffset",posBytes+colourBytes).put("byteLength",indexBytes))
    fun accessor(view: Int,count: Int,type: String,component: Int)=JSONObject().put("bufferView",view).put("componentType",component).put("count",count).put("type",type)
    val accessors=JSONArray().put(accessor(0,vertices.size/3,"VEC3",5126).put("min",JSONArray(min)).put("max",JSONArray(max)))
      .put(accessor(1,coloursOut.size/4,"VEC4",5126)).put(accessor(2,indices.size,"SCALAR",5125))
    val json=JSONObject().put("asset",JSONObject().put("version","2.0"))
      .put("extensionsUsed",JSONArray(listOf("KHR_materials_unlit")))
      .put("scene",0).put("scenes",JSONArray().put(JSONObject().put("nodes",JSONArray(listOf(0)))))
      .put("nodes",JSONArray().put(JSONObject().put("mesh",0)))
      .put("meshes",JSONArray().put(JSONObject().put("primitives",JSONArray().put(JSONObject()
        .put("attributes",JSONObject().put("POSITION",0).put("COLOR_0",1)).put("indices",2).put("material",0)))))
      .put("materials",JSONArray().put(JSONObject().put("doubleSided",true).put("extensions",JSONObject().put("KHR_materials_unlit",JSONObject()))
        .put("pbrMetallicRoughness",JSONObject().put("metallicFactor",0).put("roughnessFactor",1))))
      .put("buffers",JSONArray().put(JSONObject().put("byteLength",binary.capacity())))
      .put("bufferViews",views).put("accessors",accessors).toString().toByteArray()
    val jsonSize=(json.size+3)/4*4
    val glb=ByteBuffer.allocate(12+8+jsonSize+8+binary.capacity()).order(ByteOrder.LITTLE_ENDIAN)
    glb.putInt(0x46546c67);glb.putInt(2);glb.putInt(glb.capacity());glb.putInt(jsonSize);glb.putInt(0x4e4f534a);glb.put(json)
    repeat(jsonSize-json.size) { glb.put(32.toByte()) };glb.putInt(binary.capacity());glb.putInt(0x004e4942);glb.put(binary.array())
    file.writeBytes(glb.array())
  }
}
