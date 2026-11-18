package com.overhand.app.graph.site.floor

import com.overhand.app.graph.site.floor.FloorPlacement.Furniture
import com.overhand.app.graph.site.floor.FloorPlacement.Head
import com.overhand.app.graph.site.floor.FloorPlacement.Result
import com.overhand.app.graph.site.floor.FloorPlacement.Room
import com.overhand.app.graph.site.floor.FloorPlacement.Source
import com.overhand.app.graph.site.floor.FloorPlacement.V2
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/** #1238 the floor site over a table of rooms. Writes the card's metrics to $FLOOR_SITE_OUT (or build/). */
class FloorPlacementTest {
  companion object {
    /** developer's real head height, nov15d worn session: data/worn/nov15d/overhand_partial_20261115_144631/app/files/
     *  opening_log.jsonl line 55, "viewer"[1] = 1.198845 (median of the file's 85 viewer rows; min 1.10, max 1.25).
     *  hand_frames.jsonl in that bundle has no head rows. Standing/seated posture is NOT CHECKED. */
    const val developer_HEAD_Y = 1.198845
    const val STANDING = 1.65
  }

  private fun rect(x0: Double, z0: Double, x1: Double, z1: Double) = listOf(V2(x0,z0),V2(x1,z0),V2(x1,z1),V2(x0,z1))
  private fun ahead(y: Double) = Head(0.0,y,0.0,0.0,1.0)

  data class Case(val name: String, val head: Head, val floor: Double = 0.0, val room: Room? = null,
    val expect: Source, val check: (Result) -> String? = { null })

  private val cases = listOf(
    Case("big empty room",ahead(STANDING),room=Room(outline=rect(-4.0,-4.0,4.0,4.0)),expect=Source.ROOM) { r ->
      if(r.scale!=FloorPlacement.DEFAULT_SCALE || r.headingDeg!=0.0 || abs(r.distance-FloorPlacement.PREFERRED)>1e-9) "not the default spot: $r" else null },
    Case("no room data",ahead(STANDING),expect=Source.FLOOR_ORIGIN) { r ->
      if(r.scale!=FloorPlacement.DEFAULT_SCALE || r.headingDeg!=0.0 || abs(r.distance-1.7)>1e-9) "not the default spot: $r" else null },
    Case("no room data, turned and off origin",Head(1.2,STANDING,-0.7,-0.6,-0.8),expect=Source.FLOOR_ORIGIN) { r ->
      val dx=r.centre.x-1.2;val dz=r.centre.z+0.7
      if(abs(dx/hypot(dx,dz)+0.6)>1e-6 || abs(dz/hypot(dx,dz)+0.8)>1e-6) "not along the gaze: $r" else null },
    Case("2 x 2 m room, back to a wall",Head(0.0,STANDING,-0.7,0.0,1.0),room=Room(outline=rect(-1.0,-1.0,1.0,1.0)),expect=Source.ROOM) { r ->
      if(r.scale>=FloorPlacement.DEFAULT_SCALE) "did not shrink: $r" else null },
    Case("2 x 2 m room, standing in the middle",ahead(STANDING),room=Room(outline=rect(-1.0,-1.0,1.0,1.0)),expect=Source.NONE),
    Case("desk 1 m ahead",ahead(STANDING),room=Room(outline=rect(-2.5,-2.5,2.5,2.5),
      furniture=listOf(Furniture(rect(-0.6,0.8,0.6,1.4),0.75))),expect=Source.ROOM),
    Case("desk ahead, small room",Head(0.0,STANDING,-1.2,0.0,1.0),room=Room(outline=rect(-1.5,-1.5,1.5,1.5),
      furniture=listOf(Furniture(rect(-0.7,-0.4,0.7,0.2),0.75))),expect=Source.ROOM),
    Case("rug ahead is not an obstacle",ahead(STANDING),room=Room(outline=rect(-4.0,-4.0,4.0,4.0),
      furniture=listOf(Furniture(rect(-1.0,1.0,1.0,2.5),0.01))),expect=Source.ROOM) { r ->
      if(r.headingDeg!=0.0 || r.scale!=FloorPlacement.DEFAULT_SCALE) "moved for a rug: $r" else null },
    Case("seated, head 1.1 m",ahead(1.1),expect=Source.FLOOR_ORIGIN) { r ->
      if(r.topY>1.1-FloorPlacement.EYE_MARGIN+1e-9) "top ${r.topY} not 0.3 m below the eye" else null },
    Case("developer nov15d head 1.199 m",ahead(developer_HEAD_Y),expect=Source.FLOOR_ORIGIN),
    Case("developer nov15d head, room data",ahead(developer_HEAD_Y),room=Room(outline=rect(-3.0,-2.0,3.0,3.0)),expect=Source.ROOM),
    Case("facing a wall 0.6 m away",ahead(STANDING),room=Room(outline=rect(-1.5,-2.4,1.5,0.6)),expect=Source.ROOM) { r ->
      if(abs(r.headingDeg)<90.0) "still facing the wall: $r" else null },
    Case("room scan floor 0.04 m below origin",ahead(STANDING),room=Room(floorY=-0.04,outline=rect(-4.0,-4.0,4.0,4.0)),expect=Source.ROOM) { r ->
      if(abs(r.floorY+0.04)>1e-9) "ignored the scanned floor: $r" else null },
    Case("head on the floor (lying)",ahead(0.4),expect=Source.NONE),
  )

