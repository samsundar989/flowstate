package com.overhand.app.graph.site

import org.json.JSONObject
import kotlin.math.*

/** Stable table-space boxes. No SDK hit test, mesh handle, or replay-dependent height. */
object SiteLayers {
  const val MIN_METRES=0.054
  data class Id(val project: String,val module: String)
  data class Info(val name: String,val files: Int=0)
  data class Box(val x0: Double,val y0: Double,val z0: Double,val x1: Double,val y1: Double,val z1: Double) {
    val x get()=(x0+x1)/2;val y get()=(y0+y1)/2;val z get()=(z0+z1)/2
  }
  data class Layer(val id: Id,val name: String,val box: Box)
  data class Hit(val id: Id,val distance: Double)
  fun catalog(architecture: JSONObject,code: (String)->JSONObject): Map<Id,Info> = buildMap {
    val ps=architecture.getJSONArray("projects")
    for(i in 0 until ps.length()) {
      val p=ps.getJSONObject(i);val project=p.getString("id");val c=code(project)
      val associations=c.optJSONArray("components");val modules=c.optJSONArray("modules")
      val parts=p.optJSONArray("components") ?: continue
      for(j in 0 until parts.length()) {
        val part=parts.getJSONObject(j);val id=part.getString("id")
        val a=(0 until (associations?.length() ?: 0)).map { associations!!.getJSONObject(it) }.firstOrNull { it.optString("id")==id }?.optJSONArray("code_ids")
        val ids=(0 until (a?.length() ?: 0)).map { a!!.getString(it) }.toSet()
        val files=(0 until (modules?.length() ?: 0)).map { modules!!.getJSONObject(it) }.filter { m ->
          m.optString("id") in ids || (0 until (m.optJSONArray("sample")?.length() ?: 0)).any { m.getJSONArray("sample").getJSONObject(it).optString("id") in ids }
        }.sumOf { it.optInt("files",1) }
        put(Id(project,id),Info(part.optString("short").ifBlank { part.optString("name",id) },files))
      }
    }
  }
  /** A missing plan is honestly one Tasks layer. Dependencies below dependents; cycles use stable id order. */
  fun boxes(layout: SiteLayout,state: SiteState,scale: Double,info: Map<Id,Info> = emptyMap()): List<Layer> = buildList {
    require(scale>0 && scale.isFinite())
    for(p in layout.plots) {
      val ids=if(p.hasPlan) p.parts.map { it.id }.toMutableSet() else mutableSetOf("tasks")
      val ordered=mutableListOf<String>()
      while(ids.isNotEmpty()) {
        val next=ids.filter { id -> p.paths.none { it.second==id && it.first in ids } }.sorted().firstOrNull() ?: ids.sorted().first()
        ordered.add(next);ids.remove(next)
      }
      var y=0.012
      val sizes=ordered.associateWith { id -> info[Id(p.project,id)]?.files?.takeIf { it>0 } ?: if(id=="tasks") p.parts.sumOf { state.part(p.project,it.id).total }.coerceAtLeast(1) else state.part(p.project,id).total.coerceAtLeast(1) }
      val max=sizes.values.maxOrNull() ?: 1
      val width=min(p.w*0.72,0.20/scale);val depth=min(p.d*0.72,0.20/scale)
      for(id in ordered) {
        val h=(MIN_METRES+0.026*sizes.getValue(id)/max)/scale
        val key=Id(p.project,id);val x=p.x0+p.w/2;val z=p.z0+p.d/2
        add(Layer(key,info[key]?.name ?: if(id=="tasks") "Tasks" else id,Box(x-width/2,y,z-depth/2,x+width/2,y+h,z+depth/2)))
        y+=h+0.006/scale
      }
    }
  }
  fun distance(b: Box,o: DoubleArray,d: DoubleArray): Double? {
    if(o.size!=3 || d.size!=3 || o.any { !it.isFinite() } || d.any { !it.isFinite() } || d.all { abs(it)<1e-12 }) return null
    var near=0.0;var far=Double.POSITIVE_INFINITY
    val lo=doubleArrayOf(b.x0,b.y0,b.z0);val hi=doubleArrayOf(b.x1,b.y1,b.z1)
    for(i in 0..2) {
      if(abs(d[i])<1e-12) { if(o[i]<lo[i] || o[i]>hi[i]) return null }
      else { val a=(lo[i]-o[i])/d[i];val c=(hi[i]-o[i])/d[i];near=max(near,min(a,c));far=min(far,max(a,c));if(far<near) return null }
    }
    return near.takeIf { far>=0 }
  }
  fun hit(layers: List<Layer>,o: DoubleArray,d: DoubleArray): Hit? = layers.mapNotNull { l -> distance(l.box,o,d)?.let { Hit(l.id,it) } }.minByOrNull { it.distance }
  fun pick(layers: List<Layer>,o: DoubleArray,d: DoubleArray): Id? = hit(layers,o,d)?.id
  fun state(l: Layer,layout: SiteLayout,s: SiteState): PartState = if(l.id.module!="tasks") s.part(l.id.project,l.id.module) else {
    val ps=layout.plots.first { it.project==l.id.project }.parts.map { s.part(l.id.project,it.id) }
    PartState(ps.sumOf { it.done },ps.sumOf { it.total },when { ps.any { it.state=="blocked" } -> "blocked";ps.any { it.building } -> "building";ps.any { it.total>0 } && ps.all { it.total==it.done } -> "built";else -> "planned" },ps.sumOf { it.crew })
  }
  fun status(s: PartState)=when { s.state=="blocked" -> "Needs you";s.building -> "Working";s.finished -> "Done";else -> "Untouched" }
  fun line(l: Layer,layout: SiteLayout,s: SiteState): String = s.agents.firstOrNull { it.project==l.id.project && (it.part==l.id.module || l.id.module=="tasks") }?.now?.let { BuildSite.words(it,6).take(37) }?.takeIf { it.isNotBlank() } ?: status(state(l,layout,s))
  fun angularDegrees(height: Double,distance: Double=1.5)=Math.toDegrees(2*atan(height/(2*distance)))
  fun cardBox(l: Layer,scale: Double): Box { val b=l.box;val x=b.x1+.015/scale;val y=max(b.y,.09/scale)
    return Box(x,y-.075/scale,b.z1+.005/scale,x+.32/scale,y+.075/scale,b.z1+.009/scale) }
  fun cardHit(l: Layer,scale: Double,o: DoubleArray,d: DoubleArray): Pair<Double,String>? {
    val b=cardBox(l,scale);val t=distance(b,o,d) ?: return null
    val x=o[0]+t*d[0];val y=o[1]+t*d[1]
    return t to if(y<b.y0+.055/scale) { if(x<b.x) "Code" else "Ask" } else "Card"
  }
  /** Only stable ground and project signs use the old GLB path. */
  fun ground(f: SiteFrame,scale: Double): SiteFrame {
    val layout=SiteLayout(f.layout.plots.map { it.copy(parts=emptyList(),paths=emptyList()) })
    // K6: no text on the line work; each project's name and "n of m built" sit in its glass pill (SiteLook).
    return SiteFrame(layout,SiteState(0,"",emptyMap(),emptyList()),emptyList(),emptySet(),emptyList(),null,null,SiteMode.RECORDED)
  }
}

/** One card, edge-triggered by the caller. Aim on the card counts as staying with its subject. */
class SiteLayerInteraction(private val log: (String)->Unit = {}) {
  var hover: SiteLayers.Id?=null;private set
  var card: SiteLayers.Id?=null;private set
  private var away: Long?=null
  fun aim(id: SiteLayers.Id?,onCard: Boolean,now: Long) {
    if(hover!=id) { hover=id;if(id!=null) event("hover",id) }
    if(card==null || id==card || onCard) away=null
    else { if(away==null) away=now;if(now-away!!>=3000) close() }
  }
  fun pinch(id: SiteLayers.Id?) { if(id==null) close() else { card=id;away=null;event("pinch",id) } }
  fun code(open: (String,String)->Unit) { card?.let { event("code",it);open(it.project,it.module) } }
  fun ask(start: (SiteLayers.Id)->Unit) { card?.let { event("ask",it);start(it) } }
  fun close() { card=null;away=null }
  private fun event(name: String,id: SiteLayers.Id)=log("site event=layer_$name project=${id.project} module=${id.module}")
}
