package com.overhand.app.graph.site

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/**
 * #1003 the site as ONE unlit, vertex-coloured mesh (table, plots, blocks, scaffolding, paths, cranes). Shading is baked
 * per face (top 1.0, sides 0.78 / 0.62) because the material is unlit. Rebuilt only when the SiteState changes.
 */
object SiteMesh {
  /** Full height of a part whose tasks are all done. */
  const val BLOCK_H=0.20
  const val FOOT=0.075
  const val MAX_TRIANGLES=60_000
  const val CRANE_H=0.36

  data class Built(val positions: FloatArray,val colours: FloatArray,val indices: IntArray) { val triangles get()=indices.size/3 }
  /** [size] 0 means the kind's default Text3D size ([size] of the kind). */
  data class Label(val text: String,val x: Double,val y: Double,val z: Double,val kind: String,val agent: String?=null,val project: String?=null,
    val size: Double=0.0)
  data class Crane(val agent: SiteAgent,val baseX: Double,val baseZ: Double,val hookX: Double,val hookZ: Double,val hookY: Double)

  private fun rgb(r: Int,g: Int,b: Int)=floatArrayOf(r/255f,g/255f,b/255f)
  val TABLE=rgb(24,30,38);val TABLE_EDGE=rgb(60,78,96)
  val PLOT=rgb(34,44,56);val GRID=rgb(44,58,72)
  val GHOST=rgb(70,120,150);val PATH=rgb(52,70,86);val PATH_LIT=rgb(90,200,220)
  val BODY=rgb(84,132,180);val BUILT=rgb(70,170,140);val DONE=rgb(150,230,190);val GLOW=rgb(90,170,130)
  // K6: amber means "needs you" and lives only on the waiting ring (SiteLook); scaffold, cranes and a blocked part are cyan line work.
  val BLOCKED=SiteLook.rgb(SiteLook.CYAN);val SCAFFOLD=SiteLook.rgb(SiteLook.CYAN);val SEAM=rgb(30,40,52)
  val CRANE=SiteLook.rgb(SiteLook.CYAN);val CRANE_SEL=rgb(255,255,255);val CABLE=rgb(200,200,200)

