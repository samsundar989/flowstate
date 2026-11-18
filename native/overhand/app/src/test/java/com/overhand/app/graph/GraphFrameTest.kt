package com.overhand.app.graph

import com.overhand.app.data.HttpClient
import com.overhand.app.data.LocalReceiptStore
import com.overhand.app.data.RecordedSource
import com.overhand.app.windows.RecordedContent
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import android.graphics.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.math.*

/**
 * #1380: every part of every recorded project is framed by angle (25 to 40 degrees wide, nothing nearer than 0.9 m),
 * the q3 pinch sequence is replayed before (lane A's fixed 2.4 m / 40-degree zoom) and after, every part's file list
 * is counted, and head's-eye stills of the open view and one drill are drawn before and after.
 * Writes $A2_OUT (default ../../../runs/fix17/A2): frames.tsv, all_parts.tsv, files.tsv, summary.json, PNGs + manifest.tsv.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GraphFrameTest {
  private val out=File(System.getenv("A2_OUT") ?: "../../../runs/fix17/A2").apply { mkdirs() }
  private fun content(key: String)=RecordedContent(RecordedSource({ File("src/main/assets/$it").readText() },
    LocalReceiptStore(Files.createTempDirectory("a2").resolve("r.json").toFile()),HttpClient { _,_,_ -> error("offline") },requested=key))
  private val head=Vec(0.0,1.5,0.0);private val headQ=doubleArrayOf(1.0,0.0,0.0,0.0)
  class Root(val t: Vec,val q: DoubleArray,val zoom: Double)
  private fun opened(model: GraphModel): Root { val p=GraphPlace.choose(null,head,headQ);val z=GraphPlace.fitZoom(model,0,p.dist)
    return Root(GraphPlace.rootT(p.centre,p.q,GraphPlace.localCentre(model),z),p.q,z) }
  /** Lane A (q3): node at 40 degrees, 2.4 m out along the root's forward, 13 degrees left. */
  private fun laneA(model: GraphModel,id: String,eye: Vec,r: Root): Root {
    val n=model.nodes.first { it.id==id };val s=GraphDrill.partZoom(2.4)
    val fwd=GraphPlace.rotate(r.q,Vec(0.0,0.0,1.0));val right=GraphPlace.rotate(r.q,Vec(1.0,0.0,0.0))
    val c=eye+fwd*2.4-right*(2.4*tan(Math.toRadians(13.0)))
    return Root(c-GraphPlace.rotate(r.q,Vec(n.x,n.y,n.z)*s),r.q,s)
  }
  private fun dist(model: GraphModel,id: String,r: Root): Double { val g=GraphFrame.group(model,id)
    val c=Vec(g.map { it.x }.average(),g.map { it.y }.average(),g.map { it.z }.average())
    val w=r.t+GraphPlace.rotate(r.q,c*r.zoom)-head;return sqrt(w.dot(w)) }
  private fun projects()=listOf(RecordedSource.DAY,"cards","cells").flatMap { key -> val c=content(key)
    c.projects.map { it.getString("id") }.filter { c.components(it).isNotEmpty() }.map { Triple(key,c,it) } }
  private fun f2(x: Double)="%.2f".format(x)

  @Test fun everyRecordedPartIsFramedByAngle() {
    var total=0;var ok=0
    val rows=StringBuilder("dataset\tproject\tpart\tzoom_before\twidth_deg_before\tnearest_before\tzoom_after\tdist_after\twidth_deg_after\tnearest_after\tok\n")
    for((key,c,project) in projects()) { val model=c.graph(project);val r=opened(model)
      for(n in model.nodes) { total++
        val f=GraphFrame.frame(model,0,n.id,head,r.q)!!
        val a=laneA(model,n.id,head,r);val (wb,nb)=GraphFrame.measure(GraphFrame.group(model,n.id),model.nodes,head,a.t,a.q,a.zoom)
        val good=f.widthDeg in GraphFrame.MIN_DEG..GraphFrame.MAX_DEG && f.nearest>=GraphFrame.NEAR_M;if(good) ok++
        rows.append(listOf(key,project,n.id,"%.3f".format(a.zoom),"%.1f".format(wb),f2(nb),"%.3f".format(f.framing.zoom),f2(f.dist),"%.1f".format(f.widthDeg),f2(f.nearest),good.toString()).joinToString("\t")).append('\n')
      } }
    File(out,"all_parts.tsv").writeText(rows.toString())
    val share=ok.toDouble()/total
    File(out,"summary.json").writeText(JSONObject().put("graph_frame_ok",share).put("ok",ok).put("total",total).toString(1))
    println("graph_frame_ok=$share ($ok/$total)")
    assertEquals(1.0,share,0.0)
  }

  /** The q3 wear's drill_accept rows on nodes (data/worn/live/2026-11-17_nopid/logcat_stream.txt, OverhandGraph). */
  @Test fun q3PinchSequenceNoLongerSettlesAtFourX() {
    val c=content(RecordedSource.DAY);val project="s4a3f3b09ec1c0d92";val model=c.graph(project);val r=opened(model)
    val t=StringBuilder("when\tpart\tzoom\tdist_m\twidth_deg\tnearest_m\n")
    for(id in listOf("postprocess","submission")) {
      val a=laneA(model,id,head,r);val (wb,nb)=GraphFrame.measure(GraphFrame.group(model,id),model.nodes,head,a.t,a.q,a.zoom)
      t.append(listOf("before",id,"%.4f".format(a.zoom),f2(dist(model,id,a)),"%.1f".format(wb),f2(nb)).joinToString("\t")).append('\n')
      val f=GraphFrame.frame(model,0,id,head,r.q)!!
      t.append(listOf("after",id,"%.4f".format(f.framing.zoom),f2(f.dist),"%.1f".format(f.widthDeg),f2(f.nearest)).joinToString("\t")).append('\n')
      assertEquals(3.9705843738131166,a.zoom,1e-9)
      assertTrue("settled zoom ${f.framing.zoom}",abs(f.framing.zoom-3.97)>0.1)
      assertTrue(f.widthDeg in 25.0..40.0);assertTrue(f.nearest>=0.9)
    }
    File(out,"frames.tsv").writeText(t.toString());print(t)
  }

  @Test fun noPartShowsAnEmptyList() {
    val t=StringBuilder("dataset\tproject\tpart\tfiles_before\tfiles_after\tshows\n");var emptyBefore=0;var emptyAfter=0;var total=0
    for((key,c,project) in projects()) {
      val json=File("src/main/assets/judge/drill/$key.json").readText()
      val before=GraphDrill.parse(json);val fixture=GraphDrill.fixtureFor(json,project);val code=c.source.code(project)
      for(comp in c.components(project)) { total++
        val id=comp.getString("id");val b=before[id].orEmpty().size;val a=GraphDrill.partFiles(fixture,comp,code)
        if(b==0) emptyBefore++;if(a.isEmpty()) emptyAfter++
        val shows=if(a.isNotEmpty()) "files" else "caption: "+GraphDrill.caption(comp)
        assertTrue("Empty content for $project/$id",a.isNotEmpty() || GraphDrill.caption(comp).isNotBlank())
        t.append(listOf(key,project,id,b.toString(),a.size.toString(),shows).joinToString("\t")).append('\n') } }
    t.append("# parts=$total empty_before=$emptyBefore empty_after_files=$emptyAfter (each of those shows its caption line)\n")
    File(out,"files.tsv").writeText(t.toString());println("files: parts=$total empty_before=$emptyBefore empty_after=$emptyAfter")
  }

  // ---- Head's-eye stills: a pinhole camera at the head looking +z, 90 degrees across -------------------------------
  private fun still(name: String,model: GraphModel,r: Root,focus: String?): String {
    val w=960;val h=720;val img=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);val g=Canvas(img)
    val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize=14f;typeface=Typeface.SANS_SERIF }
    g.drawColor(Color.rgb(20,22,26));val f=w/2.0
    fun px(p: Vec): Pair<Double,Double>? { val v=p-head;if(v.z<0.05) return null;return (w/2+f*v.x/v.z) to (h/2-f*v.y/v.z) }
    paint.color=Color.rgb(90,90,90);g.drawLine(w/2f-12,h/2f,w/2f+12,h/2f,paint);g.drawLine(w/2f,h/2f-12,w/2f,h/2f+12,paint)
    val group=focus?.let { GraphFrame.group(model,it).map { n -> n.id }.toSet() } ?: emptySet()
    for(n in model.nodes.sortedByDescending { it.z }) {
      val q=listOf(-1 to -1,1 to -1,1 to 1,-1 to 1).map { (x,y) -> px(r.t+GraphPlace.rotate(r.q,Vec(n.x+x*GraphLayout.WIDTH/2,n.y+y*GraphLayout.HEIGHT/2,n.z-GraphLayout.DEPTH/2)*r.zoom)) }
      if(q.any { it==null }) continue
      val xs=q.map { it!!.first.roundToInt() }.toIntArray();val ys=q.map { it!!.second.roundToInt() }.toIntArray()
      val path=Path().apply { moveTo(xs[0].toFloat(),ys[0].toFloat());for(i in 1..3) lineTo(xs[i].toFloat(),ys[i].toFloat());close() }
      paint.style=Paint.Style.FILL;paint.color=if(n.id==focus) Color.rgb(90,150,200) else if(n.id in group) Color.rgb(60,95,125) else Color.rgb(55,60,70);g.drawPath(path,paint)
      paint.color=Color.rgb(200,210,220);paint.style=Paint.Style.STROKE;paint.strokeWidth=1.5f;g.drawPath(path,paint)
      paint.style=Paint.Style.FILL;g.drawText(n.id,(xs.min()+4).toFloat(),((ys.min()+ys.max())/2).toFloat(),paint)
    }
    paint.color=Color.WHITE;g.drawText("$name: head 1.5 m looking ahead, 90 degrees across; crosshair = straight ahead",10f,20f,paint)
    val file=File(out,"$name.png");file.outputStream().use { img.compress(Bitmap.CompressFormat.PNG,100,it) };img.recycle()
    val sha=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    return "runs/fix17/A2/$name.png\t${file.length()}\t$sha"
  }
  @Test fun headsEyeStillsBeforeAndAfter() {
    val c=content(RecordedSource.DAY);val model=c.graph("s4a3f3b09ec1c0d92")
    // Before: the q3 open as logged (root 1.961,1.477,0.346; the graph panel's yaw q=0.742,0,0.670,0), seen from a
    // head at 1.5 m looking +z. The wear does not log the head pose, so the head is an assumption.
    val qb=doubleArrayOf(0.7421431,0.0,0.6702415,0.0).let { a -> val l=sqrt(a.sumOf { it*it });a.map { it/l }.toDoubleArray() }
    val before=Root(Vec(1.961,1.477,0.346),qb,1.0)
    val after=opened(model)
    val fr=GraphFrame.frame(model,0,"submission",head,after.q)!!.framing
    val m=StringBuilder("path\tbytes\tsha256\n")
    m.append(still("open_before",model,before,null)).append('\n')
    m.append(still("open_after",model,after,null)).append('\n')
    m.append(still("drill_before",model,laneA(model,"submission",head,after),"submission")).append('\n')
    m.append(still("drill_after",model,Root(fr.t,fr.q,fr.zoom),"submission")).append('\n')
    File(out,"manifest.tsv").writeText(m.toString())
  }
}
