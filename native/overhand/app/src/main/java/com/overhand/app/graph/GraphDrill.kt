package com.overhand.app.graph

import org.json.JSONObject
import kotlin.math.*

/**
 * #999: the graph's drill-down, as model state (not mesh state), so any scene drawn from a [GraphModel] (the graph,
 * the build site) gets the same three levels: the project, one part (zoomed, its files on a card beside it), and one
 * file's code (in the Code window, opened at the lines that changed). Every step down remembers the framing it left;
 * [back] returns it so the scene restores exactly that pose and zoom.
 */
object GraphDrill {
  enum class Level { PROJECT, PART, CODE }
  /** One real file of a part: at most [MAX_CODE_LINES] lines of it, from the commit that last changed it. */
  data class File(val path: String,val sha: String,val startLine: Int,val endLine: Int,val changed: List<Int>,val text: String)
  /** Root translation, rotation (w,x,y,z) and uniform zoom. */
  data class Framing(val t: Vec,val q: DoubleArray,val zoom: Double) {
    fun distanceTo(o: Framing)=sqrt((t-o.t).dot(t-o.t))
  }
  const val ROWS=7
  const val MAX_CODE_LINES=30
  const val FILE_HINT="Pinch a file to read its contents. Pinch empty space to go back."
  /** Meta's hit-target guidance: at least 2.5 to 3 degrees of visual angle for ray targets. Rows and crumbs use 3. */
  const val NODE_MIN_DEG=2.5
  const val ROW_DEG=3.0
  /** Part level: the focused node spans about this much of the view. */
  const val PART_DEG=40.0

  /** drill/<dataset>.json: part id -> its files. */
  fun parse(json: String,clean: (String) -> String={ it }): Map<String,List<File>> {
    val parts=JSONObject(json).optJSONObject("parts") ?: return emptyMap()
    return parts.keys().asSequence().associateWith { id ->
      val a=parts.getJSONArray(id)
      (0 until a.length()).map { a.getJSONObject(it) }.map { f ->
        val ch=f.optJSONArray("changed")
        File(clean(f.getString("path")),f.optString("sha"),f.getInt("start_line"),f.getInt("end_line"),
          (0 until (ch?.length() ?: 0)).map { ch!!.getInt(it) },clean(f.getString("text")).lines().take(MAX_CODE_LINES).joinToString("\n"))
      }.filter { it.text.isNotBlank() }
    }
  }
  /** #1380: a drill fixture's parts, only when it was recorded for [project] (the fixture holds one project). */
  fun fixtureFor(json: String,project: String,clean: (String) -> String={ it }): Map<String,List<File>>? =
    if(JSONObject(json).optString("project")==project) parse(json,clean) else null
  /** A part's files: the fixture's when it has them, else the code map's (labels with their excerpts where recorded). */
  fun partFiles(fixture: Map<String,List<File>>?,component: JSONObject,code: JSONObject,clean: (String) -> String={ it }): List<File> =
    fixture?.get(component.getString("id"))?.takeIf { it.isNotEmpty() } ?: fromCodeMap(component,code,clean)
  /** The one line a part with no files shows: its description, else its name. */
  fun caption(component: JSONObject,clean: (String) -> String={ it }): String =
    clean(component.optString("desc").ifBlank { component.optString("name").ifBlank { component.optString("id") } }).lines().first().take(80)
  /** Live mode: files from the host's code map (sample labels and excerpts), when no drill fixture exists. */
  fun fromCodeMap(component: JSONObject,code: JSONObject,clean: (String) -> String={ it }): List<File> {
    val modules=code.optJSONArray("modules") ?: return emptyList()
    val d=GraphLod.detail(component,code,clean)
    val excerpts=(0 until modules.length()).mapNotNull { modules.optJSONObject(it)?.optJSONObject("excerpt") }.associateBy { clean(it.optString("path")) }
    return d.files.distinct().map { label ->
      val x=excerpts.entries.firstOrNull { it.key.endsWith(label) || label.endsWith(it.key.substringAfterLast('/')) }?.value
      if(x!=null) File(clean(x.optString("path")),x.optString("sha"),x.optInt("start_line",1),x.optInt("end_line",1),emptyList(),
        clean(x.optString("text")).lines().take(MAX_CODE_LINES).joinToString("\n"))
      else File(label,"",1,1,emptyList(),"")
    }
  }

