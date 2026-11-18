@file:OptIn(com.meta.spatial.core.SpatialSDKExperimentalAPI::class)
package com.overhand.app.features

import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.meta.spatial.core.*
import com.meta.spatial.runtime.PanelSceneObject
import com.meta.spatial.toolkit.*
import com.overhand.app.R
import com.overhand.app.features.Shell.Companion.TAG
import com.overhand.app.hands.HandJoints
import com.overhand.app.opening.Opening
import com.overhand.app.opening.WristMenu
import com.overhand.app.opening.PalmInput
import com.overhand.app.windows.PanelPolish
import com.overhand.app.windows.Theme
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.atan2

/**
 * Recorded cold launch (see [Opening]): no windows or card at start; the graph opens centred
 * and plays the recorded day by itself; stations glow, then show one name each; a palm-up
 * menu opens windows; a hint at 30 s if nothing was pinched. Live launches are unchanged.
 */
class OpeningFeature(private val s: Shell) : Feature {
  override val name = "opening"
  val active by lazy { Opening.active(s.isRecorded()) }
  val panelIds=setOf(R.id.palm_menu_panel,R.id.opening_hint_panel)
  // ECS thread state.
  private var startedAt=0L
  private var pinched=false
  private val pinchNow=mutableMapOf("left" to false,"right" to false)
  private val palmUp=mutableMapOf("left" to false,"right" to false)
  private val pinchFrames=mutableMapOf("left" to 0,"right" to 0)
  @Volatile private var menuSeen=false
  @Volatile var windowOpened=false; private set
  private var menu: Entity?=null
  private var menuShowing=false
  val poseMenuVisible get()=menuShowing
  val poseMenuHand get()=wrist.owner
  /** #1236: the menu lives on the wrist of the palm-up hand ([WristMenu]); it has no world pose of its own. */
  private val wrist=WristMenu()
  private val poke=PalmInput.Poke()
  private var written: WristMenu.Place?=null
  private var hint: Entity?=null
  private var hintShowing=false
  private var lastLog=0L
  /** Smoke negative control (debug intent `debug_drop_window <view>`): the menu pick for this window opens nothing. */
  @Volatile private var dropWindow: String?=null

  fun panels(): List<PanelRegistration> = listOf(
    LayoutXMLPanelRegistration(R.id.palm_menu_panel,layoutIdCreator={ R.layout.web_panel },
      // #1236: wrist size, 2000 dp per metre as before (20 x 10.4 cm, 2 x 2 targets of 92 x 44 mm with 8 mm gaps).
      settingsCreator={ Theme.panel(UIPanelSettings(shape=QuadShapeOptions(width=WristMenu.PANEL_W,height=WristMenu.PANEL_H),
        display=DpDisplayOptions(width=MENU_DP_W,height=MENU_DP_H))) },
      panelSetupWithRootView={ root,_,_ -> buildMenu(root.findViewById(R.id.panel_root)) }),
    LayoutXMLPanelRegistration(R.id.opening_hint_panel,layoutIdCreator={ R.layout.web_panel },
      settingsCreator={ Theme.panel(UIPanelSettings(shape=QuadShapeOptions(width=0.80f,height=0.10f),
        display=DpDisplayOptions(width=1600f,height=200f))) },
      panelSetupWithRootView={ root,_,_ ->
        val column=root.findViewById<LinearLayout>(R.id.panel_root)
        Theme.applyPanelBackground(column)
        column.gravity=Gravity.CENTER
        column.addView(TextView(column.context).apply { text=Opening.HINT;textSize=46f;setTextColor(Color.WHITE);gravity=Gravity.CENTER })
      }))

  private fun buildMenu(column: LinearLayout) {
    Theme.applyPanelBackground(column)
    val m=Math.round(MENU_GAP_DP/2*column.resources.displayMetrics.density) // half the 8 mm gap on each side
    Opening.MENU.chunked(2).forEach { pair ->
      val row=LinearLayout(column.context)
      pair.forEach { item ->
        row.addView(Button(column.context).apply { text=item.label;textSize=MENU_TEXT_SP;tag="menu-${item.view}"
          // Selection is the other index crossing the panel (followWrist), never a ray/pinch click.
          },LinearLayout.LayoutParams(0,-1,1f).apply { setMargins(m,m,m,m) })
      }
      column.addView(row,LinearLayout.LayoutParams(-1,0,1f))
    }
    PanelPolish.install(column,64,16,com.overhand.app.windows.PolishRules.TypeScale(20f,20f,20f,20f))
  }

