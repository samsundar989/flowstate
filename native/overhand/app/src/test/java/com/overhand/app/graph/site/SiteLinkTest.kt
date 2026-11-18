package com.overhand.app.graph.site

import android.app.Activity
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import com.overhand.app.data.HttpClient
import com.overhand.app.data.LocalReceiptStore
import com.overhand.app.data.RecordedSource
import com.overhand.app.graph.ArchitectureGraph
import com.overhand.app.windows.RecordedContent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * `site_link_roundtrip_ok`: the site and the real graph panel, linked both ways through one [SiteSelection].
 * For 10 sampled blocks: a ray straight down through the block picks it; the graph opens on that project, focused on
 * that part; the graph's own "Show on site" button sends the selection back, and the site highlights the same block.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class SiteLinkTest {
  private val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
  private val content=RecordedContent(RecordedSource({ File("src/main/assets/$it").readText() },
    LocalReceiptStore(Files.createTempDirectory("sitelink").resolve("receipts.json").toFile()),HttpClient { _,_,_ -> error("Offline") }))
  private val out=File(System.getProperty("site.out") ?: System.getenv("SITE_OUT") ?: "build/site_t1029").apply { mkdirs() }

  @Test fun tenBlocksRoundTripThroughTheGraph() {
    val r=SiteRecorded(content.source.architecture,content.source.events,content::projectName,content.source.membership)
    val b=r.board();r.events.forEach { b.apply(it) }
    val layout=b.layout()
    val selection=SiteSelection()
    val graph=ArchitectureGraph(activity,{ "" },{ },{ content }) { }
    val column=LinearLayout(activity);graph.attach(column)
    // The shell's wiring (InteractionFeature): a site pick opens the graph on the part; "Show on site" selects back.
    selection.listeners.add { p,from -> if(from=="site") { if(graph.project!=p.project) graph.open(p.project);graph.select(p.part) } }
    graph.showOnSite={ p,part -> selection.select(SiteSelection.Pick(p,part),"graph") }
    // Blocks that are parts of a plan (a project with no plan has no parts in the graph: its blocks are its tasks).
    val planned=layout.plots.filter { r.manifest.first { m -> m.first==it.project }.second.isNotEmpty() }
    val all=planned.flatMap { p -> p.parts.map { p.project to it } }
    val sample=(0 until 10).map { all[(it*all.size)/10] }
    val rows=JSONArray();var ok=0
    for((project,part) in sample) {
      val hit=SiteBlockHit.block(layout,doubleArrayOf(part.x,1.0,part.z),doubleArrayOf(0.0,-1.0,0.0))
      assertEquals(SiteSelection.Pick(project,part.id),hit)
      selection.select(hit!!,"site");shadowOf(Looper.getMainLooper()).idle()
      val opened=graph.project==project && graph.selectedPart==part.id
      val button=column.findViewWithTag<Button>("show-on-site")
      button?.performClick();shadowOf(Looper.getMainLooper()).idle()
      val back=selection.current==hit
      // The site draws the returned selection: white apron and beacon over that block.
      val mesh=SiteMesh.build(layout,b.state(r.end),null,null,selection.current)
      val beacon=(0 until mesh.positions.size/3).any { v -> mesh.positions[3*v+1]>SiteMesh.BLOCK_H+0.11 &&
        kotlin.math.abs(mesh.positions[3*v]-part.x)<0.03 && kotlin.math.abs(mesh.positions[3*v+2]-part.z)<0.03 &&
        mesh.colours[4*v]>0.9f && mesh.colours[4*v+1]>0.9f && mesh.colours[4*v+2]>0.9f }
      val pass=opened && button!=null && back && beacon;if(pass) ok++
      rows.put(JSONObject().put("project",project).put("part",part.id).put("graph_project",graph.project).put("graph_focus",graph.selectedPart ?: "")
        .put("show_on_site_button",button!=null).put("returned_same_block",back).put("highlighted",beacon).put("ok",pass))
    }
    val score=ok/10.0
    File(out,"link_roundtrip.json").writeText(JSONObject().put("site_link_roundtrip_ok",score).put("planned_blocks",all.size)
      .put("planless_projects",JSONArray(layout.plots.filter { it !in planned }.map { it.name })).put("samples",rows).toString(1))
    assertEquals(1.0,score,1e-9)
  }
}
