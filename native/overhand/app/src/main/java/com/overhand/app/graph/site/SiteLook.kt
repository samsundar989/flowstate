package com.overhand.app.graph.site

import kotlin.math.*

/**
 * K6 (#1377): the Yard's look as pure data (look_nov17.md sections 2 and 5). Line work is cyan and carries no text;
 * every word sits on glass (a tag on point, a card on pinch, one pill per building). Amber is only the ring round a
 * plot that waits for an answer. [frame] says what every retained entity should look like; [SiteLookApply] turns
 * frame-to-frame differences into transform, material, visibility or (text changes only) mesh calls.
 */
object SiteLook {
  /** ARGB, straight from the spec's token table. */
  const val CYAN=0xFF5CE1E6L
  const val AMBER=0xFFFFB547L
  const val GREEN=0xFF45D99AL
  const val RED=0xFFFF6B6BL
  const val TEXT=0xFFF2F6FAL
  const val CAPTION=0xB3FFFFFFL          // white at 70%
  const val GLASS=0xA60C121AL            // #0C121A at 65%
  const val GLASS_EDGE=0x26FFFFFFL       // white at 15%
  const val PLANNED=0x8CFFFFFFL          // dashed white outline at 55%
  const val ON_CYAN=0xFF06222AL          // text on a filled cyan button
  const val CONTROL_EDGE=0x38FFFFFFL     // white at 22%
  /** Cyan at 16% (selected fill) and the line work dimmed while another slab is pointed at. */
  const val CYAN_16=0x295CE1E6L
  /** The site's three FloatArray colours that used to be yellow (cranes, scaffold, agent text). */
  fun rgb(argb: Long)=floatArrayOf(((argb shr 16) and 255)/255f,((argb shr 8) and 255)/255f,(argb and 255)/255f)
  fun rgba(argb: Long)=floatArrayOf(((argb shr 16) and 255)/255f,((argb shr 8) and 255)/255f,(argb and 255)/255f,((argb shr 24) and 255)/255f)

  enum class Mark(val word: String) { PLANNED("Planned"),BUILDING("Building"),BUILT("Built") }
  /** Every part state maps to exactly one slab mark. A blocked part is still being built; its plot gets the ring. */
  fun mark(s: PartState)=when { s.finished -> Mark.BUILT;s.building || s.state=="blocked" || s.done>0 -> Mark.BUILDING;else -> Mark.PLANNED }
  fun word(s: PartState)=if(s.state=="blocked") "Needs you" else mark(s).word
  /** Hatch moves only while a worker is on the part. */
  fun marching(s: PartState)=mark(s)==Mark.BUILDING && s.crew>0

  enum class Shape { BOX,TEXT }
  /** Table metres (x right, y up, z towards the viewer); [rot] is degrees about z; [sx..sz] are box sizes. */
  data class Spec(val shape: Shape,val x: Double,val y: Double,val z: Double,val sx: Double=0.0,val sy: Double=0.0,val sz: Double=0.0,
    val rot: Double=0.0,val argb: Long,val on: Boolean,val text: String="",val size: Double=0.0)
  private fun box(x0: Double,y0: Double,z0: Double,x1: Double,y1: Double,z1: Double,argb: Long,on: Boolean)=
    Spec(Shape.BOX,(x0+x1)/2,(y0+y1)/2,(z0+z1)/2,abs(x1-x0),abs(y1-y0),abs(z1-z0),0.0,argb,on)
  private fun text(x: Double,y: Double,z: Double,value: String,size: Double,argb: Long,on: Boolean)=Spec(Shape.TEXT,x,y,z,argb=argb,on=on,text=value,size=size)
  fun textWidth(text: String,size: Double)=text.lines().maxOf { it.length }*size*0.55

  const val DASHES=3
  const val HATCH=5
  /** Edge stroke half-width (m before scale): 2 dp at rest, doubled when pointed at. */
  const val EDGE=.002
  const val EDGE_POINTED=.004