  /** UI thread (button click). */
  fun pick(view: String) {
    menuSeen=true
    Log.i(TAG,"opening event=menu_pick view=$view elapsed_ms=${SystemClock.elapsedRealtime()}")
    s.commands.add {
      pinched=true;hideMenu("pressed")
      if(view=="center") { s.recenter.centerAll("palm");return@add }
      if(view=="replay") { s.ui.post { s.interaction.openGraph(s.layout.stationProject.ifBlank { s.recordedContent.defaultProject },centred=true,detail=false,autoplay=true) };return@add }
      // #997: the Settings item is the same control as the orb: one press opens or closes it.
      if(view==SettingsPanel.VIEW) { windowOpened=true;s.settings.toggle("palm_menu");return@add }
      if(view==dropWindow) { Log.w(TAG,"opening event=menu_pick_dropped view=$view (debug_drop_window)");return@add }
      val sp=s.layout.specs.first { it.view==view }
      windowOpened=true
      s.windows.voiceHidden.remove(view)   // #1034: a calm put-on starts with it closed; the menu opens it
      // t1234: opens in its own slot (or where it was last put), never on top of the main window straight ahead.
      s.layout.createWindow(sp)
      if(view=="blueprint") s.poseLog.capture(PoseLog.Moment.BLUEPRINT_OPEN)
      s.ui.post { s.dashboards.activate(sp.id) }
    }
  }

  override fun onFrame(now: Long) {
    val t=SystemClock.elapsedRealtime()
    val viewer=s.head()
    // t765: the palm menu (with "Center windows") is on the hand in every mode, not only in the Recorded opening.
    if(!active) { palm(t,viewer,false);return }
    if(startedAt==0L) {
      startedAt=t
      s.layout.origin=WindowPose.upright(viewer);s.layout.headOrigin=s.layout.origin
      if(com.overhand.app.BuildConfig.DEBUG) runCatching { java.io.File(s.activity.filesDir,"opening_log.jsonl").delete() }
      Log.i(TAG,"opening event=start elapsed_ms=$t windows=0 card=false")
      // #1029: the build site is the default view (RecenterFeature places it once the head settles); Classic view keeps the graph.
      if(s.interaction.classicView) s.ui.post { s.interaction.openGraph(s.recordedContent.defaultProject,centred=true,detail=false,autoplay=true) }
    }
    val elapsed=t-startedAt
    palm(t,viewer,true)
    val due=Opening.hintDue(elapsed,pinched,menuSeen)
    if(due!=hintShowing) {
      hintShowing=due
      val pose=WindowPose.upright(WindowPose.upright(viewer)*Pose(Vector3(0f,-0.40f,1.05f)))
      val e=hint ?: Entity.create(listOf(Panel(R.id.opening_hint_panel),Transform(pose),Visible(false))).also { hint=it } // create-once: only while hint is null
      if(due) e.setComponent(Transform(pose))
      e.setComponent(Visible(due))
      Log.i(TAG,"opening event=hint_${if(due) "show" else "hide"} elapsed_ms=$elapsed pinched=$pinched menu_seen=$menuSeen")
    }
    s.windows.stations.mode=Opening.stationMode(elapsed,windowOpened)
    if(com.overhand.app.BuildConfig.DEBUG && elapsed<=90_000 && t-lastLog>=1000) { lastLog=t;logSecond(elapsed) }
  }

