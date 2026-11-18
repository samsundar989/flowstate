package com.overhand.app.opening

import com.overhand.app.features.PanelGrab
import com.overhand.app.features.V3
import com.overhand.app.hands.HandJoints
import com.overhand.app.hands.Point
import kotlin.math.abs

/** Shared by the live adapter and worn replay. No menu labels enter target detection. */
object PalmInput {
  private val grab=PanelGrab()
  fun targets(head: Point,hands: Map<String,HandJoints?>,panels: List<PanelGrab.Panel>,
    pointers: Map<String,PanelGrab.Ray> = emptyMap()): Set<String> = hands.mapNotNull { (s,h) ->
    h ?: return@mapNotNull null
    val r=pointers[s] ?: PanelGrab.pointer(V3(head.x,head.y,head.z),h)
    s.takeIf { grab.nearest(r.o,r.d,null,panels)!=null }
  }.toSet()

  fun right(p: WristMenu.Place)=Point(p.facing.z,0.0,-p.facing.x).unit()
  fun up(p: WristMenu.Place)=p.facing.cross(right(p)).unit()

  /** Other index only: approach from the visible side and cross 7 mm into the panel. */
  class Poke {
    private var previous: Point?=null
    fun reset() { previous=null }
    fun tick(p: WristMenu.Place,tip: Point?): Int? {
      val before=previous;previous=tip
      if(tip==null || before==null || (tip-before).length()>0.10) return null
      val q=tip-p.centre;val depth=q.dot(p.facing)
      if((before-p.centre).dot(p.facing)>=-0.007 || depth< -0.007 || depth>0.025) return null
      val x=q.dot(right(p));val y=q.dot(up(p))
      val col=if(x<0) 0 else 1;val row=if(y>0) 0 else 1
      return (row*2+col).takeIf { abs(x-(if(col==0) -0.052 else 0.052))<=0.046 &&
        abs(y-(if(row==0) 0.028 else -0.028))<=0.022 }
    }
  }
}
