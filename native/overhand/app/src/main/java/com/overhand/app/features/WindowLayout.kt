@file:OptIn(com.meta.spatial.core.SpatialSDKExperimentalAPI::class)
package com.overhand.app.features

import android.widget.LinearLayout
import com.meta.spatial.core.*
import com.meta.spatial.isdk.*
import com.meta.spatial.toolkit.*
import com.overhand.app.R
import com.overhand.app.mr.DeskPlacement
import org.json.JSONArray
import kotlin.math.*

/**
 * [x],[y],[z]: floating default, metres from the level eye (+z ahead). [ringX],[ringY],[ringZ]: the pre-t799 six-window
 * ring, still used by the MR desk frame (DeskPlacement.window spreads windows by these angles) and the web zoom.
 */
data class Spec(val id: Int, val title: String, val view: String,
  val x: Float, val y: Float, val z: Float, val w: Float = 1.2f, val h: Float = 0.75f,
  val ringX: Float = x, val ringY: Float = y, val ringZ: Float = z)

internal fun DeskPlacement.Placement.pose() = Pose(Vector3(position.x,position.y,position.z),
  Quaternion.fromEuler(0f,yaw,0f))

/** ISDK dimensions describe the registered, unscaled quad. Entity Scale changes its physical size. */
internal fun Entity.baseSize(): Vector2=tryGetComponent<IsdkPanelDimensions>()?.dimensions ?: tryGetComponent<PanelDimensions>()?.dimensions ?: Vector2(1f,0.72f)
internal fun Entity.panelSize(): Vector2 {
  val base=baseSize()
  val scale=tryGetComponent<Scale>()?.scale ?: Vector3(1f)
  return Vector2(base.x*scale.x,base.y*scale.y)
}
internal fun Entity.sizePanel(size: Vector2) {
  val base=tryGetComponent<IsdkPanelDimensions>()?.dimensions ?: tryGetComponent<PanelDimensions>()?.dimensions ?: Vector2(1f,0.72f)
  setComponent(Scale(Vector3(size.x/base.x,size.y/base.y,1f)))
}

/** Room/station rotations must never become panel pitch or roll. */
object WindowPose {
  fun upright(p: Pose): Pose {
    val forward=p.q*Vector3(0f,0f,1f)
    val yaw=atan2(forward.x,forward.z)
    return Pose(p.t,Quaternion.fromEuler(0f,Math.toDegrees(yaw.toDouble()).toFloat(),0f))
  }
  fun place(sp: Spec,origin: Pose,frame: DeskPlacement.Frame?,eye: DeskPlacement.Point): Pose =
    frame?.window(sp.ringX,sp.ringY,sp.ringZ,eye)?.pose() ?: upright(WindowPose.upright(origin) * Pose(Vector3(sp.x,sp.y,sp.z),
      Quaternion.fromEuler(0f,Math.toDegrees(atan2(sp.x,sp.z).toDouble()).toFloat(),0f)))
  /** t945: Settings opens off to the side of the level eye [eye] ([SettingsPanel]), facing the wearer. */
  fun placeSettings(eye: Pose): Pose=at(upright(eye),SettingsPanel.panelOffset())
  private fun at(level: Pose,o: V3): Pose=upright(level*Pose(Vector3(o.x.toFloat(),o.y.toFloat(),o.z.toFloat()),
    Quaternion.fromEuler(0f,Math.toDegrees(atan2(o.x,o.z)).toFloat(),0f)))
  fun follow(sp: Spec,current: Pose,delta: Pose,origin: Pose,frame: DeskPlacement.Frame?,
    eye: DeskPlacement.Point,edited: Boolean): Pose =
    if(frame!=null && !edited) place(sp,origin,frame,eye) else upright(delta*current)

  /** Separating-axis test of thin oriented panel boxes, including a 1 cm safety gap. */
  fun intersects(a: Pose,aw: Float,ah: Float,b: Pose,bw: Float,bh: Float): Boolean {
    val unit=listOf(Vector3(1f,0f,0f),Vector3(0f,1f,0f),Vector3(0f,0f,1f))
    val aa=unit.map { a.q*it };val ba=unit.map { b.q*it }
    val ae=listOf(aw/2,ah/2,0.005f);val be=listOf(bw/2,bh/2,0.005f)
    val axes=aa+ba+aa.flatMap { x -> ba.map { y -> x.cross(y) } }
    val delta=b.t-a.t
    return axes.filter { it.dot(it)>1e-8f }.all { axis ->
      abs(delta.dot(axis)) < aa.indices.sumOf { (abs(aa[it].dot(axis))*ae[it]).toDouble() }+
        ba.indices.sumOf { (abs(ba[it].dot(axis))*be[it]).toDouble() }
    }
  }
}

