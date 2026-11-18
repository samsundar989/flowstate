package com.overhand.app.graph

import android.graphics.Bitmap
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * #978: the live plan's states drawn by the GraphScene mirror (GraphRender, t775) at the framing distance, from #972's
 * recorded host run: planned (right after `plan`), building (first file written), built (first commit), and a follow-up
 * building then built. Writes build/live_graph_render/live_graph_<state>.png + .json (layout-lint scene-mirror JSON);
 * runs/native/e2e_graph/build.sh lints them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LivePlanRenderTest {
  @Test fun rendersEachState() {
    val flow=JSONObject(File("../../../host/test/fixtures/protocol/build_flow.json").readText()).getJSONArray("frames")
    val events=(0 until flow.length()).map { flow.getJSONObject(it) }.map { it.optJSONObject("frame") ?: it }.filter { it.optString("type")=="event" }
    fun seq(kind: String,task: String,nth: Int=0)=events.filter { it.optString("kind")==kind && it.optString("task_id")==task }[nth].getLong("seq")
    val pid=events.first { it.optString("kind")=="plan" }.getString("project_id")
    val stops=linkedMapOf("planned" to seq("plan","t1"),"building" to seq("file_written","t1",0),"built" to seq("finished","t1"),
      "followup_building" to seq("file_written","t2",1),"followup_built" to seq("finished","t2"))
    val out=File("build/live_graph_render").apply { deleteRecursively();mkdirs() }
    val summary=StringBuilder()
    stops.forEach { (name,upTo) ->
      val p=LivePlan(pid);events.filter { it.getLong("seq")<=upTo }.forEach { p.add(it) }
      val infos=GraphLod.infos(p.components(),JSONObject(),{ it },p.states())
      val r=GraphRender.render("live_graph_$name",p.model(),infos,null,1.0,2.4,pxPerM=700.0)
      File(out,"live_graph_$name.png").outputStream().use { r.bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
      File(out,"live_graph_$name.json").writeText(r.meta.toString(1))
      val els=r.meta.getJSONArray("elements");val texts=(0 until els.length()).map { els.getJSONObject(it) }.filter { it.optString("class")=="Text3D" }
      assertEquals("$name: every part's label is drawn",p.parts.size,texts.size)
      summary.appendLine("live_graph_$name states=${p.states()} texts=${texts.map { it.getString("text") }}")
    }
    File(out,"summary.txt").writeText(summary.toString());print(summary)
  }
}