  private class B { val p=ArrayList<Float>(60_000);val c=ArrayList<Float>(80_000);val i=ArrayList<Int>(60_000)
    /** #1029: an idle plot is drawn dimmed (never empty); 1 = lit. */
    var dim=1f
    /** #1202: vertex offsets where a piece starts (only read by [pieces]; [build]'s arrays are unchanged by it). */
    val cuts=ArrayList<Int>()
    fun cut() { cuts.add(p.size/3) }
    fun built()=Built(p.toFloatArray(),c.toFloatArray(),i.toIntArray())
    fun quad(a: DoubleArray,b: DoubleArray,c2: DoubleArray,d: DoubleArray,col: FloatArray,shade: Float) {
      val o=p.size/3;val k=shade*dim
      for(v in listOf(a,b,c2,d)) { p.add(v[0].toFloat());p.add(v[1].toFloat());p.add(v[2].toFloat())
        c.add(col[0]*k);c.add(col[1]*k);c.add(col[2]*k);c.add(1f) }
      i.addAll(listOf(o,o+1,o+2,o,o+2,o+3))
    }
    /** Axis-aligned box from (x0,y0,z0) to (x1,y1,z1); 5 faces (no bottom) = 10 triangles. */
    fun box(x0: Double,y0: Double,z0: Double,x1: Double,y1: Double,z1: Double,col: FloatArray,top: Float=1f) {
      quad(doubleArrayOf(x0,y1,z0),doubleArrayOf(x1,y1,z0),doubleArrayOf(x1,y1,z1),doubleArrayOf(x0,y1,z1),col,top)
      quad(doubleArrayOf(x0,y0,z1),doubleArrayOf(x1,y0,z1),doubleArrayOf(x1,y1,z1),doubleArrayOf(x0,y1,z1),col,0.78f*top)
      quad(doubleArrayOf(x0,y0,z0),doubleArrayOf(x0,y1,z0),doubleArrayOf(x1,y1,z0),doubleArrayOf(x1,y0,z0),col,0.78f*top)
      quad(doubleArrayOf(x1,y0,z0),doubleArrayOf(x1,y1,z0),doubleArrayOf(x1,y1,z1),doubleArrayOf(x1,y0,z1),col,0.62f*top)
      quad(doubleArrayOf(x0,y0,z0),doubleArrayOf(x0,y0,z1),doubleArrayOf(x0,y1,z1),doubleArrayOf(x0,y1,z0),col,0.62f*top)
    }
    fun cbox(cx: Double,y0: Double,cz: Double,w: Double,h: Double,d: Double,col: FloatArray,top: Float=1f)=box(cx-w/2,y0,cz-d/2,cx+w/2,y0+h,cz+d/2,col,top)
    /** Square rod between two points (any direction). */
    fun rod(a: DoubleArray,b: DoubleArray,w: Double,col: FloatArray) {
      val d=doubleArrayOf(b[0]-a[0],b[1]-a[1],b[2]-a[2]);val len=sqrt(d[0]*d[0]+d[1]*d[1]+d[2]*d[2]).coerceAtLeast(1e-9)
      val u=d.map { it/len }
      val ref=if(abs(u[1])<0.9) doubleArrayOf(0.0,1.0,0.0) else doubleArrayOf(1.0,0.0,0.0)
      fun cross(p: List<Double>,q: DoubleArray)=listOf(p[1]*q[2]-p[2]*q[1],p[2]*q[0]-p[0]*q[2],p[0]*q[1]-p[1]*q[0])
      val s=cross(u,ref).let { v -> val l=sqrt(v.sumOf { it*it });v.map { it/l*w/2 } }
      val t=cross(u,s.toDoubleArray()).let { v -> val l=sqrt(v.sumOf { it*it });v.map { it/l*w/2 } }
      fun at(o: DoubleArray,i: Int,j: Int)=doubleArrayOf(o[0]+i*s[0]+j*t[0],o[1]+i*s[1]+j*t[1],o[2]+i*s[2]+j*t[2])
      val corners=listOf(1 to 1,-1 to 1,-1 to -1,1 to -1)
      for(k in 0 until 4) { val (i0,j0)=corners[k];val (i1,j1)=corners[(k+1)%4]
        quad(at(a,i0,j0),at(a,i1,j1),at(b,i1,j1),at(b,i0,j0),col,if(k%2==0) 0.95f else 0.7f) }
    }
  }

  /** Where each running agent's crane stands: beside its part (or its plot's corner when the task has no part). */
  fun cranes(layout: SiteLayout,state: SiteState): List<Crane> {
    val slot=HashMap<String,Int>()
    return state.agents.mapNotNull { a ->
      val plot=layout.plots.firstOrNull { it.project==a.project } ?: return@mapNotNull null
      val part=a.part?.let { id -> plot.parts.firstOrNull { it.id==id } }
      val key=part?.let { "${a.project}/${it.id}" } ?: a.project
      val k=slot.getOrPut(key) { 0 };slot[key]=k+1
      val hx=part?.x ?: (plot.x0+0.04);val hz=part?.z ?: (plot.z0+0.04)
      val ang=PI*0.25+k*PI*0.5
      val r=0.075
      val st=part?.let { state.part(a.project,it.id) }
      val hy=(st?.share ?: 0.0)*BLOCK_H
      Crane(a,hx+r*cos(ang),hz-r*sin(ang),hx,hz,hy)
    }
  }

  /** Plot edge of a plot with agents at work (#1029: lit), and the shared selection's highlight. */
  val LIT_EDGE=SiteLook.rgb(SiteLook.CYAN);val PICK=rgb(255,255,255) // look_nov17 s2: amber only for waiting
  const val DIM=0.42f