  /** The 135 degree hatch on the slab's front face: [HATCH] bars clipped to the face. Returns (cx, cy, length). */
  fun hatch(b: SiteLayers.Box,phase: Double=0.0): List<Triple<Double,Double,Double>> {
    val lo=b.x0+b.y0;val hi=b.x1+b.y1;val step=(hi-lo)/HATCH
    return (0 until HATCH).map { j ->
      val c=lo+((j+.5+phase).mod(HATCH.toDouble()))*step
      val xa=max(b.x0,c-b.y1);val xb=min(b.x1,c-b.y0)
      val len=max(0.0,xb-xa)*sqrt(2.0)
      Triple((xa+xb)/2,c-(xa+xb)/2,len)
    }
  }
  /** Edge [i] of 12 as (from, to); then [DASHES] segments, dashed (with gaps) or solid (touching). */
  private fun edges(b: SiteLayers.Box): List<Pair<DoubleArray,DoubleArray>> = buildList {
    for(y in listOf(b.y0,b.y1)) {
      for(z in listOf(b.z0,b.z1)) add(doubleArrayOf(b.x0,y,z) to doubleArrayOf(b.x1,y,z))
      for(x in listOf(b.x0,b.x1)) add(doubleArrayOf(x,y,b.z0) to doubleArrayOf(x,y,b.z1))
    }
    for(x in listOf(b.x0,b.x1)) for(z in listOf(b.z0,b.z1)) add(doubleArrayOf(x,b.y0,z) to doubleArrayOf(x,b.y1,z))
  }
  private fun segment(a: DoubleArray,c: DoubleArray,k: Int,dashed: Boolean,w: Double,argb: Long,on: Boolean): Spec {
    val (t0,t1)=if(dashed) { val dash=1.0/(DASHES+(DASHES-1)*.5);val s=k*dash*1.5;s to s+dash } else k.toDouble()/DASHES to (k+1.0)/DASHES
    val p0=DoubleArray(3) { a[it]+(c[it]-a[it])*t0 };val p1=DoubleArray(3) { a[it]+(c[it]-a[it])*t1 }
    return box(min(p0[0],p1[0])-w,min(p0[1],p1[1])-w,min(p0[2],p1[2])-w,max(p0[0],p1[0])+w,max(p0[1],p1[1])+w,max(p0[2],p1[2])+w,argb,on)
  }
  private fun whole(a: DoubleArray,c: DoubleArray,w: Double,argb: Long)=
    box(min(a[0],c[0])-w,min(a[1],c[1])-w,min(a[2],c[2])-w,max(a[0],c[0])+w,max(a[1],c[1])+w,max(a[2],c[2])+w,argb,true)
  fun waiting(project: String,layers: List<SiteLayers.Layer>,layout: SiteLayout,state: SiteState)=
    layers.any { it.id.project==project && SiteLayers.state(it,layout,state).state=="blocked" }
  fun built(project: String,layers: List<SiteLayers.Layer>,layout: SiteLayout,state: SiteState): Pair<Int,Int> {
    val mine=layers.filter { it.id.project==project }
    return mine.count { mark(SiteLayers.state(it,layout,state))==Mark.BUILT } to mine.size
  }
  fun pill(name: String,n: Int,m: Int)="$name\n$n of $m built"

  /** Spec px (Yard.dc.html) to table metres at scale 1: the card is 520 px wide (line 54) and [SiteLayers.cardBox] .32 m. */
  const val PX=.32/520
  /** Glass top edge, white at 30% (Yard.dc.html line 14 `border-top-color:rgba(255,255,255,.3)`). */
  const val GLASS_TOP=0x4DFFFFFFL
  /** Corner radii in spec px: card 32 (line 54), tag 20 (line 48), pill 999 (lines 16, 45). */
  const val R_CARD=32.0
  const val R_TAG=20.0
  const val R_PILL=999.0
  /** Strips per rounded corner band. */
  const val STEPS=3
  /** Type in spec px: tag name 30 and word 24 (lines 49-50); card title 40, word 30, caption 30 (lines 55-57); pill 30 (lines 16, 45). */
  const val T_TAG=30.0
  const val T_TAG_WORD=24.0
  const val T_TITLE=40.0
  const val T_BODY=30.0
  /** Controls: 72 px pill height (line 16) and 16 px gap (line 58); the round's floor is 64 dp targets and 16 dp gaps. */
  const val BUTTON_H=72.0
  const val BUTTON_GAP=16.0