  /** Zoom that makes a node [GraphLayout.WIDTH] wide span [deg] at [distance] metres. */
  fun partZoom(distance: Double,deg: Double=PART_DEG)=GraphLod.clampZoom(2*distance*tan(Math.toRadians(deg/2))/GraphLayout.WIDTH)
  /** Half-size (graph-local metres) a hit box needs to subtend [deg] at [distance] for a root scaled by [zoom]. */
  fun minHalf(deg: Double,distance: Double,zoom: Double)=distance*tan(Math.toRadians(deg/2))/zoom
  /** Visual angle of a [size]-metre extent at [distance]. */
  fun degrees(size: Double,distance: Double)=Math.toDegrees(2*atan(size/(2*distance)))

  /** A part name as spoken or typed: id, label or full name, case-insensitive; whole match first, then containment. */
  fun resolve(name: String,infos: Map<String,GraphLod.NodeInfo>,names: Map<String,String> =emptyMap()): String? {
    val q=name.trim().lowercase().replace('_',' ')
    if(q.isEmpty()) return null
    fun keys(id: String)=listOfNotNull(id.lowercase().replace('_',' '),infos[id]?.label?.lowercase(),names[id]?.lowercase())
    return infos.keys.firstOrNull { id -> keys(id).any { it==q } } ?: infos.keys.firstOrNull { id -> keys(id).any { it.contains(q) || q.contains(it) } }
  }

  /** A flat target in graph-local metres facing the viewer (files-card rows, page button, breadcrumb segments). */
  data class Rect(val key: String,val x: Double,val y: Double,val z: Double,val halfW: Double,val halfH: Double)
  /**
   * What a graph-local ray points at: a card or breadcrumb [rects] key first (they float in front), then
   * "node:<id>", then "empty" when it crosses the graph's own area (back by pinching empty space), else null.
   */
  fun target(model: GraphModel,page: Int,rects: List<Rect>,o: Vec,d: Vec,minHalf: Double): String? {
    rects.mapNotNull { r -> if(abs(d.z)<1e-9) null else ((r.z-o.z)/d.z).takeIf { it>0 }?.let { t -> val p=o+d*t
      if(abs(p.x-r.x)<=r.halfW && abs(p.y-r.y)<=r.halfH) t to r.key else null } }.minByOrNull { it.first }?.let { return it.second }
    GraphLayout.hit(model,o,d,page,minHalf)?.let { return "node:$it" }
    val nodes=model.nodes.drop(page*GraphLayout.PAGE_SIZE).take(GraphLayout.PAGE_SIZE)
    if(nodes.isEmpty() || abs(d.z)<1e-9) return null
    val t=(nodes.map { it.z }.average()-o.z)/d.z
    if(t<=0) return null
    val p=o+d*t;val m=0.3
    return if(p.x in nodes.minOf { it.x }-GraphLayout.WIDTH/2-m..nodes.maxOf { it.x }+GraphLayout.WIDTH/2+m &&
      p.y in nodes.minOf { it.y }-GraphLayout.HEIGHT/2-m..nodes.maxOf { it.y }+GraphLayout.HEIGHT/2+m) "empty" else null
  }