  /**
   * [lit]: projects drawn lit (null: all, as #1003). Others are dimmed, never empty. [pick]: the shared selection's
   * block and plot, outlined in white.
   */
  fun build(layout: SiteLayout,state: SiteState,selected: String?=null,lit: Set<String>?=null,pick: SiteSelection.Pick?=null): Built =
    draw(layout,state,selected,lit,pick).built()

  /**
   * #1202 the same mesh as [build], cut into pieces: the table, each plot's pad, grid and paths, each part, each pick
   * outline, each crane. Their concatenation is [build]'s arrays exactly (same floats), so the pieces drawn together are
   * the same triangles. Each piece's indices start at its own first vertex. Piece 0 is the table, which never changes.
   */
  fun pieces(layout: SiteLayout,state: SiteState,selected: String?=null,lit: Set<String>?=null,pick: SiteSelection.Pick?=null): List<Built> =
    slice(draw(layout,state,selected,lit,pick))
  /** Every pick outline [build] can draw on [layout] (a plot's frame, a part's marker), as pieces. */
  fun pickPieces(layout: SiteLayout): List<Built> { val b=B()
    for(plot in layout.plots) { b.cut();plotPick(b,plot);plot.parts.forEach { b.cut();partPick(b,it) } }
    return slice(b) }
  /** A crane drawn as [build] draws it ([selected]: the talk target's white crane), as one piece. */
  fun cranePiece(c: Crane,selected: Boolean): Built { val b=B();crane(b,c,selected);return b.built() }
  private fun slice(b: B): List<Built> {
    val at=(listOf(0)+b.cuts+listOf(b.p.size/3)).distinct().sorted()
    // Every primitive is a quad (4 vertices, 6 indices), so a piece's indices start at vertex*3/2.
    return at.zipWithNext().filter { (v0,v1) -> v1>v0 }.map { (v0,v1) ->
      Built(FloatArray((v1-v0)*3) { b.p[v0*3+it] },FloatArray((v1-v0)*4) { b.c[v0*4+it] },IntArray((v1-v0)*3/2) { b.i[v0*3/2+it]-v0 }) }
  }
  private fun plotPick(b: B,plot: SitePlot) { b.dim=1f;frame(b,plot,0.006,PICK,0.02) }
  private fun partPick(b: B,part: SitePart) { b.dim=1f
    b.cbox(part.x,0.006,part.z,FOOT+0.06,0.004,FOOT+0.06,PICK)
    b.rod(doubleArrayOf(part.x,BLOCK_H+0.02,part.z),doubleArrayOf(part.x,BLOCK_H+0.12,part.z),0.012,PICK)
    b.cbox(part.x,BLOCK_H+0.12,part.z,0.03,0.03,0.03,PICK) }

