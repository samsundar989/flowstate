@file:OptIn(com.meta.spatial.core.SpatialSDKExperimentalAPI::class)
package com.overhand.app.features

import android.content.Intent
import android.net.Uri
import android.util.Log
import com.meta.spatial.core.*
import com.meta.spatial.isdk.*
import com.meta.spatial.runtime.PointerEvent
import com.meta.spatial.runtime.PointerEventType
import com.meta.spatial.toolkit.*
import com.overhand.app.hands.HandFrameLog
import com.overhand.app.hands.HandJoints
import com.overhand.app.mr.MrStations
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.atan2

/** Native ISDK bar grabs: the headset owns pointing, pinch recognition and movement. */
class GrabFeature(private val s: Shell) : Feature {
  override val name="grab"
  // t1282: the shared arbiter: the card's press path asks the same one, so one pinch has one owner.
  private val grab=PanelGrab(owner=PinchOwner.shared).also { g -> g.clickableAt={ id,u,v -> clickableAt(id,u,v) } }
  private class Bar(val bar: Entity,val visual: Entity) { var origin: V3?=null;var panel: PanelGrab.Panel?=null }
  private val hits=mutableMapOf<Entity,PointerEvent>()
  private var pointerObserver: ((PointerEvent) -> Unit)?=null
  private var gapLogAt=0L
  private val bars=mutableMapOf<String,Bar>()
  private val isdkHeld=mutableMapOf<String,Boolean>()
  private val events=ArrayDeque<JSONObject>()
  private var replay: List<Pair<Long,HandJoints>>?=null
  private var replayStart=0L
  private var replayWhat=""

  fun holding(view: String)=grab.holding(view) || bars[view]?.bar?.tryGetComponent<IsdkGrabbable>()?.grabState==IsdkGrabState.Grabbed

  /** Grab targets by id: the six windows (view name), "decision" and "graph". */
  private fun targets(): Map<String,Entity> {
    val l=s.layout
    val m=linkedMapOf<String,Entity>()
    l.entities.forEach { (id,e) -> val view=l.specs.first { it.id==id }.view
      if(isTarget(view,e.tryGetComponent<Visible>()?.isVisible==true,s.settings.open)) m[view]=e }
    if(s.interaction.decisionShowing) s.interaction.decisionEntity?.let { m["decision"]=it }
    s.interaction.graphPanel?.let { if(it.tryGetComponent<Visible>()?.isVisible==true) m["graph"]=it }
    s.voice.panel?.let { m["voice"]=it } // #1001: the caption window
    // t998: the graph alone (no text panel): its root is the target, moved by ray like a window.
    if("graph" !in m && s.interaction.graphVisible) s.interaction.architecture.sceneGraph.rootEntity()?.let { m["graph"]=it }
    dockFeature?.grabEntity?.let { m[DOCK]=it } // Fix G: the control dock, the same SDK bar as every panel
    return m
  }
  private val dockFeature by lazy { s.features.filterIsInstance<ControlDockFeature>().firstOrNull() }
  private fun dockRow(): List<Dock.Target> = emptyList()
  /** t1282: a clickable control under panel-local (u, v) metres. Only the card has Android controls inside a grab zone. */
  private fun clickableAt(id: String,u: Double,v: Double): Boolean {
    if(id!="decision" || !s.interaction.decisionShowing) return false
    val p=s.interaction.decisionEntity?.let { panel(id,it) } ?: return false
    return s.interaction.decisions.clickableAt(u/p.w+0.5,0.5-v/p.h)
  }
  private fun Vector3.v()=V3(x.toDouble(),y.toDouble(),z.toDouble())
  private fun V3.f()=Vector3(x.toFloat(),y.toFloat(),z.toFloat())
  private fun yawDeg(p: Pose): Double { val f=p.q*Vector3(0f,0f,1f);return Math.toDegrees(atan2(f.x,f.z).toDouble()) }
  private fun isGraphRoot(id: String,e: Entity)=id=="graph" && s.interaction.graphVisible && e==s.interaction.architecture.sceneGraph.rootEntity()
  private fun panel(id: String,e: Entity): PanelGrab.Panel {
    val p=e.getComponent<Transform>().transform
    if(isGraphRoot(id,e)) s.interaction.architecture.sceneGraph.grabRect()?.let { r ->
      return PanelGrab.Panel(id,V3(r[0].toDouble(),r[1].toDouble(),r[2].toDouble()),yawDeg(p),r[3].toDouble(),r[4].toDouble(),resizable=false) }
    val d=e.panelSize()
    // Keep layout dimensions unchanged during a grab.
    val base=s.layout.specs.firstOrNull { it.view==id }?.let { s.layout.defaultSize(it) } ?: e.baseSize()
    return PanelGrab.Panel(id,p.t.v(),yawDeg(p),d.x.toDouble(),d.y.toDouble(),baseW=base.x.toDouble(),baseH=base.y.toDouble())
  }

