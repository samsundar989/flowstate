package com.overhand.app.graph

import androidx.compose.ui.graphics.toArgb
import com.overhand.app.design.FlowstateTokens
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.*

/**
 * t775 acceptance: labels (<= 14 characters, whole words, never an ellipsis) on the recorded day's full node set and
 * on both #759 demo projects; level of detail (nothing drawn below the caption floor at any zoom 0.5x to 8x); zoom
 * keeps the focus point fixed within 1 %. Inputs are the raw asset files, not the code under test.
 */
class GraphLodTest {
  private val assets=File("src/main/assets/judge")
  private val words=File("src/main/assets/windows.js").readText().let { shell ->
    Regex("'([^']+)'").findAll(shell.substringAfter("const deny = [").substringBefore("];")).map { it.groupValues[1] }.toList() }+ArchitectureGraph.EXTRA_DENY
  private val gate=GraphText.pattern(words)
  private fun clean(s: String)=gate.replace(s,"agent")
  /** (file, project, components, code map) for the day's projects and both demos. */
  private fun projects(): List<Triple<String,JSONObject,JSONObject>> {
    val out=mutableListOf<Triple<String,JSONObject,JSONObject>>()
    for(f in listOf("architecture.json","demo/cards/architecture.json","demo/cells/architecture.json","demo/farm/architecture.json","demo/grid/architecture.json")) {
      val a=JSONObject(File(assets,f).readText().removePrefix("﻿"))
      val ps=a.getJSONArray("projects")
      for(i in 0 until ps.length()) { val p=ps.getJSONObject(i)
        if((p.optJSONArray("components")?.length() ?: 0)==0) continue
        val code=if(f.startsWith("demo")) demoCode(File(assets,f).parentFile,p) else File(assets,"code/${p.getString("id")}.json").let { if(it.isFile) JSONObject(it.readText().removePrefix("﻿")) else JSONObject() }
        out.add(Triple(f,p,code)) }
    }
    return out
  }
  /** Demo code.json in the code-map shape (mirrors RecordedSource.demoCodeMap's fields the graph reads). */
  private fun demoCode(dir: File,p: JSONObject): JSONObject {
    val c=JSONObject(File(dir,"code.json").readText().removePrefix("﻿"));val x=c.getJSONArray("excerpts")
    val modules=org.json.JSONArray();val comps=org.json.JSONArray()
    for(i in 0 until x.length()) { val e=x.getJSONObject(i);val id="code-${e.getString("component")}-$i"
      modules.put(JSONObject().put("id",id).put("label",e.getString("component")).put("top",org.json.JSONArray())
        .put("sample",org.json.JSONArray().put(JSONObject().put("id","$id-file").put("label",e.getString("path"))))
        .put("excerpt",JSONObject().put("path",e.getString("path")).put("text",e.getString("text"))))
      comps.put(JSONObject().put("id",e.getString("component")).put("code_ids",org.json.JSONArray().put(id))) }
    return JSONObject().put("modules",modules).put("components",comps)
  }
  private fun components(p: JSONObject)=(0 until p.getJSONArray("components").length()).map { p.getJSONArray("components").getJSONObject(it) }
  private fun model(p: JSONObject)=GraphLayout.build(components(p).map { c -> val a=c.optJSONArray("after")
    Component(c.getString("id"),(0 until (a?.length() ?: 0)).map { a!!.getString(it) }) })