/** The six windows' placement, persistence and station-relative origin. */
class WindowLayout(private val s: Shell) {
  val specs = defaultSpecs
  val near get()=s.prefs.getBoolean("touch-near",false)
  var nearOrigin: Pose?=null
  fun nearPose()=NearTouch.pose(nearOrigin ?: s.head())
  fun setNear(enabled: Boolean) {
    android.util.Log.i(Shell.TAG,"window event=control why=touch_near value=$enabled")
    s.commands.add {
      // #1111: the choice is saved first, so it survives even if placing the windows fails (switch off/on gate).
      s.prefs.edit().putBoolean("touch-near",enabled).apply()
      nearOrigin=s.head()
      // t945: Settings (where this toggle lives) stays where the wearer put it.
      specs.filter { it.view!=SettingsPanel.VIEW }.forEach { createWindow(it,"touch_near") }
      s.interaction.decisionEntity?.let {
        it.windowPose(s.interaction.decisionPose(),"WindowLayout_setNear")
        it.sizePanel(if(enabled) Vector2(NearTouch.CARD_WIDTH,NearTouch.CARD_HEIGHT) else Vector2(StageWindows.CARD_W,StageWindows.CARD_H))
      }
    }
  }
  val entities = linkedMapOf<Int, Entity>() // ECS thread only
  val roots = linkedMapOf<Int, LinearLayout>()
  val last = mutableMapOf<Int, String>()
  val held = mutableSetOf<Int>()
  var origin = Pose()
  /**
   * t799: where the wearer's head was at the last head-based placement. Default (unsaved) windows are placed around
   * it, so a station project loading later cannot push the default layout out to the station ring (seen on device:
   * 1.42-1.59 m and 6 degrees off the gaze). Saved layouts stay relative to [origin].
   */
  var headOrigin: Pose? = null
  /** Eye height at the last placement (t765): the window ring is level with the head, never with a station. */
  var eyeY = Float.NaN
  @Volatile var stationProject = ""
  var stationPose: Pose? = null
  @Volatile var fleetPlaced=false

