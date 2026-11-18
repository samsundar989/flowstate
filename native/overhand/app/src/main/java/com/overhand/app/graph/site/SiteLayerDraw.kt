@file:OptIn(com.meta.spatial.core.SpatialSDKExperimentalAPI::class)
package com.overhand.app.graph.site

import android.net.Uri
import com.meta.spatial.core.*
import com.meta.spatial.toolkit.*

/**
 * ECS-only, retained for the scene's lifetime (B2), drawn in K6's look ([SiteLook]). Every surface is excluded from SDK
 * ray tests. Point and pinch change transforms, materials and visibility only; a Text3D mesh is set only when words change.
 */
class SiteLayerDraw(log: (String) -> Unit={}) {
  var layers: List<SiteLayers.Layer> = emptyList();private set
  private var scale=1.0
  private var layout: SiteLayout?=null
  private var state: SiteState?=null
  private var lastFocus: Pair<SiteLayers.Id?,SiteLayers.Id?>?=null
  private var parent: Entity?=null
  private val entities=HashMap<String,Entity>()
  private val apply=SiteLookApply(object: SiteLookApply.Sink {
    override fun create(slot: String,s: SiteLook.Spec) {
      val p=parent ?: return
      val mesh=if(s.shape==SiteLook.Shape.BOX) Mesh(Uri.parse("mesh://box"),MeshCollision.NoCollision)
        else Mesh(Text3D.create(text=s.text,fontSize=s.size.toFloat(),depth=.0005f),MeshCollision.NoCollision)
      entities[slot]=Entity.create(listOfNotNull(mesh,if(s.shape==SiteLook.Shape.BOX) Box(Vector3(-.5f),Vector3(.5f)) else null,
        material(s.argb),pose(s),Scale(size(s)),TransformParent(p),Visible(s.on)))
    }
    override fun place(slot: String,s: SiteLook.Spec) { entities[slot]?.let { it.setComponent(pose(s));it.setComponent(Scale(size(s))) } }
    override fun colour(slot: String,s: SiteLook.Spec) { entities[slot]?.setComponent(material(s.argb)) } // rebuild-ok: SiteLookApply calls colour only when o.argb != s.argb
    override fun visible(slot: String,on: Boolean) { entities[slot]?.setComponent(Visible(on)) }
    override fun text(slot: String,s: SiteLook.Spec) {
      entities[slot]?.setComponent(Mesh(Text3D.create(text=s.text,fontSize=s.size.toFloat(),depth=.0005f),MeshCollision.NoCollision)) } // rebuild-ok: its words changed
    override fun destroy(slot: String) { entities.remove(slot)?.destroy() }
  },SiteLookApply.Budget(),log)
  val visibilityWaiting get()=apply.visibilityWaiting
  private val slotLog=SiteSlotLog(log)
  private fun material(argb: Long)=Material().apply {
    val c=SiteLook.rgba(argb);unlit=true;baseColor=Color4(c[0],c[1],c[2],c[3])
    if(c[3]<1f) alphaMode=com.meta.spatial.runtime.AlphaMode.TRANSLUCENT.mode
  }
  private fun pose(s: SiteLook.Spec): Transform {
    val h=Math.toRadians(s.rot)/2
    return Transform(Pose(Vector3(s.x.toFloat(),s.y.toFloat(),-s.z.toFloat()),Quaternion(Math.cos(h).toFloat(),0f,0f,Math.sin(h).toFloat())))
  }
  private fun size(s: SiteLook.Spec)=if(s.shape==SiteLook.Shape.BOX) Vector3(s.sx.toFloat(),s.sy.toFloat(),s.sz.toFloat()) else Vector3(1f)

  /** Called only when the board changes. New ids create once; removed ids are hidden and kept. */
  fun update(parent: Entity,next: List<SiteLayers.Layer>,layout: SiteLayout,state: SiteState,scale: Double) {
    this.parent=parent;this.scale=scale;this.layout=layout;this.state=state;layers=next
    lastFocus=null
  }
  fun focus(hover: SiteLayers.Id?,card: SiteLayers.Id?,op: String="focus") {
    if(lastFocus==(hover to card)) return
    val l=layout ?: return;val s=state ?: return
    lastFocus=hover to card
    apply.apply(SiteLook.frame(layers,l,s,scale,hover,card),op)
  }
  /** Y1: every frame, make a few queued visible slots (8 boxes or 1 text, 4 ms at most). */
  fun step() {
    val t0=System.nanoTime()/1e6;val n=apply.drain();val t1=System.nanoTime()/1e6
    slotLog.frame(t1,n,t1-t0,apply.waiting,apply.slots,apply.notMade)
  }
  fun cardBox(l: SiteLayers.Layer)=SiteLayers.cardBox(l,scale)
  /** Card plane hit, before the layer behind it. Code/Ask occupy the bottom half. */
  fun cardHit(id: SiteLayers.Id?,o: DoubleArray,d: DoubleArray): Pair<Double,String>? {
    val l=layers.firstOrNull { it.id==id } ?: return null
    return SiteLayers.cardHit(l,scale,o,d)
  }
  fun hide() { apply.hideAll();lastFocus=null }
  fun resume() { lastFocus=null;focus(null,null,"show") }
  /** Root/ground/legacy children consume the very same frame budget as look slots. */
  fun visibility(key: Any,current: Boolean,on: Boolean,write: (Boolean) -> Unit)=apply.visibility(key,current,on,write)
  fun close() { apply.close();entities.clear() }
}
