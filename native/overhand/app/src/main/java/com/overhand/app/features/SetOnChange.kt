package com.overhand.app.features

/**
 * #944: per-frame component writes only when the state changes. A Material write on a visible mesh://box makes the SDK
 * rebuild its scene object (logcat `createSceneObjectImpl ... ident='Box'`, ~40/s per grab bar before this fix); the
 * pointer ray then raced the rebuild (SIGSEGV in GraphicsHSR::lineSegmentIntersectScene, Nov 13 12:41).
 * Pure Kotlin so it is JVM-tested; callers keep one per component kind and pass SDK writes as [apply].
 */
class SetOnChange<K: Any,S: Any>(private val same: (S,S) -> Boolean = { a,b -> a==b }) {
  private val last=HashMap<K,S>()
  /** Writes performed (not skipped) since construction; for tests and the debug snapshot. */
  var writes=0L;private set
  /** Applies [state] to [key] unless the last applied state is the same. Returns true when it wrote. */
  fun set(key: K,state: S,apply: (S) -> Unit): Boolean {
    val prev=last[key]
    if(prev!=null && same(prev,state)) return false
    apply(state);last[key]=state;writes++
    return true
  }
  /** Forget [key] (entity destroyed or written elsewhere), so the next [set] writes. */
  fun forget(key: K) { last.remove(key) }
  fun clear() { last.clear() }

  companion object {
    /** Component-wise closeness for poses/vectors flattened to floats (position metres, quaternion units). */
    fun near(a: FloatArray,b: FloatArray,eps: Float): Boolean {
      if(a.size!=b.size) return false
      for(i in a.indices) if(kotlin.math.abs(a[i]-b[i])>eps) return false
      return true
    }
    /** Pose and scale changes below 0.1 mm / 1e-4 are not written. */
    const val POSE_EPS=1e-4f
  }
}

/**
 * #944: panel resize throttle. During a drag the Scale write is applied only when the size moved by at least
 * [minStepM] and [minIntervalMs] has passed since the last applied size; the release applies the exact final size.
 */
class ResizeThrottle(private val minStepM: Double=0.01,private val minIntervalMs: Long=100) {
  private val applied=HashMap<String,Pair<Long,Pair<Double,Double>>>()
  fun shouldApply(id: String,now: Long,w: Double,h: Double,currentW: Double,currentH: Double): Boolean {
    val step=maxOf(kotlin.math.abs(w-currentW),kotlin.math.abs(h-currentH))
    if(step<0.002) return false
    val prev=applied[id]
    if(prev!=null && (now-prev.first<minIntervalMs || step<minStepM)) return false
    applied[id]=now to (w to h)
    return true
  }
  /** The grab ended: the next drag starts unthrottled. */
  fun end(id: String) { applied.remove(id) }
}
