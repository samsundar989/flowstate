package com.overhand.app.graph.site

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import kotlin.math.sqrt

/** Explicit Yard intent toggles the site, independent of flat windows and startup preferences. */
object YardPress {
  const val WHY="yard_press"
  fun press(shown: Boolean,prepare: () -> Unit,recenter: () -> Unit,show: () -> Unit,hide: () -> Unit): String? {
    if(shown) hide() else { prepare();recenter();show() }
    return null
  }
  fun keepSaved(saved: Pose?,head: Pose): Boolean {
    if(saved==null) return false
    val d=saved.t-head.t;val f=head.q*Vector3(0f,0f,1f)
    val distance=sqrt((d.x*d.x+d.y*d.y+d.z*d.z).toDouble())
    return distance>0.001 && distance<=2.5 && (d.x*f.x+d.y*f.y+d.z*f.z)/distance>=0.5
  }
  fun label(drawn: Boolean,reason: String?)=reason ?: if(drawn) "\u25cf Yard" else "Yard"
}
