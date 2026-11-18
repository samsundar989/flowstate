@file:OptIn(com.meta.spatial.core.SpatialSDKExperimentalAPI::class)
package com.overhand.app.features

import com.meta.spatial.core.*
import com.meta.spatial.toolkit.*
import com.meta.spatial.isdk.*
import com.overhand.app.hands.HandJoints
import kotlin.math.*

/** ECS-thread observer. The ordinary frame hook does only the primitive clock check between ticks. */
class PoseLogFeature(private val s: Shell): Feature {
  override val name="pose_log"
  private val clock=PoseLog.Clock()
  private var away=false
  override fun onFrame(now: Long) {
    Query.where { has(Panel.id) }.eval().forEach { e ->
      if(e.tryGetComponent<IsdkGrabbable>()?.grabState==IsdkGrabState.Grabbed)
        WindowWrites.externalGrab(e)
    }
  }
  override fun onSceneReady() {
    if(!EventLog.enabled) return
    s.activity.systemManager.registerSystem(object: SystemBase() {
      override fun execute() {
        if(!s.paused && !s.stopped && clock.due(android.os.SystemClock.elapsedRealtime())) capture(PoseLog.Moment.TICK)
      }
    })
  }
  /** Called with already-sampled joints/head before WristMenu.tick can close the menu. No per-frame allocation. */
  fun palmEdge(h: Pose,hands: Map<String,HandJoints?>,owner: String?) {
    if(!EventLog.enabled) return
    if(owner==null) { away=false;return }
    val j=hands[owner]
    val dot=if(j!=null) PoseLog.facing(j,owner=="left",h.t.x.toDouble(),h.t.y.toDouble(),h.t.z.toDouble()) else Double.NaN
    val next=owner!=null && dot.isFinite() && dot<0.20
    if(next && !away) capture(PoseLog.Moment.PALM_AWAY)
    away=next
  }
  fun capture(moment: PoseLog.Moment) {
    if(!EventLog.enabled) return
    runCatching {
      val p=s.head();val f=p.q*Vector3(0f,0f,1f)
      val h=PoseLog.Head(p.t.x.toDouble(),p.t.y.toDouble(),p.t.z.toDouble(),Math.toDegrees(atan2(f.x,f.z).toDouble()),Math.toDegrees(asin(f.y.coerceIn(-1f,1f)).toDouble()))
      val bodies=mutableListOf<PoseLog.Body>()
      fun body(id: String,e: Entity,held: Boolean=false) {
        val at=e.tryGetComponent<Transform>()?.transform ?: return
        val dir=at.q*Vector3(0f,0f,1f)
        val x=at.t.x.toDouble();val y=at.t.y.toDouble();val z=at.t.z.toDouble()
        bodies.add(PoseLog.Body(id,x,y,z,Math.toDegrees(atan2(dir.x,dir.z).toDouble()),e.tryGetComponent<Visible>()?.isVisible ?: true,PoseLog.cone(h,x,y,z),held))
      }
      s.interaction.site.poseEntity()?.let { body("site",it) }
      Query.where { has(Panel.id) }.eval().forEach { e ->
        val id=e.getComponent<Panel>().panelRegistrationId
        if(id in s.opening.panelIds) return@forEach
        val spec=s.layout.specs.firstOrNull { it.id==id }
        val key=if(id==ControlDockFeature.PANEL) "dock" else spec?.view ?: when(e) {
          s.interaction.decisionEntity -> "decision"
          s.interaction.graphPanel -> "graph"
          s.voice.panel -> "voice"
          else -> "panel_$id"
        }
        // Keep hidden open windows too: their visible flag is the observation under test.
        if(spec!=null && (spec.id !in s.layout.entities || spec.view in s.windows.voiceHidden)) return@forEach
        body(key,e,s.grab.holding(key) || e.tryGetComponent<IsdkGrabbable>()?.grabState==IsdkGrabState.Grabbed)
      }
      val hands=s.interaction.hands
      fun wrist(side: String): PoseLog.Wrist? {
        val j=hands[side]?.first ?: return null
        return PoseLog.Wrist(j.wrist.y-h.y,PoseLog.facing(j,side=="left",h.x,h.y,h.z))
      }
      EventLog.direct("OverhandPose",PoseLog.line(moment,h,bodies,wrist("left"),wrist("right"),s.opening.poseMenuVisible,s.opening.poseMenuHand))
    }
  }
}

/** All app-owned window writes. A reason is mandatory, including first creation and visibility fades.
 * SDK-owned grabs are reported separately by observeGrab; unrelated SDK mutations are never excused as grabs.
 */
object WindowWrites {
  private val external=mutableMapOf<Long,Pose>()
  private fun key(e: Entity): String {
    val id=e.tryGetComponent<Panel>()?.panelRegistrationId ?: return "entity_${e.id}"
    return WindowLayout.defaultSpecs.firstOrNull { it.id==id }?.view ?: when(id) {
      com.overhand.app.R.id.decision_panel -> "decision"
      com.overhand.app.R.id.graph_panel -> "graph"
      VoiceFeature.PANEL -> "voice"
      ControlDockFeature.PANEL -> "dock"
      else -> "panel_$id"
    }
  }
  private fun same(a: Pose?,b: Pose)=a!=null && a.t.x==b.t.x && a.t.y==b.t.y && a.t.z==b.t.z &&
    a.q.w==b.q.w && a.q.x==b.q.x && a.q.y==b.q.y && a.q.z==b.q.z
  /** ISDK owns its native grab transform; observe that owner every ECS frame, never attribute other drift to a grab. */
  fun externalGrab(e: Entity) {
    val p=e.tryGetComponent<Transform>()?.transform ?: return
    if(!same(external.put(e.id,p),p)) EventLog.direct("OverhandWindow",
      "window event=pose_set id=${key(e)} why=isdk_grab p=${p.t.x},${p.t.y},${p.t.z}",log=true)
  }
  internal var emit: (String) -> Unit = { EventLog.direct("OverhandWindow",it,log=true) }
  fun set(entity: Entity,why: String,pose: Pose?=null,visible: Boolean?=null) {
    require(why.isNotBlank())
    pose?.let { p ->
      val old=entity.tryGetComponent<Transform>()?.transform
      if(!same(old,p)) {
        emit("window event=pose_set id=${key(entity)} why=$why p=${p.t.x},${p.t.y},${p.t.z} q=${p.q.w},${p.q.x},${p.q.y},${p.q.z}")
        entity.setComponent(Transform(p))
      }
    }
    visible?.let { v -> if(entity.tryGetComponent<Visible>()?.isVisible!=v) {
      emit("window event=visible_set id=${key(entity)} why=$why value=$v")
      entity.setComponent(Visible(v))
    } }
  }
}
fun Entity.windowPose(pose: Pose,why: String)=WindowWrites.set(this,why,pose=pose)
fun Entity.windowVisible(visible: Boolean,why: String)=WindowWrites.set(this,why,visible=visible)