  private fun draw(layout: SiteLayout,state: SiteState,selected: String?,lit: Set<String>?,pick: SiteSelection.Pick?): B {
    val b=B();val h=SiteGeometry.TABLE/2
    // Table top: a slab with a lit rim, then each plot as a raised pad with its survey grid.
    b.box(-h,-0.03,-h,h,0.0,h,TABLE,0.9f)
    b.box(-h-0.012,-0.03,-h-0.012,h+0.012,0.002,-h,TABLE_EDGE);b.box(-h-0.012,-0.03,h,h+0.012,0.002,h+0.012,TABLE_EDGE)
    b.box(-h-0.012,-0.03,-h,-h,0.002,h,TABLE_EDGE);b.box(h,-0.03,-h,h+0.012,0.002,h,TABLE_EDGE)
    for(plot in layout.plots) {
      val on=lit==null || plot.project in lit
      b.dim=if(on) 1f else DIM
      b.cut()
      b.box(plot.x0,0.0,plot.z0,plot.x0+plot.w,0.006,plot.z0+plot.d,PLOT)
      if(lit!=null && on) frame(b,plot,0.012,LIT_EDGE)
      if(pick?.project==plot.project) { b.cut();plotPick(b,plot);b.dim=if(on) 1f else DIM }
      b.cut()
      var gx=plot.x0+0.03;while(gx<plot.x0+plot.w-0.01) { b.box(gx-0.0012,0.006,plot.z0+0.01,gx+0.0012,0.0068,plot.z0+plot.d-0.01,GRID);gx+=0.06 }
      var gz=plot.z0+0.03;while(gz<plot.z0+plot.d-0.01) { b.box(plot.x0+0.01,0.006,gz-0.0012,plot.x0+plot.w-0.01,0.0068,gz+0.0012,GRID);gz+=0.06 }
      val byId=plot.parts.associateBy { it.id }
      b.cut()
      // Dependencies are paths on the ground: L-shaped strips, lit once the part they come from has any finished work.
      plot.paths.forEach { (from,to) -> val a=byId[from] ?: return@forEach;val c=byId[to] ?: return@forEach
        val lit=state.part(plot.project,from).done>0;val col=if(lit) PATH_LIT else PATH;val y=0.0075;val w=0.006
        val mx=(a.x+c.x)/2
        b.box(min(a.x,mx)-w/2,0.006,a.z-w/2,max(a.x,mx)+w/2,y,a.z+w/2,col)
        b.box(mx-w/2,0.006,min(a.z,c.z)-w/2,mx+w/2,y,max(a.z,c.z)+w/2,col)
        b.box(min(mx,c.x)-w/2,0.006,c.z-w/2,max(mx,c.x)+w/2,y,c.z+w/2,col) }
      plot.parts.forEach { part -> b.cut();part(b,part,state.part(plot.project,part.id))
        if(pick!=null && pick.project==plot.project && pick.part==part.id) { val keep=b.dim;b.cut();partPick(b,part);b.dim=keep } }
    }
    b.dim=1f
    cranes(layout,state).forEach { b.cut();crane(b,it,it.agent.id==selected) }
    return b
  }

  /** A raised rim round a plot (lit edge, or the selection's white outline). */
  private fun frame(b: B,p: SitePlot,w: Double,col: FloatArray,out: Double=0.0) {
    val x0=p.x0-out;val z0=p.z0-out;val x1=p.x0+p.w+out;val z1=p.z0+p.d+out;val y0=0.0;val y1=0.014
    b.box(x0-w,y0,z0-w,x1+w,y1,z0,col);b.box(x0-w,y0,z1,x1+w,y1,z1+w,col)
    b.box(x0-w,y0,z0,x0,y1,z1,col);b.box(x1,y0,z0,x1+w,y1,z1,col)
  }