  /** Rounded glass as non-overlapping horizontal strips (so translucent alpha never doubles): one middle band and
   *  [STEPS] strips above and below whose inset follows the corner arc. Retained boxes; no mesh, no texture. */
  fun rounded(out: MutableMap<String,Spec>,key: String,x0: Double,y0: Double,x1: Double,y1: Double,z0: Double,z1: Double,r0: Double,
    argb: Long,on: Boolean,top: Long=argb) {
    val r=minOf(r0,(y1-y0)/2,(x1-x0)/2)
    out["$key.m"]=box(x0,y0+r,z0,x1,y1-r,z1,argb,on && y1-y0>2*r+1e-9)
    for(i in 0 until STEPS) {
      val dy=(i+.5)/STEPS*r;val inset=r-sqrt(max(0.0,r*r-dy*dy));val a=r*i/STEPS;val b=r*(i+1)/STEPS
      out["$key.t$i"]=box(x0+inset,y1-r+a,z0,x1-inset,y1-r+b,z1,top,on)
      out["$key.b$i"]=box(x0+inset,y0+r-b,z0,x1-inset,y0+r-a,z1,argb,on)
    }
  }
  /** A glass surface (look s2): 2 px edge behind, white 15% with a 30% top, then the 65% fill. */
  private fun glass(out: MutableMap<String,Spec>,key: String,x0: Double,y0: Double,x1: Double,y1: Double,z: Double,rPx: Double,u: Double,on: Boolean) {
    val e=2*PX*u;val r=rPx*PX*u
    rounded(out,"${key}Edge",x0-e,y0-e,x1+e,y1+e,z-.0005*u,z,r+e,GLASS_EDGE,on,GLASS_TOP)
    rounded(out,key,x0,y0,x1,y1,z,z+.001*u,r,GLASS,on)
  }
  /** The shared status mark (Yard.dc.html line 56, look s2): planned dashed white, building cyan edge with hatch,
   *  built solid cyan; a 30 px square. Slots are fixed; a status change is only material and visibility. */
  fun statusMark(out: MutableMap<String,Spec>,key: String,x0: Double,yc: Double,z: Double,m: Mark,u: Double,on: Boolean) {
    val s=30*PX*u;val w=2*PX*u;val x1=x0+s;val y0=yc-s/2;val y1=yc+s/2
    val line=if(m==Mark.PLANNED) PLANNED else CYAN
    out["$key.fill"]=box(x0,y0,z,x1,y1,z+.0005*u,CYAN,on && m==Mark.BUILT)
    // Four sides, two segments each: touching when solid, with a gap when dashed.
    val sides=listOf(doubleArrayOf(x0,y1,x1,y1),doubleArrayOf(x0,y0,x1,y0),doubleArrayOf(x0,y0,x0,y1),doubleArrayOf(x1,y0,x1,y1))
    sides.forEachIndexed { i,e -> for(k in 0..1) {
      val t0=if(m==Mark.PLANNED) k*.6 else k*.5;val t1=if(m==Mark.PLANNED) t0+.4 else t0+.5
      val px0=e[0]+(e[2]-e[0])*t0;val py0=e[1]+(e[3]-e[1])*t0;val px1=e[0]+(e[2]-e[0])*t1;val py1=e[1]+(e[3]-e[1])*t1
      out["$key.e$i.$k"]=box(min(px0,px1)-w/2,min(py0,py1)-w/2,z+.0005*u,max(px0,px1)+w/2,max(py0,py1)+w/2,z+.001*u,line,on) } }
    hatch(SiteLayers.Box(x0,y0,z,x1,y1,z),0.0).take(3).forEachIndexed { j,(x,y,len) ->
      out["$key.h$j"]=Spec(Shape.BOX,x,y,z+.0008*u,len,3*PX*u,.0005*u,135.0,CYAN,on && m==Mark.BUILDING && len>0) }
  }
  /** Worker hexagon (Yard.dc.html line 43: 40 x 44 px, cyan, number in #06222A): three boxes at 0, 60 and 120 degrees. */
  private fun worker(out: MutableMap<String,Spec>,key: String,x: Double,y: Double,z: Double,number: String,u: Double,on: Boolean) {
    val r=22*PX*u
    for((i,a) in listOf(0.0,60.0,120.0).withIndex()) out["$key#hex$i"]=Spec(Shape.BOX,x,y,z,sqrt(3.0)*r,r,.001*u,a,CYAN,on)
    val size=20*PX*u
    out["$key#num"]=text(x-textWidth(number,size)/2,y,z+.001*u,number,size,ON_CYAN,on)
  }