  /**
   * The files card beside node [n] and the breadcrumb above the graph, as targets, for a root drawn at [zoom] seen
   * from [distance]: each row [ROW_DEG] tall. Row i is "row:i", the page button "more", crumb i "crumb:i".
   */
  fun cardRects(n: GraphNode,rows: Int,paged: Boolean,zoom: Double,distance: Double): List<Rect> {
    val rowH=2*minHalf(ROW_DEG,distance,zoom)*1.1;val w=minHalf(24.0,distance,zoom)
    val x=n.x+GraphLayout.WIDTH/2+0.03/zoom+w;val top=n.y+GraphLayout.HEIGHT/2;val z=n.z-0.05/zoom
    return (0 until rows).map { Rect("row:$it",x,top-rowH*(it+0.5),z,w,rowH/2*0.92) }+
      (if(paged) listOf(Rect("more",x,top-rowH*(rows+0.5),z,w,rowH/2*0.92)) else emptyList())
  }
  fun crumbRects(labels: List<String>,left: Double,top: Double,z: Double,zoom: Double,distance: Double): List<Rect> {
    val h=minHalf(ROW_DEG,distance,zoom)*1.1;var x=left
    return labels.mapIndexed { i,l -> val w=max(minHalf(ROW_DEG*2,distance,zoom),(l.length+3)*0.55*h);val r=Rect("crumb:$i",x+w,top-h,z,w,h);x+=2*w+0.2*h;r }
  }

  /** The text a code view shows: line numbers, a mark on each changed line, at most [MAX_CODE_LINES] lines. */
  fun codeText(f: File): String {
    val lines=f.text.lines().take(MAX_CODE_LINES)
    val w=(f.startLine+lines.size).toString().length
    return lines.mapIndexed { i,l -> val n=f.startLine+i
      (if(n in f.changed) "+" else " ")+n.toString().padStart(w)+"  "+l.replace("\t","  ") }.joinToString("\n")
  }
}

/** The selection state machine. [F] is whatever framing the scene restores (the scene uses [GraphDrill.Framing]). */
class GraphSelection<F>(val projectLabel: String,private val files: (String) -> List<GraphDrill.File>,private val partLabel: (String) -> String={ it }) {
  var level=GraphDrill.Level.PROJECT;private set
  var part: String?=null;private set
  var file: GraphDrill.File?=null;private set
  var page=0;private set
  private val framings=ArrayDeque<F>()
  val depth get()=framings.size

  fun partFiles()=part?.let(files).orEmpty()
  val pages get()=max(1,(partFiles().size+GraphDrill.ROWS-1)/GraphDrill.ROWS)
  /** The rows the files card shows now: at most [GraphDrill.ROWS]. */
  fun rows()=partFiles().drop(page*GraphDrill.ROWS).take(GraphDrill.ROWS)
  fun nextPage() { if(level==GraphDrill.Level.PART) page=(page+1)%pages }

  /** Pinch on a node. From the project, remember [current] and go down; on another part, switch in place. */
  fun select(id: String,current: F): Boolean {
    when(level) {
      GraphDrill.Level.PROJECT -> { framings.addLast(current);level=GraphDrill.Level.PART }
      GraphDrill.Level.PART -> if(id==part) return false
      GraphDrill.Level.CODE -> { framings.removeLast();level=GraphDrill.Level.PART;file=null }
    }
    part=id;page=0;file=null
    return true
  }
  /** Pinch on a files-card row ([row] within the shown page). */
  fun open(row: Int,current: F): GraphDrill.File? {
    if(level!=GraphDrill.Level.PART) return null
    val f=rows().getOrNull(row) ?: return null
    framings.addLast(current);level=GraphDrill.Level.CODE;file=f
    return f
  }
  /** One level up; returns the framing to restore, or null at the project. */
  fun back(): F? {
    if(framings.isEmpty()) return null
    val f=framings.removeLast()
    when(level) {
      GraphDrill.Level.CODE -> { level=GraphDrill.Level.PART;file=null }
      else -> { level=GraphDrill.Level.PROJECT;part=null;page=0;file=null }
    }
    return f
  }
  /** Back up to breadcrumb [index] (0 = project); returns the framing of that level. */
  fun backTo(index: Int): F? {
    var out: F?=null
    while(framings.size>index.coerceAtLeast(0)) out=back()
    return out
  }
  fun reset() { framings.clear();level=GraphDrill.Level.PROJECT;part=null;file=null;page=0 }
  /** "Project > Part > File", one entry per level reached. */
  fun breadcrumb(): List<String> = listOfNotNull(projectLabel,part?.let(partLabel),file?.path?.substringAfterLast('/'))
}
