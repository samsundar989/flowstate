@file:OptIn(com.meta.spatial.core.SpatialSDKExperimentalAPI::class)
package com.overhand.app.features

import android.content.Intent
import android.util.Log
import com.meta.spatial.core.*
import com.meta.spatial.isdk.IsdkGrabState
import com.meta.spatial.isdk.IsdkGrabbable
import com.meta.spatial.toolkit.*
import com.overhand.app.R
import com.overhand.app.features.Shell.Companion.TAG
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.atan2

/**
 * t765: windows are never lost.
 * - First placement waits for a tracked, level, steady head ([HeadSettle]); never a pose captured mid-donning.
 * - [centerAll] is the one entry point that re-places every window, the card, the graph and the station arc in front
 *   of the wearer's current head (yaw only, level). Wired to the palm menu, the system recenter, a debug intent and
 *   [voiceCenter] (hook only).
 * - [fit] is the comfort clamp ([Comfort]). It runs when a window is placed (open, Center windows, system recenter) and
 *   once at the moment a grab ends if the window was let go nearer than [Comfort.MIN_M] ([ReleaseRule]); never afterwards
 *   (t1234: the estimated-head control moves the high Chat release; the worn log has no contemporaneous head pose).
 * - After sleep / headset mount, the layout re-places when the head settles again, unless anything was moved by hand.
 */
/**
 * t1234: the whole rule for a released window, in pure Kotlin so the worn log replays through it (WindowReleaseReplayTest).
 * At the frame a grab ends, a window let go nearer than [Comfort.MIN_M] is clamped into reach once; otherwise, and on
 * every later frame until the next grab, it stays exactly where it was let go.
 */
object ReleaseRule {
  fun atRelease(head: V3,centre: V3,yawDeg: Double): V3? =
    if((centre-head).length()<Comfort.MIN_M) Comfort.clamp(head,centre,yawDeg) else null
}

class RecenterFeature(private val s: Shell) : Feature {
  override val name = "recenter"
  private val l get()=s.layout
  // ECS thread state.
  private val settle=HeadSettle()
  /** True once the first placement happened; until then the frame loop only runs commands. */
  private val gate=FirstPlacementGate()   // #1351
  val placedOnce get()=gate.placedOnce
  private var pending: String?=null
  @Volatile var movedByHand=false; private set
  private val grabbed=mutableMapOf<Entity,Boolean>()
  private var lastCentre=""

  /** Voice hook ("center"): call from any thread. Voice itself is not built here. */
  fun voiceCenter()=request("voice")
  /** Any thread: re-place everything on the next ECS frame. */
  fun request(why: String) { s.commands.add { centerAll(why) } }

  /** Viewer pose for placement (debug builds may inject one, see [onDebugIntent]). */
  private fun head()=s.head()
  private fun Vector3.v()=V3(x.toDouble(),y.toDouble(),z.toDouble())
  private fun yawDeg(p: Pose): Double { val f=p.q*Vector3(0f,0f,1f);return Math.toDegrees(atan2(f.x,f.z).toDouble()) }

  /** Comfort clamp for one panel pose, against the current head. Upright; a moved panel turns to face the head. */
  fun fit(p: Pose): Pose {
    val h=head();val c=p.t.v();val yaw=yawDeg(h)
    if(Comfort.ok(h.t.v(),c,yaw)) return WindowPose.upright(p)
    val n=Comfort.clamp(h.t.v(),c,yaw)
    return Pose(Vector3(n.x.toFloat(),n.y.toFloat(),n.z.toFloat()),
      Quaternion.fromEuler(0f,Comfort.facingYawDeg(h.t.v(),n).toFloat(),0f))
  }
  fun ok(p: Pose)=head().let { Comfort.ok(it.t.v(),p.t.v(),yawDeg(it)) }

