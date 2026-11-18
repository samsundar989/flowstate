package com.overhand.app.graph

/** Graph visuals are never native ray targets. Keys retain their entities until the graph closes.
 * Retirement hides everything first; disposal happens on a later scene tick, outside input callbacks. */
class GraphEntities<E>(private val hide: (E)->Unit,private val destroy: (E)->Unit) {
  private val active=linkedMapOf<String,E>()
  private val retired=mutableListOf<Pair<Long,List<E>>>()
  private var frame=0L
  fun get(key: String,create: ()->E): E=active.getOrPut(key,create)
  fun retire() {
    val old=active.values.toList()
    old.forEach(hide)
    retired.add(frame+2 to old)
    active.clear()
  }
  fun tick() {
    frame++
    val ready=retired.filter { it.first<=frame }
    ready.forEach { (_,entities) -> entities.forEach(destroy) }
    retired.removeAll(ready.toSet())
  }
}

/** Reserve at pinch onset, before posting to the UI, then block until the actual move has finished. */
class GraphDrillInput {
  private var until=Long.MIN_VALUE
  fun moving(now: Long)=now<until
  fun accept(now: Long): Boolean {
    if(moving(now)) return false
    block(now)
    return true
  }
  fun block(now: Long) { until=now+MOVE_MS }
  fun reset() { until=Long.MIN_VALUE }
  companion object { const val MOVE_MS=400L }
}

/** Same eased pose sampled by the scene and the regression harness. No accumulated per-frame delta. */
object GraphDrillMove {
  fun sample(a: GraphDrill.Framing,b: GraphDrill.Framing,t: Double): GraphDrill.Framing {
    val k=GraphLod.ease(t)
    val sign=if(a.q.indices.sumOf { a.q[it]*b.q[it] }<0) -1 else 1
    val q=DoubleArray(4) { a.q[it]+(sign*b.q[it]-a.q[it])*k }
    val length=kotlin.math.sqrt(q.sumOf { it*it }).coerceAtLeast(1e-9)
    return GraphDrill.Framing(a.t+(b.t-a.t)*k,DoubleArray(4) { q[it]/length },a.zoom+(b.zoom-a.zoom)*k)
  }
}