  override fun onSceneReady() {
    val sdk=s.activity.systemManager.findSystem<IsdkSystem>()
    val observer: (PointerEvent) -> Unit = { ev ->
      if(ev.type==PointerEventType.Unhover.id || ev.type==PointerEventType.Cancel.id) {
        if(hits[ev.source]?.hitInfo?.entity==ev.hitInfo.entity) hits.remove(ev.source)
      } else hits[ev.source]=ev
      if(ev.type==PointerEventType.Select.id) {
        val id=bars.entries.firstOrNull { it.value.bar==ev.hitInfo.entity }?.key
        if(id==null) Log.i(TAG,"grab event=miss panel=${targets().entries.firstOrNull { it.value==ev.hitInfo.entity }?.key ?: "none"} why=not_bar")
      }
    }
    pointerObserver=observer;sdk.registerObserver(observer)
    s.activity.systemManager.registerSystem(object : SystemBase() {
      override fun execute() { if(!s.paused && !s.stopped && s.recenter.placedOnce) tick() }
    })
  }

  override fun onDestroy() {
    pointerObserver?.let { s.activity.systemManager.findSystem<IsdkSystem>().unregisterObserver(it) };pointerObserver=null
    hits.clear()
  }

  private fun tick() {
    val now=android.os.SystemClock.elapsedRealtime()
    val t=targets()
    val panels=t.map { (id,e) -> panel(id,e) }
    val hands: Map<String,HandJoints?> = replay?.let { frames ->
      val el=now-replayStart
      if(el>frames.last().first+300) {
        replay=null;Log.i(TAG,"grab_replay_done $replayWhat");mapOf("left" to null,"right" to null)
      } else mapOf("left" to null,"right" to (frames.lastOrNull { it.first<=el } ?: frames.first()).second)
    } ?: s.interaction.sampleHands(now)
    val head=s.head().t.v()
    // Recorded joints are replay-only. Live grabbing never uses the old derived ray or tip-gap threshold.
    val (updates,evs)=if(replay!=null) grab.tick(head,hands,panels,nowMs=now,dock=dockRow())
      else emptyList<PanelGrab.Update>() to emptyList<PanelGrab.Event>()
    evs.forEach { ev -> t[ev.id]?.let { onEvent(ev,it) } }
    updates.forEach { u ->
      val e=t[u.id] ?: return@forEach
      if(isGraphRoot(u.id,e)) {
        // The graph root moves as a whole (its own zoom and spread keep their pose); no resize.
        val c=panels.firstOrNull { it.id==u.id }?.centre ?: return@forEach
        val d=(u.centre-c).f();if(d.x*d.x+d.y*d.y+d.z*d.z>1e-8f) s.interaction.architecture.sceneGraph.moveBy(d)
        return@forEach
      }
      val before=e.getComponent<Transform>().transform
      val pose=Pose(u.centre.f(),Quaternion.fromEuler(0f,u.yawDeg.toFloat(),0f))
      // A still hand writes nothing.
      if(!near(before,pose)) e.windowPose(pose,"GrabFeature_tick")
      if(u.id=="graph" && !near(before,pose)) s.interaction.architecture.sceneGraph.moveBy(pose.t-before.t)
    }
    nativeGrabs(t)
    drawBars(t)
    logGap(now,head,hands,t)
  }