  /** t799 grab bar: a window moved by hand is not re-placed on resume. */
  fun markMovedByHand() { movedByHand=true }
  /** Grab end (bar or ISDK), ECS thread: [ReleaseRule], applied once. A window let go farther than 0.5 m stays exactly there. */
  fun onRelease(e: Entity) {
    if(!easesBack(viewOf(e))) return
    val p=e.getComponent<Transform>().transform;val h=head()
    ReleaseRule.atRelease(h.t.v(),p.t.v(),yawDeg(h))?.let { n ->
      val c=Vector3(n.x.toFloat(),n.y.toFloat(),n.z.toFloat())
      e.windowPose(Pose(c,Quaternion.fromEuler(0f,Comfort.facingYawDeg(h.t.v(),n).toFloat(),0f)),"RecenterFeature_onRelease")
      Log.i(TAG,"recenter event=release_clamp from=${fmt(p)}")
    }
  }

  /** Frame loop gate (ECS thread). Returns false until the first placement may happen. */
  fun ready(now: Long): Boolean {
    val h=head();val f=h.q*Vector3(0f,0f,1f)
    val first=settle.sample(now,h.t.v(),f.v())
    if(first) Log.i(TAG,"recenter event=settled reason=${settle.reason} pending=${pending} head=${fmt(h)}")
    if(!placedOnce) {
      if(!first) { cardFollow();return false }
      gate.settled { firstPlace(h,"settled") }
      return true
    }
    if(first) pending?.let { why -> pending=null;centerAll(why) }
    return true
  }
  /** Before the first placement the card (Live) follows the level head, so it is in front when placement happens. */
  private fun cardFollow() { if(s.interaction.decisionShowing) s.interaction.placeCard() }
  /** The first placement, once per process (ECS thread), from whichever gate fires first ([FirstPlacementGate]). */
  private fun firstPlace(h: Pose,via: String) {
    pending=null
    l.origin=WindowPose.upright(h);l.headOrigin=l.origin;l.eyeY=h.t.y
    Log.i(TAG,"recenter event=first_place via=$via head=${fmt(h)}")
    // #1351: isolated, so a throwing card can no longer skip put-on (and the site) for the whole process.
    FirstPlacement.run(listOf(
      "placeCard" to { s.interaction.placeCard() },
      // #1029: the build site in front and the main window over it (unless Classic view)
      "putOn" to { s.interaction.putOn() })) { Log.w(com.overhand.app.graph.site.SiteScene.TAG,it) }
  }

  /** Re-place once the head is steady again (any thread). */
  fun resettle(why: String,force: Boolean=false) = s.commands.add {
    if(!placedOnce) return@add
    if(movedByHand && !force) { Log.i(TAG,"recenter event=resettle_skipped why=$why moved_by_hand=true");return@add }
    pending=why;settle.reset(System.currentTimeMillis())
    Log.i(TAG,"recenter event=resettle why=$why")
  }

  /** System recenter (long press of the system button): the world moved under the wearer, so always re-place. */
  fun systemRecenter(userInitiated: Boolean) {
    Log.i(TAG,"recenter event=system user_initiated=$userInitiated")
    resettle("system_recenter",force=true)
  }

  /** The one re-place entry point (ECS thread). */
  fun centerAll(why: String) {
    // Recheck here: a grab may occur after resettle was queued but before the head settles.
    // This also protects direct automatic callers such as first60_steady.
    if(movedByHand && !SettingsPanel.followsRecenter(why)) {
      Log.i(TAG,"recenter event=center_skipped why=$why moved_by_hand=true");return
    }
    if(StageWindows.keepsPlaced(placedOnce,SettingsPanel.followsRecenter(why))) {
      Log.i(TAG,"recenter event=center_kept why=$why stage_locked=true");return
    }
    // #1351: a centerAll before the first placement (First60's steady gate won the race) is the first placement.
    gate.centerAll({ firstPlace(head(),why) }) { recenterAll(why) }
  }
  private fun recenterAll(why: String) {
    val h=head();val level=placementFrame(h,s.interaction.site.placement.pose,SettingsPanel.followsRecenter(why))
    l.eyeY=h.t.y;l.nearOrigin=h;l.origin=level;l.headOrigin=level
    // t945: Settings moves only when the wearer asked (palm, voice, system recenter), never on a resume or mount settle.
    l.specs.filter { it.id in l.entities && (it.view!=SettingsPanel.VIEW || SettingsPanel.followsRecenter(why)) }.forEach { sp ->
      s.prefs.edit().remove(l.layoutKey(sp)).apply()
      l.createWindow(sp,"recenter_$why")
    }
    l.held.clear()
    s.interaction.placeCard()
    s.interaction.recenterGraph()
    s.interaction.recenterSite(why)
    s.windows.stations.recenter(level)
    movedByHand=false
    lastCentre=why
    Log.i(TAG,"recenter event=center_all why=$why head=${fmt(h)} windows=${l.entities.size} card=${s.interaction.decisionShowing}")
  }