  fun inverse(p: Pose): Pose {
    val q=Quaternion(p.q.w,-p.q.x,-p.q.y,-p.q.z)
    return Pose(Vector3(0f),q)*Pose(-p.t)
  }
  fun layoutKey(id: Int)=layoutKey(specs.first { it.id==id })
  fun layoutKey(sp: Spec)=(if(near && sp.view=="fleet") "near/" else "")+(if(sp.view==FOCUSED && !s.interaction.classicView) "site/" else "")+(if(s.isRecorded()) "recorded/" else "")+
    (if(stationProject.isEmpty()) "global/${sp.view}" else "project/$stationProject/${sp.view}")
  fun eye()=s.head().t.let { DeskPlacement.Point(it.x,it.y,it.z) }
  fun defaultPose(sp: Spec) = if(sp.view==FOCUSED && !s.interaction.classicView) siteMainPose(headOrigin ?: origin) else if(sp.view==SettingsPanel.VIEW) WindowPose.placeSettings(headOrigin ?: origin) else if(near && sp.view=="fleet") nearPose() else WindowPose.place(sp,headOrigin ?: origin,s.mr.deskFrame,eye())
  /** Default quad size; Settings grows with its farther spawn distance (t945, [SettingsPanel.SCALE]). */
  fun defaultSize(sp: Spec): Vector2 = when {
    near && sp.view=="fleet" -> Vector2(NearTouch.WIDTH,NearTouch.HEIGHT)
    sp.view==SettingsPanel.VIEW -> Vector2((sp.w*SettingsPanel.SCALE).toFloat(),(sp.h*SettingsPanel.SCALE).toFloat())
    else -> Vector2(sp.w,sp.h)
  }
  /** #1029 default view: the main window floats straight ahead over the build site ([com.overhand.app.graph.site.SitePutOn]). */
  fun siteMainPose(level: Pose): Pose { val c=com.overhand.app.graph.site.SitePutOn.mainCentre()
    return WindowPose.upright(WindowPose.upright(level)*Pose(Vector3(c[0].toFloat(),c[1].toFloat(),c[2].toFloat()),
      Quaternion.fromEuler(0f,Math.toDegrees(atan2(c[0],c[2])).toFloat(),0f))) }
  fun saved(sp: Spec): List<Float> {
    val size=defaultSize(sp)
    val fallback = listOf(sp.x,sp.y,sp.z,1f,0f,0f,0f,size.x,size.y)
    // t945: Settings keeps its pose and size for the session only (on its entity), never from a saved layout.
    if(sp.view==SettingsPanel.VIEW) return fallback
    return try {
      val a = JSONArray(s.prefs.getString(layoutKey(sp), "[]"))
      val v = (0 until a.length()).map { a.getDouble(it).toFloat() }
      if (v.size==9 && v.all { it.isFinite() } && v.take(3).all { abs(it)<20 } &&
          v[7] in 0.5f..2.4f && v[8] in 0.35f..1.5f &&
          sqrt(v.slice(3..6).sumOf { (it*it).toDouble() }) in 0.9..1.1) v else fallback
    } catch (_: Exception) { fallback }
  }
  fun createWindow(sp: Spec,why: String="open") {
    val v=saved(sp)
    val stored=if(sp.view!=SettingsPanel.VIEW && s.prefs.contains(layoutKey(sp)) && v!=listOf(sp.x,sp.y,sp.z,1f,0f,0f,0f,
        defaultSize(sp).x,defaultSize(sp).y))
      (if(stationProject.isEmpty()) Pose() else origin) * Pose(Vector3(v[0],v[1],v[2]),Quaternion(v[3],v[4],v[5],v[6])) else null
    // t765: a saved layout that breaks the comfort rule is ignored; every placement is clamped into reach.
    val candidate=stored ?: defaultPose(sp)
    val pose=if(!s.interaction.classicView && sp.view==FOCUSED && stored==null) candidate else s.recenter.fit(candidate)
    val size=defaultSize(sp) // Fixed by layout; old saved corner resizes no longer set window dimensions.
    entities[sp.id]?.let { existing ->
      existing.windowPose(WindowPose.upright(pose),"layout_$why");existing.sizePanel(size)
      last[sp.id]=encode(existing)
      return
    }
    entities[sp.id]=Entity.create(listOf(Panel(sp.id),
      PanelDimensions(size))).also { it.windowPose(WindowPose.upright(pose),"layout_$why");it.windowVisible(false,"layout_$why") }
    // Automatic defaults are not user edits. Only persist a subsequent move/resize.
    last[sp.id]=encode(entities.getValue(sp.id))
    if(sp.view=="fleet") {
      fleetPlaced=true
      s.ui.post { roots[sp.id]?.invalidate() }
    }
  }
  fun encode(e: Entity): String {
    val world=e.getComponent<Transform>().transform
    val p=if(stationProject.isEmpty()) world else inverse(origin)*world
    val d=e.panelSize()
    return JSONArray(listOf(p.t.x,p.t.y,p.t.z,p.q.w,p.q.x,p.q.y,p.q.z,d.x,d.y)).toString()
  }
  fun resetLayout() {
    android.util.Log.i(Shell.TAG,"window event=control why=reset_layout")
    s.commands.add {
      nearOrigin=s.head()
      origin=RecenterFeature.placementFrame(s.head(),s.interaction.site.placement.pose,false);headOrigin=origin;eyeY=origin.t.y
      specs.forEach { sp ->
        s.prefs.edit().remove(layoutKey(sp)).apply()
        entities.remove(sp.id)?.destroy()
        createWindow(sp)
      }
    }
  }
  companion object {
    /** t1234: a saved or resized size stays within [PanelGrab.SCALE_MIN]..[PanelGrab.SCALE_MAX] of the default (a layout
     *  saved before the cap, e.g. a 2.40 m wide window, comes back at 1.5 times its default). */
    fun scaled(d: Vector2,k: Double)=Vector2(d.x*k.toFloat(),d.y*k.toFloat())
    fun capSize(def: Vector2,w: Float,h: Float)=Vector2(
      w.coerceIn(def.x*PanelGrab.SCALE_MIN.toFloat(),def.x*PanelGrab.SCALE_MAX.toFloat()),
      h.coerceIn(def.y*PanelGrab.SCALE_MIN.toFloat(),def.y*PanelGrab.SCALE_MAX.toFloat()))
    /**
     * t799 default layout, within reach and centred on the gaze (developer, Nov 12: "too far away and not centred").
     * Two rows in front of the level eye: Chat (the focused window) straight ahead 10 degrees below eye level at 1.0 m,
     * Fleet at -65 and Code at +73 degrees (t1234: clear of Settings' spawn), Blueprint and Yard at 50 and +23 degrees
     * (t1234: 4 degrees or more above Fleet and Code); Settings
     * at +30 and -21 degrees, 1.3 m. Every non-Settings centre is
     * within 73 degrees of the gaze, 1.0-1.12 m away and below +25 degrees. Windows are 0.84 x 0.525 m (same 16:10).
     */
    const val FOCUSED="chat"
    data class Slot(val bearingDeg: Double, val elevationDeg: Double, val distanceM: Double)
    val slots = linkedMapOf(
      "fleet" to Slot(-65.0,-10.0,1.05), "blueprint" to Slot(-50.0,23.0,1.12), "code" to Slot(73.0,-10.0,1.05),
      "build" to Slot(50.0,23.0,1.12), "chat" to Slot(0.0,-10.45,1.0),
      // t945: Settings' legibility slot only (type, targets, layout_lint and the MR ring read it). It is never placed
      // here: it opens off to the side ([SettingsPanel], WindowPose.placeSettings) at the same visual size.
      "settings" to Slot(0.0,24.0,SettingsPanel.LEGIBLE_AT_M))
    private fun spec(id: Int,title: String,view: String,w: Float,h: Float,ringX: Float,ringY: Float,ringZ: Float): Spec {
      val sl=slots.getValue(view)
      val b=Math.toRadians(sl.bearingDeg);val e=Math.toRadians(sl.elevationDeg)
      val hz=sl.distanceM*cos(e)
      return Spec(id,title,view,(hz*sin(b)).toFloat(),(sl.distanceM*sin(e)).toFloat(),(hz*cos(b)).toFloat(),w,h,ringX,ringY,ringZ)
    }
    val defaultSpecs = listOf(
      spec(R.id.fleet_panel, "Fleet", "fleet", 0.84f, 0.525f, -1.386f, 0f, 0.8f),
      spec(R.id.blueprint_panel, "Blueprint", "blueprint", 0.84f, 0.525f, -1.386f, 0f, -0.8f),
      spec(R.id.code_panel, "Code", "code", 0.84f, 0.525f, 1.386f, 0f, 0.8f),
      spec(R.id.yard_panel, "Yard", "build", 0.84f, 0.525f, 1.386f, 0f, -0.8f),
      spec(R.id.chat_panel, "Chat / Decisions", "chat", 0.84f, com.overhand.app.graph.site.SitePutOn.MAIN_H.toFloat(), 0f, 0f, -1.6f),
      // Ring values exactly as t765 left them, so the MR desk frame places Settings as before.
      spec(R.id.settings_panel, "Settings", "settings", 0.6f, 0.5625f, 0f, 0.5f, 1.2f),
    )

    const val PANEL_W_DP=1280f
    const val PANEL_H_DP=800f
    fun heightDp(view: String)=if(view==FOCUSED) 1066.6666f else PANEL_H_DP
    fun type(view: String)=if(view==FOCUSED) com.overhand.app.windows.PolishRules.floor(mainGeometry()) else com.overhand.app.windows.PolishRules.WINDOW
    fun target(view: String)=if(view==FOCUSED) com.overhand.app.windows.PolishRules.targetDp(mainGeometry()) else com.overhand.app.windows.PolishRules.WINDOW_TARGET_DP
    private fun mainGeometry()=com.overhand.app.design.FlowstateTokens.PanelGeometry(PANEL_W_DP/0.84f,com.overhand.app.graph.site.SitePutOn.MAIN_DISTANCE.toFloat())
    /** The farthest any default window sits (#861): type and targets are sized to hold here. */
    val FARTHEST_M: Float get()=slots.values.maxOf { it.distanceM }.toFloat()
  }
}

