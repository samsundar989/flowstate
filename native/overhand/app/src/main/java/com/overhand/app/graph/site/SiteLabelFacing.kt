package com.overhand.app.graph.site

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import kotlin.math.*

/** Reuses the pose, quaternion and component. Text3D's readable front is local -Z. */
internal class SiteLabelFacing(x: Float,y: Float,z: Float) {
  val pose=Pose(Vector3(x,y,z),Quaternion())
  fun face(headX: Double,headZ: Double) {
    val p=pose
    val dx=headX-p.t.x;val dz=headZ-p.t.z
    if(dx*dx+dz*dz<1e-12) return
    val half=atan2(-dx,-dz)/2
    p.q.w=cos(half).toFloat();p.q.x=0f;p.q.y=sin(half).toFloat();p.q.z=0f
  }
}
