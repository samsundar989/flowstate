package com.overhand.app.graph.site

import com.overhand.app.graph.GraphMotion
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** K6 (#1377): the Yard's look. Colours from the spec, amber only on the waiting ring, marks for every part state, and
 *  point/pinch that never create, destroy or re-mesh anything. */
class SiteLookTest {
  private val dir=File("src/main/assets/judge")
  private val arch=JSONObject(dir.resolve("architecture.json").readText())
  private val ps=arch.getJSONArray("projects")
  private val names=(0 until ps.length()).associate { ps.getJSONObject(it).let { p -> p.getString("id") to p.getString("name") } }
  private val rows=dir.resolve("real_day.jsonl").readLines().filter { it.isNotBlank() }.map(::JSONObject)
  private val day=SiteRecorded(arch,rows,{ names.getValue(it) },GraphMotion.MEMBERSHIP)
  private val info=SiteLayers.catalog(arch) { p -> dir.resolve("code/$p.json").takeIf { it.exists() }?.readText()?.let(::JSONObject) ?: JSONObject() }
  private val board=day.board().also { b -> day.events.forEach { b.apply(it) } }
  private val layout=board.layout()
  private val state=board.state(day.end)
  private val scale=.7
  private val layers=SiteLayers.boxes(layout,state,scale,info)
  private val out=File(System.getenv("SITE_LOOK_OUT") ?: "build/site_look").apply { mkdirs() }

  private fun amber(argb: Long): Boolean {
    val r=((argb shr 16) and 255)/255.0;val g=((argb shr 8) and 255)/255.0;val b=(argb and 255)/255.0
    val mx=maxOf(r,g,b);val mn=minOf(r,g,b);if(mx<.5 || mx-mn<.4*mx) return false
    val h=when(mx) { r -> 60*(((g-b)/(mx-mn)).mod(6.0));g -> 60*((b-r)/(mx-mn)+2);else -> 60*((r-g)/(mx-mn)+4) }
    return h in 22.0..58.0
  }
  private fun hex(f: FloatArray)=0xFF000000L or ((f[0]*255+.5).toLong() shl 16) or ((f[1]*255+.5).toLong() shl 8) or (f[2]*255+.5).toLong()

  @Test fun siteColourConstantsEqualTheSpec() {
    assertEquals(0xFF5CE1E6L,SiteLook.CYAN);assertEquals(0xFFFFB547L,SiteLook.AMBER)
    assertEquals(0xFF45D99AL,SiteLook.GREEN);assertEquals(0xFFFF6B6BL,SiteLook.RED)
    assertEquals(0xFFF2F6FAL,SiteLook.TEXT);assertEquals(0xA60C121AL,SiteLook.GLASS);assertEquals(0xFF06222AL,SiteLook.ON_CYAN)
    assertEquals(0x26FFFFFFL,SiteLook.GLASS_EDGE);assertEquals(0xB3FFFFFFL,SiteLook.CAPTION);assertEquals(0x8CFFFFFFL,SiteLook.PLANNED)
    for(c in listOf(SiteMesh.CRANE,SiteMesh.SCAFFOLD,SiteMesh.BLOCKED)) assertEquals(SiteLook.CYAN,hex(c))
  }

  @Test fun noAmberAnywhereInTheSiteExceptTheWaitingRing() {
    // 1. Every drawn slot in every look state of the recorded day.
    val waiting=waitingState()
    for(st in listOf(state,waiting)) for((h,c) in listOf(null to null,layers[0].id to null,null to layers[3].id)) {
      val bad=SiteLook.frame(layers,layout,st,scale,h,c).filter { (k,s) -> amber(s.argb) && !k.contains("#ring") }
      assertTrue("amber outside the ring: ${bad.keys.take(5)}",bad.isEmpty())
    }
    assertTrue(SiteLook.frame(layers,layout,waiting,scale,null,null).any { (k,s) -> k.contains("#ring") && s.on && s.argb==SiteLook.AMBER })
    // 2. Every colour literal in the site's source (rgb(..), Color4(..), 0xAARRGGBB).
    val src=File("src/main/java/com/overhand/app/graph/site").walk().filter { it.extension=="kt" }.toList()
    val hits=mutableListOf<String>()
    for(f in src) f.readLines().forEachIndexed { i,line ->
      Regex("""rgb\((\d+),(\d+),(\d+)\)""").findAll(line).forEach { m -> val (r,g,b)=m.destructured
        if(amber(0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong())) hits+="${f.name}:${i+1} ${m.value}" }
      Regex("""Color4\(([\d.]+)f,\s*([\d.]+)f,\s*([\d.]+)f""").findAll(line).forEach { m -> val v=m.destructured.toList().map { (it.toDouble()*255).toLong() }
        if(amber(0xFF000000L or (v[0] shl 16) or (v[1] shl 8) or v[2])) hits+="${f.name}:${i+1} ${m.value}" }
      Regex("""0x([0-9A-Fa-f]{8})L""").findAll(line).forEach { m ->
        if(amber(m.groupValues[1].toLong(16)) && !line.contains("AMBER=")) hits+="${f.name}:${i+1} ${m.value}" }
    }
    assertTrue("amber literals: $hits",hits.isEmpty())
  }

  /** The recorded end state with the first unfinished part of one project marked blocked (a plot that waits). */
  private fun waitingState(): SiteState {
    val l=layers.first { it.id.module!="tasks" && !state.part(it.id.project,it.id.module).finished }
    val p=state.part(l.id.project,l.id.module)
    return state.copy(parts=state.parts+((l.id.project to l.id.module) to p.copy(state="blocked")))
  }

  @Test fun everyPartStateInTheRecordedDayMapsToASlabMark() {
    val b=day.board();val seen=LinkedHashMap<String,MutableMap<SiteLook.Mark,Int>>()
    var states=0
    for(e in day.events) { b.apply(e);val st=b.state(day.end)
      st.parts.values.forEach { s -> states++
        assertTrue("unknown part state ${s.state}",s.state in setOf("planned","building","built","blocked"))
        val m=SiteLook.mark(s)
        if(s.finished) assertEquals(SiteLook.Mark.BUILT,m)
        if(s.state=="building") assertEquals(SiteLook.Mark.BUILDING,m)
        if(s.state=="planned" && s.done==0) assertEquals(SiteLook.Mark.PLANNED,m)
        seen.getOrPut(s.state) { LinkedHashMap() }.merge(m,1,Int::plus) } }
    assertTrue(states>0)
    out.resolve("marks.txt").writeText("part_states_checked=$states\n"+seen.entries.joinToString("\n") { "${it.key} -> ${it.value}" }+"\n")
  }

  private class Count: SiteLookApply.Sink {
    var create=0;var destroy=0;var mesh=0;var place=0;var colour=0;var visible=0
    val meshSlots=mutableListOf<String>()
    override fun create(slot: String,s: SiteLook.Spec) { create++ }
    override fun place(slot: String,s: SiteLook.Spec) { place++ }
    override fun colour(slot: String,s: SiteLook.Spec) { colour++ }
    override fun visible(slot: String,on: Boolean) { visible++ }
    override fun text(slot: String,s: SiteLook.Spec) { mesh++;meshSlots+=slot }
    override fun destroy(slot: String) { destroy++ }
  }

  @Test fun pointingAndLeavingASlabFiftyTimesCreatesNothingAndSwapsNoMesh() {
    val c=Count();val a=SiteLookApply(c)
    a.apply(SiteLook.frame(layers,layout,state,scale,null,null))
    val first=c.create;assertEquals(a.slots,first)
    val target=layers[layers.size/2].id
    // Y1 (#1406): hidden slots are made the first time they are on, once; so pointing makes only the target's tag.
    val onceOn={ fs: List<Map<String,SiteLook.Spec>> -> fs.flatMap { f -> f.filterValues { it.on }.keys }.toSet() }
    val base=SiteLook.frame(layers,layout,state,scale,null,null)
    repeat(50) {
      a.apply(SiteLook.frame(layers,layout,state,scale,target,null))
      a.apply(SiteLook.frame(layers,layout,state,scale,null,null))
    }
    val tagSlots=(onceOn(listOf(SiteLook.frame(layers,layout,state,scale,target,null)))-onceOn(listOf(base))).size
    val pointCreates=c.create-first-tagSlots;val pointMeshes=c.mesh;val pointDestroys=c.destroy;val pointColours=c.colour
    // And pinching (card open, then moved to another slab, then closed) 50 times.
    val other=layers[0].id
    repeat(50) {
      a.apply(SiteLook.frame(layers,layout,state,scale,target,target))
      a.apply(SiteLook.frame(layers,layout,state,scale,other,other))
      a.apply(SiteLook.frame(layers,layout,state,scale,null,null))
    }
    assertEquals(0,pointCreates);assertEquals(0,pointDestroys);assertEquals(0,pointMeshes)
    val everOn=onceOn(listOf(base,SiteLook.frame(layers,layout,state,scale,target,null),SiteLook.frame(layers,layout,state,scale,target,target),
      SiteLook.frame(layers,layout,state,scale,other,other)))
    assertTrue(tagSlots>0);assertEquals(everOn.size,c.create);assertEquals(0,c.destroy);assertEquals(0,c.mesh)
    assertTrue(c.visible>0 && c.place>0)
    // K6b (#1383): point and pinch never recolour; #1377's replay of the same 250 frames made 331,800 material writes,
    // because the hover dim recoloured every other slab's edges and body. The replay is read as 120 s of use.
    val before=331_800;val perMin={ n: Int -> n/2.0 }
    assertEquals(0,c.colour)
    out.resolve("replay.txt").writeText("slots=$first layers=${layers.size}\npoint_leave_50: entity_creates=$pointCreates entity_destroys=$pointDestroys mesh_swaps=$pointMeshes material=$pointColours\n"+
      "pinch_move_close_50 (cumulative): entity_creates=${c.create-first} entity_destroys=${c.destroy} mesh_swaps=${c.mesh}\n"+
      "calls: visible=${c.visible} material=${c.colour} transform=${c.place}\n"+
      "material_writes_per_min (replay as 120 s): before=${perMin(before)} (#1377) after=${perMin(c.colour)}\n")
  }

  /** A status change recolours only the slab whose state changed (plus its card mark), not the whole site. */
  @Test fun aStatusChangeWritesOnlyTheChangedSlab() {
    val c=Count();val a=SiteLookApply(c)
    a.apply(SiteLook.frame(layers,layout,state,scale,null,null));val first=c.create
    val st=waitingState();val k=st.parts.keys.first { st.parts[it]!!.state=="blocked" && state.parts[it]?.state!="blocked" }
    a.apply(SiteLook.frame(layers,layout,st,scale,null,null))
    val touched=c.colour+c.visible
    assertTrue("status change wrote $touched slots",touched<200)
    out.resolve("status_change.txt").writeText("part=${k.first}/${k.second} material=${c.colour} visible=${c.visible} transform=${c.place} creates_after_first=${c.create-first}\n")
  }

  /** The card's mark is the shared status mark: dashed white, cyan edge with hatch, or solid cyan. */
  @Test fun cardStatusMarkFollowsThePartState() {
    for(m in SiteLook.Mark.values()) {
      val f=LinkedHashMap<String,SiteLook.Spec>()
      SiteLook.statusMark(f,"k",0.0,0.0,0.0,m,1.0,true)
      val fill=f.getValue("k.fill");val e0=f.getValue("k.e0.0");val e1=f.getValue("k.e0.1");val h=f.getValue("k.h1")
      when(m) {
        SiteLook.Mark.PLANNED -> { assertFalse(fill.on);assertFalse(h.on);assertEquals(SiteLook.PLANNED,e0.argb)
          assertTrue("dashed: a gap",(e1.x-e1.sx/2)-(e0.x+e0.sx/2)>0) }
        SiteLook.Mark.BUILDING -> { assertFalse(fill.on);assertTrue(h.on);assertEquals(135.0,h.rot,0.0);assertEquals(SiteLook.CYAN,e0.argb) }
        SiteLook.Mark.BUILT -> { assertTrue(fill.on);assertFalse(h.on);assertEquals(SiteLook.CYAN,fill.argb) }
      }
    }
    // In the frame: one slab of each present mark shows its own mark on its card, never a lone square.
    for(l in layers.distinctBy { SiteLook.mark(SiteLayers.state(it,layout,state)) }) {
      val k="${l.id.project}/${l.id.module}";val m=SiteLook.mark(SiteLayers.state(l,layout,state))
      val g=SiteLook.frame(layers,layout,state,scale,l.id,l.id)
      assertEquals(m==SiteLook.Mark.BUILT,g.getValue("$k#mark.fill").on);assertEquals(m==SiteLook.Mark.BUILDING,g.getValue("$k#mark.h0").on)
    }
  }

  /** Radius 32 card, 20 tag, 999 (half height) pill, in spec px measured from the exported strips; 30% top edge. */
  @Test fun glassIsRoundedWithALighterTopEdge() {
    val l=layers[2];val k="${l.id.project}/${l.id.module}";val px=SiteLook.PX/scale
    val g=SiteLook.frame(layers,layout,state,scale,l.id,l.id);val f=SiteLook.frame(layers,layout,state,scale,l.id,null)
    fun radius(fr: Map<String,SiteLook.Spec>,key: String): Double { val m=fr.getValue("$key.m");val t=fr.getValue("$key.t${SiteLook.STEPS-1}")
      return ((t.y+t.sy/2)-(m.y+m.sy/2))/px }
    assertEquals(32.0,radius(g,"$k#card"),1e-6);assertEquals(20.0,radius(f,"$k#tag"),1e-6)
    val p=layout.plots.first { pl -> layers.any { it.id.project==pl.project } }.project
    val pm=f.getValue("$p#pill.m");val pt=f.getValue("$p#pill.t${SiteLook.STEPS-1}");val pb=f.getValue("$p#pill.b${SiteLook.STEPS-1}")
    assertEquals("pill radius is half its height",((pt.y+pt.sy/2)-(pb.y-pb.sy/2))/2,(pt.y+pt.sy/2)-pm.y,1e-9)
    // Corners are inset: the top strip is narrower than the band under it.
    assertTrue(g.getValue("$k#card.t${SiteLook.STEPS-1}").sx<g.getValue("$k#card.t0").sx && g.getValue("$k#card.t0").sx<g.getValue("$k#card.m").sx)
    assertEquals(SiteLook.GLASS_TOP,g.getValue("$k#cardEdge.t${SiteLook.STEPS-1}").argb);assertEquals(0x4DFFFFFFL,SiteLook.GLASS_TOP)
    assertEquals(SiteLook.GLASS_EDGE,g.getValue("$k#cardEdge.b0").argb)
    val lo="b${SiteLook.STEPS-1}";val cb=g.getValue("$k#card.$lo");val eb=g.getValue("$k#cardEdge.$lo")
    assertEquals("2 px edge",2.0,((cb.y-cb.sy/2)-(eb.y-eb.sy/2))/px,1e-6)
  }

  /** Spec type sizes and the round's 64 dp targets with 16 dp gaps, measured from the exported boxes. */
  @Test fun typeAndTargetsMatchTheSpec() {
    val l=layers[2];val k="${l.id.project}/${l.id.module}";val px=SiteLook.PX/scale
    val g=SiteLook.frame(layers,layout,state,scale,l.id,l.id);val f=SiteLook.frame(layers,layout,state,scale,l.id,null)
    assertEquals(30.0,f.getValue("$k#tagName").size/px,1e-6);assertEquals(24.0,f.getValue("$k#tagWord").size/px,1e-6)
    assertEquals(40.0,g.getValue("$k#title").size/px,1e-6)
    for(s in listOf("word","line","code","ask")) assertEquals(30.0,g.getValue("$k#$s").size/px,1e-6)
    val code=g.getValue("$k#codeFill.t0");val ask=g.getValue("$k#askEdge.t0")
    val ct=g.getValue("$k#codeFill.t${SiteLook.STEPS-1}");val cb=g.getValue("$k#codeFill.b${SiteLook.STEPS-1}")
    val h=((ct.y+ct.sy/2)-(cb.y-cb.sy/2))/px;val w=code.sx/px
    val gap=((ask.x-ask.sx/2)-(code.x+code.sx/2))/px
    assertTrue("button $w x $h px",h>=64 && w>=64);assertTrue("gap $gap px",gap>=16-1e-6)
    // The buttons sit inside B2's Code/Ask hit band.
    val c=SiteLayers.cardBox(l,scale);assertTrue((ct.y+ct.sy/2)<=c.y0+.055/scale+1e-9)
    val slab=layers.minOf { it.box.y1-it.box.y0 }/px
    assertTrue("slab target $slab px",slab>=64)
    out.resolve("targets.txt").writeText("px_per_m_at_scale=${1/px}\nbutton_w_px=$w button_h_px=$h gap_px=$gap\nmin_slab_h_px=$slab\n"+
      "slab_gap_px=${(0.006/scale)/px}\ntag_name_px=30 tag_word_px=24 title_px=40 body_px=30\n")
  }

  /** One worker mark per worker, on the slab it is building; moving it is a transform, never a create. */
  @Test fun workersAreRetainedAndMoveWithTheirPart() {
    val b=day.board();val c=Count();val a=SiteLookApply(c);val seen=HashSet<String>();val known=HashSet<String>();var samples=0;var moved=0
    var last: Map<String,SiteLook.Spec> = emptyMap()
    val retained=HashMap<String,SiteLook.Spec>()
    for((i,e) in day.events.withIndex()) { b.apply(e)
      if(i%25!=0) continue
      val st=b.state(e.tMs);val f=SiteLook.frame(layers,layout,st,scale,null,null)
      val ws=f.filterKeys { it.startsWith("worker:") && it.endsWith("#hex0") }
      ws.forEach { (k,s) -> if(s.on) { samples++;val o=last[k];if(o!=null && o.on && (o.x!=s.x || o.y!=s.y)) moved++ } }
      // Each live worker is beside a slab of its project.
      st.agents.forEach { ag -> val s=f["worker:${ag.id}#hex0"];if(s!=null && s.on) assertTrue(layers.any { it.id.project==ag.project && Math.abs(it.box.y-s.y)<1e-9 }) }
      seen.addAll(ws.keys);last=last+ws
      // After the first frame, the only slots ever created are a worker's, the first time that worker appears.
      // Board captions legitimately change as work progresses. Attribute every mesh update to changed text,
      // and independently require worker slots (including their task numbers) to remain retained.
      val expectedText=f.filter { (k,s) -> a.made(k) && retained[k]?.let { it.text!=s.text || it.size!=s.size } ?: false }.keys.toList()
      val meshBefore=c.mesh
      val firstOn=f.filter { (k,s) -> s.on && !a.made(k) }.size
      val fresh=f.keys-known;val before=c.create;a.apply(f);known.addAll(f.keys);retained.putAll(f)
      assertEquals(expectedText,c.meshSlots.drop(meshBefore))
      assertEquals(firstOn,c.create-before)
      if(i>0) assertTrue("non-worker slot created: ${fresh.take(3)}",fresh.all { it.startsWith("worker:") })
    }
    assertEquals(0,c.destroy)
    assertTrue("worker mesh swaps: ${c.meshSlots.filter { it.startsWith("worker:") }}",c.meshSlots.none { it.startsWith("worker:") })
    assertTrue("workers drawn",samples>0)
    out.resolve("workers.txt").writeText("workers=${seen.size} samples_on=$samples moves=$moved creates=${c.create} worker_mesh_swaps=0 board_text_mesh_swaps=${c.mesh} destroys=${c.destroy}\n")
  }

  /** Cranes beside a building with a slab being built (Yard.dc.html line 41), cyan, off otherwise. */
  @Test fun craneStandsOnlyBesideActiveBuildings() {
    val f=SiteLook.frame(layers,layout,state,scale,null,null)
    for(p in layout.plots) { val mine=layers.filter { it.id.project==p.project };if(mine.isEmpty()) continue
      val active=mine.any { SiteLook.mark(SiteLayers.state(it,layout,state))==SiteLook.Mark.BUILDING }
      val mast=f.getValue("${p.project}#craneMast");assertEquals(active,mast.on);assertEquals(SiteLook.CYAN,mast.argb)
      assertTrue(mast.x>mine.maxOf { it.box.x1 }) }
  }

  @Test fun tagIsBesideThePointedSlabAndTheCardHasCodeFilledAndAsk() {
    val l=layers[2];val k="${l.id.project}/${l.id.module}"
    val f=SiteLook.frame(layers,layout,state,scale,l.id,null)
    assertTrue(f.getValue("$k#tag.t0").on && f.getValue("$k#leader").on && f.getValue("$k#tagName").on && f.getValue("$k#tagWord").on)
    assertTrue(f.getValue("$k#tag.m").x>l.box.x1)
    assertEquals(BuildSite.words(l.name,2),f.getValue("$k#tagName").text)
    assertEquals(SiteLook.EDGE_POINTED/scale*2,f.getValue("$k#e0.0").sy,1e-9)
    assertFalse(f.filterKeys { "#tag" in it && !it.startsWith(k) }.values.any { it.on })
    val g=SiteLook.frame(layers,layout,state,scale,l.id,l.id)
    assertFalse(g.getValue("$k#tag.t0").on)
    assertEquals(SiteLook.CYAN,g.getValue("$k#codeFill.t0").argb);assertEquals(SiteLook.ON_CYAN,g.getValue("$k#code").argb)
    assertTrue(listOf("card.m","title","mark.e0.0","word","line","code","ask","askEdge.t0").all { g.getValue("$k#$it").on })
    // Code and Ask sit where B2's card hit says they are.
    val c=SiteLayers.cardBox(l,scale);val code=g.getValue("$k#codeFill.t0");val ask=g.getValue("$k#askEdge.t0")
    assertTrue(code.x<c.x && ask.x>c.x && code.y+code.sy/2<=c.y0+.055/scale+1e-9 && ask.y+ask.sy/2<=c.y0+.055/scale+1e-9)
    // No text on the line work: every text slot is on glass, and the ground carries no labels.
    val f0=SiteLook.frame(layers,layout,state,scale,null,null)
    assertTrue(f0.filter { it.value.shape==SiteLook.Shape.TEXT && it.value.on }.keys.all { it.endsWith("#pillText") || it.endsWith("#num") })
    val pump=SitePump(day,null,0);val s=pump.step(0,0,true)!!
    assertTrue(SiteLayers.ground(SiteFrame.of(pump.board,s.day,pump.mode,"clock",null,null),scale).text.isEmpty())
  }

  @Test fun planBuildingAndBuiltHaveTheirMarks() {
    val box=SiteLayers.Box(0.0,0.0,0.0,.2,.06,.2)
    SiteLook.hatch(box).forEach { (x,y,len) -> assertTrue(len>0 && x in 0.0..0.2 && y in 0.0..0.06) }
    val byMark=layers.groupBy { SiteLook.mark(SiteLayers.state(it,layout,state)) }
    val f=SiteLook.frame(layers,layout,state,scale,null,null)
    byMark[SiteLook.Mark.BUILT]?.forEach { l -> val k="${l.id.project}/${l.id.module}"
      assertTrue(f.getValue("$k#body").on);assertEquals(SiteLook.CYAN,f.getValue("$k#e0.0").argb);assertFalse(f.getValue("$k#h0").on) }
    byMark[SiteLook.Mark.PLANNED]?.forEach { l -> val k="${l.id.project}/${l.id.module}"
      assertFalse(f.getValue("$k#body").on);assertEquals(SiteLook.PLANNED,f.getValue("$k#e0.0").argb)
      assertTrue(f.getValue("$k#e0.1").x-f.getValue("$k#e0.0").x>f.getValue("$k#e0.0").sx) }  // gaps between dashes
    byMark[SiteLook.Mark.BUILDING]?.forEach { l -> val k="${l.id.project}/${l.id.module}"
      assertFalse(f.getValue("$k#body").on);assertTrue(f.getValue("$k#h0").on);assertEquals(135.0,f.getValue("$k#h0").rot,0.0) }
    out.resolve("marks_end.txt").writeText(byMark.entries.joinToString("\n") { "${it.key}: ${it.value.size}" }+"\n")
  }

  /** Front-view specs for the offline renderer (runs/look17/K6/render.py): rest, point, card, one plot waiting. */
  @Test fun exportLookScenes() {
    val l=layers.first { SiteLook.mark(SiteLayers.state(it,layout,state))==SiteLook.Mark.BUILDING }
    val scenes=listOf("rest" to SiteLook.frame(layers,layout,state,scale,null,null),
      "point" to SiteLook.frame(layers,layout,state,scale,l.id,null),
      "card" to SiteLook.frame(layers,layout,state,scale,l.id,l.id),
      "waiting" to SiteLook.frame(layers,layout,waitingState(),scale,null,null))
    val root=JSONObject()
    for((name,f) in scenes) { val a=JSONArray()
      f.forEach { (k,s) -> if(s.on) a.put(JSONObject().put("slot",k).put("shape",s.shape.name).put("x",s.x).put("y",s.y).put("z",s.z)
        .put("sx",s.sx).put("sy",s.sy).put("sz",s.sz).put("rot",s.rot).put("argb",java.lang.Long.toHexString(s.argb)).put("text",s.text).put("size",s.size)) }
      root.put(name,a) }
    val plots=JSONArray();layout.plots.forEach { p -> plots.put(JSONObject().put("x0",p.x0).put("z0",p.z0).put("w",p.w).put("d",p.d).put("name",p.name)) }
    root.put("plots",plots).put("pointed",l.name)
    out.resolve("scenes.json").writeText(root.toString())
    assertEquals(4,scenes.size)
  }
}
