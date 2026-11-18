package com.overhand.app.graph.site

/**
 * #1185 the site's 1 Hz pump without Android: which Recorded events are due, the mode, the replay hold. [SiteScene] runs
 * it on the UI thread; the JVM test replays whole loops through the same object.
 */
class SitePump(private val recorded: SiteRecorded?,live: SiteBoard?,now: Long) {
  lateinit var board: SiteBoard;private set
  lateinit var feed: SiteFeed;private set
  private var offered=0
  private var began=0L
  private var heldAt: Long?=null
  var mode=SiteMode.RECORDED;private set
  init { if(recorded!=null) restart(now) else { board=live!!;feed=SiteFeed(live) } }
  private fun restart(now: Long) { val r=recorded ?: return;val b=r.board();board=b;feed=SiteFeed(b);offered=0;began=now }
  val held get()=heldAt!=null
  /** On: nothing is fed or redrawn. Off: the replay resumes where it stopped (the held time is not played). */
  fun hold(on: Boolean,now: Long) {
    if(on) { if(heldAt==null) heldAt=now }
    else heldAt?.let { began+=now-it;heldAt=null }
  }
  data class Step(val day: Long,val arrivals: LongArray?)
  /** Feed due events, flush; null while held. [wall]: System.currentTimeMillis (Live day time). */
  fun step(now: Long,wall: Long,connected: Boolean): Step? {
    if(heldAt!=null) return null
    val f=feed
    val day: Long
    val r=recorded
    if(r!=null) {
      val w=now-began
      if(w>SiteRecorded.PLAY_MS+SiteRecorded.ENDED_MS) { restart(now);return step(now,wall,connected) }
      day=r.dayAt(w)
      while(offered<r.events.size && r.events[offered].tMs<=day) f.offer(r.events[offered++],now)
      mode=if(w>=SiteRecorded.PLAY_MS) SiteMode.DAY_ENDED else SiteMode.RECORDED
    } else { day=wall;mode=if(connected) SiteMode.LIVE else SiteMode.NO_COMPUTER }
    return Step(day,f.flush(now))
  }
}

/** What the wearer would see at one tick, and the redraw key today's path compares (unchanged from #1029). */
class SiteFrame(val layout: SiteLayout,val state: SiteState,val signs: List<SiteSign>,val lit: Set<String>,
  val text: List<SiteMesh.Label>,val target: String?,val pick: SiteSelection.Pick?,val mode: SiteMode) {
  val key get()=state.shape().joinToString("|")+text.joinToString("|") { it.text+it.kind }+lit+pick+mode+target
  /** Every input [SiteMesh.build] reads (the state minus its clock and the agents' words): equal keys, equal meshes. */
  val meshKey get()=listOf(layout,state.parts,state.agents.map { Triple(it.id,it.project,it.part) },target,lit,pick)
  fun mesh()=SiteMesh.build(layout,state,target,lit,pick)
  /** #1202 the same mesh as [mesh], as pieces ([SiteMesh.pieces]). */
  fun pieces()=SiteMesh.pieces(layout,state,target,lit,pick)
  companion object {
    fun of(b: SiteBoard,day: Long,mode: SiteMode,clock: String,target: String?,pick: SiteSelection.Pick?): SiteFrame {
      val l=b.layout();val st=b.state(day,clock);val sg=b.signs(day)
      val lit=sg.filter { SiteText.lit(mode,it) }.map { it.project }.toSet()
      val text=SiteMesh.signLabels(l,st,sg,SiteText.header(mode,b,day),SiteText.subline(mode,clock),mode)
      return SiteFrame(l,st,sg,lit,text,target,pick,mode)
    }
  }
}

/**
 * #1185 a redraw as calls on the scene. Today's path (smooth off): reload the mesh, destroy every label, create every
 * label. Smooth: reload only when [SiteFrame.meshKey] changed; keep labels whose text, kind, size and pose are unchanged,
 * re-text in place one whose only change is its text, destroy and create the rest. UI thread.
 */
