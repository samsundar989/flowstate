package com.overhand.app.graph

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/**
 * t775: the graph's levels of detail, as pure geometry the scene, the off-device render and the tests share.
 * Everything is in graph-local metres (the native Text3D frame, x right, y up) at zoom 1; the scene scales the
 * root by the zoom. An item is drawn only while its glyphs subtend at least [MIN_DEG] (the design caption floor),
 * fading in over the next 10 % (no fade-out: half-transparent text would fail contrast). Levels: SYSTEM (every node: label and number), then
 * for the focused node only MODULE, FILE and CODE columns inside its tile, each set in a smaller font so it
 * becomes readable at a deeper zoom.
 */
object GraphLod {
  enum class Level { SYSTEM, MODULE, FILE, CODE }
  /** Design doc §2.1 caption: nothing smaller. Same constant as FlowstateTokens.TYPE_CAPTION_DEG (tested). */
  const val MIN_DEG=1.05
  const val MAX_DEG=60.0
  const val MIN_ZOOM=0.5
  const val MAX_ZOOM=8.0
  const val EASE_MS=250L

  // Text3D font sizes (em, metres). Calibrated glyph advance about 0.43 em (LayoutLintCapture, headset frame).
  const val LABEL_EM=0.048
  const val MODULE_EM=0.024
  const val FILE_EM=0.0135
  const val CODE_EM=0.0080
  const val ADVANCE_EM=0.43
  // Text3D anchor to glyph box, in em, calibrated on the Nov 6 headset frame: glyph top 1.21 em above the
  // anchor, the line's lowest ink 0.25 em above it, first glyph 0.68 em right of it.
  const val TOP_EM=1.21
  const val BOTTOM_EM=0.25
  const val START_EM=0.68

  /** Columns inside a tile (node-centre offsets): [left, right, top]. */
  private val COLUMNS=mapOf(Level.MODULE to doubleArrayOf(-0.205,-0.065,0.030),Level.FILE to doubleArrayOf(-0.055,0.070,0.030),
    Level.CODE to doubleArrayOf(0.080,0.205,0.030))
  private const val DETAIL_BOTTOM=-0.145
  const val RING_X=-0.170
  const val RING_Y=0.095
  const val RING_R=0.040
  private const val TEXT_X=-0.125

  data class Detail(val modules: List<String>,val files: List<String>,val code: List<String>)
  data class NodeInfo(val id: String,val label: String,val kind: GraphLabels.Kind,val state: String,val number: String?,val detail: Detail)
  /** One line of text: [x],[y] is the glyph box's top-left in graph-local metres; [em] its font size. */
  data class Item(val key: String,val node: String,val level: Level,val text: String,val x: Double,val y: Double,val z: Double,val em: Double) {
    val width get()=text.length*ADVANCE_EM*em
    val height get()=(TOP_EM-BOTTOM_EM)*em
    /** Where Text3D must be anchored so its glyph box starts at (x, y). */
    val anchorX get()=x-START_EM*em
    val anchorY get()=y-TOP_EM*em
  }

  fun em(level: Level)=when(level) { Level.SYSTEM -> LABEL_EM;Level.MODULE -> MODULE_EM;Level.FILE -> FILE_EM;Level.CODE -> CODE_EM }
  /** Max characters per line in each column, from its width and the calibrated advance. */
  fun maxChars(level: Level): Int=if(level==Level.SYSTEM) GraphLabels.MAX else COLUMNS.getValue(level).let { ((it[1]-it[0])/(ADVANCE_EM*em(level))).toInt() }