  /** Pinch and palm-up tracking; shows the palm menu over a raised, upturned palm. */
  private fun palm(t: Long,viewer: Pose,opening: Boolean) {
    val elapsed=t-startedAt
    val frame=s.interaction.hands
    val head=com.overhand.app.hands.Point(viewer.t.x.toDouble(),viewer.t.y.toDouble(),viewer.t.z.toDouble())
    listOf("left","right").forEach { side ->
      // A palm resting on the lap (Nov 12: wrist 0.6 m below the head) is not a menu request.
      val h=frame[side]?.first?.takeIf { Opening.plausible(it.wrist,head) && it.wrist.y-head.y>-Opening.MENU_DROP_M }
      val p=Opening.pinching(h,pinchNow.getValue(side))
      pinchNow[side]=p
      pinchFrames[side]=if(p) pinchFrames.getValue(side)+1 else 0
      if(opening && pinchFrames.getValue(side)==Opening.PINCH_FRAMES && !pinched) { pinched=true;Log.i(TAG,"opening event=first_pinch hand=$side elapsed_ms=$elapsed ${where(h,head)}") }
      palmUp[side]=Opening.palmUp(h,side=="left",palmUp.getValue(side))
    }
  }

  override fun onSceneReady() {
    // Feature.onFrame is throttled to 10 Hz by OverhandActivity. A dedicated ECS system follows at render rate.
    s.activity.systemManager.registerSystem(object : SystemBase() {
      override fun execute() {
        if(!s.paused && !s.stopped && s.recenter.placedOnce) followWrist(SystemClock.elapsedRealtime(),s.head())
      }
    })
  }
  override fun onPause() { s.commands.add { hideMenu("hand_lost") } }

  private fun followWrist(t: Long,viewer: Pose) {
    val head=com.overhand.app.hands.Point(viewer.t.x.toDouble(),viewer.t.y.toDouble(),viewer.t.z.toDouble())
    val joints=s.interaction.sampleHands(t)
    val fwd=viewer.forward()
    val wHead=WristMenu.Head(head,com.overhand.app.hands.Point(fwd.x.toDouble(),fwd.y.toDouble(),fwd.z.toDouble()))
    s.poseLog.palmEdge(viewer,joints,wrist.owner)
    val panels=Query.where { has(Panel.id) }.eval().filter { it!=menu && it!=hint && it.tryGetComponent<Visible>()?.isVisible==true }.mapNotNull { e ->
      val p=e.tryGetComponent<Transform>()?.transform ?: return@mapNotNull null
      val size=e.panelSize();val f=p.forward()
      PanelGrab.Panel(e.id.toString(),V3(p.t.x.toDouble(),p.t.y.toDouble(),p.t.z.toDouble()),
        Math.toDegrees(atan2(f.x,f.z).toDouble()),size.x.toDouble(),size.y.toDouble())
    }.toList()
    val pointers=joints.mapNotNull { (side,h) ->
      h ?: return@mapNotNull null
      val p=runCatching { s.activity.scene.getControllerPoseAtTime(side=="left",0L) }.getOrNull() ?: return@mapNotNull null
      val mask=com.meta.spatial.runtime.ControllerPose.TrackedBits or com.meta.spatial.runtime.ControllerPose.ValidBits
      if((p.flags and mask)!=mask) return@mapNotNull null
      val d=p.pose.forward();val r=PanelGrab.Ray(V3(p.pose.t.x.toDouble(),p.pose.t.y.toDouble(),p.pose.t.z.toDouble()),V3(d.x.toDouble(),d.y.toDouble(),d.z.toDouble()).unit())
      val derived=PanelGrab.pointer(V3(head.x,head.y,head.z),h)
      if(r.d.dot(derived.d)<0.5) null else side to r
    }.toMap()
    wrist.tick(t,wHead,joints,holdingWindow(),PalmInput.targets(head,joints,panels,pointers)).forEach { e ->
      val (kind,arg)=e.split(':')
      if(kind=="show") { Log.i(TAG,"menu event=show hand=${arg.first()} why=palm_face");written=null;poke.reset();showMenu("palm_face") }
      else hideMenu(arg)
    }
    if(wrist.fresh && menuShowing) wrist.place?.let(::writePlace)
    if(menuShowing) wrist.place?.let { p ->
      val other=if(wrist.owner=="left") "right" else "left"
      poke.tick(p,joints[other]?.fingers?.firstOrNull()?.tip)?.let { index -> pick(Opening.MENU[index].view) }
    } else poke.reset()
  }
  /** Any window held by a hand grab (the windows, the card, the graph, the caption window). */
  private fun holdingWindow()=(s.layout.specs.map { it.view }+listOf("decision","graph","voice")).any { s.grab.holding(it) } ||
    Query.where { has(Panel.id) }.eval().any {
      it.tryGetComponent<com.meta.spatial.isdk.IsdkGrabbable>()?.grabState==com.meta.spatial.isdk.IsdkGrabState.Grabbed
    }
  /** The only pose writes the menu gets: its hand's target this frame (write on a 1 mm or 1 degree change). */
  private fun writePlace(p: WristMenu.Place) {
    val w=written
    if(w!=null && (p.centre-w.centre).length()<0.001 && p.facing.dot(w.facing)>0.99985) return
    written=p
    val c=p.centre;val f=p.facing
    val yaw=Math.toDegrees(atan2(f.x,f.z)).toFloat()
    val pitch=Math.toDegrees(kotlin.math.asin(f.y.coerceIn(-1.0,1.0))).toFloat()
    // Same sign check as RecenterFeature: the panel's +z must point along [facing] (head to panel), tilted to the head.
    val q=Quaternion.fromEuler(-pitch,yaw,0f).let { q -> val z=q*Vector3(0f,0f,1f);if((z.y>0)!=(f.y>0) && pitch!=0f) Quaternion.fromEuler(pitch,yaw,0f) else q }
    menu?.setComponent(Transform(Pose(Vector3(c.x.toFloat(),c.y.toFloat(),c.z.toFloat()),q)))
  }