/** Stage-relative defaults. Main: 0 degrees bearing / +13 elevation (SitePutOn).
 * Decision: 0 / +13 at 2 m. Six project cards: +/-36 / 0,-12,-24 at 3.2 m.
 * Side columns clear the site's angular bounds; all quad corners fit a conservative 89 x 90 degree view.
 * User-moved cards are never snapped back to these defaults.
 */
object StageWindows {
  private fun at(frame: Pose,bearing: Double,elevation: Double,distance: Double): Pose {
    val o=SettingsPanel.offset(bearing,elevation,distance)
    return WindowPose.upright(frame*Pose(Vector3(o.x.toFloat(),o.y.toFloat(),o.z.toFloat()),Quaternion.fromEuler(0f,bearing.toFloat(),0f)))
  }
  const val CARD_W=1f
  const val CARD_H=1.07f
  const val CARD_W_DP=1000f
  const val CARD_H_DP=1070f
  const val CARD_DISTANCE=2.8
  val CARD_GEOMETRY get()=com.overhand.app.design.FlowstateTokens.PanelGeometry(CARD_W_DP/CARD_W,CARD_DISTANCE.toFloat())
  val CARD_TYPE get()=com.overhand.app.windows.PolishRules.floor(CARD_GEOMETRY).let { com.overhand.app.windows.PolishRules.TypeScale(it.caption,it.body,it.title,it.caption) }
  val CARD_TARGET get()=com.overhand.app.windows.PolishRules.targetDp(CARD_GEOMETRY)
  fun decision(frame: Pose)=at(frame,27.0,12.0,CARD_DISTANCE)
  fun project(frame: Pose,index: Int,count: Int): Pose {
    require(count>0 && index in 0 until count)
    val rows=maxOf(3,(count+1)/2)
    return at(frame,if(index%2==0) -36.0 else 36.0,-4.0-24.0*(index/2)/(rows-1),3.2*rows/3)
  }
  fun keepsPlaced(placed: Boolean,explicit: Boolean)=placed && !explicit
}