  /** Every item that could be drawn, at zoom 1, for the visible page; detail columns only for [focus]. */
  fun items(model: GraphModel,info: Map<String,NodeInfo>,page: Int,focus: String?): List<Item> {
    val out=mutableListOf<Item>()
    model.nodes.drop(page*GraphLayout.PAGE_SIZE).take(GraphLayout.PAGE_SIZE).forEach { n ->
      val i=info[n.id] ?: return@forEach
      val z=n.z-0.03
      out.add(Item("label:${n.id}",n.id,Level.SYSTEM,i.label,n.x+TEXT_X,n.y+0.140,z,LABEL_EM))
      i.number?.let { out.add(Item("number:${n.id}",n.id,Level.SYSTEM,it,n.x+TEXT_X,n.y+0.085,z,LABEL_EM)) }
      if(n.id!=focus) return@forEach
      listOf(Level.MODULE to i.detail.modules,Level.FILE to i.detail.files,Level.CODE to i.detail.code).forEach { (level,lines) ->
        val c=COLUMNS.getValue(level);val e=em(level);val pitch=(TOP_EM-BOTTOM_EM)*e*1.25
        val rows=((c[2]-DETAIL_BOTTOM)/pitch).toInt()
        lines.take(rows).forEachIndexed { row,text -> out.add(Item("${level.name.lowercase()}:${n.id}:$row",n.id,level,text,n.x+c[0],n.y+c[2]-row*pitch,z,e)) }
      }
    }
    return out
  }

  /** Visual angle of an item's em at [distance] metres, for a root scaled by [zoom]. */
  fun degrees(em: Double,zoom: Double,distance: Double)=Math.toDegrees(2*atan(em*zoom/(2*distance)))
  /** 0 = not drawn; fades in over the first 10 % above the floor. */
  fun alpha(em: Double,zoom: Double,distance: Double): Double {
    val a=degrees(em,zoom,distance)
    if(a<MIN_DEG || a>MAX_DEG) return 0.0
    return min(1.0,((a-MIN_DEG)/(0.10*MIN_DEG)).coerceAtLeast(0.05))
  }
  /** The zoom at which [level] becomes readable at [distance] (its fade begins). */
  fun threshold(level: Level,distance: Double)=2*distance*tan(Math.toRadians(MIN_DEG)/2)/em(level)
  /** Pinch on the focused node: the next level that is not yet readable, else back to 1. */
  fun nextZoom(zoom: Double,distance: Double): Double {
    val next=listOf(Level.SYSTEM,Level.MODULE,Level.FILE,Level.CODE).map { threshold(it,distance)*1.3 }.firstOrNull { it>zoom*1.05 }
    return if(next==null || next>MAX_ZOOM) (if(zoom>=MAX_ZOOM*0.95) 1.0 else MAX_ZOOM) else clampZoom(next)
  }
  fun clampZoom(z: Double)=z.coerceIn(MIN_ZOOM,MAX_ZOOM)
  /** Deepest level any item is drawn at, for logs and the smoke. */
  fun level(zoom: Double,distance: Double)=Level.values().lastOrNull { threshold(it,distance)<=zoom } ?: Level.SYSTEM

  // ---- Zoom about a point --------------------------------------------------------------------------------------
  /**
   * The root's new translation when its uniform scale goes from [s0] to [s1] while the world point [anchor] stays
   * where it is (rotation unchanged): world = T + R(s q), so T1 = anchor + (T0 - anchor) s1/s0.
   */
  fun about(root: Vec,anchor: Vec,s0: Double,s1: Double)=anchor+(root-anchor)*(s1/s0)
  /** FastOutSlowIn-like ease (cubic out), t in 0..1. */
  fun ease(t: Double): Double { val u=t.coerceIn(0.0,1.0);return 1-(1-u).pow(3) }
  /** Two-hand spread: scale follows the ratio of hand separation, clamped. */
  fun spread(s0: Double,d0: Double,d1: Double)=clampZoom(s0*d1/max(d0,0.02))

