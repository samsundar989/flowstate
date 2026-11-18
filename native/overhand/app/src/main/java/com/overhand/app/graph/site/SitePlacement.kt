package com.overhand.app.graph.site

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import com.meta.spatial.core.Quaternion
import com.overhand.app.graph.site.floor.FloorPlacement
import java.util.Locale

/**
 * #1232 the build site is world-locked (developer, worn nov15d: it re-centred with his head and kept disappearing).
 * - Its root pose is written at the first placement and on a system recenter. Nothing else writes it: Center windows
 *   (palm, voice, a resume or mount settle, the first-60 steady head) moves windows, not the site.
 * - It is hidden only when the wearer closes it; reopening restores its last world pose, never the current head.
 * Every pose and visibility write is one `site event=` line naming its why, so a worn bundle names the writer.
 * Pure, so his recorded head path is replayed on the JVM ([SiteWorldLockTest]).
 */
class SitePlacement(private val log: (String) -> Unit = {}) {
  var pose: Pose?=null; private set
  var open=false; private set
  var floor: FloorPlacement.Result?=null; private set
  val inPassthrough get()=floor!=null
  val floorMode get()=floor?.source?.let { it!=FloorPlacement.Source.NONE } == true
  val scale get()=if(floorMode) floor!!.scale else SitePutOn.SCALE
  private var table: Pose?=null

  /** Open (or reopen) the site. [head] is where a first placement stands. Returns the pose to write, or null to keep it. */
  @Synchronized fun open(head: Pose,why: String,floor: FloorPlacement.Result?=null,eyeY: Double=Double.NaN): Pose? {
    open=true;log("site event=visible_set value=true why=open_$why target=root")
    if(pose!=null) { log("site event=pose_kept why=open_$why");return null }
    table=head
    return if(floor!=null) floorWrite(head,floor,eyeY,"first") else write(head,FIRST_PLACE,why)
  }
  @Synchronized fun close(why: String) {
    if(!open) return
    open=false;log("site event=visible_set value=false why=close_$why target=root")
  }
  /** centerAll: only a system recenter re-poses the site, including its cached pose while closed. */
  @Synchronized fun recenter(head: Pose,why: String,floor: FloorPlacement.Result?=null,eyeY: Double=Double.NaN): Pose? {
    if(why!=SYSTEM_RECENTER || pose==null) { log("site event=pose_skipped why=$why");return null }
    table=head
    return if(inPassthrough) floorWrite(head,floor ?: this.floor!!,eyeY,why) else write(head,SYSTEM_RECENTER,why)
  }
  /** A place transition is explicit; ordinary reopen and room localization never re-place the site. */
  @Synchronized fun changePlace(head: Pose,next: FloorPlacement.Result?,eyeY: Double): Pose? {
    if(pose==null || (next!=null)==inPassthrough) return null
    if(next!=null) { table=pose;return floorWrite(head,next,eyeY,"place_enter") }
    val old=floor!!;floor=null
    val restored=table ?: head
    floorLog(old,eyeY,"place_leave",SitePutOn.SCALE)
    return write(restored,"place_leave","place_leave")
  }
  private fun floorWrite(fallback: Pose,result: FloorPlacement.Result,eyeY: Double,why: String): Pose {
    floor=result
    val at=if(result.source==FloorPlacement.Source.NONE) table ?: fallback else Pose(
      Vector3(result.centre.x.toFloat(),result.rootY.toFloat(),result.centre.z.toFloat()),
      Quaternion.fromEuler(0f,result.yawDeg.toFloat(),0f))
    floorLog(result,eyeY,why,scale)
    return write(at,why,why)
  }
  private fun floorLog(r: FloorPlacement.Result,eyeY: Double,why: String,scale: Double) {
    val source=when(r.source) { FloorPlacement.Source.ROOM -> "ROOM";FloorPlacement.Source.FLOOR_ORIGIN -> "DEFAULT";else -> "NONE" }
    log(String.format(Locale.US,"site event=floor_place source=%s scale=%.3f heading_deg=%.2f dist_m=%.3f floor_y=%.3f eye_y=%.3f why=%s",
      source,scale,r.headingDeg,r.distance,r.floorY,eyeY,why))
  }
  private fun write(p: Pose,why: String,trigger: String): Pose {
    pose=p;log("site event=pose_set why=$why trigger=$trigger target=root at=%.2f,%.2f,%.2f".format(p.t.x,p.t.y,p.t.z));return p
  }

  companion object {
    const val FIRST_PLACE="first_place"
    /** RecenterFeature.systemRecenter's resettle why, which reaches centerAll unchanged. */
    const val SYSTEM_RECENTER="system_recenter"
  }
}
