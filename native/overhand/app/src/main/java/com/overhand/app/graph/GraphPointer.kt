package com.overhand.app.graph

import com.overhand.app.features.PanelGrab
import com.overhand.app.features.V3
import com.overhand.app.hands.HandJoints

/**
 * #999: the graph's ray and pinch, from the tracked hand joints the app already reads (the same joints, pinch
 * thresholds and shoulder-to-hand ray as the distance grab, [PanelGrab]). Before #999 the graph read
 * `scene.getControllerPoseAtTime(left, 0L)` and the controller's pinch bit; no pinch from a real hand ever
 * reached `select` (every logged selection since Nov 5 is `simulated_pinch=true`).
 *
 * A pinch selects what the ray pointed at just before the fingers closed: closing the pinch drags the ray
 * (the hand rotates as thumb meets index), so the target is the hover of the last frame whose tip gap was still
 * at least [OPEN_M] within [LOOKBACK_MS] of the onset, else the hit at the onset.
 */
class GraphPointer {
  sealed class Event { data class Hover(val hand: String,val target: String?) : Event()
    data class Select(val hand: String,val target: String?,val onsetHit: String?) : Event() }
  private val pinching=mutableMapOf<String,Boolean>()
  private val hover=mutableMapOf<String,String?>()
  private val history=mutableMapOf<String,ArrayDeque<Triple<Long,Double,String?>>>()
  /** Last ray per hand (origin, unit direction), world metres, for logs and the hover cursor. */
  val rays=mutableMapOf<String,Pair<V3,V3>>()

  /** One frame. [hit] maps a world ray to a target key (or null for nothing). */
  fun update(t: Long,head: V3,hands: Map<String,HandJoints?>,hit: (V3,V3) -> String?): List<Event> {
    val out=mutableListOf<Event>()
    for(side in listOf("left","right")) {
      val j=hands[side]
      if(j==null) { rays.remove(side);history.remove(side)
        if(hover.put(side,null)!=null) out.add(Event.Hover(side,null));continue }
      val gap=PanelGrab.tipGap(j)
      val was=pinching[side] ?: false
      val now=if(was) gap<PanelGrab.PINCH_OFF_M else gap<PanelGrab.PINCH_ON_M
      pinching[side]=now
      val ray=PanelGrab.ray(head,j);rays[side]=ray
      val target=hit(ray.first,ray.second)
      val h=history.getOrPut(side) { ArrayDeque() }
      h.addLast(Triple(t,gap,target));while(h.isNotEmpty() && t-h.first().first>LOOKBACK_MS) h.removeFirst()
      if(!now && hover[side]!=target) { hover[side]=target;out.add(Event.Hover(side,target)) }
      if(!was && now) {
        val intended=h.lastOrNull { it.second>=OPEN_M }?.third ?: target
        out.add(Event.Select(side,intended,target))
      }
    }
    return out
  }
  fun pinched(side: String)=pinching[side]==true
  companion object {
    const val OPEN_M=0.030
    const val LOOKBACK_MS=300L
  }
}