  // ---- Node data --------------------------------------------------------------------------------------------------
  /** The state word a ring shows: the five design states (verified is built, drawn as a double ring). */
  fun state(status: String)=when(status) {
    "verified" -> "verified";"built","done","success" -> "built";"building","active","claimed","partial" -> "building"
    "blocked","at_risk","failed" -> "failed";else -> "planned"
  }
  /** Design §2.3 state colours (#8A99A8, #47A5FA, #6EE146, #F05F69), as RGB floats. */
  fun ringColour(state: String): FloatArray=when(state(state)) {
    "building" -> rgb(0x47A5FA);"built","verified" -> rgb(0x6EE146);"failed" -> rgb(0xF05F69);else -> rgb(0x8A99A8)
  }
  fun rgb(c: Int)=floatArrayOf((c shr 16 and 255)/255f,(c shr 8 and 255)/255f,(c and 255)/255f,1f)
  /** A glanceable number: open tasks when the plan lists tasks, else percent while not finished. No words. */
  fun number(c: JSONObject): String? {
    val tasks=c.optJSONArray("tasks")
    if(tasks!=null && tasks.length()>0) {
      val open=(0 until tasks.length()).count { tasks.optJSONObject(it)?.optString("status") !in setOf("done","success","verified","built","cancelled") }
      return if(open>0) "$open open" else null
    }
    val pct=c.optDouble("pct",Double.NaN)
    return if(!pct.isNaN() && pct>0 && pct<1) "${(pct*100).roundToInt()}%" else null
  }
  private fun rows(a: JSONArray?)=(0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it) }
  /** Redacted or placeholder text (the recording's privacy gate) is never drawn as a module or file name. */
  private fun usable(s: String)=s.isNotBlank() && !s.contains('[') && !s.trim().startsWith("(") && !s.contains("Recorded detail",true) && !s.contains("not recorded",true)
  /** Module, file and code lines for one component from the project's code map (Recorded or Live). */
  fun detail(component: JSONObject,code: JSONObject,clean: (String) -> String={ it }): Detail {
    val id=component.getString("id")
    val linked=rows(code.optJSONArray("components")).firstOrNull { it.optString("id")==id }?.optJSONArray("code_ids")
      ?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
    val paths=component.optJSONArray("paths")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
    val modules=rows(code.optJSONArray("modules")).filter { m -> m.optString("id") in linked ||
      rows(m.optJSONArray("sample")).any { it.optString("id") in linked } ||
      paths.any { p -> rows(m.optJSONArray("sample")).any { ArchitectureGraph.matchesPath(it.optString("label"),p) } } }
    val files=modules.flatMap { m -> listOfNotNull(m.optJSONObject("excerpt")?.optString("path"))+rows(m.optJSONArray("sample")).map { it.optString("label") } }
      .map(clean).filter(::usable).distinct()
    val dirs=files.mapNotNull { f -> f.replace('\\','/').substringBeforeLast('/',"").takeIf { it.isNotBlank() }?.substringAfterLast('/') }
    val moduleNames=(dirs+modules.map { clean(it.optString("label")) }.filter(::usable).filter { it!=component.optString("name") })
      .map { GraphLabels.shorten(it,maxChars(Level.MODULE)) }.distinct()
    val fileNames=files.map { GraphLabels.shorten(it,maxChars(Level.FILE)) }.distinct()
    val code=modules.flatMap { m ->
      val excerpt=m.optJSONObject("excerpt")?.optString("text")?.lines().orEmpty().map { it.trimEnd() }.filter { it.isNotBlank() }
      excerpt+rows(m.optJSONArray("top")).map { "${it.optString("name")} · ${it.optString("kind")} · ${it.optInt("used_by")} refs" }
    }.map { line(clean(it),maxChars(Level.CODE)) }.filter { it.isNotBlank() }
    return Detail(moduleNames,fileNames,code)
  }
  /** A code line cut at the last whole token that fits, indentation kept; never an ellipsis. */
  fun line(text: String,max: Int): String {
    val t=text.replace("\t","  ")
    if(t.length<=max) return t
    val cut=t.lastIndexOf(' ',max).takeIf { it>t.indexOfFirst { c -> c!=' ' } } ?: max
    return t.substring(0,cut).trimEnd()
  }
  /**
   * Every component's node info for one project. Two nodes never share a label: on a clash, the later one takes
   * its other whole-word form (suffix instead of prefix), else its id's form.
   */
  fun infos(components: List<JSONObject>,code: JSONObject,clean: (String) -> String={ it },states: Map<String,String> =emptyMap()): Map<String,NodeInfo> {
    val used=mutableSetOf<String>()
    return components.associate { c ->
      var i=info(c,code,clean,states[c.getString("id")])
      if(!used.add(i.label.lowercase())) {
        val raw=clean(c.optString("short").ifBlank { c.optString("name").ifBlank { c.getString("id") } })
        val alt=listOf(GraphLabels.shorten(raw,preferSuffix=true),GraphLabels.shorten(clean(c.getString("id"))),GraphLabels.shorten(clean(c.optString("name")),preferSuffix=true))
          .firstOrNull { it.isNotBlank() && it.lowercase() !in used }
        if(alt!=null) { i=i.copy(label=alt);used.add(alt.lowercase()) }
      }
      c.getString("id") to i
    }
  }
  fun info(c: JSONObject,code: JSONObject,clean: (String) -> String={ it },liveState: String?=null): NodeInfo {
    val raw=clean(c.optString("short").ifBlank { c.optString("name").ifBlank { c.getString("id") } })
    val status=liveState ?: c.optString("shown",c.optString("status"))
    return NodeInfo(c.getString("id"),GraphLabels.shorten(raw),GraphLabels.kind(c.optString("kind"),c.getString("id"),c.optString("name")),
      state(status),number(c),detail(c,code,clean))
  }

  // ---- Edges and workers -----------------------------------------------------------------------------------------
  /** An edge's polyline (native frame): out of the source's right face, along a depth lane, into the target. */
  fun edgePath(a: GraphNode,b: GraphNode,index: Int): List<Vec> {
    val start=Vec(a.x+GraphLayout.WIDTH/2,a.y,a.z+0.025);val end=Vec(b.x-GraphLayout.WIDTH/2,b.y,b.z+0.025)
    val lane=0.09+0.012*(index%5)
    return listOf(start,Vec(start.x+0.025,start.y,lane),Vec(end.x-0.025,end.y,lane),end)
  }
  /** Point at fraction [t] of a polyline's length. */
  fun along(path: List<Vec>,t: Double): Vec {
    val lengths=path.zipWithNext { p,q -> sqrt((q-p).dot(q-p)) };val total=lengths.sum().coerceAtLeast(1e-9)
    var d=t.coerceIn(0.0,1.0)*total
    lengths.forEachIndexed { i,l -> if(d<=l || i==lengths.lastIndex) return path[i]+(path[i+1]-path[i])*(if(l<1e-9) 0.0 else (d/l).coerceAtMost(1.0));d-=l }
    return path.last()
  }
  /** The path a worker takes from node [from] to node [to]: the edge if one joins them, else a straight hop. */
  fun workerPath(model: GraphModel,from: String,to: String): List<Vec>? {
    val byId=model.nodes.associateBy { it.id };val a=byId[from] ?: return null;val b=byId[to] ?: return null
    val i=model.edges.indexOfFirst { it.from==from && it.to==to }
    if(i>=0) return edgePath(a,b,i)
    val j=model.edges.indexOfFirst { it.from==to && it.to==from }
    if(j>=0) return edgePath(b,a,j).asReversed()
    return listOf(Vec(a.x,a.y-0.12,a.z+0.05),Vec(b.x,b.y-0.12,b.z+0.05))
  }
  /** Building halo alpha (design §2.5): 0.20 to 0.70 over 1600 ms, ease-in-out sine. */
  fun breath(ms: Long)=0.20+0.50*(0.5-0.5*cos(2*PI*(ms%1600)/1600.0))
  /** #944: [breath] quantised to [steps] levels (0 = dimmest), and the breath value of a level. */
  fun breathStep(ms: Long,steps: Int)=Math.round((breath(ms)-0.20)/0.50*(steps-1)).toInt().coerceIn(0,steps-1)
  fun breathAt(step: Int,steps: Int)=0.20+0.50*step/(steps-1)
}