  @Test fun roomsTable() {
    var pass=0;var overlaps=0;var below=0;var above=0;var outside=0
    val lines=ArrayList<String>()
    for(c in cases) {
      val r=FloorPlacement.place(c.head,c.floor,c.room)
      val o=FloorPlacement.overlapsWearer(r,c.head);val b=FloorPlacement.belowFloor(r);val a=FloorPlacement.aboveEye(r,c.head)
      val inRoom=c.room==null || FloorPlacement.insideRoom(r,c.room)
      if(o) overlaps++;if(b) below++;if(a) above++;if(!inRoom) outside++
      val why=when {
        r.source!=c.expect -> "source ${r.source}, expected ${c.expect} (${r.reason})"
        o -> "within ${FloorPlacement.NEAR_EDGE} m of the feet";b -> "below the floor";a -> "above the eye";!inRoom -> "crosses a wall or furniture"
        r.source!=Source.NONE && r.distance>FloorPlacement.PREFERRED_MAX+1e-9 -> "too far: ${r.distance}"
        r.source!=Source.NONE && r.scale<FloorPlacement.MIN_SCALE-1e-9 -> "too small: ${r.scale}"
        else -> c.check(r)
      }
      if(why==null) pass++
      lines.add("%s\t%s\t%s\tscale=%.2f heading=%.0f dist=%.2f top=%.2f feet=%.2f".format(if(why==null) "PASS" else "FAIL",c.name,r.source,
        r.scale,r.headingDeg,r.distance,r.topY,if(r.source==Source.NONE) Double.NaN else FloorPlacement.nearestToFeet(r,c.head))+(why?.let { "\t$it" } ?: ""))
    }
    val planted=FloorPlacement.centredOnWearer(ahead(STANDING))
    val plantedFails=if(FloorPlacement.overlapsWearer(planted,ahead(STANDING))) 1 else 0
    val out=File(System.getenv("FLOOR_SITE_OUT") ?: "build/floor_site")
    out.mkdirs()
    File(out,"cases.tsv").writeText(lines.joinToString("\n")+"\n")
    File(out,"metrics.txt").writeText("floor_site_cases_pass=$pass\nfloor_site_cases_total=${cases.size}\n" +
      "floor_site_overlaps_wearer=$overlaps\nfloor_site_below_floor=$below\nfloor_site_above_eye=$above\n" +
      "floor_site_outside_room=$outside\nfloor_site_planted_fails=$plantedFails\n")
    assertEquals(lines.filter { it.startsWith("FAIL") }.joinToString("\n"),cases.size,pass)
    assertEquals(0,overlaps);assertEquals(0,below);assertEquals(0,above);assertEquals(0,outside)
    assertEquals("planted control (site on the wearer) must fail the overlap check",1,plantedFails)
  }

  @Test fun groundingShapesSitOnTheFloorInsideThePlot() {
    val r=FloorPlacement.place(ahead(STANDING),0.0,null,listOf(FloorPlacement.Rect(-0.3,-0.2,0.1,0.4),FloorPlacement.Rect(0.9,0.9,1.2,1.2)))
    assertEquals(3,r.shapes.size)
    assertEquals("outline",r.shapes[0].kind)
    r.shapes.forEach { s -> s.corners.forEach { p ->
      assertTrue(p.y>=0.0 && p.y<0.01)
      // inside the outline square (world), measured in the site frame
      val dx=p.x-r.centre.x;val dz=p.z-r.centre.z
      assertTrue(abs(dx)<=r.half+1e-9 && abs(dz)<=r.half+1e-9)
    } }
    // the slab bottom rests on the floor: root is lifted by the slab depth at scale
    assertEquals(FloorPlacement.SLAB*r.scale,r.rootY,1e-12)
  }

  @Test fun plantedControlFails() {
    val h=ahead(STANDING);val r=FloorPlacement.centredOnWearer(h)
    assertTrue(FloorPlacement.overlapsWearer(r,h))
    assertEquals(0.0,FloorPlacement.nearestToFeet(r,h),1e-12)
  }

  @Test fun worldLockedSamePoseSameAnswer() {
    val h=Head(0.3,STANDING,0.2,0.2,0.9);val room=Room(outline=rect(-3.0,-3.0,3.0,3.0))
    assertEquals(FloorPlacement.place(h,0.0,room),FloorPlacement.place(h,0.0,room))
  }
}