  private fun part(b: B,p: SitePart,s: PartState) {
    val f=FOOT/2;val x=p.x;val z=p.z;val base=0.006
    // Footing: every planned part shows its ground slab and a faint blueprint outline of its finished height.
    b.cbox(x,base,z,FOOT+0.012,0.004,FOOT+0.012,if(s.finished) GLOW else GHOST,0.55f)
    if(!s.finished) {
      val top=base+BLOCK_H;val w=0.0022
      for((dx,dz) in listOf(-f to -f,f to -f,f to f,-f to f)) b.rod(doubleArrayOf(x+dx,base,z+dz),doubleArrayOf(x+dx,top,z+dz),w,GHOST)
      val c=listOf(-f to -f,f to -f,f to f,-f to f)
      for(k in 0 until 4) { val (ax,az)=c[k];val (cx,cz)=c[(k+1)%4]; b.rod(doubleArrayOf(x+ax,top,z+az),doubleArrayOf(x+cx,top,z+cz),w,GHOST) }
    }
    val hgt=s.share*BLOCK_H
    val col=when { s.finished -> DONE; s.state=="blocked" -> BLOCKED; s.building -> BODY; else -> BUILT }
    if(s.finished) b.cbox(x,base,z,FOOT+0.05,0.002,FOOT+0.05,GLOW,0.8f)   // soft glow: a lit apron round a finished part
    if(hgt>0) {
      b.cbox(x,base+0.004,z,FOOT,hgt,FOOT,col)
      // Storeys: a dark seam at each finished task's level, so height reads as floors laid one by one.
      for(k in 1 until s.done) { val y=base+0.004+k*BLOCK_H/s.total;b.cbox(x,y-0.002,z,FOOT+0.004,0.003,FOOT+0.004,SEAM) }
      if(s.finished) b.cbox(x,base+0.004+hgt,z,FOOT*0.7,0.006,FOOT*0.7,floatArrayOf(.85f,1f,.92f))
    }
    if(s.scaffold) {
      // Scaffolding up to the next storey: corner poles plus rails every 3 cm.
      val top=base+0.004+min(BLOCK_H,hgt+BLOCK_H/s.total)+0.012;val g=f+0.008;val w=0.003
      for((dx,dz) in listOf(-g to -g,g to -g,g to g,-g to g)) b.rod(doubleArrayOf(x+dx,base,z+dz),doubleArrayOf(x+dx,top,z+dz),w,SCAFFOLD)
      var y=base+0.03
      while(y<=top+1e-6) { val c=listOf(-g to -g,g to -g,g to g,-g to g)
        for(k in 0 until 4) { val (ax,az)=c[k];val (cx,cz)=c[(k+1)%4];b.rod(doubleArrayOf(x+ax,y,z+az),doubleArrayOf(x+cx,y,z+cz),w*0.8,SCAFFOLD) }
        y+=0.03 }
      // A diagonal brace on the front face reads as "under construction" at any distance.
      b.rod(doubleArrayOf(x-g,base,z+g),doubleArrayOf(x+g,top,z+g),w*0.8,SCAFFOLD)
    }
  }

  private fun crane(b: B,c: Crane,selected: Boolean) {
    val col=if(selected) CRANE_SEL else CRANE;val w=0.008
    val bx=c.baseX;val bz=c.baseZ;val top=CRANE_H
    b.cbox(bx,0.006,bz,0.03,0.012,0.03,col,0.7f)                                   // base
    // Lattice mast: two rails with zig-zag bracing.
    b.rod(doubleArrayOf(bx-w/2,0.0,bz),doubleArrayOf(bx-w/2,top,bz),0.004,col)
    b.rod(doubleArrayOf(bx+w/2,0.0,bz),doubleArrayOf(bx+w/2,top,bz),0.004,col)
    var y=0.02;var left=true
    while(y<top-0.02) { b.rod(doubleArrayOf(bx+(if(left) -w/2 else w/2),y,bz),doubleArrayOf(bx+(if(left) w/2 else -w/2),y+0.03,bz),0.0025,col);y+=0.03;left=!left }
    // Jib over the part and a counter-jib behind; the cab at the top.
    val dx=c.hookX-bx;val dz=c.hookZ-bz;val len=sqrt(dx*dx+dz*dz).coerceAtLeast(1e-6);val ux=dx/len;val uz=dz/len
    val tip=doubleArrayOf(bx+ux*(len+0.03),top,bz+uz*(len+0.03));val back=doubleArrayOf(bx-ux*0.05,top,bz-uz*0.05)
    b.rod(back,tip,0.006,col)
    b.rod(doubleArrayOf(bx,top+0.035,bz),tip,0.0025,col);b.rod(doubleArrayOf(bx,top+0.035,bz),back,0.0025,col)
    b.rod(doubleArrayOf(bx,top,bz),doubleArrayOf(bx,top+0.035,bz),0.004,col)
    b.cbox(bx-ux*0.04,top-0.012,bz-uz*0.04,0.018,0.012,0.018,rgb(120,130,140))   // counterweight
    b.cbox(bx,top-0.02,bz,0.016,0.016,0.016,col,0.85f)                             // cab
    // Cable and hook to the top of the part it is building.
    val hy=0.006+c.hookY+0.03
    b.rod(doubleArrayOf(c.hookX,top,c.hookZ),doubleArrayOf(c.hookX,hy,c.hookZ),0.0018,CABLE)
    b.cbox(c.hookX,hy-0.01,c.hookZ,0.01,0.01,0.01,col)
    if(selected) b.cbox(bx,0.006,bz,0.06,0.003,0.06,CRANE_SEL)
  }