  override fun onFrame(now: Long) {
    // ISDK frame grabs: the release rule once at the frame the grab ends; nothing moves a released window afterwards.
    val panels=l.entities.values+listOfNotNull(s.interaction.graphPanel)
    panels.forEach { e ->
      if(!easesBack(viewOf(e))) return@forEach
      val g=e.tryGetComponent<IsdkGrabbable>()?.grabState==IsdkGrabState.Grabbed
      val was=grabbed.put(e,g)==true
      if(g) { movedByHand=true;return@forEach }
      if(was) onRelease(e)
    }
  }

  // Re-place after a real sleep only: a system-menu overlay (or an adb intent) pauses for well under 5 s.
  @Volatile private var pausedAt=0L
  override fun onPause() { pausedAt=android.os.SystemClock.elapsedRealtime() }
  override fun onResume() {
    val away=if(pausedAt>0) android.os.SystemClock.elapsedRealtime()-pausedAt else 0L
    if(away>=RESUME_AFTER_MS) resettle("resume_after_${away/1000}s")
    if(placedOnce) s.interaction.retryNeverOpened("resume")   // #1351
  }
  private fun viewOf(e: Entity)=l.entities.entries.firstOrNull { it.value==e }?.let { (id,_) -> l.specs.first { it.id==id }.view }
  companion object {
    /** Frame seam shared by the production recenter and worn-session replay. */
    fun placementFrame(head: Pose,site: Pose?,explicit: Boolean): Pose {
      if(explicit || site==null) return WindowPose.upright(head)
      val d=site.t-head.t
      if(d.x*d.x+d.z*d.z<1e-6f) return WindowPose.upright(head)
      return Pose(head.t,Quaternion.fromEuler(0f,Math.toDegrees(atan2(d.x,d.z).toDouble()).toFloat(),0f))
    }
    const val RESUME_AFTER_MS=5_000L
    /** t945: the release rule applies to every window except Settings, which stays where it is put. */
    fun easesBack(view: String?)=view!=SettingsPanel.VIEW
  }

  override fun onCreate() {
    if(!com.overhand.app.BuildConfig.DEBUG) return
    s.activity.intent?.getStringExtra("fake_head")?.let { s.fakeHead=parseHead(it) }
  }

  private fun parseHead(v: String): Pose? = runCatching {
    val a=v.split(',').map { it.trim().toFloat() }
    Pose(Vector3(a[0],a[1],a[2]),Quaternion.fromEuler(-a[3],a[4],0f).let { q ->
      // Pitch up is positive; verify the sign against forward once, so a positive pitch always looks up.
      val f=q*Vector3(0f,0f,1f);if((f.y>0)!=(a[3]>0) && a[3]!=0f) Quaternion.fromEuler(a[3],a[4],0f) else q })
  }.getOrNull()

