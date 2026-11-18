package com.overhand.app.graph

import android.graphics.*
import com.overhand.app.data.HttpClient
import com.overhand.app.data.LocalReceiptStore
import com.overhand.app.data.RecordedSource
import com.overhand.app.windows.RecordedContent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import kotlin.math.roundToInt

/**
 * #999 off-device stills of the drill's levels, by #863's route (GraphRender, the scene's own geometry and LOD rule):
 * the project with its breadcrumb, the part level (node at [GraphDrill.PART_DEG], files card and breadcrumb drawn from
 * [GraphDrill.cardRects]/[GraphDrill.crumbRects]), and the code level's text as the Code window shows it.
 * Writes build/graph_drill/stills/ (and $AF_OUT/stills/): PNGs, never committed, and stills.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GraphDrillRenderTest {
  private val content by lazy { RecordedContent(RecordedSource({ File("src/main/assets/$it").readText() },
    LocalReceiptStore(Files.createTempDirectory("drillrender").resolve("r.json").toFile()),HttpClient { _,_,_ -> error("offline") })) }
  private val gate=GraphText.pattern(File("src/main/assets/windows.js").readText().let { shell ->
    Regex("'([^']+)'").findAll(shell.substringAfter("const deny = [").substringBefore("];")).map { it.groupValues[1] }.toList() }+ArchitectureGraph.EXTRA_DENY)

  @Test fun rendersTheThreeLevels() {
    val project=content.defaultProject;val model=content.graph(project)
    val infos=GraphLod.infos(content.components(project),content.source.code(project),{ gate.replace(it,"agent") })
    val files=GraphDrill.parse(File("src/main/assets/judge/drill/day.json").readText()) { gate.replace(it,"agent") }
    val part=files.maxByOrNull { it.value.size }!!.key
    val s=GraphSelection<Int>("Headset competition",{ files[it].orEmpty() },{ infos[it]?.label ?: it })
    val outs=listOfNotNull(File("build/graph_drill/stills"),System.getenv("AF_OUT")?.let { File(it,"stills") }).onEach { it.mkdirs() }
    val d=2.4;val manifest=JSONArray()
    fun save(name: String,b: Bitmap,meta: JSONObject) { outs.forEach { o -> File(o,"$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) } }
      manifest.put(meta.put("name","$name.png").put("w",b.width).put("h",b.height)) }
    val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface=Typeface.SANS_SERIF;color=Color.rgb(241,244,247) }
    val plate=Paint().apply { color=Color.rgb(38,43,51) }
    fun overlay(b: Bitmap,rects: List<GraphDrill.Rect>,labels: List<String>,minX: Double,maxY: Double,scale: Double) {
      val c=Canvas(b)
      rects.forEachIndexed { i,r -> val box=RectF(((r.x-r.halfW-minX)*scale).toFloat(),((maxY-(r.y+r.halfH))*scale).toFloat(),((r.x+r.halfW-minX)*scale).toFloat(),((maxY-(r.y-r.halfH))*scale).toFloat())
        c.drawRect(box,plate);paint.textSize=(r.halfH*0.9*scale).toFloat();c.drawText(labels[i],box.left+paint.textSize*0.4f,box.centerY()+paint.textSize*0.35f,paint) }
    }
    // 1. Project: the whole graph and its one-segment breadcrumb.
    val r1=GraphRender.render("drill_project",model,infos,null,1.0,d,pxPerM=700.0)
    val minX1=model.nodes.minOf { it.x }-GraphLayout.WIDTH/2-0.12;val maxY1=model.nodes.maxOf { it.y }+GraphLayout.HEIGHT/2+0.12
    val crumb1=GraphDrill.crumbRects(s.breadcrumb(),minX1+0.12,maxY1-0.02,0.0,1.0,d)
    overlay(r1.bitmap,crumb1,s.breadcrumb(),minX1,maxY1,700.0)
    save("drill_project",r1.bitmap,JSONObject().put("level","PROJECT").put("crumbs",JSONArray(s.breadcrumb())))
    // 2. Part: the node spans 40 degrees; the files card beside it; the breadcrumb above.
    s.select(part,0);val z=GraphDrill.partZoom(d);val n=model.nodes.first { it.id==part }
    val card=GraphDrill.cardRects(n,s.rows().size,s.pages>1,z,d)
    val crumbs=GraphDrill.crumbRects(s.breadcrumb(),n.x-GraphLayout.WIDTH/2,n.y+GraphLayout.HEIGHT/2+card.first().halfH*2*1.6,n.z,z,d)
    val wM=2*d*Math.tan(Math.toRadians(36.0));val hM=2*d*Math.tan(Math.toRadians(24.0))
    val off=((n.x-GraphLayout.WIDTH/2)+(card.first().x+card.first().halfW))/2-n.x
    val r2=GraphRender.render("drill_part",model,infos,part,z,d,widthM=wM,heightM=hM,pxPerM=500.0,offset=off to 0.05)
    val scale2=500.0*z;val minX2=n.x+off-wM/z/2;val maxY2=n.y+0.05+hM/z/2
    overlay(r2.bitmap,card+crumbs,s.rows().map { it.path.substringAfterLast('/') }+(if(s.pages>1) listOf("More files") else emptyList())+
      s.breadcrumb().mapIndexed { i,c -> (if(i>0) "> " else "")+c },minX2,maxY2,scale2)
    val nodeDeg=GraphDrill.degrees(GraphLayout.WIDTH*z,d)
    save("drill_part",r2.bitmap,JSONObject().put("level","PART").put("part",part).put("node_deg",nodeDeg).put("rows",s.rows().size).put("pages",s.pages)
      .put("row_deg",GraphDrill.degrees(2*card.first().halfH*z,d)).put("crumbs",JSONArray(s.breadcrumb())))
    // 3. Code: the opened file as the Code window shows it (monospace, numbered, changed lines marked).
    val f=s.open(0,1)!!;val text=GraphDrill.codeText(f).lines()
    val mono=Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface=Typeface.MONOSPACE;textSize=22f;color=Color.rgb(241,244,247) }
    val b3=Bitmap.createBitmap(1000,60+30*text.size,Bitmap.Config.ARGB_8888);val c3=Canvas(b3);c3.drawColor(Color.rgb(28,31,36))
    paint.textSize=24f;c3.drawText(s.breadcrumb().joinToString(" > "),16f,34f,paint)
    text.forEachIndexed { i,l -> mono.color=if(l.startsWith("+")) Color.rgb(110,225,70) else Color.rgb(241,244,247);c3.drawText(l,16f,(76+30*i).toFloat(),mono) }
    save("drill_code",b3,JSONObject().put("level","CODE").put("file",f.path).put("sha",f.sha).put("lines",text.size).put("changed",JSONArray(f.changed)))
    outs.forEach { File(it,"stills.json").writeText(JSONObject().put("stills",manifest).toString(1)) }
    assertEquals(40.0,nodeDeg,0.5);assertTrue(text.size in 1..GraphDrill.MAX_CODE_LINES);assertTrue(s.rows().size<=GraphDrill.ROWS)
  }
}