  /** Text anchors (table metres) the scene shows as Text3D and the stills draw: plot names, agent labels, part names, the clock. */
  fun labels(layout: SiteLayout,state: SiteState): List<Label> {
    val out=mutableListOf<Label>()
    layout.plots.forEach { p -> val done=p.parts.sumOf { state.part(p.project,it.id).done };val total=p.parts.sumOf { state.part(p.project,it.id).total }
      out.add(Label(p.name,p.x0+0.01,0.01,p.z0+p.d+0.028,"plot"))
      out.add(Label("$done of $total tasks done",p.x0+0.01,0.01,p.z0+p.d+0.068,"plot_sub")) }
    cranes(layout,state).forEach { c -> out.add(Label(c.agent.label,c.baseX,CRANE_H+0.06,c.baseZ,"agent",c.agent.id)) }
    out.add(Label(state.clock,0.0,0.02,SiteGeometry.TABLE/2+0.06,"clock"))
    return out
  }

  /** Table metres to the root's native frame (labels, rays). */
  fun native(x: Double,y: Double,z: Double)=doubleArrayOf(x,y,-z)

  /** Rough Text3D width (m) of a line at [size]; used to centre lines and keep signs inside the table. */
  fun width(text: String,size: Double)=text.length*size*0.55

  /**
   * #1029 the default view's text: a header over the far edge (one line, plus where and when), and one sign per
   * plot standing at its back edge: name, progress figure and agents at work, then the latest update. Idle plots say so.
   */
  fun signLabels(layout: SiteLayout,state: SiteState,signs: List<SiteSign>,header: String,sub: String,mode: SiteMode): List<Label> {
    val out=mutableListOf<Label>()
    val half=SiteGeometry.TABLE/2
    out.add(Label(header,-width(header,HEADER)/2,0.70,-half+0.02,"header"))
    out.add(Label(sub,-width(sub,SUB)/2,0.63,-half+0.02,"sub"))
    val byProject=signs.associateBy { it.project }
    // #1029 signs must not pile up at put-on: plots stand in shelves one behind the other, so each shelf's sign gets its
    // own band of the wearer's view (farthest highest), the nearest shelf's sign stands low on its front edge, and each
    // sign is scaled (then clipped) to the width before the next plot on its shelf.
    val shelves=layout.plots.groupBy { it.z0 }.toSortedMap()
    val eyeY=SitePutOn.DROP/SitePutOn.SCALE;val eyeZ=SitePutOn.DISTANCE/SitePutOn.SCALE
    var below=-1.0 // depression (tan) of the bottom of the band above
    shelves.entries.forEachIndexed { i,(z0,row) ->
      val nearest=shelves.size>1 && i==shelves.size-1
      val z=if(nearest) row.maxOf { it.z0+it.d }+0.04 else z0-0.01
      val top=when { nearest -> SIGN_LOW;below<0 -> SIGN_TOP;else -> minOf(SIGN_TOP,eyeY-(eyeZ-z)*(below+BAND_GAP)) }.coerceAtLeast(SIGN_LOW)
      val nameY=top-NAME/2
      below=(eyeY-(nameY-SIGN_SPAN))/(eyeZ-z)
      val sorted=row.sortedBy { it.x0 }
      sorted.forEachIndexed { k,p -> val s=byProject[p.project] ?: return@forEachIndexed
        val on=SiteText.lit(mode,s)
        val x=p.x0+0.01
        val room=(if(k+1<sorted.size) sorted[k+1].x0 else half)-x-0.03
        val crew=if(s.crew.isEmpty()) SiteText.IDLE_PLOT else s.crew.joinToString(" · ")
        val lines=listOf(s.name,"${s.figure} · $crew",s.latest)
        val k0=minOf(1.0,maxOf(MIN_SCALE,room/maxOf(width(lines[0],NAME),width(lines[1],LINE),width(lines[2],LINE))))
        fun fit(t: String,sz: Double)=clip(t,sz,room)
        out.add(Label(fit(s.name,NAME*k0),x,nameY,z,if(on) "sign" else "sign_dim",project=p.project,size=NAME*k0))
        val second=fit(lines[1],LINE*k0).let { t -> if(t!=lines[1] && s.crew.isEmpty()) fit("${s.figure} · Idle",LINE*k0) else t }
        out.add(Label(second,x,nameY-0.055*k0,z,if(on) "sign_crew" else "sign_dim2",project=p.project,size=LINE*k0))
        if(s.latest.isNotBlank()) out.add(Label(fit(s.latest,LINE*k0),x,nameY-0.095*k0,z,if(on) "sign_latest" else "sign_dim2",project=p.project,size=LINE*k0))
      }
    }
    // A crane's name tag is drawn only where it covers no sign (the sign's crew line already names that agent) nor another tag.
    val taken=out.filter { it.kind.startsWith("sign") }.map { putOnBox(it) }.toMutableList()
    cranes(layout,state).forEach { c -> val l=Label(c.agent.label,c.baseX,CRANE_H+0.05,c.baseZ,"agent",c.agent.id);val b=putOnBox(l)
      if(taken.none { overlaps(it,b) }) { out.add(l);taken.add(b) } }
    return out
  }
  /** A label's box in the wearer's view at put-on (tangent units: left, right, bottom, top). Agent tags are centred pills. */
  fun putOnBox(l: Label): DoubleArray {
    val ey=SitePutOn.DROP/SitePutOn.SCALE;val ez=SitePutOn.DISTANCE/SitePutOn.SCALE;val d=ez-l.z
    val sz=if(l.size>0) l.size else when(l.kind) { "header" -> HEADER;"sub" -> SUB;"sign","sign_dim" -> NAME;"agent" -> 0.03;else -> LINE }
    val w=width(l.text,sz)+(if(l.kind=="agent") 0.03 else 0.0);val h=if(l.kind=="agent") sz*1.8 else sz
    val x0=if(l.kind=="agent") l.x-w/2 else l.x
    return doubleArrayOf(x0/d,(x0+w)/d,(l.y-h/2-ey)/d,(l.y+h/2-ey)/d)
  }
  fun overlaps(a: DoubleArray,b: DoubleArray)=a[0]<b[1] && b[0]<a[1] && a[2]<b[3] && b[2]<a[3]
  /** [text] cut at a word (with an ellipsis) so it is at most [room] metres wide at [size]. */
  fun clip(text: String,size: Double,room: Double): String {
    if(width(text,size)<=room) return text
    var t=text.trimEnd('…')
    while(t.contains(' ') && width("$t…",size)>room) t=t.substringBeforeLast(' ').trimEnd(',',':',' ','·')
    return "$t…"
  }
  const val NAME=0.06
  const val LINE=0.04
  const val MIN_SCALE=0.7
  /** Highest sign top, the lowest, the band from a name's centre to its last line's bottom, and the gap between bands. */
  const val SIGN_TOP=0.55
  const val SIGN_LOW=0.20
  const val SIGN_SPAN=0.115
  const val BAND_GAP=0.006
  const val HEADER=0.075
  const val SUB=0.04