  /** Compare the removed shoulder ray with the exact hit consumed by the SDK cursor, one hand/second. */
  private fun logGap(now: Long,head: V3,hands: Map<String,HandJoints?>,t: Map<String,Entity>) {
    if(now-gapLogAt<1000) return
    val sdk=s.activity.systemManager.findSystem<IsdkSystem>()
    for(ev in hits.values) {
      val id=t.entries.firstOrNull { it.value==ev.hitInfo.entity }?.key
        ?: bars.entries.firstOrNull { it.value.bar==ev.hitInfo.entity && it.key in t }?.key ?: continue
      val side=when(sdk.getHandForPointerEvent(ev)) { Hand.LEFT -> "left";Hand.RIGHT -> "right";else -> continue }
      val j=hands[side] ?: continue
      val r=PanelGrab.pointer(head,j);val p=panel(id,t.getValue(id));val pt=ev.hitInfo.point.v()
      val den=r.d.dot(p.forward)
      if(kotlin.math.abs(den)<0.05) continue
      // Use the system hit's plane, including the bar's front-face offset.
      val distance=(pt-r.o).dot(p.forward)/den
      if(distance<=0) continue
      val gap=PanelGrab.dist(r.o+r.d*distance,pt)*100
      Log.i(TAG,"grab event=pointer_gap panel=$id hand=$side gap_cm=%.2f pointer=system".format(gap))
      gapLogAt=now;break
    }
  }

  private fun onEvent(ev: PanelGrab.Event,e: Entity) {
    val p=ev.panel
    val line="grab_${ev.kind} id=${ev.id} hand=${ev.hand} mode=${ev.mode} pos=%.2f,%.2f,%.2f size=%.2fx%.2f".format(
      p.centre.x,p.centre.y,p.centre.z,p.w,p.h)+(if(ev.kind=="end") " moved_m=%.3f scale=%.2f".format(ev.movedM,ev.scale) else "")+
      (if(replay!=null) " injected=true" else "")
    Log.i(TAG,line)
    s.poseLog.capture(if(ev.kind=="start") PoseLog.Moment.GRAB else PoseLog.Moment.RELEASE)
    // t998: the card's line, one per start and end.
    Log.i(TAG,"grab event=${ev.kind} mode=${ev.via} panel=${ev.id} why=${if(ev.kind=="start") "bar" else "release"} id=${ev.id} dist_m=%.2f hand_reach_m=%.3f arm_m=%.2f zone=${ev.mode}".format(ev.distM,ev.handReachM,ev.armM)+
      (if(replay!=null) " injected=true" else " pointer=recorded_derived"))
    MrStations.store?.log?.event("grab_${ev.kind}",ev.id,detail=line)
    record(ev.kind,ev.id,ev.hand,ev.mode,JSONObject().put("moved_m",ev.movedM).put("scale",ev.scale)
      .put("pos",JSONArray(listOf(p.centre.x,p.centre.y,p.centre.z))).put("size",JSONArray(listOf(p.w,p.h))))
    when(ev.kind) {
      "start" -> {
        s.audio.earcons.at(START_CUE,e)
        // t945: moving Settings is not a reason to keep the other windows from re-placing after sleep.
        // t1234: moving the card, the voice caption or the graph is (resume must not move what the wearer put).
        if(ev.id!=SettingsPanel.VIEW) s.recenter.markMovedByHand()
      }
      "end" -> {
        s.recenter.onRelease(e)
      }
    }
  }
  private fun record(kind: String,id: String,hand: String,mode: String,extra: JSONObject) {
    events.addLast(extra.put("t",android.os.SystemClock.elapsedRealtime()).put("kind",kind).put("id",id).put("hand",hand).put("mode",mode)
      .put("injected",replay!=null))
    while(events.size>200) events.removeFirst()
  }