  /** Every slot's look for this board, pointer and card. Slot keys are stable across frames. Only the pointed slab
   *  changes on point (its edge and its tag) and only the pinched slab's card on pinch: nothing else is recoloured. */
  fun frame(layers: List<SiteLayers.Layer>,layout: SiteLayout,state: SiteState,scale: Double,hover: SiteLayers.Id?,card: SiteLayers.Id?,
    phase: Double=0.0): LinkedHashMap<String,Spec> {
    val out=LinkedHashMap<String,Spec>();val u=1.0/scale
    for(l in layers) {
      val key="${l.id.project}/${l.id.module}";val b=l.box;val s=SiteLayers.state(l,layout,state);val m=mark(s)
      val pointed=hover==l.id
      val line=if(m==Mark.PLANNED) PLANNED else CYAN
      out["$key#body"]=box(b.x0,b.y0,b.z0,b.x1,b.y1,b.z1,0xD95CE1E6L,m==Mark.BUILT)
      val w=(if(pointed) EDGE_POINTED else EDGE)*u
      // Y1: only planned edges are dashed; a solid edge is one box (slot .0), its other slots stay off and unmade.
      val dashed=m==Mark.PLANNED
      edges(b).forEachIndexed { i,(a,c) -> for(k in 0 until DASHES)
        out["$key#e$i.$k"]=if(dashed) segment(a,c,k,true,w,line,true) else if(k==0) whole(a,c,w,line) else segment(a,c,k,false,w,line,false) }
      hatch(b,if(marching(s)) phase else 0.0).forEachIndexed { j,(x,y,len) ->
        out["$key#h$j"]=Spec(Shape.BOX,x,y,b.z1+.0015*u,len,.0016*u,.001*u,135.0,line,m==Mark.BUILDING && len>0) }
      // Tag: beside the slab, joined by a leader line. Only while pointed at and no card is open.
      val tagOn=pointed && card==null;val size=T_TAG*PX*u;val wsize=T_TAG_WORD*PX*u
      val name=BuildSite.words(l.name,2);val word=word(s)
      val tx=b.x1+.03*u;val pad=20*PX*u;val tw=max(textWidth(name,size),textWidth(word,wsize))+2*pad;val th=size+wsize+28*PX*u;val ty=b.y;val tz=b.z1
      out["$key#leader"]=box(b.x1,ty-.001*u,tz-.001*u,tx,ty+.001*u,tz+.001*u,CYAN,tagOn)
      glass(out,"$key#tag",tx,ty-th/2,tx+tw,ty+th/2,tz,R_TAG,u,tagOn)
      out["$key#tagName"]=text(tx+pad,ty+wsize*.5,tz+.002*u,name,size,TEXT,tagOn)
      out["$key#tagWord"]=text(tx+pad,ty-wsize*.7,tz+.002*u,word,wsize,CAPTION,tagOn)
      // Card: name, mark and word, one caption line, Code (filled) and Ask. Same box as B2's card hit.
      val cardOn=card==l.id;val c=SiteLayers.cardBox(l,scale);val cz=c.z1;val mid=c.x;val inX=36*PX*u
      glass(out,"$key#card",c.x0,c.y0,c.x1,c.y1,c.z0,R_CARD,u,cardOn)
      out["$key#title"]=text(c.x0+inX,c.y1-.03*u,cz+.002*u,name,T_TITLE*PX*u,TEXT,cardOn)
      val my=c.y1-.058*u
      statusMark(out,"$key#mark",c.x0+inX,my,cz+.001*u,m,u,cardOn)
      out["$key#word"]=text(c.x0+inX+44*PX*u,my-.004*u,cz+.002*u,word,T_BODY*PX*u,TEXT,cardOn)
      out["$key#line"]=text(c.x0+inX,c.y1-.085*u,cz+.002*u,SiteLayers.line(l,layout,state),T_BODY*PX*u,CAPTION,cardOn)
      val by0=c.y0+.008*u;val by1=by0+BUTTON_H*PX*u;val gap=BUTTON_GAP*PX*u
      rounded(out,"$key#codeFill",c.x0+inX,by0,mid-gap/2,by1,cz+.001*u,cz+.0015*u,R_PILL*PX*u,CYAN,cardOn)
      out["$key#code"]=text(c.x0+inX+.03*u,(by0+by1)/2-.004*u,cz+.003*u,"Code",T_BODY*PX*u,ON_CYAN,cardOn)
      rounded(out,"$key#askEdge",mid+gap/2,by0,c.x1-inX,by1,cz+.001*u,cz+.0015*u,R_PILL*PX*u,CONTROL_EDGE,cardOn)
      out["$key#ask"]=text(mid+gap/2+.03*u,(by0+by1)/2-.004*u,cz+.003*u,"Ask",T_BODY*PX*u,TEXT,cardOn)
    }
    // Workers: one slot per worker beside the slab it is building; it moves and is never re-made.
    for(a in state.agents) {
      val l=layers.firstOrNull { it.id.project==a.project && it.id.module==a.part } ?: layers.firstOrNull { it.id.project==a.project && it.id.module=="tasks" }
      val b=l?.box;val number=a.taskId.ifBlank { a.task.toString() }
      worker(out,"worker:${a.id}",(b?.x1 ?: 0.0)+.012*u,b?.y ?: 0.0,(b?.z1 ?: 0.0)+.003*u,number,u,b!=null)
    }
    for(p in layout.plots) {
      val mine=layers.filter { it.id.project==p.project }
      if(mine.isEmpty()) continue
      val key=p.project;val wait=waiting(p.project,layers,layout,state);val r=.006*u;val y0=.0;val y1=.008*u
      val x0=p.x0-.01;val x1=p.x0+p.w+.01;val z0=p.z0-.01;val z1=p.z0+p.d+.01
      out["$key#ring0"]=box(x0-r,y0,z0-r,x1+r,y1,z0,AMBER,wait);out["$key#ring1"]=box(x0-r,y0,z1,x1+r,y1,z1+r,AMBER,wait)
      out["$key#ring2"]=box(x0-r,y0,z0,x0,y1,z1,AMBER,wait);out["$key#ring3"]=box(x1,y0,z0,x1+r,y1,z1,AMBER,wait)
      // Crane beside a building with a slab being built (Yard.dc.html line 41: mast, jib, cable and hook, 4 px cyan).
      val active=mine.any { mark(SiteLayers.state(it,layout,state))==Mark.BUILDING }
      val top=mine.maxOf { it.box.y1 };val bx0=mine.minOf { it.box.x0 };val bx1=mine.maxOf { it.box.x1 };val bz=mine.first().box.z
      val st=4*PX*u;val mx=bx1+.03*u;val jy=top+.05*u;val hx=(bx0+bx1)/2
      out["$key#craneMast"]=box(mx-st/2,0.0,bz-st/2,mx+st/2,jy,bz+st/2,CYAN,active)
      out["$key#craneJib"]=box(hx-.01*u,jy-st/2,bz-st/2,mx+.06*u,jy+st/2,bz+st/2,CYAN,active)
      out["$key#craneCable"]=box(hx-st/4,top+.012*u,bz-st/4,hx+st/4,jy,bz+st/4,CYAN,active)
      out["$key#craneHook"]=box(hx-.004*u,top+.008*u,bz-.004*u,hx+.004*u,top+.016*u,bz+.004*u,CYAN,active)
      val (n,m)=built(p.project,layers,layout,state);val size=T_BODY*PX*u;val t=pill(p.name,n,m)
      val pw=textWidth(t,size)+60*PX*u;val ph=size*2.6;val px=p.x0+p.w/2-pw/2;val pz=p.z0+p.d+.02;val py=.012*u
      glass(out,"$key#pill",px,py,px+pw,py+ph,pz,R_PILL,u,true)
      out["$key#pillText"]=text(px+30*PX*u,py+ph-size*.9,pz+.002*u,t,size,TEXT,true)
    }
    return out
  }
}