  /** The agent card's text: what it is doing now (12 words at most), its last three steps, and Talk. */
  fun card(a: SiteAgent)=JSONObject().put("title",a.label).put("now",a.now).put("steps",JSONArray(a.steps)).put("progress_pct",a.pct)
    .put("actions",JSONArray(listOf("Talk")))

  /** Writes the GLB. Spatial SDK's glTF importer applies a Y half-turn, so x and z are negated here (as GraphMesh does). */
  fun write(file: File,m: Built,blend: Boolean=false) {
    // #1029: native = (x, y, -z) (the table's +z faces the wearer, the root's -z); the importer's Y half-turn negates
    // x and z, so (-x, y, z) is written. Labels and rays use the same native frame ([SiteMesh.native]).
    val pos=FloatArray(m.positions.size) { i -> if(i%3==0) -m.positions[i] else m.positions[i] }
    val posBytes=pos.size*4;val colourBytes=m.colours.size*4;val indexBytes=m.indices.size*4
    val bin=ByteBuffer.allocate(posBytes+colourBytes+indexBytes).order(ByteOrder.LITTLE_ENDIAN)
    pos.forEach { bin.putFloat(it) };m.colours.forEach { bin.putFloat(it) };m.indices.forEach { bin.putInt(it) }
    val min=(0..2).map { a -> (a until pos.size step 3).minOfOrNull { pos[it] } ?: 0f }
    val max=(0..2).map { a -> (a until pos.size step 3).maxOfOrNull { pos[it] } ?: 0f }
    fun acc(view: Int,count: Int,type: String,ct: Int)=JSONObject().put("bufferView",view).put("componentType",ct).put("count",count).put("type",type)
    val json=JSONObject().put("asset",JSONObject().put("version","2.0")).put("extensionsUsed",JSONArray(listOf("KHR_materials_unlit")))
      .put("scene",0).put("scenes",JSONArray().put(JSONObject().put("nodes",JSONArray(listOf(0))))).put("nodes",JSONArray().put(JSONObject().put("mesh",0)))
      .put("meshes",JSONArray().put(JSONObject().put("primitives",JSONArray().put(JSONObject()
        .put("attributes",JSONObject().put("POSITION",0).put("COLOR_0",1)).put("indices",2).put("material",0)))))
      .put("materials",JSONArray().put(JSONObject().put("doubleSided",true).put("alphaMode",if(blend) "BLEND" else "OPAQUE").put("extensions",JSONObject().put("KHR_materials_unlit",JSONObject()))
        .put("pbrMetallicRoughness",JSONObject().put("metallicFactor",0).put("roughnessFactor",1))))
      .put("buffers",JSONArray().put(JSONObject().put("byteLength",bin.capacity())))
      .put("bufferViews",JSONArray().put(JSONObject().put("buffer",0).put("byteOffset",0).put("byteLength",posBytes))
        .put(JSONObject().put("buffer",0).put("byteOffset",posBytes).put("byteLength",colourBytes))
        .put(JSONObject().put("buffer",0).put("byteOffset",posBytes+colourBytes).put("byteLength",indexBytes)))
      .put("accessors",JSONArray().put(acc(0,pos.size/3,"VEC3",5126).put("min",JSONArray(min)).put("max",JSONArray(max)))
        .put(acc(1,m.colours.size/4,"VEC4",5126)).put(acc(2,m.indices.size,"SCALAR",5125))).toString().toByteArray()
    val jl=(json.size+3)/4*4
    val glb=ByteBuffer.allocate(12+8+jl+8+bin.capacity()).order(ByteOrder.LITTLE_ENDIAN)
    glb.putInt(0x46546c67);glb.putInt(2);glb.putInt(glb.capacity());glb.putInt(jl);glb.putInt(0x4e4f534a);glb.put(json)
    repeat(jl-json.size) { glb.put(32.toByte()) };glb.putInt(bin.capacity());glb.putInt(0x004e4942);glb.put(bin.array())
    file.writeBytes(glb.array())
  }
}