  private fun where(h: HandJoints?,head: com.overhand.app.hands.Point)=h?.let { "wrist_from_head=%.2f,%.2f,%.2f".format(it.wrist.x-head.x,it.wrist.y-head.y,it.wrist.z-head.z) } ?: ""
  override fun onCreate() { if(active) s.interaction.decisions.autoOpen=false }
  private fun showMenu(why: String) {
    val p=wrist.place ?: return
    val c=p.centre
    val e=menu ?: Entity.create(listOf(Panel(R.id.palm_menu_panel),
      Transform(Pose(Vector3(c.x.toFloat(),c.y.toFloat(),c.z.toFloat()))),Visible(false))).also { menu=it }
    menuShowing=true;menuSeen=true
    writePlace(p)
    e.setComponent(Visible(true))
    s.poseLog.capture(PoseLog.Moment.MENU_OPEN)
    Log.i(TAG,"opening event=menu_show why=$why elapsed_ms=${SystemClock.elapsedRealtime()-startedAt}")
  }
  private fun hideMenu(why: String) {
    wrist.hide(why)
    if(!menuShowing) return
    menuShowing=false;written=null;menu?.setComponent(Visible(false))
    s.poseLog.capture(PoseLog.Moment.MENU_CLOSE)
    Log.i(TAG,"menu event=hide why=$why")
    Log.i(TAG,"opening event=menu_hide why=$why elapsed_ms=${SystemClock.elapsedRealtime()-startedAt}")
  }
  private companion object {
    const val MENU_DP_W=416f
    const val MENU_DP_H=224f
    const val MENU_TEXT_SP=20f
    const val MENU_GAP_DP=24f
  }