class SiteDraw(val once: Boolean=false,private val deduplicate: Boolean=true) {
  sealed class Op {
    data class Mesh(val built: SiteMesh.Built): Op()
    /** #1202 once: a piece's own child entity, hidden, its glTF loaded (one model load). */
    class Load(val id: Int,val built: SiteMesh.Built): Op()
    /** #1202 once: a loaded piece shown or hidden (a Visible write, no load). */
    data class Show(val id: Int,val on: Boolean): Op()
    /** #1202 once: a piece loaded after start, no longer shown: its entity is destroyed (Live grows; nothing piles up). */
    data class Drop(val id: Int): Op()
    data class Destroy(val id: Int): Op()
    data class Create(val id: Int,val label: SiteMesh.Label): Op()
    /** Same entity, new Text3D mesh (Text3D.create gives a Uri; the label's Mesh component is replaced). */
    data class Retext(val id: Int,val label: SiteMesh.Label): Op()
  }
  /** [loads]: pieces loaded by this plan (once); [rebuilt]: the drawn geometry was recomputed (a mesh reload or new pieces). */
  class Plan(val ops: List<Op>,val mesh: SiteMesh.Built?,val made: Int,val kept: Int,val loads: Int=0,val rebuilt: Boolean=mesh!=null)
  private var drawn: String?=null
  private var meshKey: Any?=null
  private var meshContent: SiteOnce.Key?=null
  var meshReason="initial";private set
  private var meshFrame: SiteFrame?=null
  /** A failed import must be retryable even if its next geometry is byte-identical. */
  fun retryMesh() { drawn=null;meshKey=null;meshContent=null;meshFrame=null }
  private val live=LinkedHashMap<Int,SiteMesh.Label>()
  private var next=0
  /** Triangles of the mesh on the root. */
  var triangles=0;private set
  /** The next tick redraws (a new pick, a new put-on); the scene's entities are as they were. */
  fun invalidate() { drawn=null }
  /** The labels were destroyed and the root hidden ([SiteScene.hide]): the next redraw starts from nothing. */
  fun reset() { drawn=null;meshKey=null;live.clear();shown.clear() }

  // ---- #1202 once: every piece loaded once, then only shown or hidden ----
  /** Content (and its n-th copy in one frame) to the piece's id. */
  private val library=HashMap<SiteOnce.Key,Int>()
  private val preloaded=HashSet<Int>()
  private val pending=ArrayList<Op.Load>()
  private val shown=LinkedHashSet<Int>()
  private var pieceIds=0
  /** The root holds the table piece (smooth off: the whole mesh). */
  private var base=false
  /** Start ([SiteScene.startRecorded]): these pieces are loaded with the next plan, before anything is shown. */
  fun preload(pieces: List<SiteMesh.Built>) { if(!once) return
    pieces.map { SiteOnce.Key(it,0) }.forEach { k -> if(k !in library) { val id=pieceIds++;library[k]=id;preloaded.add(id);pending.add(Op.Load(id,k.m)) } } }
  /** The pieces' entities were destroyed ([SiteScene.close]). */
  fun forget() { retryMesh();reset();library.clear();preloaded.clear();pending.clear();base=false }
  val loadedPieces get()=library.size

  private fun piecesPlan(f: SiteFrame,mk: Any?,ops: ArrayList<Op>): Pair<SiteMesh.Built?,Int> {
    var loads=pending.size;ops.addAll(pending);pending.clear()
    val ps=f.pieces();meshKey=mk;triangles=ps.sumOf { it.triangles }
    val root=if(!base) ps[0].also { ops.add(Op.Mesh(it));base=true } else null
    val want=LinkedHashSet<Int>()
    SiteOnce.keys(ps.drop(1)).forEach { k -> want.add(library[k] ?: pieceIds++.also { id -> library[k]=id;ops.add(Op.Load(id,k.m));loads++ }) }
    shown.filter { it !in want }.forEach { id -> ops.add(Op.Show(id,false))
      if(id !in preloaded) { ops.add(Op.Drop(id));library.entries.removeIf { it.value==id } } }
    want.filter { it !in shown }.forEach { ops.add(Op.Show(it,true)) }
    shown.clear();shown.addAll(want)
    return root to loads
  }