  /** Only the bar has an SDK grab volume. Carry its translation to the panel and save on native release. */
  private fun nativeGrabs(t: Map<String,Entity>) {
    bars.forEach { (id,b) ->
      val e=t[id]
      val held=b.bar.tryGetComponent<IsdkGrabbable>()?.grabState==IsdkGrabState.Grabbed
      val was=isdkHeld[id]==true
      if(held && !was && e!=null) {
        s.audio.earcons.at(START_CUE,e)
        if(id!=SettingsPanel.VIEW && id!=DOCK) s.recenter.markMovedByHand()
      }
      if((held || was) && e!=null) {
        val start=b.panel;val origin=b.origin
        if(start!=null && origin!=null) {
          val c=PanelGrab.carried(start,origin,b.bar.getComponent<Transform>().transform.t.v())
          val pose=Pose(c.f(),Quaternion.fromEuler(0f,Comfort.facingYawDeg(s.head().t.v(),c).toFloat(),0f))
          val before=e.getComponent<Transform>().transform
          if(id==DOCK) dockFeature?.barMove(c)
          else if(isGraphRoot(id,e)) s.interaction.architecture.sceneGraph.moveBy((c-panel(id,e).centre).f())
          else if(!near(before,pose)) {
            e.windowPose(pose,"GrabFeature_native")
            if(id=="graph") s.interaction.architecture.sceneGraph.moveBy(pose.t-before.t)
          }
        }
      }
      if(held!=was && e!=null) {
        val kind=if(held) "start" else "end"
        Log.i(TAG,nativeRow(id,held))
        record(kind,id,"system","bar",JSONObject())
        s.poseLog.capture(if(held) PoseLog.Moment.GRAB else PoseLog.Moment.RELEASE)
        if(!held) { if(id==DOCK) dockFeature?.barRelease() else s.recenter.onRelease(e);b.origin=null;b.panel=null }
      }
      isdkHeld[id]=held
    }
  }

