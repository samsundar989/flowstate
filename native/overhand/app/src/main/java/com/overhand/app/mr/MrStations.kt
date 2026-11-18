package com.overhand.app.mr
import com.overhand.app.features.windowPose
import com.overhand.app.features.windowVisible

import com.meta.spatial.core.*
import com.meta.spatial.isdk.*
import com.meta.spatial.toolkit.*
import java.util.WeakHashMap

/** One ECS hook; all station placement policy stays with MR. User grabs win. */
object MrStations {
  var surface: DeskPlacement.Frame?=null
  /** Scene anchor the surface came from (ECS thread). Placements bind to it in [store]. */
  var anchor: AnchorRef?=null
  var store: AnchorStore?=null
  /** UI-visible: room load finished without an anchor, so saved transforms are in use. */
  @Volatile var notAnchored=false; private set
  private data class Snap(val frame: DeskPlacement.Frame,val pose: Pose)
  private val applied=WeakHashMap<Entity,Snap>()
  private val floating=WeakHashMap<Entity,Pose>()

  /** Room load outcome: relocalize the desk record against [next], or log the fallback. */
  fun settle(next: AnchorRef?, reason: String) {
    anchor=next;notAnchored=next==null
    val s=store ?: return
    s.keys().forEach { s.resolve(it,next) }
    if(next!=null) s.persist("desk",next.pose,next)
    else s.log.event("failed","desk",s.record("desk")?.anchor,"not anchored: $reason")
  }

  fun persist(key: String, pose: Pose) { store?.persist(key,pose,if(surface!=null) anchor else null) }

  fun place(entity: Entity, key: String, slot: Int, count: Int, viewer: Pose) {
    if(entity.tryGetComponent<IsdkGrabbable>()?.grabState==IsdkGrabState.Grabbed) return
    val frame=surface
    if(frame==null) {
      floating.remove(entity)?.let { entity.windowPose(it,"MrStations_place") }
      applied.remove(entity)
      return
    }
    val previous=applied[entity]
    if(previous?.frame==frame) return
    floating.putIfAbsent(entity,entity.getComponent<Transform>().transform)
    // A placement saved against this scene anchor already includes the user's grab offset.
    val bound=store?.resolve(key,anchor)?.takeIf { it.anchored }?.pose
    val p=frame.station(slot,count,DeskPlacement.Point(viewer.t.x,viewer.t.y,viewer.t.z))
    val base=bound ?: Pose(Vector3(p.position.x,p.position.y,p.position.z),Quaternion.fromEuler(0f,p.yaw,0f))
    // Apply anchor motion to the current pose, preserving a user's grab offset.
    val pose=if(previous==null || bound!=null) base else follow(previous.pose,base,entity.getComponent<Transform>().transform)
    entity.windowPose(pose,"MrStations_place")
    applied[entity]=Snap(frame,base)
  }
  internal fun follow(previous: Pose,next: Pose,current: Pose)=next*inverse(previous)*current
  private fun inverse(p: Pose)=Pose(Vector3(0f),Quaternion(p.q.w,-p.q.x,-p.q.y,-p.q.z))*Pose(-p.t)
}
