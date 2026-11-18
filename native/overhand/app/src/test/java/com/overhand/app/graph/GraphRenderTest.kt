package com.overhand.app.graph

import android.graphics.Bitmap
import com.overhand.app.data.LocalReceiptStore
import com.overhand.app.data.RecordedSource
import com.overhand.app.data.HttpClient
import com.overhand.app.windows.RecordedContent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files

/**
 * t775: off-device renders of the four levels of detail for the card-battle demo project (#759, real code excerpts), at the framing distance
 * (2.4 m), through the same GraphLod rule the headset uses. Writes docs/design/before_after/graph_{level}.png and
 * build/graph_render/<level>.json (layout-lint element JSON).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GraphRenderTest {
  private val content by lazy { RecordedContent(RecordedSource({ File("src/main/assets/$it").readText() },
    LocalReceiptStore(Files.createTempDirectory("graphrender").resolve("receipts.json").toFile()),
    HttpClient { _,_,_ -> error("Offline: HTTP forbidden") },requested="cards")) }
  private val words=File("src/main/assets/windows.js").readText().let { shell ->
    Regex("'([^']+)'").findAll(shell.substringAfter("const deny = [").substringBefore("];")).map { it.groupValues[1] }.toList() }+ArchitectureGraph.EXTRA_DENY
  private val gate=GraphText.pattern(words)

  @Test fun rendersTheFourLevels() {
    val project=content.defaultProject
    val code=content.source.code(project)
    val infos=GraphLod.infos(content.components(project),code,{ s -> gate.replace(s,"agent") })
    val model=content.graph(project)
    // Focus: the node with the most to show at every deeper level.
    val focus=infos.values.filter { it.detail.modules.isNotEmpty() && it.detail.files.isNotEmpty() && it.detail.code.isNotEmpty() }
      .maxByOrNull { it.detail.code.size+it.detail.files.size }?.id ?: infos.keys.first()
    val docs=File("../../../docs/design/before_after").apply { mkdirs() }
    val out=File("build/graph_render").apply { mkdirs() }
    val d=2.4
    val levels=listOf(GraphLod.Level.SYSTEM to 1.0,GraphLod.Level.MODULE to GraphLod.threshold(GraphLod.Level.MODULE,d)*1.25,
      GraphLod.Level.FILE to GraphLod.threshold(GraphLod.Level.FILE,d)*1.25,GraphLod.Level.CODE to GraphLod.clampZoom(GraphLod.threshold(GraphLod.Level.CODE,d)*1.25))
    val summary=StringBuilder()
    levels.forEach { (level,zoom) ->
      val name="graph_${level.name.lowercase()}"
      val r=if(level==GraphLod.Level.SYSTEM) GraphRender.render(name,model,infos,null,zoom,d,pxPerM=700.0)
        else GraphRender.render(name,model,infos,focus,zoom,d,widthM=1.6,heightM=1.0,pxPerM=700.0,
          offset=when(level) { GraphLod.Level.MODULE -> 0.0 to 0.0;GraphLod.Level.FILE -> -0.03 to -0.02;else -> 0.135 to -0.06 })
      File(docs,"$name.png").outputStream().use { r.bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
      File(out,"$name.json").writeText(r.meta.toString(1))
      val els=r.meta.getJSONArray("elements");val texts=(0 until els.length()).map { els.getJSONObject(it) }.filter { it.optString("class")=="Text3D" }
      val drawnLevels=texts.map { it.getString("name").substringBefore('[') }.toSet()
      summary.appendLine("$name zoom=${"%.2f".format(zoom)} focus=$focus texts=${texts.size} levels=$drawnLevels")
      assertTrue("$name draws its level",level.name.lowercase() in drawnLevels)
    }
    before(project,model,File(docs,"graph_before.png"))
    File(out,"summary.txt").writeText(summary.toString())
    print(summary)
  }

  /**
   * The rule on main before t775 (state-filled 0.44 x 0.31 m boxes, PolishRules.graphLabel(name, status) cut by
   * chunked(9).take(3), Text3D 0.09 m), drawn with the same headset calibration the old lint mirror used.
   */
  private fun before(project: String,model: GraphModel,file: File) {
    val components=content.components(project)
    val visible=model.nodes.take(GraphLayout.PAGE_SIZE);val pxPerM=700.0;val margin=0.12
    val minX=visible.minOf { it.x }-GraphLayout.WIDTH/2-margin;val maxX=visible.maxOf { it.x }+GraphLayout.WIDTH/2+margin
    val minY=visible.minOf { it.y }-GraphLayout.HEIGHT/2-margin;val maxY=visible.maxOf { it.y }+GraphLayout.HEIGHT/2+margin
    fun px(x: Double)=((x-minX)*pxPerM).toFloat()
    fun py(y: Double)=((maxY-y)*pxPerM).toFloat()
    val bitmap=Bitmap.createBitmap(((maxX-minX)*pxPerM).toInt(),((maxY-minY)*pxPerM).toInt(),Bitmap.Config.ARGB_8888)
    val canvas=android.graphics.Canvas(bitmap);canvas.drawColor(android.graphics.Color.rgb(16,20,24))
    val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { typeface=android.graphics.Typeface.SANS_SERIF;textSize=(0.09*pxPerM).toFloat();color=android.graphics.Color.WHITE }
    paint.textScaleX=(0.35*pxPerM).toFloat()/paint.measureText("Replay th")
    val fill=android.graphics.Paint()
    visible.forEach { n ->
      val c=components.first { it.getString("id")==n.id };val status=c.optString("shown",c.optString("status"))
      val k=when(status) { "verified","built" -> floatArrayOf(.07f,.34f,.25f);"building" -> floatArrayOf(.12f,.29f,.48f)
        "blocked","at_risk" -> floatArrayOf(.48f,.20f,.08f);"dropped" -> floatArrayOf(.23f,.23f,.27f);else -> floatArrayOf(.22f,.29f,.37f) }
      fill.color=android.graphics.Color.rgb((k[0]*255).toInt(),(k[1]*255).toInt(),(k[2]*255).toInt())
      canvas.drawRect(px(n.x-GraphLayout.WIDTH/2),py(n.y+GraphLayout.HEIGHT/2),px(n.x+GraphLayout.WIDTH/2),py(n.y-GraphLayout.HEIGHT/2),fill)
      val name=gate.replace(com.overhand.app.windows.PolishRules.graphLabel(c.optString("short").ifBlank { c.getString("id").replace('_',' ') },status),"agent")
      val ascent=-paint.fontMetrics.ascent*0.75f/pxPerM;val dy=0.155+0.014-ascent-0.06
      name.chunked(9).take(3).forEachIndexed { row,line -> canvas.drawText(line,px(n.x-0.20+0.061),py(n.y+0.06-row*0.095+dy),paint) }
    }
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
  }
}