  // #944: the three bar colours are built once; a bar's Material is written only when its colour state changes. Lazy: Material touches native code, and JVM tests construct Shell (and so GrabFeature) without it.
  private enum class Tone(val argb: Int) { IDLE(GrabBarLook.IDLE_ARGB),LIT(GrabBarLook.CYAN_ARGB) }
  private fun material(argb: Int)=Material().apply {
    unlit=true;alphaMode=com.meta.spatial.runtime.AlphaMode.TRANSLUCENT.mode
    baseColor=Color4(((argb ushr 16) and 255)/255f,((argb ushr 8) and 255)/255f,(argb and 255)/255f,(argb ushr 24)/255f)
  }
  private val materials by lazy { Tone.entries.associateWith { material(it.argb) } }
  private val invisible by lazy { material(0x00FFFFFF) }
  // One mesh for all bars, white vertex colours so state changes affect material only.
  private val pillMesh by lazy {
    val outline=GrabBarLook.outline()
    val positions=floatArrayOf(0f,0f,0f)+outline.flatMap { listOf(it.first.toFloat(),it.second.toFloat(),0f) }.toFloatArray()
    val indices=outline.indices.flatMap { listOf(0,it+1,(it+1)%outline.size+1) }.toIntArray()
    File(s.activity.cacheDir,"grab_pill.glb").also { file ->
      com.overhand.app.graph.site.SiteMesh.write(file,com.overhand.app.graph.site.SiteMesh.Built(positions,FloatArray(positions.size/3*4) { 1f },indices),blend=true)
    }
  }
  private fun panelWidthDp(id: String): Double=when(id) {
    "decision" -> StageWindows.CARD_W_DP.toDouble()
    "voice", "graph" -> 1000.0
    SettingsPanel.VIEW -> com.overhand.app.windows.PolishRules.SETTINGS_W_DP.toDouble()
    DOCK -> ControlDock.DP.toDouble()
    else -> WindowLayout.PANEL_W_DP.toDouble()
  }
  private val tones=SetOnChange<Entity,Tone>()
  private val shown=SetOnChange<Entity,Boolean>()
  private val poses=SetOnChange<Entity,Pose> { a,b -> near(a,b) }
  private fun Pose.flat()=floatArrayOf(t.x,t.y,t.z,q.w,q.x,q.y,q.z)
  private fun near(a: Pose,b: Pose)=SetOnChange.near(a.flat(),b.flat(),SetOnChange.POSE_EPS)
  private fun box(hw: Float,hh: Float,tone: Tone)=Entity.create(listOf(Mesh(Uri.parse("mesh://box")),
    Box(Vector3(-hw,-hh,-0.006f),Vector3(hw,hh,0.006f)),invisible)).also { it.windowPose(Pose(),"GrabFeature_create");it.windowVisible(false,"GrabFeature_create") }
    .also { tones.set(it,tone) {};shown.set(it,false) {} }
  private fun show(e: Entity,v: Boolean) { shown.set(e,v) { e.windowVisible(it,"GrabFeature_show") } }
  private fun place(e: Entity,p: Pose) { poses.set(e,p) { e.windowPose(it,"GrabFeature_place") } }
  private fun drawBars(t: Map<String,Entity>) {
    (bars.keys-t.keys).forEach { id -> bars[id]?.let { show(it.bar,false);show(it.visual,false) } }
    t.forEach { (id,e) ->
      val b=bars.getOrPut(id) {
        val target=box((PanelGrab.BAR_W/2).toFloat(),(PanelGrab.BAR_H/2).toFloat(),Tone.IDLE).also { // create-once: bars.getOrPut(id) creates only for a new panel id
          // Fix G: the dock's narrow 136 dp panel gets a 64 dp tall hit box.
          if(id==DOCK) it.setComponent(Box(Vector3(-(PanelGrab.BAR_W/2).toFloat(),-(barHitH(id)/2).toFloat(),-0.006f),Vector3((PanelGrab.BAR_W/2).toFloat(),(barHitH(id)/2).toFloat(),0.006f))) // rebuild-ok: initial hit box inside bars.getOrPut(id)
          it.setComponent(IsdkGrabbable())
          it.setComponent(IsdkGrabFilter(handContactPinch=true,handDistancePinch=true,handContactPalm=false,handDistancePalm=false))
          it.setComponent(IsdkGrabConstraints(maxGrabbers=1).apply {
            lockFlags=IsdkGrabConstraints.LockFlags.ROTATION or IsdkGrabConstraints.LockFlags.SCALE
          })
        }
        val visual=Entity.create(listOf(Mesh(Uri.fromFile(pillMesh),MeshCollision.NoCollision),materials.getValue(Tone.IDLE), // create-once: bars.getOrPut(id) retains the visual per panel id
          Transform(Pose()),TransformParent(target),Visible(false)))
        Log.i(TAG,"grab event=bar_created panel=$id visual_dp=160x8 target_m=${PanelGrab.BAR_W}x${barHitH(id)}")
        Bar(target,visual)
      }
      val p=panel(id,e)
      // Never overwrite a native held bar's pose. Its SDK transform drives the panel.
      if(!holding(id)) {
        val size=GrabBarLook.size(p.w,panelWidthDp(id))
        val at=p.centre+V3(0.0,barDrop(id,p.h,size),0.0)+p.forward*(-0.01)
        place(b.bar,Pose(at.f(),Quaternion.fromEuler(0f,p.yawDeg.toFloat(),0f)))
        b.visual.setComponent(Scale(Vector3((p.w/panelWidthDp(id)).toFloat())))
        b.origin=at;b.panel=p
      }
      val pointed=hits.values.any { it.hitInfo.entity==b.bar }
      val tone=if(GrabBarLook.colour(pointed,holding(id))==GrabBarLook.CYAN_ARGB) Tone.LIT else Tone.IDLE
      tones.set(b.visual,tone) { b.visual.setComponent(materials.getValue(it)) }
      show(b.bar,true)
      show(b.visual,true)
    }
  }

  override fun onDebugIntent(intent: Intent) {
    if(!com.overhand.app.BuildConfig.DEBUG) return
    intent.getStringExtra("grab_replay")?.let { v -> s.commands.add { startReplay(v) } }
    if(intent.getBooleanExtra("grab_snapshot",false)) s.commands.add { snapshot() }
  }