  fun plan(f: SiteFrame,smooth: Boolean): Plan? {
    val key=f.key
    if(key==drawn) return null
    drawn=key
    val ops=ArrayList<Op>()
    val mk=f.meshKey
    var loads=0;var rebuilt=false
    var built=if(once && smooth) { if(mk!=meshKey || !base) { rebuilt=true;piecesPlan(f,mk,ops).let { loads=it.second;it.first } } else null }
      else if(!smooth || mk!=meshKey) f.mesh() else null
    if(built!=null && !(once && smooth)) {
      meshKey=mk
      val content=SiteOnce.Key(built,0)
      if(deduplicate && content==meshContent) built=null else {
        val previous=meshFrame
        meshReason=if(previous==null) "initial" else buildList {
          if(previous.layout!=f.layout) add("layout")
          if(previous.state.parts!=f.state.parts) add("parts_or_crew")
          if(previous.state.agents.map { Triple(it.id,it.project,it.part) }!=f.state.agents.map { Triple(it.id,it.project,it.part) }) add("cranes")
          if(previous.target!=f.target) add("target")
          if(previous.lit!=f.lit) add("lit")
          if(previous.pick!=f.pick) add("pick")
        }.joinToString("+").ifEmpty { "geometry_bytes" }
        meshContent=content;meshFrame=f
      }
    }
    if(built!=null && !(once && smooth)) { ops.add(Op.Mesh(built));triangles=built.triangles
      if(once) { shown.forEach { ops.add(Op.Show(it,false)) };shown.clear();base=false } }
    if(!smooth) {
      live.keys.forEach { ops.add(Op.Destroy(it)) };live.clear()
      f.text.forEach { l -> val id=next++;live[id]=l;ops.add(Op.Create(id,l)) }
      return Plan(ops,built,f.text.size,0)
    }
    val old=live.entries.map { it.key to it.value }.toMutableList()
    val placed=arrayOfNulls<Int>(f.text.size)
    f.text.forEachIndexed { i,l -> val k=old.indexOfFirst { same(it.second,l) };if(k>=0) { placed[i]=old[k].first;old.removeAt(k) } }
    val kept=placed.count { it!=null }
    val retext=ArrayList<Op>();val create=ArrayList<Op>()
    f.text.forEachIndexed { i,l -> if(placed[i]!=null) return@forEachIndexed
      val k=old.indexOfFirst { at(it.second,l) }
      if(k>=0) { val id=old[k].first;old.removeAt(k);placed[i]=id;retext.add(Op.Retext(id,l)) }
      else { val id=next++;placed[i]=id;create.add(Op.Create(id,l)) } }
    old.forEach { ops.add(Op.Destroy(it.first)) }
    ops.addAll(retext);ops.addAll(create)
    live.clear();f.text.forEachIndexed { i,l -> live[placed[i]!!]=l }
    return Plan(ops,built,f.text.size-kept,kept,loads,rebuilt || built!=null)
  }
  companion object {
    /** Where and how a label is drawn, its words aside. */
    fun at(a: SiteMesh.Label,b: SiteMesh.Label)=a.kind==b.kind && a.size==b.size && a.x==b.x && a.y==b.y && a.z==b.z
    fun same(a: SiteMesh.Label,b: SiteMesh.Label)=a.text==b.text && at(a,b)
  }
}

/**
 * #1202 the pieces a Recorded loop can show, found at start without drawing: the recording is applied event by event and
 * each distinct day time is cut into pieces (as the pump would draw it: lit while playing, dimmed when the day has ended),
 * plus every crane as the talk target and every pick outline. A state the pump reaches that this misses is loaded then.
 */
object SiteOnce {
  /** A piece by content; [n]: its n-th identical copy within one frame. */
  class Key(val m: SiteMesh.Built,val n: Int) {
    private val h=(m.positions.contentHashCode()*31+m.colours.contentHashCode())*31+m.indices.contentHashCode()*7+n
    override fun hashCode()=h
    override fun equals(other: Any?)=other is Key && other.h==h && other.n==n && other.m.positions.contentEquals(m.positions) &&
      other.m.colours.contentEquals(m.colours) && other.m.indices.contentEquals(m.indices)
  }
  fun keys(pieces: List<SiteMesh.Built>): List<Key> { val seen=HashMap<Key,Int>()
    return pieces.map { m -> val k0=Key(m,0);val n=seen.getOrDefault(k0,0);seen[k0]=n+1;if(n==0) k0 else Key(m,n) } }

  fun library(r: SiteRecorded): List<SiteMesh.Built> {
    val b=r.board();val out=ArrayList<SiteMesh.Built>();val seen=HashSet<Any>();val layouts=java.util.IdentityHashMap<SiteLayout,Boolean>()
    val days=r.events.map { it.tMs }.distinct()
    var k=0
    days.forEachIndexed { i,d ->
      while(k<r.events.size && r.events[k].tMs<=d) b.apply(r.events[k++])
      val l=b.layout();val st=b.state(d);val sg=b.signs(d)
      if(layouts.put(l,true)==null) out.addAll(SiteMesh.pickPieces(l))
      for(mode in if(i==days.size-1) listOf(SiteMode.RECORDED,SiteMode.DAY_ENDED) else listOf(SiteMode.RECORDED)) {
        val lit=sg.filter { SiteText.lit(mode,it) }.map { it.project }.toSet()
        if(!seen.add(listOf(l,st.parts,st.agents.map { Triple(it.id,it.project,it.part) },lit))) continue
        out.addAll(SiteMesh.pieces(l,st,null,lit,null).drop(1))
        SiteMesh.cranes(l,st).forEach { out.add(SiteMesh.cranePiece(it,true)) }
      }
    }
    return out
  }
}
