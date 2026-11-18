package com.overhand.app.graph.site.floor

import com.overhand.app.graph.site.SiteMesh
import kotlin.math.hypot

/** The same filter is applied to the batched mesh and every once-mode piece before GLB import. */
object FloorDrawing {
  fun covering(m: SiteMesh.Built,triangle: List<Int>)=triangle.all { m.positions[it*3+1]<=0.006001f }
  fun site(m: SiteMesh.Built,floor: Boolean): SiteMesh.Built = if(!floor) m else
    m.copy(indices=m.indices.toList().chunked(3).filterNot { covering(m,it) }.flatten().toIntArray())

  /** Result shapes are world metres. SiteMesh.write converts table +z to native -z, hence negate z here.
   * Outline is a 6 mm ribbon; shadow alpha is 0.35. No slab or filled plot is introduced. */
  fun ground(shapes: List<FloorPlacement.Shape>): SiteMesh.Built {
    val p=ArrayList<Float>();val c=ArrayList<Float>();val ix=ArrayList<Int>()
    fun quad(vs: List<FloorPlacement.V3>,shadow: Boolean) {
      val start=p.size/3
      vs.forEach { v -> p.addAll(listOf(v.x.toFloat(),v.y.toFloat(),-v.z.toFloat()))
        c.addAll(if(shadow) listOf(0f,0f,0f,0.35f) else listOf(.8f,.88f,.9f,1f)) }
      ix.addAll(listOf(start,start+1,start+2,start,start+2,start+3))
    }
    for(s in shapes) if(s.kind=="shadow") quad(s.corners,true) else for(i in s.corners.indices) {
      val a=s.corners[i];val b=s.corners[(i+1)%s.corners.size];val len=hypot(b.x-a.x,b.z-a.z)
      if(len<1e-8) continue
      val dx=-(b.z-a.z)/len*.006;val dz=(b.x-a.x)/len*.006
      quad(listOf(a,b,b.copy(x=b.x+dx,z=b.z+dz),a.copy(x=a.x+dx,z=a.z+dz)),false)
    }
    return SiteMesh.Built(p.toFloatArray(),c.toFloatArray(),ix.toIntArray())
  }
}