  /** "<id>:<move|pull|resize>". developer's pinch frames come from files/grab_pinch.jsonl (HandFrameLog lines, right hand). */
  private fun startReplay(v: String) {
    val parts=v.split(':');val (id,what)=parts[0] to parts.getOrElse(1) { "move" };val asRay=parts.getOrNull(2)=="ray"
    val e=targets()[id] ?: run { Log.w(TAG,"grab_replay id=$id missing");return }
    val file=File(s.activity.filesDir,"grab_pinch.jsonl")
    val frames=runCatching { file.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
      val t=JSONObject(line).getLong("t");HandFrameLog.parse(line)["right"]?.let { t to it } } }.getOrElse { emptyList() }
    if(frames.isEmpty()) { Log.w(TAG,"grab_replay no frames in ${file.name}");return }
    val p=panel(id,e)
    val action=GrabReplay.Action.valueOf(what.uppercase())
    val target=if(action==GrabReplay.Action.RESIZE) p.centre+p.right*(p.w/2)+V3(0.0,-p.h/2,0.0) else p.bar
    val t0=frames.first().first
    val rel=frames.map { (it.first-t0) to it.second }
    replay=if(asRay) GrabReplay.retargetRay(rel,s.head().t.v(),p,target,action).let { f -> val z=f.first().first;f.map { (it.first-z) to it.second } }
      else GrabReplay.retarget(rel,s.head().t.v(),p,target,action)
    replayStart=android.os.SystemClock.elapsedRealtime();replayWhat="id=$id action=$what frames=${replay!!.size}"
    Log.i(TAG,"grab_replay_start $replayWhat injected=true")
  }

  private fun snapshot() {
    val h=s.head()
    val rows=JSONArray()
    targets().forEach { (id,e) ->
      val p=e.getComponent<Transform>().transform;val d=e.panelSize()
      val key=s.layout.specs.firstOrNull { it.view==id }?.let { s.layout.layoutKey(it) }
      rows.put(JSONObject().put("id",id).put("pose",JSONArray(listOf(p.t.x,p.t.y,p.t.z,p.q.w,p.q.x,p.q.y,p.q.z)))
        .put("size",JSONArray(listOf(d.x,d.y))).put("distance_m",(p.t-h.t).let { Math.sqrt(it.dot(it).toDouble()) })
        .put("saved",key?.let { s.prefs.getString(it,null) } ?: JSONObject.NULL)
        .put("isdk_grabbable",e.tryGetComponent<IsdkGrabbable>()?.enabled ?: JSONObject.NULL))
    }
    val data=JSONObject().put("elapsed_ms",android.os.SystemClock.elapsedRealtime()).put("replaying",replay!=null)
      .put("fake_head",s.fakeHead!=null).put("head",JSONArray(listOf(h.t.x,h.t.y,h.t.z,h.q.w,h.q.x,h.q.y,h.q.z)))
      .put("bar",JSONObject().put("visual_w",PanelGrab.BAR_W).put("visual_h",PanelGrab.BAR_H).put("hit_w",PanelGrab.BAR_HIT_W).put("hit_h",PanelGrab.BAR_HIT_H))
      .put("panels",rows).put("events",JSONArray(events.toList()))
    s.activity.openFileOutput("grab_snapshot.json",0).use { it.write(data.toString(2).toByteArray()) }
  }
  companion object {
    const val TAG="OverhandGrab"
    /** t1282 (worn nov15g: 4 of 6 caption grabs moved under 2 cm, each sounding like a choice): a grab start's cue. */
    val START_CUE=com.overhand.app.audio.Earcon.GRAB
    /** A window is a grab target while shown; Settings (t945) while open, with the same grab bar as the others. */
    fun isTarget(view: String,visible: Boolean,settingsOpen: Boolean)=SettingsPanel.visible(view,settingsOpen,visible)
    /** The row a native bar grab start or end writes. */
    fun nativeRow(id: String,held: Boolean)="grab event=${if(held) "start" else "end"} panel=$id why=${if(held) "system_bar" else "system_release"} mode=isdk"
    /** Fix G: the control dock's grab id. */
    const val DOCK="dock"
    /** The SDK hit box height: the dock's is [ControlDock.BAR_TARGET_DP] (its 136 dp panel is narrow and tall). */
    fun barHitH(id: String)=if(id==DOCK) maxOf(PanelGrab.BAR_H,ControlDock.BAR_TARGET_H_M) else PanelGrab.BAR_H
    /** Bar centre below the panel centre (negative). The dock's box sits wholly under its panel, so the panel never covers it. */
    fun barDrop(id: String,h: Double,size: GrabBarLook.Size)=if(id==DOCK) ControlDock.BAR_OFFSET_M else -h/2-size.gap-size.h/2
  }
}
