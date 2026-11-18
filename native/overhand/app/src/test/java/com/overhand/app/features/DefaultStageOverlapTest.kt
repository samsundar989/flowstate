package com.overhand.app.features

import com.meta.spatial.core.*
import com.overhand.app.graph.site.SitePutOn
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.*

/** Perspective-projected quad areas, not world-space intersection or rendered pixels. */
class DefaultStageOverlapTest {
  private data class P(val x: Double,val y: Double)
  private data class Quad(val name: String,val corners: List<Vector3>)
  private fun quad(name: String,p: Pose,w: Float,h: Float)=Quad(name,
    listOf(-1f to -1f,1f to -1f,1f to 1f,-1f to 1f).map { (x,y) -> p.t+p.q*Vector3(x*w/2,y*h/2,0f) })
  private fun project(q: Quad,eye: Float)=q.corners.map { P(it.x.toDouble()/it.z,(it.y-eye).toDouble()/it.z) }
  private fun cross(a: P,b: P,c: P)=(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x)
  private fun area(p: List<P>): Double=if(p.size<3) 0.0 else abs(p.indices.sumOf { i ->
    val j=(i+1)%p.size;p[i].x*p[j].y-p[j].x*p[i].y })/2
  private fun intersection(a: List<P>,b: List<P>): Double {
    var out=a
    for(i in b.indices) {
      val u=b[i];val v=b[(i+1)%b.size];val input=out;out=emptyList()
      if(input.isEmpty()) break
      val next=mutableListOf<P>()
      for(j in input.indices) {
        val s=input[j];val e=input[(j+1)%input.size];val ds=cross(u,v,s);val de=cross(u,v,e)
        if((ds>=0)!=(de>=0)) { val t=ds/(ds-de);next.add(P(s.x+t*(e.x-s.x),s.y+t*(e.y-s.y))) }
        if(de>=0) next.add(e)
      }
      out=next
    }
    return area(out)
  }
  private fun main(frame: Pose): Quad {
    val c=SitePutOn.mainCentre()
    return quad("main",frame*Pose(Vector3(c[0].toFloat(),c[1].toFloat(),c[2].toFloat())),SitePutOn.MAIN_W.toFloat(),SitePutOn.MAIN_H.toFloat())
  }
  private fun decision(frame: Pose,elevation: Double): Quad {
    val o=SettingsPanel.offset(0.0,elevation,2.0)
    return quad("decision",frame*Pose(Vector3(o.x.toFloat(),o.y.toFloat(),o.z.toFloat())),1f,0.72f)
  }
  /** Same corner/site/FOV conditions as #1264, without weakening that test. */
  private fun priorConstraints(q: Quad,eye: Float): Boolean {
    val box=SitePutOn.siteBox();val pitch=atan2(-0.65,2.0)
    val elevations=q.corners.map { Math.toDegrees(atan2((it.y-eye).toDouble(),hypot(it.x.toDouble(),it.z.toDouble()))) }
    if(elevations.min()<=Math.toDegrees(atan2(box.y1,box.z1))) return false
    return q.corners.all {
      val dy=(it.y-eye).toDouble();val z=sin(pitch)*dy+cos(pitch)*it.z
      val y=cos(pitch)*dy-sin(pitch)*it.z
      abs(Math.toDegrees(atan2(it.x.toDouble(),z)))<=44.5 && abs(Math.toDegrees(atan2(y,z)))<=45.0
    }
  }
  private fun fails(fraction: Double,covered: Double)=fraction>0.1 || covered>1e-8
  @Test fun originalGeometryFaultIsCaught() {
    for(eye in listOf(1.0f,1.6f)) {
      val frame=Pose(Vector3(0f,eye,0f))
      val offset=SettingsPanel.offset(0.0,13.0,1.05)
      val oldMain=quad("main",frame*Pose(Vector3(offset.x.toFloat(),offset.y.toFloat(),offset.z.toFloat())),0.84f,0.525f)
      val oldCard=decision(frame,13.0)
      val a=project(oldMain,eye);val b=project(oldCard,eye);val overlap=intersection(a,b)
      assertTrue("old overlap escaped oracle",fails(overlap/min(area(a),area(b)),overlap/area(b)))
      println("old_geometry_eye=$eye covered=${overlap/area(b)} layout_old_geometry_fault_caught=1")
    }
  }
  @Test fun defaultVisibleStageHasNoMainCardOcclusionOrPairOverTenPercent() {
    val lines=mutableListOf("eye_m\ta\tb\toverlap_fraction_smaller\tdecision_covered_fraction")
    var bad=0
    for(eye in listOf(1.0f,1.6f)) {
      val frame=Pose(Vector3(0f,eye,0f))
      val quads=listOf(main(frame),quad("decision",StageWindows.decision(frame),StageWindows.CARD_W,StageWindows.CARD_H))+
        (0 until 6).map { quad("project_$it",StageWindows.project(frame,it,6),0.6f,0.4f) }
      for(i in quads.indices) for(j in i+1 until quads.size) {
        val a=project(quads[i],eye);val b=project(quads[j],eye);val overlap=intersection(a,b)
        val fraction=overlap/min(area(a),area(b));val covered=if(i==0 && j==1) overlap/area(b) else 0.0
        lines.add("$eye\t${quads[i].name}\t${quads[j].name}\t$fraction\t$covered")
        if(fails(fraction,covered)) bad++
      }
      val candidates=(-890..890).map { it/10.0 }.filter { e ->
        val q=decision(frame,e)
        priorConstraints(q,eye) && intersection(project(main(frame),eye),project(q,eye))<1e-8
      }
      lines.add("# eye=$eye elevation_sweep_step_deg=0.1 feasible_elevations=$candidates")
      // Analytic necessary bounds: clear the main's top, or put the card below its bottom.
      val m=project(main(frame),eye);val top=m.maxOf { it.y };val bottom=m.minOf { it.y }
      val clearAbove=Math.toDegrees(atan(top)+asin(0.36/(2*sqrt(1+top*top))))
      val clearBelow=Math.toDegrees(atan(bottom)-asin(0.36/(2*sqrt(1+bottom*bottom))))
      lines.add("# eye=$eye clear_above_min_elevation_deg=$clearAbove clear_below_max_elevation_deg=$clearBelow main_top_deg=${Math.toDegrees(atan(top))}")
    }
    System.getenv("DEFAULT_OVERLAP_OUT")?.let { File(it).apply { parentFile.mkdirs();writeText(lines.joinToString("\n")+"\n") } }
    println(lines.joinToString("\n"))
    assertEquals("default stage overlap failures; see DEFAULT_OVERLAP_OUT",0,bad)
  }
}