  override fun onDebugIntent(intent: Intent) {
    // Probes for scripts/native_recenter_check.mjs; injected poses, never real-hand evidence.
    intent.getStringExtra("fake_head")?.let { v -> s.fakeHead=parseHead(v);Log.i(TAG,"recenter event=fake_head value=$v") }
    if(intent.getBooleanExtra("fake_head_clear",false)) s.fakeHead=null
    if(intent.getBooleanExtra("center_all",false)) request("debug")
    if(intent.getBooleanExtra("sim_system_recenter",false)) s.ui.post { s.activity.onRecenter(true) }
    intent.getStringExtra("recenter_resettle")?.let { resettle(it) }
    if(intent.getBooleanExtra("recenter_open_all",false)) s.commands.add {
      l.specs.forEach { l.createWindow(it) }
      if(!s.interaction.decisionShowing) s.ui.post { s.interaction.decisions.open() }
    }
    intent.getStringExtra("push_window")?.let { v -> s.commands.add {
      val (view,where)=v.split(':').let { it[0] to it.getOrElse(1) { "up" } }
      val e=l.specs.firstOrNull { it.view==view }?.let { l.entities[it.id] } ?: return@add
      val h=WindowPose.upright(head())
      val p=if(where=="behind") h*Pose(Vector3(0f,0f,-4f),Quaternion.fromEuler(0f,180f,0f)) else h*Pose(Vector3(0f,4f,0.8f))
      e.windowPose(p,"RecenterFeature_onDebugIntent")
      Log.i(TAG,"recenter event=push_window view=$view where=$where pose=${fmt(p)}")
    } }
    if(intent.getBooleanExtra("recenter_snapshot",false)) s.commands.add { snapshot() }
  }

  /** ECS thread: every panel's pose against the head, and the rule's verdict, for the device check. */
  private fun snapshot() {
    val h=head();val real=s.activity.scene.getViewerPose()
    val rows=JSONArray()
    fun row(kind: String,name: String,e: Entity?,p: Pose?,visible: Boolean) {
      p ?: return
      val c=Comfort.check(h.t.v(),p.t.v(),yawDeg(h))
      rows.put(JSONObject().put("kind",kind).put("name",name).put("visible",visible)
        .put("pose",JSONArray(listOf(p.t.x,p.t.y,p.t.z,p.q.w,p.q.x,p.q.y,p.q.z)))
        .put("distance_m",c.distance).put("elevation_deg",c.elevationDeg).put("bearing_deg",c.bearingDeg)
        .put("horizontal_m",c.horizontal).put("ok",c.ok).put("why",c.why)
        .put("grabbable",e?.tryGetComponent<IsdkGrabbable>()!=null))
    }
    l.entities.forEach { (id,e) -> row("window",l.specs.first { it.id==id }.view,e,e.getComponent<Transform>().transform,true) }
    s.interaction.decisionEntity?.let { e -> row("card","decision",e,e.getComponent<Transform>().transform,s.interaction.decisionShowing) }
    if(s.interaction.graphVisible) row("graph","graph",null,s.interaction.architecture.sceneGraph.rootPose(),true)
    s.interaction.graphPanel?.let { e -> if(e.tryGetComponent<Visible>()?.isVisible==true) row("graph","graph_detail",e,e.getComponent<Transform>().transform,true) }
    s.windows.stations.poses().forEach { (id,p) -> row("station",id,null,p,true) }
    val data=JSONObject().put("utc",java.time.Instant.now().toString()).put("elapsed_ms",android.os.SystemClock.elapsedRealtime())
      .put("placed",placedOnce).put("settled",settle.settled).put("settle_reason",settle.reason).put("pending",pending ?: JSONObject.NULL)
      .put("moved_by_hand",movedByHand).put("last_center",lastCentre).put("fake_head",s.fakeHead!=null)
      .put("head",JSONArray(listOf(h.t.x,h.t.y,h.t.z,h.q.w,h.q.x,h.q.y,h.q.z))).put("head_yaw_deg",yawDeg(h))
      .put("head_pitch_deg",HeadSettle.pitchDeg((h.q*Vector3(0f,0f,1f)).v()))
      .put("real_viewer",JSONArray(listOf(real.t.x,real.t.y,real.t.z,real.q.w,real.q.x,real.q.y,real.q.z)))
      .put("rule",JSONObject().put("min_m",Comfort.MIN_M).put("max_m",Comfort.MAX_M).put("up_deg",Comfort.UP_DEG)
        .put("down_deg",Comfort.DOWN_DEG).put("ring_m",Comfort.RING_M))
      .put("panels",rows)
    s.activity.openFileOutput("recenter_snapshot.json",0).use { it.write(data.toString(2).toByteArray()) }
  }
  private fun fmt(p: Pose)="%.2f,%.2f,%.2f/yaw%.0f".format(p.t.x,p.t.y,p.t.z,yawDeg(p))
}
