package com.overhand.app.hands

import android.util.Log
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.Query
import com.meta.spatial.toolkit.AvatarBody
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.ControllerType
import com.meta.spatial.runtime.*

/** Reuses SDK output buffers. Inferred, invalid and untracked joints never enter classification. */
@OptIn(SpatialSDKExperimentalAPI::class)
class SdkHandSource(private val scene: Scene) {
  private val poses=mutableListOf<ControllerPose>()
  private val skeleton=mutableListOf<SkeletonJoint>()
  private val indices=listOf("LEFT","RIGHT").associateWith { side ->
    val names=listOf("WRIST","THUMB_METACARPAL","THUMB_PROXIMAL","THUMB_DISTAL","THUMB_TIP")+
      listOf("INDEX","MIDDLE","RING","LITTLE").flatMap { f -> listOf("PROXIMAL","INTERMEDIATE","DISTAL","TIP").map { "${f}_$it" } }
    names.map { JointType.valueOf("${side}_HAND_$it").index }
  }
  var trackedHands=0; private set
  /** Last frame's joints and recognizer verdict per hand, for the debug frame record. */
  @Volatile var lastFrame: Map<String,Pair<HandJoints?,String>> = emptyMap(); private set
  private var lastProbe=0L
  init { scene.setBodyTrackingJointSet(JointSet.DEFAULT) }
  fun sample(now: Long): Map<String,ThumbPose> {
    val updated=runCatching { scene.updateBodyTrackingBuffersAtTime(poses,skeleton) }.getOrDefault(false)
    val body=Query.where { has(AvatarBody.id) }.eval()
      .firstOrNull { it.isLocal() && it.getComponent<AvatarBody>().isPlayerControlled }
      ?.getComponent<AvatarBody>()
    val active=listOf("LEFT" to body?.leftHand,"RIGHT" to body?.rightHand).filter { (_,entity) ->
      val c=entity?.tryGetComponent<Controller>()
      c!=null && c.isActive && c.type==ControllerType.HAND
    }.map { it.first }.toSet()
    var validHands=0
    val frame=mutableMapOf<String,Pair<HandJoints?,String>>()
    val result=indices.map { (side,ids) ->
      val required=ControllerPose.LocationValidBit or ControllerPose.LocationTrackedBit
      val points=if(updated && side in active) ids.map { i -> poses.getOrNull(i)?.takeIf { (it.flags and required)==required }?.pose?.t?.let { Point(it.x.toDouble(),it.y.toDouble(),it.z.toDouble()) } } else emptyList()
      val hand=if(points.size==21 && points.all { it!=null }) {
        validHands++
        val p=points.filterNotNull()
        fun finger(i: Int)=Finger(p[i],p[i+1],p[i+2],p[i+3])
        HandJoints(p[0],finger(1),listOf(5,9,13,17).map(::finger))
      } else null
      val (pose,why)=ThumbsRecognizer.evaluate(hand)
      frame[side.lowercase()]=hand to why
      side.lowercase() to pose
    }.toMap()
    trackedHands=validHands;lastFrame=frame
    if(now-lastProbe>=1000) {
      lastProbe=now
      Log.i("OverhandHands","updated=$updated joints=${poses.size} valid_hands=$validHands active_bare_hands=$active location_tracked=${poses.count { (it.flags and ControllerPose.LocationTrackedBit)!=0 }}")
    }
    return result
  }
  fun close() { scene.releaseBodyTrackingBuffers() }
}