/** Applies [SiteLook.frame]s to retained entities: a slot is created once, then only moved, recoloured or shown/hidden.
 *  Y1 (#1406): a slot is not created until it is first on; with a [Budget], visible slots wait in a queue that [drain]
 *  empties a few per frame (building by building, lowest first; a slot that turns on later goes to the front). */
class SiteLookApply(private val sink: Sink,private val budget: Budget?=null,private val log: (String) -> Unit={}) {
  interface Sink {
    fun create(slot: String,s: SiteLook.Spec)
    fun place(slot: String,s: SiteLook.Spec)
    fun colour(slot: String,s: SiteLook.Spec)
    fun visible(slot: String,on: Boolean)
    /** Text or text size changed: the one look change that needs a new mesh (Text3D). */
    fun text(slot: String,s: SiteLook.Spec)
    fun destroy(slot: String)
  }
  /** Per frame: at most [boxes] boxes or [texts] texts (a text costs boxes/texts units), and stop once [ms] are spent. */
  class Budget(val boxes: Int=8,val texts: Int=1,val ms: Double=4.0,val clockMs: () -> Double={ System.nanoTime()/1e6 })
  private val have=LinkedHashMap<String,SiteLook.Spec>()
  /** Known but not made: the latest spec, made the first time it is on. */
  private val want=LinkedHashMap<String,SiteLook.Spec>()
  private val queue=ArrayDeque<String>()
  private val queued=HashSet<String>()
  private data class Visibility(val on: Boolean,val write: (Boolean) -> Unit)
  private val visibility=LinkedHashMap<Any,Visibility>()
  val visibilityWaiting get()=visibility.size
  private var operation: String?=null
  private var opStart=0.0;private var opFrames=0;private var opWrites=0
  private fun now()=budget?.clockMs?.invoke() ?: System.nanoTime()/1e6
  /** Latest intent wins, including a show that reverses a partly drained hide. */
  fun visibility(key: Any,current: Boolean,on: Boolean,write: (Boolean) -> Unit) {
    requestVisibility(key,current,on,write)
  }
  private fun requestVisibility(key: Any,current: Boolean,on: Boolean,write: (Boolean) -> Unit) {
    if(current==on) { visibility.remove(key);return }
    if(budget==null) write(on) else visibility[key]=Visibility(on,write)
  }
  fun beginVisibility(op: String) {
    if(operation==op) return
    // An interrupted operation finishes when its remaining writes are superseded by the new intent.
    finishVisibility(cancelled=visibility.isNotEmpty() || waiting>0)
    operation=op;opStart=now();opFrames=0;opWrites=0
  }
  private fun finishVisibility(cancelled: Boolean=false) {
    val op=operation ?: return
    log("site event=vis op=$op writes=$opWrites frames=$opFrames ms=%.1f".format(java.util.Locale.ROOT,now()-opStart)+(if(cancelled) " cancelled=true" else ""))
    operation=null
  }
  private fun slotVisibility(k: String,on: Boolean) {
    requestVisibility(k,have.getValue(k).on,on) { value ->
      sink.visible(k,value);have[k]=have.getValue(k).copy(on=value)
    }
  }
  var creates=0;private set
  var destroys=0;private set
  var meshes=0;private set
  /** Slots that exist as entities. */
  val slots get()=have.size
  /** Visible slots waiting to be made. */
  val waiting get()=queued.count { want[it]?.on==true }
  /** Known slots never made (hidden ones and the queue). */
  val notMade get()=want.size
  fun made(slot: String)=slot in have
  fun apply(next: Map<String,SiteLook.Spec>,op: String="focus") {
    beginVisibility(op)
    val fresh=ArrayList<String>();val urgent=ArrayList<String>()
    next.forEach { (k,s) ->
      val o=have[k]
      if(o==null) {
        val w=want.put(k,s)
        if(s.on) {
          if(budget==null) make(k,s)
          else if(k !in queued) { queued+=k;if(w==null) fresh+=k else urgent+=k }
          else if(w?.on==false) { queue.remove(k);urgent+=k }
        }
      } else {
        if(o.text!=s.text || o.size!=s.size) { sink.text(k,s);meshes++ }
        if(o.x!=s.x || o.y!=s.y || o.z!=s.z || o.sx!=s.sx || o.sy!=s.sy || o.sz!=s.sz || o.rot!=s.rot) sink.place(k,s)
        if(o.argb!=s.argb) sink.colour(k,s)
        have[k]=s.copy(on=o.on)
        slotVisibility(k,s.on)
      }
    }
    // Known slots that just turned on (a tag on point, a card on pinch) go to the front, in frame order.
    urgent.asReversed().forEach { queue.addFirst(it) }
    // Never-seen visible slots join the back, building by building in frame order, lowest first.
    val building=HashMap<String,Int>()
    fresh.forEach { building.getOrPut(group(it)) { building.size } }
    fresh.sortedWith(compareBy<String>({ building.getValue(group(it)) },{ want.getValue(it).y })).forEach { queue.addLast(it) }
    // Slots of a building that left the board are hidden and kept (B2's rule), never destroyed mid-session.
    have.keys.filter { it !in next }.forEach { k -> slotVisibility(k,false) }
    want.entries.forEach { e -> if(e.key !in next && e.value.on) e.setValue(e.value.copy(on=false)) }
  }
  private fun group(k: String)=k.substringBefore('#').substringBefore('/')
  private fun make(k: String,s: SiteLook.Spec) { sink.create(k,s);creates++;have[k]=s;want.remove(k) }
  /** One drain per ECS tick. Visibility and creation share the same units and elapsed-time cap.
   * A native call cannot be preempted: after any call reaches 4 ms no further call starts. */
  fun drain(): Int {
    val b=budget ?: return 0
    val t0=b.clockMs();var units=0;var n=0;val textCost=b.boxes/b.texts
    if(operation!=null) opFrames++
    while(visibility.isNotEmpty()) {
      if(units>=b.boxes || (units>0 && b.clockMs()-t0>=b.ms)) break
      val e=visibility.entries.first();visibility.remove(e.key)
      e.value.write(e.value.on);units++;opWrites++
    }
    while(queue.isNotEmpty()) {
      val k=queue.first();val s=want[k]
      if(s==null || !s.on) { queue.removeFirst();queued-=k;continue }
      val cost=if(s.shape==SiteLook.Shape.TEXT) textCost else 1
      if(units+cost>b.boxes || (units>0 && b.clockMs()-t0>=b.ms)) break
      queue.removeFirst();queued-=k;make(k,s);units+=cost;n++;opWrites++
    }
    if(visibility.isEmpty() && waiting==0) finishVisibility()
    return n
  }
  fun hideAll() {
    beginVisibility("hide")
    // Largest projected box face first; text uses its estimated face area. No parent inheritance assumed.
    have.entries.sortedByDescending { (_,s) ->
      if(s.shape==SiteLook.Shape.TEXT) SiteLook.textWidth(s.text,s.size)*s.size*s.text.lines().size
      else maxOf(s.sx*s.sy,s.sx*s.sz,s.sy*s.sz)
    }.forEach { (k,_) -> slotVisibility(k,false) }
    want.entries.forEach { e -> if(e.value.on) e.setValue(e.value.copy(on=false)) }
  }
  fun close() { have.keys.forEach { sink.destroy(it);destroys++ };have.clear();want.clear();queue.clear();queued.clear();visibility.clear();operation=null }
}

/** Y1: `site event=slots` at most four rows a second while building, then one `slots_done` row per build. */
class SiteSlotLog(private val log: (String) -> Unit,private val every: Double=250.0) {
  private var start=-1.0;private var last=-1.0;private var made=0;private var spent=0.0
  fun frame(now: Double,n: Int,ms: Double,queued: Int,total: Int,notMade: Int) {
    if(n==0 && queued==0 && start<0) return
    if(start<0) { start=now;last=now-every }
    made+=n;spent+=ms
    if(queued==0) {
      log("site event=slots_done total=$total not_made=$notMade ms=%.0f".format(java.util.Locale.ROOT,now-start));start=-1.0;made=0;spent=0.0;return
    }
    if(now-last>=every) { log("site event=slots made=$made queued=$queued ms=%.1f".format(java.util.Locale.ROOT,spent));last=now;made=0;spent=0.0 }
  }
}