  @Test fun everyNodeLabelFitsOnOneLineInWholeWords() {
    val vocabulary=GraphLabels.ABBREVIATIONS.values.map { it.lowercase() }.toSet()
    val table=StringBuilder();var longest=0;var count=0
    projects().forEach { (file,p,_) -> components(p).forEach { c ->
      val raw=clean(c.optString("short").ifBlank { c.optString("name").ifBlank { c.getString("id") } })
      val label=GraphLabels.shorten(raw)
      count++;longest=max(longest,label.length)
      table.appendLine("$file\t${p.getString("id")}\t${c.getString("id")}\t$raw\t$label\t${label.length}")
      assertTrue("$raw -> '$label' over ${GraphLabels.MAX}",label.length in 1..GraphLabels.MAX)
      assertFalse("one line: $label",label.contains('\n'))
      assertFalse("no ellipsis: $label",label.contains('…') || label.contains("..."))
      // Every word is a whole word of the source (any case) or a table abbreviation; never a fragment.
      val source=Regex("[A-Za-z0-9]+").findAll(raw.replace(Regex("""(?<=[a-z0-9])(?=[A-Z])""")," ")).map { it.value.lowercase() }.toSet()
      Regex("[A-Za-z0-9]+").findAll(label).map { it.value.lowercase() }.forEach { w ->
        assertTrue("'$label' (from '$raw'): '$w' is not a whole source word or table abbreviation",w in source || w in vocabulary)
      }
      // A source that already fits is shown whole (only separators become spaces).
      if(raw.length<=GraphLabels.MAX && !raw.contains('[') && !raw.contains('('))
        assertEquals(raw.replace('_',' ').let { if(it.contains(' ')) it else it.replace('-',' ') }.lowercase(),label.lowercase())
    } }
    // No two nodes of one project share a label.
    projects().forEach { (_,p,code) -> val labels=GraphLod.infos(components(p),code,::clean).values.map { it.label.lowercase() }
      assertEquals("${p.getString("id")} labels unique: $labels",labels.size,labels.toSet().size)
      assertTrue(labels.all { it.length<=GraphLabels.MAX }) }
    File("build/graph_labels.tsv").apply { parentFile.mkdirs() }.writeText(table.toString())
    println("graph_label_max_chars=$longest over $count nodes (build/graph_labels.tsv)")
    assertTrue(count>=50)
  }

  @Test fun knownShortenings() {
    assertEquals("Event schema",GraphLabels.shorten("Agent event schema + replay fixtures"))
    assertEquals("Graph.kt",GraphLabels.shorten("src/app/graph/Graph.kt"))
    assertEquals("Feat geophys",GraphLabels.shorten("features-geophys"))
    assertEquals("Data & labels",GraphLabels.shorten("Data and labels"))
    assertEquals("Replay night",GraphLabels.shorten("Replay the night"))
    assertEquals("Architecture",GraphLabels.shorten("Architecture / code deep-dive in 3D"))
    assertEquals("Feature Stack",GraphLabels.shorten("featureStack"))
    assertEquals("Bridge",GraphLabels.shorten("Bridge: replay | live | record"))
    assertTrue(GraphLabels.shorten("Supercalifragilisticexpialidocious").let { it.length==GraphLabels.MAX && it.endsWith(".") })
  }

  @Test fun nothingIsDrawnBelowTheCaptionFloorAtAnyZoom() {
    assertEquals(FlowstateTokens.TYPE_CAPTION_DEG.toDouble(),GraphLod.MIN_DEG,1e-6)
    var checked=0;val reached=mutableSetOf<GraphLod.Level>()
    projects().forEach { (_,p,code) ->
      val m=model(p);val infos=components(p).associate { it.getString("id") to GraphLod.info(it,code,::clean) }
      m.nodes.forEach { focus ->
        val items=GraphLod.items(m,infos,0,focus.id)
        var z=GraphLod.MIN_ZOOM
        while(z<=GraphLod.MAX_ZOOM+1e-9) {
          for(d in listOf(0.6,1.0,1.6,2.4,3.0)) items.forEach { item ->
            val a=GraphLod.alpha(item.em,z,d)
            if(a>0) {
              checked++
              val deg=GraphLod.degrees(item.em,z,d)
              assertTrue("${item.text} at ${z}x ${d} m is ${deg} deg",deg>=GraphLod.MIN_DEG && deg<=GraphLod.MAX_DEG)
              if(abs(d-2.4)<1e-9) reached.add(item.level)
            }
          }
          z+=0.05
        }
      }
    }
    println("lod_items_checked=$checked levels_reached_at_2.4m=$reached")
    // At the framing distance every level is reachable between 0.5x and 8x; at 0.5x labels are not drawn.
    assertEquals(GraphLod.Level.values().toSet(),reached)
    assertEquals(0.0,GraphLod.alpha(GraphLod.LABEL_EM,0.5,2.4),0.0)
    assertTrue("labels read at rest at 2.4 m",GraphLod.alpha(GraphLod.LABEL_EM,1.0,2.4)>=0.9)
    GraphLod.Level.values().forEach { assertTrue("$it within range",GraphLod.threshold(it,2.4)<GraphLod.MAX_ZOOM) }
  }

