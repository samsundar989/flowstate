package com.overhand.app.graph.site

import com.overhand.app.graph.GraphMotion
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * #1185 the smooth build site: the same picture at every tick with less work. Each fixture is replayed at the pump's
 * 1 s tick through three drivers fed the same inputs: [MainRef] (SiteScene.step/show/apply as they were at main
 * 0419b11c1, transcribed), the app's [SitePump]+[SiteFrame]+[SiteDraw] with smooth off, and with smooth on. A recording
 * stand-in for the ECS applier keeps the scene each leaves. Prints and writes `site_*` metrics (runs/perf/t1185).
 */
class SiteSmoothTest {
  private val dir=File("src/main/assets/judge")
  private val out=File(System.getenv("SMOOTH_OUT") ?: "../../../runs/perf/t1185").apply { mkdirs() }
  private fun rows(f: File)=f.readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
  private val arch=JSONObject(dir.resolve("architecture.json").readText())
  private fun names(a: JSONObject): (String) -> String { val ps=a.getJSONArray("projects")
    val m=(0 until ps.length()).associate { ps.getJSONObject(it).let { p -> p.getString("id") to p.optString("name",p.getString("id")) } };return { m.getValue(it) } }
  private fun recorded()=SiteRecorded(arch,rows(dir.resolve("real_day.jsonl")),names(arch),GraphMotion.MEMBERSHIP)
  private val recordedClock: (Long) -> String={ GraphMotion.clock(it) }
  private val liveClock: (Long) -> String={ t -> DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(t)) }

  // ---- main as it was (SiteScene at 0419b11c1), with the applier calls recorded ----
  class MainRef(private val recorded: SiteRecorded?,live: SiteBoard?,private val clockOf: (Long) -> String) {
    private var board: SiteBoard?=null;var feed: SiteFeed?=null
    private var offered=0;private var began=0L
    var mode=SiteMode.RECORDED
    var drawn: String?=null
    private val labels=ArrayList<Int>();private var next=0
    val calls=ArrayList<String>()
    var lastState: SiteState?=null
    init { if(recorded!=null) restart(0L) else { board=live;feed=SiteFeed(live!!) } }
    private fun restart(now: Long) { val r=recorded ?: return;val b=r.board();board=b;feed=SiteFeed(b);offered=0;began=now }
    fun step(now: Long,wallNow: Long,connected: Boolean,target: String?,pick: SiteSelection.Pick?) {
      val f=feed ?: return
      val day: Long
      val r=recorded
      if(r!=null) {
        val wall=now-began
        if(wall>SiteRecorded.PLAY_MS+SiteRecorded.ENDED_MS) { restart(now);return step(now,wallNow,connected,target,pick) }
        day=r.dayAt(wall)
        while(offered<r.events.size && r.events[offered].tMs<=day) f.offer(r.events[offered++],now)
        mode=if(wall>=SiteRecorded.PLAY_MS) SiteMode.DAY_ENDED else SiteMode.RECORDED
      } else { day=wallNow;mode=if(connected) SiteMode.LIVE else SiteMode.NO_COMPUTER }
      f.flush(now)
      show(day,target,pick)
    }
    private fun show(day: Long,target: String?,pick: SiteSelection.Pick?) {
      val b=board ?: return
      val l=b.layout();val st=b.state(day,clockOf(day));val sg=b.signs(day)
      val lit=sg.filter { SiteText.lit(mode,it) }.map { it.project }.toSet()
      val text=SiteMesh.signLabels(l,st,sg,SiteText.header(mode,b,day),SiteText.subline(mode,clockOf(day)),mode)
      lastState=st
      val key=st.shape().joinToString("|")+text.joinToString("|") { it.text+it.kind }+lit+pick+mode+target
      if(key==drawn) return
      drawn=key
      val mesh=SiteMesh.build(l,st,target,lit,pick)
      calls.add(meshCall(mesh))
      labels.forEach { calls.add("destroy $it") };labels.clear()
      text.forEach { t -> val id=next++;labels.add(id);calls.add("create $id $t") }
    }
    fun hide() { labels.forEach { calls.add("destroy $it") };labels.clear();drawn=null }
  }

  // ---- the recording applier: what the scene holds ----
  class Scene {
    var mesh: SiteMesh.Built?=null;var inputs: List<Any?>?=null;var visible=false
    val labels=LinkedHashMap<Int,SiteMesh.Label>()
    val calls=ArrayList<String>()
    /** #1202 once: loaded piece entities (children of the root) and which are visible. */
    val pieces=HashMap<Int,SiteMesh.Built>();val shown=LinkedHashSet<Int>()
    fun apply(p: SiteDraw.Plan,f: SiteFrame,skip: (SiteDraw.Op) -> Boolean={ false }) { visible=true
      inputs=listOf(f.lit,f.pick,f.target) // Desired inputs can change while the exact generated arrays remain equal.
      if(p.rebuilt && p.mesh==null) inputs=listOf(f.lit,f.pick,f.target)   // #1202 once: the pieces were re-chosen for this frame
      for(op in p.ops) { if(skip(op)) continue
        when(op) {
          is SiteDraw.Op.Load -> { pieces[op.id]=op.built;calls.add("load ${op.id}") }
          is SiteDraw.Op.Show -> { if(op.id in pieces) { if(op.on) shown.add(op.id) else shown.remove(op.id) } }
          is SiteDraw.Op.Drop -> { pieces.remove(op.id);shown.remove(op.id) }
          is SiteDraw.Op.Mesh -> { mesh=op.built;inputs=listOf(f.lit,f.pick,f.target);calls.add(meshCall(op.built)) }
          is SiteDraw.Op.Destroy -> { labels.remove(op.id);calls.add("destroy ${op.id}") }
          is SiteDraw.Op.Create -> { labels[op.id]=op.label;calls.add("create ${op.id} ${op.label}") }
          is SiteDraw.Op.Retext -> { labels[op.id]?.let { labels[op.id]=it.copy(text=op.label.text) };calls.add("retext ${op.id} ${op.label.text}") }
        } } }
    fun hide() { labels.keys.forEach { calls.add("destroy $it") };labels.clear();visible=false;shown.clear() }
    /** #1202 what is drawn: the root's mesh plus every visible piece (the root's own object when no piece is loaded). */
    fun picture(): SiteMesh.Built? { val m=mesh ?: return null;if(pieces.isEmpty()) return m
      val all=listOf(m)+shown.sorted().map { pieces.getValue(it) };var v=0
      val idx=ArrayList<Int>();all.forEach { b -> b.indices.forEach { idx.add(it+v) };v+=b.positions.size/3 }
      return SiteMesh.Built(all.map { it.positions }.reduce { a,b -> a+b },all.map { it.colours }.reduce { a,b -> a+b },idx.toIntArray()) }
    fun labelSet()=labels.values.map { listOf(it.text,it.kind,it.size,it.x,it.y,it.z).joinToString("|") }.sorted()
  }
  companion object {
    fun meshCall(m: SiteMesh.Built)="mesh ${m.triangles} ${m.positions.contentHashCode()} ${m.colours.contentHashCode()} ${m.indices.contentHashCode()}"
    /** Triangles as a set: one 64-bit FNV hash of each triangle's three positions and colours, sorted. */
    fun triangleSet(m: SiteMesh.Built): LongArray { val out=LongArray(m.triangles)
      for(t in 0 until m.triangles) { var h=-0x340d631b7bdddcdbL
        for(c in 0..2) { val v=m.indices[3*t+c]
          for(k in 0..2) { h=(h xor java.lang.Float.floatToIntBits(m.positions[3*v+k]).toLong())*0x100000001b3L }
          for(k in 0..3) { h=(h xor java.lang.Float.floatToIntBits(m.colours[4*v+k]).toLong())*0x100000001b3L } }
        out[t]=h }
      out.sort();return out }
    fun sameBuilt(a: SiteMesh.Built?,b: SiteMesh.Built?)=a!=null && b!=null && a.positions.contentEquals(b.positions) && a.colours.contentEquals(b.colours) && a.indices.contentEquals(b.indices)
    fun pct(xs: List<Long>,q: Double)=if(xs.isEmpty()) 0L else xs.sorted()[((xs.size-1)*q).toInt()]
  }

  /** One driver of the app's own code: a pump, a planner, a scene. */
  class App(recorded: SiteRecorded?,live: SiteBoard?,var smooth: Boolean,once: Boolean=false,val plantReload: Boolean=false) {
    val pump=SitePump(recorded,live,0L);val draw=SiteDraw(once,deduplicate=smooth);val scene=Scene()
    var redraws=0;var reloads=0;var made=0;var kept=0
    /** #1202 model (glTF) loads by tick, counted from the ops the scene receives: a root Mesh(Uri) or a piece Load. */
    val loadsAt=ArrayList<Pair<Int,Int>>();var tick=0
    val preloadMs: Long=if(once && recorded!=null) { val t0=System.nanoTime();draw.preload(SiteOnce.library(recorded));(System.nanoTime()-t0)/1_000_000 } else 0L
    var pieceBytes=0L
    val buildMs=ArrayList<Long>();val bytes=ArrayList<Long>();val tris=ArrayList<Int>()
    var tmp: File?=null
    fun step(now: Long,wall: Long,connected: Boolean,clock: (Long) -> String,target: String?,pick: SiteSelection.Pick?,skip: (SiteDraw.Op) -> Boolean={ false }): Pair<SiteDraw.Plan?,SiteFrame?> {
      val s=pump.step(now,wall,connected) ?: return null to null
      val f=SiteFrame.of(pump.board,s.day,pump.mode,clock(s.day),target,pick)
      val t0=System.nanoTime()
      val p0=draw.plan(f,smooth) ?: return null to f
      // #1202 planted regression: a path that reloads one piece every tick after the first.
      val p=if(plantReload && tick>0) SiteDraw.Plan(p0.ops+SiteDraw.Op.Load(100_000+tick,f.mesh()),p0.mesh,p0.made,p0.kept,p0.loads+1,p0.rebuilt) else p0
      p.mesh?.let { m -> val file=tmp ?: File.createTempFile("site_smooth",".glb").also { it.deleteOnExit();tmp=it };SiteMesh.write(file,m);bytes.add(file.length());tris.add(m.triangles) }
      p.ops.filterIsInstance<SiteDraw.Op.Load>().forEach { op -> val file=tmp ?: File.createTempFile("site_smooth",".glb").also { it.deleteOnExit();tmp=it };SiteMesh.write(file,op.built);pieceBytes+=file.length() }
      if(p.mesh!=null) buildMs.add((System.nanoTime()-t0)/1_000_000)
      loadsAt.add(tick to p.ops.count { it is SiteDraw.Op.Mesh || it is SiteDraw.Op.Load })
      redraws++;if(p.mesh!=null) reloads++;made+=p.made;kept+=p.kept
      scene.apply(p,f,skip)
      return p to f
    }
    fun hide() { scene.hide();draw.reset() }
  }

  class Outcome(val ticks: Int,val mismatch: Int,val offDiffs: Int,val before: App,val after: App,val textOnly: Int,val reallyDiffered: Int,
    val keyMissed: Int,val missedSaving: Int,val firstMismatch: String?,
    /** #1202 the once driver against the smooth one (same scene comparison). */
    val once: App?=null,val onceMismatch: Int=0,val onceFirst: String?=null,val onceTicks: List<Int> = emptyList())

  /**
   * [feedAt] offers a tick's live events (to every board); [targetAt]/[pickAt] give a tick's talk target and pick (from
   * main's last state); [hiddenAt] hides the site (as Classic view or Close does) and shows it again.
   */
  private fun run(ticks: Int,recorded: () -> SiteRecorded?,live: () -> SiteBoard?,clock: (Long) -> String,wall0: Long=0L,
    feedAt: (Int) -> List<SiteEvent> = { emptyList() },connectedAt: (Int) -> Boolean={ true },
    targetAt: (Int,SiteState?) -> String? = { _,_ -> null },pickAt: (Int,SiteLayout?) -> SiteSelection.Pick? = { _,_ -> null },
    hiddenAt: (Int) -> Boolean={ false },plant: Boolean=false,countLoop: IntRange=0 until ticks,
    smoothAt: (Int) -> Boolean={ true },plantReload: Boolean=false): Outcome {
    val main=MainRef(recorded(),live(),clock);val off=App(recorded(),live(),false);val on=App(recorded(),live(),true)
    val once=App(recorded(),live(),true,once=true,plantReload=plantReload)
    var onceMismatch=0;var onceFirst: String?=null;val onceTicks=ArrayList<Int>()
    var pick: SiteSelection.Pick?=null;var hidden=false
    var mismatch=0;var first: String?=null
    var textOnly=0;var differed=0;var keyMissed=0;var missedSaving=0
    var prevBuilt: SiteMesh.Built?=null;var prevKey: Any?=null;var prevLabels: List<SiteMesh.Label> = emptyList()
    val cache=java.util.IdentityHashMap<SiteMesh.Built,LongArray>()
    fun set(m: SiteMesh.Built)=cache.getOrPut(m) { triangleSet(m) }
    var planted=false
    for(i in 0 until ticks) {
      val now=i*SiteScene.TICK_MS;val wall=wall0+now
      feedAt(i).forEach { e -> main.feed!!.offer(e,now);off.pump.feed.offer(e,now);on.pump.feed.offer(e,now);once.pump.feed.offer(e,now) }
      val h=hiddenAt(i)
      if(h && !hidden) { main.hide();off.hide();on.hide();once.hide();hidden=true }
      if(!h && hidden) { hidden=false;main.drawn=null;off.draw.invalidate();on.draw.invalidate();once.draw.invalidate() }   // place(): drawn=null
      if(hidden) continue
      val p=pickAt(i,off.pump.board.layout())
      if(p!=pick) { pick=p;main.drawn=null;off.draw.invalidate();on.draw.invalidate();once.draw.invalidate() }               // selection listener: drawn=null
      // #1202 Settings "Smooth build site" flipped: takes effect at the next redraw (force one, as a changed key would).
      val sm=smoothAt(i);if(sm!=on.smooth) { on.smooth=sm;once.smooth=sm;on.draw.invalidate();once.draw.invalidate() }
      val target=targetAt(i,main.lastState)
      val conn=connectedAt(i)
      main.step(now,wall,conn,target,pick)
      val (po,fo)=off.step(now,wall,conn,clock,target,pick)
      val skip: (SiteDraw.Op) -> Boolean={ op -> plant && !planted && i>0 && (op is SiteDraw.Op.Retext || op is SiteDraw.Op.Create).also { if(it) planted=true } }
      on.step(now,wall,conn,clock,target,pick,skip)
      once.tick=i;once.step(now,wall,conn,clock,target,pick)
      // Today's redraws: was the mesh unchanged, did the key see it, how many labels really differed from the tick before.
      if(po!=null && fo!=null && i in countLoop) {
        val built=po.mesh!!
        if(sameBuilt(built,prevBuilt)) { textOnly++;if(fo.meshKey!=prevKey) missedSaving++ } else if(fo.meshKey==prevKey) keyMissed++
        val left=prevLabels.toMutableList()
        fo.text.forEach { l -> val k=left.indexOfFirst { SiteDraw.same(it,l) };if(k>=0) left.removeAt(k) else differed++ }
      }
      if(po!=null && fo!=null) { prevBuilt=po.mesh;prevKey=fo.meshKey;prevLabels=fo.text }
      // The scene each path leaves.
      val a=off.scene;val b=on.scene
      val same=a.visible==b.visible && a.inputs==b.inputs && a.labelSet()==b.labelSet() &&
        (a.mesh==null)==(b.mesh==null) && (a.mesh==null || set(a.mesh!!).contentEquals(set(b.mesh!!)))
      if(!same) { mismatch++;if(first==null) first="tick $i labels ${a.labelSet().size}/${b.labelSet().size} inputs ${a.inputs==b.inputs}" }
      // #1202 once against smooth: the same comparison, on what each draws.
      val c=once.scene;val bp=b.picture();val cp=c.picture()
      val same2=b.visible==c.visible && b.inputs==c.inputs && b.labelSet()==c.labelSet() && (bp==null)==(cp==null) &&
        (bp==null || set(bp).contentEquals(set(cp!!)))
      if(!same2) { onceMismatch++;onceTicks.add(i);if(onceFirst==null) onceFirst="tick $i labels ${b.labelSet()==c.labelSet()} inputs ${b.inputs==c.inputs} " +
        "triangles ${bp?.triangles}/${cp?.triangles}" }
    }
    // Smooth off against main: the applier call sequences.
    val x=main.calls;val y=off.scene.calls
    val diffs=(0 until maxOf(x.size,y.size)).count { x.getOrNull(it)!=y.getOrNull(it) }
    return Outcome(ticks,mismatch,diffs,off,on,textOnly,differed,keyMissed,missedSaving,first,once,onceMismatch,onceFirst,onceTicks)
  }

  private val loop=((SiteRecorded.PLAY_MS+SiteRecorded.ENDED_MS)/SiteScene.TICK_MS+1).toInt()   // 88 ticks: 0..87 s

  // ---- fixtures ----
  private fun recordedLoops(n: Int,plant: Boolean=false,picks: Boolean=false,smoothAt: (Int) -> Boolean={ true },plantReload: Boolean=false): Outcome = run(loop*n,{ recorded() },{ null },recordedClock,
    targetAt={ i,st -> if(picks && i%30 in 8..20) st?.agents?.getOrNull(i/30%2)?.id else null },
    pickAt={ i,l -> if(picks && i%40>=12) l?.plots?.getOrNull(i/40%2)?.parts?.firstOrNull()?.let { SiteSelection.Pick(it.project,it.id) } else null },
    plant=plant,countLoop=0 until loop,smoothAt=smoothAt,plantReload=plantReload)

  /** Built from nothing: the bundled real host run (frames.jsonl, 204 s), events at their real times, through Live. */
  private fun builtFromNothing(): Outcome {
    val frames=rows(dir.resolve("build_from_nothing/frames.jsonl")).filter { it.optString("type")=="event" }
    val t0=Instant.parse(frames.first().getString("at")).toEpochMilli()
    val byTick=frames.groupBy { ((Instant.parse(it.getString("at")).toEpochMilli()-t0)/1000).toInt() }
    val last=byTick.keys.max()
    return run(last+15,{ null },{ SiteBoard(strict=false) },liveClock,wall0=t0,
      feedAt={ i -> byTick[i].orEmpty().mapNotNull { SiteEvents.host(it,t0+i*1000L) } })
  }

  /** A Live fixture: three projects, plans, agents starting, progress, notes, a lost connection, a pick, a talk target, a hide. */
  private fun liveFixture(): Outcome {
    val t0=1_794_650_000_000L
    fun ev(p: String,kind: String,vararg kv: Pair<String,Any>)=JSONObject().put("project_id",p).put("kind",kind).apply { kv.forEach { (k,v) -> put(k,v) } }
    val script=HashMap<Int,MutableList<JSONObject>>()
    fun at(i: Int,e: JSONObject) { script.getOrPut(i) { mutableListOf() }.add(e) }
    listOf("p_a" to "Weather app","p_b" to "Chess bot","p_c" to "Notes").forEachIndexed { k,(p,n) ->
      at(k,ev(p,"project_created","name" to n))
      at(k+1,ev(p,"plan","parts" to JSONArray(listOf("ui","api","store").map { JSONObject().put("id",it) })))
      for(t in 1..4) {
        val s=4+k*3+t*9;val task="t$t";val part=listOf("ui","api","store")[t%3]
        at(s,ev(p,"task_state","task_id" to task,"state" to "queued","title" to "Task $t of $n"))
        at(s+2,ev(p,"started","task_id" to task,"title" to "Task $t of $n"))
        for(q in 0..3) at(s+3+q*2,ev(p,if(q%2==0) "file_written" else "command_run","task_id" to task,"part_id" to part,"title" to "Step $q of task $t"))
        if(t%2==0) at(s+5,ev(p,"message","title" to "Note $t on $n"))
        at(s+12,ev(p,"finished","task_id" to task,"ok" to (t!=3),"title" to "Task $t of $n"))
      } }
    val last=script.keys.max()+10
    return run(last,{ null },{ SiteBoard(strict=false) },liveClock,wall0=t0,
      feedAt={ i -> script[i].orEmpty().mapNotNull { SiteEvents.host(it,t0+i*1000L) } },
      connectedAt={ it !in 40..48 },
      targetAt={ i,st -> if(i in 20..34) st?.agents?.firstOrNull()?.id else null },
      pickAt={ i,l -> if(i in 25..55) l?.plots?.firstOrNull()?.parts?.lastOrNull()?.let { SiteSelection.Pick(it.project,it.id) } else null },
      hiddenAt={ it in 60..63 })
  }

  @Test fun smoothLeavesTheSameSceneAtEveryTickAndOffIsMain() {
    val rec=recordedLoops(2);val recPick=recordedLoops(2,picks=true);val bfn=builtFromNothing();val live=liveFixture()
    val all=listOf("recorded_2_loops" to rec,"recorded_2_loops_picks_targets" to recPick,"built_from_nothing" to bfn,"live_fixture" to live)
    val mismatch=all.sumOf { it.second.mismatch };val offDiffs=all.sumOf { it.second.offDiffs }
    // Per loop: the first Recorded loop alone, plain replay (no pick, no target), as the wearer sees it.
    val one=recordedLoops(1)
    val b=one.before;val a=one.after
    val bDestroyed=b.scene.calls.count { it.startsWith("destroy") };val aDestroyed=a.scene.calls.count { it.startsWith("destroy") }
    val aCreated=a.scene.calls.count { it.startsWith("create") };val aRetext=a.scene.calls.count { it.startsWith("retext") }
    val bCreated=b.scene.calls.count { it.startsWith("create") }
    val share=one.textOnly.toDouble()/b.redraws
    val table=StringBuilder("metric\tbefore\tafter\tnote\n")
    fun row(m: String,x: Any,y: Any,n: String="") { table.append("$m\t$x\t$y\t$n\n") }
    row("ticks",one.ticks,one.ticks,"one Recorded loop: 75 s play + 12 s ended, 1 s tick")
    row("redraws",b.redraws,a.redraws,"today's key changed")
    row("mesh_reloads",b.reloads,a.reloads,"Mesh(Uri) set on the root = one glTF load each")
    row("glb_bytes_written",b.bytes.sum(),a.bytes.sum())
    row("triangles_p50",pct(b.tris.map { it.toLong() },0.5),pct(a.tris.map { it.toLong() },0.5))
    row("triangles_max",b.tris.maxOrNull() ?: 0,a.tris.maxOrNull() ?: 0)
    row("labels_destroyed",bDestroyed,aDestroyed)
    row("labels_created",bCreated,aCreated,"new Text3D entities")
    row("labels_retexted",0,aRetext,"same entity, new Text3D mesh")
    row("labels_kept",0,a.kept,"untouched entities")
    row("build_ms_p50_pc",pct(b.buildMs,0.5),pct(a.buildMs,0.5),"build + GLB write, PC JVM, per reload")
    row("build_ms_p95_pc",pct(b.buildMs,0.95),pct(a.buildMs,0.95))
    row("redraws_mesh_unchanged",one.textOnly,"","today's redraws whose built mesh equals the one before (bytes)")
    row("labels_really_differed",one.reallyDiffered,"","labels with no identical (text, kind, size, pose) label the redraw before")
    row("mesh_changed_but_key_same",one.keyMissed,"","must be 0: the mesh key never hides a mesh change")
    row("mesh_unchanged_but_key_changed",one.missedSaving,"","reloads the smooth path could still skip")
    File(out,"load_table.tsv").writeText(table.toString())
    val json=JSONObject().put("site_scene_mismatch_ticks",mismatch).put("site_smooth_off_diffs",offDiffs)
      .put("site_redraws_text_only_share",share)
      .put("site_mesh_reloads_per_loop_before",b.reloads).put("site_mesh_reloads_per_loop_after",a.reloads)
      .put("site_labels_created_per_loop_before",bCreated).put("site_labels_created_per_loop_after",aCreated)
      .put("site_labels_retexted_per_loop_after",aRetext)
      .put("fixtures",JSONArray(all.map { (n,r) -> JSONObject().put("name",n).put("ticks",r.ticks).put("mismatch_ticks",r.mismatch)
        .put("off_diffs",r.offDiffs).put("redraws",r.before.redraws).put("reloads_before",r.before.reloads).put("reloads_after",r.after.reloads)
        .put("first_mismatch",r.firstMismatch ?: "") }))
    File(out,"site_smooth.json").writeText(json.toString(1))
    print(table)
    println("site_scene_mismatch_ticks=$mismatch site_smooth_off_diffs=$offDiffs site_redraws_text_only_share=${"%.4f".format(share)} " +
      "site_mesh_reloads_per_loop_before=${b.reloads} site_mesh_reloads_per_loop_after=${a.reloads} " +
      "site_labels_created_per_loop_before=$bCreated site_labels_created_per_loop_after=$aCreated")
    all.forEach { (n,r) -> println("fixture $n ticks=${r.ticks} mismatch=${r.mismatch} off_diffs=${r.offDiffs} redraws=${r.before.redraws} reloads=${r.before.reloads}->${r.after.reloads} first=${r.firstMismatch}") }
    all.forEach { (n,r) -> assertTrue("$n redraws",r.before.redraws>3) }
    assertEquals("site_scene_mismatch_ticks",0,mismatch)
    assertEquals("site_smooth_off_diffs",0,offDiffs)
    assertEquals("the mesh key never hides a mesh change",0,all.sumOf { it.second.keyMissed }+one.keyMissed)
  }

  // ---- #1202 once: every piece loaded at start, then only shown or hidden ----
  /** Model loads (a root Mesh(Uri) or a piece Load) at ticks in [ticks]. */
  private fun loads(a: App,ticks: IntRange)=a.loadsAt.filter { it.first in ticks }.sumOf { it.second }
  /** The acceptance check: no model load in a Recorded loop after its first second (tick 0). */
  private fun reloadsAfterFirstSecond(a: App)=loads(a,1 until loop)

  @Test fun onceLoadsNothingAfterTheFirstSecondAndDrawsTheSame() {
    val rec=recordedLoops(2);val recPick=recordedLoops(2,picks=true);val bfn=builtFromNothing();val live=liveFixture()
    // Settings "Smooth build site" off for ticks 20..35 and 60..70 of a loop, then on again (both modes keep working).
    val toggle=recordedLoops(2,picks=true,smoothAt={ it%loop !in 20..35 && it%loop !in 60..70 })
    val all=listOf("recorded_2_loops" to rec,"recorded_2_loops_picks_targets" to recPick,"built_from_nothing" to bfn,"live_fixture" to live,
      "recorded_2_loops_smooth_switch" to toggle)
    val o=rec.once!!;val s=rec.after
    val after1=reloadsAfterFirstSecond(o);val loop2=loads(o,loop until 2*loop)
    // Start-up: launch to the first complete picture (pump, frame, plan, GLB writes; once adds the preload), median of 7.
    fun startMs(once: Boolean): Long { val xs=(0 until 7).map { val t0=System.nanoTime();val a=App(recorded(),null,true,once=once)
      a.step(0L,0L,true,recordedClock,null,null);(System.nanoTime()-t0)/1_000_000 };return xs.sorted()[3] }
    startMs(true);startMs(false)   // warm the JVM
    val before0=startMs(false);val after0=startMs(true)
    val t0Pieces=o.scene.pieces.size   // all loaded at tick 0 (after1 == 0)
    val table=StringBuilder("metric\tsmooth\tonce\tnote\n")
    fun row(m: String,x: Any,y: Any,n: String="") { table.append("$m\t$x\t$y\t$n\n") }
    row("model_loads_tick0",loads(s,0..0),loads(o,0..0),"first second: root Mesh(Uri) + piece Loads")
    row("model_loads_loop1_after_first_second",reloadsAfterFirstSecond(s),after1,"ticks 1..87 of the first Recorded loop")
    row("model_loads_loop2",loads(s,loop until 2*loop),loop2,"ticks 88..175")
    val one=recordedLoops(1)
    row("labels_created_loop1",one.after.scene.calls.count { it.startsWith("create") },one.once!!.scene.calls.count { it.startsWith("create") },"sign rebuilds (new Text3D entities)")
    row("labels_retexted_loop1",one.after.scene.calls.count { it.startsWith("retext") },one.once!!.scene.calls.count { it.startsWith("retext") })
    row("entities_at_start",1+s.scene.labels.size,1+t0Pieces+o.scene.labels.size,"root + pieces + labels")
    row("glb_bytes_at_start",s.bytes.first(),o.bytes.first()+o.pieceBytes,"once: table on the root + every piece")
    row("pieces_loaded",0,t0Pieces)
    row("pieces_visible_end",0,o.scene.shown.size,"draw calls besides the root")
    row("preload_ms_pc",0,o.preloadMs,"SiteOnce.library on the PC JVM")
    row("start_to_first_picture_ms_pc",before0,after0,"median of 7 warm runs")
    File(out,"once_table.tsv").writeText(table.toString())
    val json=JSONObject().put("site_model_reloads_per_loop",after1).put("site_model_reloads_loop2",loop2)
      .put("site_once_mismatch_ticks",all.sumOf { it.second.onceMismatch }).put("site_once_ticks",all.sumOf { it.second.ticks })
      .put("start_ms_before",before0).put("start_ms_after",after0).put("pieces_loaded",t0Pieces)
      .put("glb_bytes_at_start_before",s.bytes.first()).put("glb_bytes_at_start_after",o.bytes.first()+o.pieceBytes)
      .put("fixtures",JSONArray(all.map { (n,r) -> JSONObject().put("name",n).put("ticks",r.ticks).put("once_mismatch_ticks",r.onceMismatch)
        .put("once_mismatch_list",JSONArray(r.onceTicks)).put("first",r.onceFirst ?: "")
        .put("loads_smooth",r.after.loadsAt.sumOf { it.second }).put("loads_once",r.once!!.loadsAt.sumOf { it.second })
        .put("loads_once_after_tick0",loads(r.once!!,1 until r.ticks)) }))
    File(out,"site_once.json").writeText(json.toString(1))
    print(table)
    all.forEach { (n,r) -> println("once fixture $n ticks=${r.ticks} mismatch=${r.onceMismatch} loads smooth=${r.after.loadsAt.sumOf { it.second }} " +
      "once=${r.once!!.loadsAt.sumOf { it.second }} once_after_tick0=${loads(r.once!!,1 until r.ticks)} first=${r.onceFirst}") }
    println("site_model_reloads_per_loop=$after1 loop2=$loop2")
    assertEquals("once draws what smooth draws at every tick",0,all.sumOf { it.second.onceMismatch })
    assertEquals("site_model_reloads_per_loop (Recorded, after the first second)",0,after1)
    assertEquals("no model load in the second loop",0,loop2)
    all.forEach { (n,r) -> assertTrue("$n: smooth/off drivers never see a piece",r.after.scene.pieces.isEmpty() && r.before.scene.pieces.isEmpty()) }
  }

  @Test fun plantedReloadEveryTickFailsTheCheck() {
    val r=recordedLoops(1,plantReload=true);val n=reloadsAfterFirstSecond(r.once!!)
    println("planted_reload_after_first_second=$n")
    File(out,"planted_reload.json").writeText(JSONObject().put("planted_reloads_after_first_second",n).put("check_passes",n==0).toString(1))
    assertTrue("a path that reloads one piece per tick must fail the zero-reload check",n>0)
  }

  @Test fun propertyOffIsTheDefaultAndTheSitePathIsMains() {
    assertFalse("-PoverhandSiteOnce is off unless asked for",com.overhand.app.BuildConfig.SITE_ONCE)
    assertFalse(SiteScene.ONCE)
    assertFalse(SiteDraw().once)
    // Off: the pieces API is unused and the one mesh is exactly build()'s (the planner never cuts it).
    val r=recorded();val b=r.board();r.events.take(200).forEach { b.apply(it) }
    val st=b.state(r.events[199].tMs);val l=b.layout()
    val whole=SiteMesh.build(l,st);val ps=SiteMesh.pieces(l,st)
    assertArrayEquals(whole.positions,ps.map { it.positions }.reduce { x,y -> x+y },0f)
    assertArrayEquals(whole.colours,ps.map { it.colours }.reduce { x,y -> x+y },0f)
  }

  @Test fun plantedControlSkippingOneLabelUpdateFails() {
    val r=recordedLoops(1,plant=true)
    println("planted_control_mismatch_ticks=${r.mismatch} first=${r.firstMismatch}")
    File(out,"planted_control.json").writeText(JSONObject().put("planted_mismatch_ticks",r.mismatch).put("first",r.firstMismatch ?: "").toString(1))
    assertTrue("a smooth path that skips one label update must show a mismatch",r.mismatch>0)
  }

  @Test fun holdKeepsTheSiteAndResumesWhereItStopped() {
    val held=SitePump(recorded(),null,0L);val plain=SitePump(recorded(),null,0L)
    val a=ArrayList<Long>();val b=ArrayList<Long>()
    for(i in 0..30) { held.step(i*1000L,0,true)?.let { a.add(it.day) } }
    held.hold(true,31_000L)
    for(i in 31..50) assertNull("held: nothing fed or redrawn",held.step(i*1000L,0,true))
    held.hold(false,51_000L)
    for(i in 51..70) held.step(i*1000L,0,true)?.let { a.add(it.day) }
    for(i in 0..50) plain.step(i*1000L,0,true)?.let { b.add(it.day) }
    assertEquals("resumes at the day time it stopped",b,a)
    assertEquals(plain.board.state(b.last()).shape(),held.board.state(a.last()).shape())
  }
}
