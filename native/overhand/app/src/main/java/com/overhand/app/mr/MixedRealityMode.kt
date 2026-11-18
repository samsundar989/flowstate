package com.overhand.app.mr

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.meta.spatial.core.*
import com.meta.spatial.mruk.*
import com.meta.spatial.toolkit.getAbsoluteTransform
import com.overhand.app.BuildConfig
import com.overhand.app.mr.DeskPlacement.Point

/** Permission/UI on Android thread; MRUK queries and placement on the app ECS command queue. */
class MixedRealityMode(private val activity: Activity, private val mruk: MRUKFeature,
  private val enqueue: (() -> Unit) -> Unit, private val viewer: () -> Pose,
  private val changed: (Boolean, DeskPlacement.Frame?) -> Unit) {
  private val ui=Handler(Looper.getMainLooper())
  private val prefs=activity.getSharedPreferences("windows-v1",Activity.MODE_PRIVATE)
  @Volatile private var generation=0
  @Volatile private var closed=false
  var status="Virtual room"; private set
  var statusChanged: ((String) -> Unit)?=null
  val enabled get()=prefs.getBoolean("passthrough",true)
  private var anchor: Entity?=null // ECS only
  private var seatedEye=Point(0f,1.2f,0f)
  private var previous: DeskPlacement.Frame?=null
  val roomAccessGranted get()=activity.checkSelfPermission(PERMISSION)==PackageManager.PERMISSION_GRANTED
  private val permission=ScenePermissionCoordinator({ roomAccessGranted },{ enabled },
    { activity.requestPermissions(arrayOf(PERMISSION),REQUEST) },::loadScene,::showFallback)

  /** Startup path: the card and passthrough never depend on, or trigger, a permission dialog. */
  fun start() { permission.refresh() }
  /** The user asked for desk placement; this is the only route to the system dialog. */
  fun requestRoom() { permission.explicitAction() }

  private fun begin(): Int {
    val token=++generation
    source="device"
    enqueue { anchor=null;previous=null;MrStations.anchor=null;changed(enabled,null) }
    return token
  }

  private fun showFallback(reason: ScenePermissionCoordinator.Reason) {
    begin()
    if(reason==ScenePermissionCoordinator.Reason.PASSTHROUGH_OFF) {
      enqueue { publish(null,"passthrough off") };report("Virtual room · passthrough off");return
    }
    enqueue { publish(null,"Spatial Data permission not granted") }
    report(when(reason) {
      ScenePermissionCoordinator.Reason.WAITING -> "Passthrough · floating layout; waiting for room access"
      ScenePermissionCoordinator.Reason.DENIED -> "Passthrough · floating layout; room access not allowed. Try again, or allow Spatial Data in system settings"
      else -> "Passthrough · floating layout; allow room access for desk placement"
    })
  }

  private fun loadScene() {
    val token=begin()
    report("Passthrough · looking for a desk")
    // Invalidate late completion after eight seconds, off/on, denial or activity destruction.
    ui.postDelayed({ if(!closed && generation==token && status=="Passthrough · looking for a desk") {
      generation++;enqueue { anchor=null;previous=null;publish(null,"room load timed out");changed(enabled,null) }
      report("Passthrough · floating layout; room load timed out")
    } },8000)
    enqueue {
      if(closed || generation!=token) return@enqueue
      try {
        mruk.loadSceneFromDevice(false).whenComplete { result,error -> enqueue complete@{
          if(closed || generation!=token) return@complete
          Log.i(TAG,"room load result=$result error=${error?.javaClass?.simpleName} rooms=${mruk.rooms.size}")
          val rooms=mruk.rooms
          rooms.forEach { room ->
            Log.i(TAG,"room anchors=${room.anchors.size}")
            room.anchors.forEach { entity ->
              val a=entity.getComponent<MRUKAnchor>()
              if(BuildConfig.DEBUG) Log.i(TAG,"anchor=${a.uuid} labels=${labels(a)}")
            }
          }
          if(error!=null || result!=MRUKLoadDeviceResult.SUCCESS) {
            publish(null,"room load result=$result error=${error?.javaClass?.simpleName}")
            report(if(result==MRUKLoadDeviceResult.ERROR_NO_ROOMS_FOUND) "Passthrough · floating layout; optional Space Setup in system settings"
              else "Passthrough · floating layout; room unavailable");return@complete
          }
          chooseSurface(false)
        } }
      } catch(e: Exception) { Log.w(TAG,"scene load failed",e);publish(null,"scene load threw ${e.javaClass.simpleName}");report("Passthrough · floating layout; scene unavailable") }
    }
  }

  private var source="device"
  private val sceneEvents=object: MRUKSceneEventListener {
    override fun onRoomAdded(room: MRUKRoom) {
      val token=generation
      if(source.startsWith("synthetic:")) enqueue {
        if(!closed && generation==token) chooseSurface(true)
      }
    }
  }
  init { mruk.addSceneEventListener(sceneEvents) }
  private fun chooseSurface(synthetic: Boolean) {
    seatedEye=viewer().t.point()
    val candidates=mruk.getCurrentRoom()?.anchors ?: mruk.rooms.flatMap { it.anchors }
    val saved=prefs.getString("mr-surface-uuid",null)
    val chosen=candidates.mapNotNull { entity -> frame(entity)?.let { entity to it } }
      .sortedWith(compareBy<Pair<Entity,DeskPlacement.Frame>> { if(it.second.desk) 0 else 1 }
        .thenBy { if(it.first.getComponent<MRUKAnchor>().uuid.toString()==saved) 0 else 1 }
        .thenBy { it.second.near.distance(seatedEye) }).firstOrNull()
    anchor=chosen?.first
    previous=chosen?.second
    if(DeskPlacement.fallback(enabled,
        activity.checkSelfPermission(PERMISSION)==PackageManager.PERMISSION_GRANTED,false,previous)) {
      anchor=null;previous=null
      publish(null,"no usable desk or wall among ${candidates.size} scene anchors")
      changed(enabled,null);report("Passthrough - floating layout; no usable desk or wall")
    } else {
      if(!synthetic) prefs.edit().putString("mr-surface-uuid",chosen!!.first.getComponent<MRUKAnchor>().uuid.toString()).apply()
      mruk.setWorldLockingEnabled(!synthetic)
      publish(chosen!!.first,if(chosen.second.desk) "desk" else "wall")
      changed(enabled,chosen.second)
      report("Passthrough - ${if(synthetic) "SYNTHETIC " else ""}${if(chosen.second.desk) "desk" else "wall"} anchored")
    }
    Log.i(TAG,"placement source=$source rooms=${mruk.rooms.size} frame=$previous")
  }

  fun snapshot()=org.json.JSONObject().put("source",source).put("rooms",mruk.rooms.size)
    .put("anchor",anchor?.getComponent<MRUKAnchor>()?.uuid?.toString())
    .put("frame",previous?.toString())

  /** Debug-only SDK JSON injection, never substituted for missing real scene data. */
  fun debugRoom(name: String) {
    if(!BuildConfig.DEBUG || name !in setOf("Bedroom00","Livingroom01")) return
    generation++
    source="synthetic:$name"
    enqueue {
      try {
        anchor=null;previous=null;changed(enabled,null)
        val json=java.io.File(activity.filesDir,"$name.json").readText()
        mruk.setWorldLockingEnabled(false)
        mruk.clearRooms()
        mruk.loadSceneFromJsonString(json)
        Log.i(TAG,com.overhand.app.PrivateLog.syntheticRoom(name,mruk.rooms.size))
        // onRoomAdded selects after MRUK has constructed room anchors.
      } catch(e: Exception) { Log.w(TAG,"synthetic room failed",e) }
    }
  }

  fun toggle(value: Boolean,requestAccess: Boolean=true) {
    prefs.edit().putBoolean("passthrough",value).apply()
    if(value && requestAccess) requestRoom() else start()
  }
  /** ECS thread, placement events only. Reading a room never loads one or requests permission. */
  fun floorRoom(): com.overhand.app.graph.site.floor.FloorPlacement.Room? {
    if(!enabled || !roomAccessGranted) return null
    val room=mruk.getCurrentRoom() ?: return null
    fun point(p: Vector3)=com.overhand.app.graph.site.floor.FloorPlacement.V3(p.x.toDouble(),p.y.toDouble(),p.z.toDouble())
    val anchors=room.anchors.map { e ->
      val pose=getAbsoluteTransform(e)
      val plane=e.tryGetComponent<MRUKPlane>()
      val floor=if(e in room.floors && plane!=null) (0 until plane.boundariesCount).mapNotNull { i ->
        plane.boundary[i]?.let { point(pose*Vector3(it.x,it.y,0f)) } } else emptyList()
      val v=e.tryGetComponent<MRUKVolume>()
      val volume=if(v!=null) listOf(v.min.x,v.max.x).flatMap { x -> listOf(v.min.y,v.max.y).flatMap { y ->
        listOf(v.min.z,v.max.z).map { z -> point(pose*Vector3(x,y,z)) } } } else emptyList()
      FloorRoomAdapter.Anchor(floor,volume)
    }
    val eye=viewer().t
    return FloorRoomAdapter.room(anchors,eye.x.toDouble(),eye.z.toDouble())
  }
  fun permissionResult() { permission.permissionResult() }
  fun resumed() { permission.resumed() }
  fun tick() {
    val entity=anchor ?: return
    val next=runCatching { frame(entity) }.getOrNull()
    if(next==null) {
      anchor=null;previous=null;publish(null,"desk tracking lost");changed(enabled,null);report("Passthrough · floating layout; desk tracking lost")
    } else if(next!=previous) { previous=next;MrStations.anchor=ref(entity);changed(enabled,next) }
  }
  fun close() { mruk.removeSceneEventListener(sceneEvents);closed=true;generation++;ui.removeCallbacksAndMessages(null);statusChanged=null }

  private fun frame(entity: Entity): DeskPlacement.Frame? {
    val a=entity.getComponent<MRUKAnchor>()
    val names=labels(a)
    // MRUK anchors are parented under their room. A raw Transform is room-local,
    // and is wrong after room localization/recentering. Use the SDK hierarchy resolver.
    val pose=getAbsoluteTransform(entity)
    if("TABLE" in names) {
      val v=entity.tryGetComponent<MRUKVolume>()
      val p=entity.tryGetComponent<MRUKPlane>()
      val corners=if(v!=null) {
        // Avoid assuming which local axis is up: MRUK's table plane normal is usually +Z.
        listOf(v.min.x,v.max.x).flatMap { x -> listOf(v.min.y,v.max.y).flatMap { y ->
          listOf(v.min.z,v.max.z).map { z -> (pose*Pose(Vector3(x,y,z))).t.point() }
        } }.sortedByDescending { it.y }.take(4)
      } else if(p!=null) listOf(p.min.x,p.max.x).flatMap { x ->
        listOf(p.min.y,p.max.y).map { y -> (pose*Pose(Vector3(x,y,0f))).t.point() }
      } else return null
      return DeskPlacement.desk(corners,seatedEye)
    }
    if("WALL_FACE" in names) {
      val p=entity.tryGetComponent<MRUKPlane>() ?: return null
      // Ignore narrow slivers; center the arc on the labelled physical wall.
      if(p.max.x-p.min.x<1f || p.max.y-p.min.y<0.8f) return null
      val center=(pose*Pose(Vector3((p.min.x+p.max.x)/2f,(p.min.y+p.max.y)/2f,0f))).t
      val normal=(pose*Pose(Vector3(0f,0f,1f))).t-pose.t
      return DeskPlacement.wall(center.point(),normal.point(),seatedEye)
    }
    return null
  }
  /** ECS thread. A synthetic room's UUIDs are prefixed so they can never satisfy a real anchor record. */
  private fun ref(entity: Entity)=AnchorRef((if(source.startsWith("synthetic:")) "synthetic:" else "")+
    entity.getComponent<MRUKAnchor>().uuid.toString(),getAbsoluteTransform(entity))
  private fun publish(entity: Entity?, reason: String) {
    val r=entity?.let(::ref)
    Log.i(TAG,"anchor settle ${r?.uuid ?: "none"} $reason")
    MrStations.settle(r,reason)
  }
  private fun labels(a: MRUKAnchor)=(0 until a.labelsCount).mapNotNull { a.labels[it] }
  private fun Vector3.point()=Point(x,y,z)
  private fun report(value: String) {
    // Fallback statuses say plainly that panels sit at saved transforms, not on a room anchor.
    val shown=if("floating layout" in value) "$value · not anchored" else value
    Log.i(TAG,shown)
    ui.post { if(!closed) { status=shown;statusChanged?.invoke(shown) } }
  }
  companion object {
    const val PERMISSION="com.oculus.permission.USE_SCENE"
    const val REQUEST=270
    private const val TAG="OverhandMR"
  }
}