  @Test fun detailFitsInsideTheTileAndInsideItsColumn() {
    projects().forEach { (_,p,code) ->
      val m=model(p);val infos=components(p).associate { it.getString("id") to GraphLod.info(it,code,::clean) }
      m.nodes.forEach { n -> GraphLod.items(m,infos,0,n.id).filter { it.node==n.id }.forEach { item ->
        assertTrue("${item.text} width",item.x+item.width<=n.x+GraphLayout.WIDTH/2+1e-9)
        assertTrue("${item.text} left",item.x>=n.x-GraphLayout.WIDTH/2-1e-9)
        assertTrue("${item.text} top",item.y<=n.y+GraphLayout.HEIGHT/2+1e-9)
        assertTrue("${item.text} bottom",item.y-item.height>=n.y-GraphLayout.HEIGHT/2-1e-9)
        assertTrue("${item.text} chars",item.text.length<=GraphLod.maxChars(item.level))
      } }
    }
    // Something real to show at each deeper level on the default projects. (The Geothermal project's module and
    // file names are redacted in the recording, so only its code level has lines; nothing is invented for it.)
    val day=projects().first { it.second.getString("id")=="s74617e7e4f3c9e64" }
    val cards=projects().first { it.first.contains("cards") }
    listOf(day,cards).forEach { (_,p,code) ->
      val d=components(p).map { GraphLod.detail(it,code,::clean) }
      assertTrue("${p.getString("id")} modules",d.any { it.modules.isNotEmpty() })
      assertTrue("${p.getString("id")} code",d.any { it.code.isNotEmpty() })
    }
  }

  /** Rotate v by unit quaternion (w,x,y,z). */
  private fun rot(q: DoubleArray,v: Vec): Vec {
    val (w,x,y,z)=q.toList();val u=Vec(x,y,z);val t=u.cross(v)*2.0
    return v+t*w+u.cross(t)
  }
  @Test fun zoomKeepsTheFocusPointFixed() {
    val rnd=java.util.Random(775);var worst=0.0
    repeat(500) {
      val axis=Vec(rnd.nextGaussian(),rnd.nextGaussian(),rnd.nextGaussian()).unit();val ang=rnd.nextDouble()*PI
      val q=doubleArrayOf(cos(ang/2),axis.x*sin(ang/2),axis.y*sin(ang/2),axis.z*sin(ang/2))
      val root=Vec(rnd.nextDouble()-0.5,1.2+rnd.nextDouble(),-1.5-rnd.nextDouble())
      val s0=GraphLod.MIN_ZOOM+rnd.nextDouble()*(GraphLod.MAX_ZOOM-GraphLod.MIN_ZOOM)
      val local=Vec(rnd.nextDouble()*2-1,rnd.nextDouble()*2-1,-0.02)
      val anchor=root+rot(q,local*s0)
      val target=GraphLod.clampZoom(s0*(0.2+rnd.nextDouble()*5))
      // Every eased frame of the 250 ms transition, as the scene computes it.
      for(f in 0..15) {
        val s=s0+(target-s0)*GraphLod.ease(f/15.0)
        val world=GraphLod.about(root,anchor,s0,s)+rot(q,local*s)
        val err=sqrt((world-anchor).dot(world-anchor))/sqrt((anchor-Vec(0.0,1.6,0.0)).dot(anchor-Vec(0.0,1.6,0.0)))
        worst=max(worst,err)
      }
    }
    println("zoom_focus_drift_max=$worst")
    assertTrue("focus drift $worst",worst<0.01)
  }