  /** Debug builds: one line a second of what is on screen (panel text, scene labels, graph changes). */
  private fun logSecond(elapsed: Long) {
    val objects=s.activity.systemManager.findSystem<SceneObjectSystem>()
    val roots=Query.where { has(Panel.id) }.eval().filter { it.tryGetComponent<Visible>()?.isVisible ?: true }.mapNotNull { e ->
      val f=objects.getSceneObject(e) ?: return@mapNotNull null
      if(!f.isDone || f.isCompletedExceptionally) return@mapNotNull null
      val root=(f.getNow(null) as? PanelSceneObject)?.rootView ?: return@mapNotNull null
      e.getComponent<Panel>().panelRegistrationId to root
    }.toList() // evaluate here, on the ECS thread
    val sceneText=s.interaction.architecture.sceneGraph.shownText()
    // Where things are relative to the view: degrees off the view direction, metres away.
    val viewer=s.activity.scene.getViewerPose();val fwd=viewer.forward()
    fun bearing(p: Vector3): JSONArray { val d=p-viewer.t;val n=kotlin.math.sqrt(d.dot(d)).coerceAtLeast(1e-4f)
      return JSONArray(listOf(Math.toDegrees(kotlin.math.acos((d.dot(fwd)/n).coerceIn(-1f,1f)).toDouble()).toInt(),"%.2f".format(n).toDouble())) }
    val graphAt=s.interaction.architecture.sceneGraph.rootPose()?.let { bearing(it.t) }
    val stationAt=Query.where { has(Panel.id) }.eval().filter { e -> e.tryGetComponent<Visible>()?.isVisible==true && panelName(e.getComponent<Panel>().panelRegistrationId)=="station" }
      .map { bearing(it.getComponent<Transform>().transform.t) }.toList()
    val windows=s.layout.entities.keys.mapNotNull { id -> s.layout.specs.firstOrNull { it.id==id }?.view }
    val hintOn=hintShowing;val menuOn=menuShowing;val mode=s.windows.stations.mode
    s.ui.post { runCatching {
      fun texts(v: View): List<TextView> = (if(v is TextView) listOf(v) else emptyList())+
        (if(v is android.view.ViewGroup) (0 until v.childCount).flatMap { texts(v.getChildAt(it)) } else emptyList())
      val panels=JSONArray();var lines=0;val all=mutableListOf<String>()
      roots.forEach { (id,root) ->
        val shown=texts(root).filter { it.isShown && it.text.isNotBlank() }
        val n=shown.sumOf { tv -> tv.layout?.lineCount ?: tv.text.split('\n').size }
        lines+=n;shown.forEach { all.add(it.text.toString()) }
        panels.put(JSONObject().put("id",id).put("name",panelName(id)).put("lines",n).put("text",JSONArray(shown.map { it.text.toString() })))
      }
      val row=JSONObject().put("elapsed_ms",elapsed).put("uptime_ms",SystemClock.uptimeMillis())
        .put("graph_changes",s.interaction.architecture.motionChanges).put("graph_clock",s.interaction.architecture.motionClockText)
        .put("panel_text_lines",lines).put("visible_panels",panels.length()).put("windows_open",JSONArray(windows))
        .put("hint",hintOn).put("menu",menuOn).put("station_mode",mode)
        .put("graph_bearing",graphAt ?: JSONObject.NULL).put("station_bearings",JSONArray(stationAt))
        .put("viewer",JSONArray(listOf(viewer.t.x,viewer.t.y,viewer.t.z,fwd.x,fwd.y,fwd.z)))
        .put("scene_labels",JSONArray(sceneText)).put("leaks",JSONArray(Opening.leaks(all+sceneText))).put("panels",panels)
      runCatching { java.io.File(s.activity.filesDir,"opening_log.jsonl").appendText(row.toString()+"\n") }
      Log.i(TAG,"opening second=${elapsed/1000} graph_changes=${s.interaction.architecture.motionChanges} panel_text_lines=$lines windows=${windows.size} hint=$hintOn menu=$menuOn leaks=${Opening.leaks(all+sceneText).size}")
    }.onFailure { Log.w(TAG,"opening log failed",it) } }
  }
  private fun panelName(id: Int)=s.layout.specs.firstOrNull { it.id==id }?.view ?: when(id) {
    R.id.decision_panel -> "decision";R.id.graph_panel -> "graph_detail";R.id.palm_menu_panel -> "palm_menu"
    R.id.opening_hint_panel -> "hint";else -> "station" }

  override fun onDebugIntent(intent: Intent) {
    // Probes for the device check; never reported as real-hand input.
    intent.getStringExtra("debug_drop_window")?.let { dropWindow=it.ifBlank { null };Log.w(TAG,"opening debug_drop_window=${dropWindow}") }
    if(intent.getBooleanExtra("opening_palm",false))
      Log.i(TAG,"opening probe_palm ignored: a tracked palm is required")
    intent.getStringExtra("opening_pick")?.let { view -> s.ui.post {
      // The same Button.onClick a pinch on the menu fires.
      fun find(v: View): Button? = (v as? Button)?.takeIf { it.tag=="menu-$view" } ?:
        (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
      s.commands.add {
        val objects=s.activity.systemManager.findSystem<SceneObjectSystem>()
        val root=menu?.let { objects.getSceneObject(it)?.getNow(null) as? PanelSceneObject }?.rootView
        s.ui.post { val b=root?.let(::find);Log.i(TAG,"opening event=probe_pick view=$view found=${b!=null}");b?.performClick() }
      }
    } }
  }
}