  @Test fun pinchedNodeStaysUnderTheHandThroughAllLevels() {
    val (_,p,_)=projects().first { it.second.getString("id")=="s4a3f3b09ec1c0d92" }
    val m=model(p);val eye=Vec(0.0,0.0,-2.4)
    m.nodes.forEach { n ->
      var s=1.0;var root=Vec(0.0,0.0,0.0)
      val anchor=Vec(n.x+0.05,n.y-0.03,n.z-GraphLayout.DEPTH/2)   // world == local at zoom 1, root at origin
      repeat(4) {
        val next=GraphLod.nextZoom(s,sqrt((anchor-eye).dot(anchor-eye)))
        root=GraphLod.about(root,anchor,s,next);s=next
        // The ray from the eye through the hand point, in the scaled graph's local frame, still hits this node.
        val o=(eye-root)*(1/s);val d=anchor-eye
        assertEquals("zoom ${s}x",n.id,GraphLayout.hit(m,o,d))
      }
    }
    assertEquals(1.0,GraphLod.nextZoom(GraphLod.MAX_ZOOM,2.4),0.0)
  }

  @Test fun spreadFollowsTheHandsAndIsClamped() {
    assertEquals(2.0,GraphLod.spread(1.0,0.2,0.4),1e-9)
    assertEquals(GraphLod.MAX_ZOOM,GraphLod.spread(4.0,0.1,0.9),1e-9)
    assertEquals(GraphLod.MIN_ZOOM,GraphLod.spread(1.0,0.5,0.05),1e-9)
  }

  @Test fun ringColoursAreTheDesignStateTokens() {
    fun argb(f: FloatArray)=(0xFF shl 24) or ((f[0]*255).roundToInt() shl 16) or ((f[1]*255).roundToInt() shl 8) or (f[2]*255).roundToInt()
    assertEquals(FlowstateTokens.LiveState.PLANNED.color.toArgb(),argb(GraphLod.ringColour("planned")))
    assertEquals(FlowstateTokens.LiveState.BUILDING.color.toArgb(),argb(GraphLod.ringColour("building")))
    assertEquals(FlowstateTokens.LiveState.BUILT.color.toArgb(),argb(GraphLod.ringColour("built")))
    assertEquals(FlowstateTokens.LiveState.BUILT.color.toArgb(),argb(GraphLod.ringColour("verified")))
    assertEquals(FlowstateTokens.LiveState.BLOCKED.color.toArgb(),argb(GraphLod.ringColour("failed")))
    // State is not colour alone: each state has its own ring shape.
    val shapes=listOf("planned","building","verified","failed").map { GraphMesh.ringSegments(it).size }
    assertEquals(4,shapes.toSet().size)
  }

  @Test fun workerTravelsAlongTheJoiningEdge() {
    val (_,p,_)=projects().first { it.second.getString("id")=="s4a3f3b09ec1c0d92" }
    val m=model(p);val e=m.edges.first()
    val path=GraphLod.workerPath(m,e.from,e.to)!!
    val a=m.nodes.first { it.id==e.from };val b=m.nodes.first { it.id==e.to }
    assertEquals(a.x+GraphLayout.WIDTH/2,GraphLod.along(path,0.0).x,1e-9)
    assertEquals(b.x-GraphLayout.WIDTH/2,GraphLod.along(path,1.0).x,1e-9)
    assertTrue(GraphLod.along(path,0.5).z>a.z)   // travels in the edge's depth lane
    assertEquals(0.20,GraphLod.breath(0),1e-9);assertEquals(0.70,GraphLod.breath(800),1e-9)
  }
}
