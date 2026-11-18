@file:OptIn(com.meta.spatial.core.SpatialSDKExperimentalAPI::class)
package com.overhand.app.features

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import com.meta.spatial.core.*
import com.meta.spatial.isdk.*
import com.meta.spatial.toolkit.*
import com.overhand.app.FirstMinutes
import com.overhand.app.R
import com.overhand.app.data.RecordedSource
import com.overhand.app.features.Shell.Companion.TAG
import com.overhand.app.mr.PanelFacing
import com.overhand.app.stations.Stations
import com.overhand.app.windows.PanelPolish
import com.overhand.app.windows.PolishRules
import com.overhand.app.windows.PanelTransition
import com.meta.spatial.runtime.PanelSceneObject
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.roundToInt

/** The six windows and stations: panel registration, gaze activation, window_* probes. */
class WindowsFeature(private val s: Shell) : Feature {
  override val name = "windows"
  private val l get()=s.layout
  private val d get()=s.dashboards
  private val gaze=HeadGaze()
  private var highlighted=0
  private var gazeAt=0L
  private var candidate = 0
  private var candidateSince = 0L
  private data class Motion(val curve: PanelTransition=PanelTransition(),var root: View?=null,
    @Volatile var hidden: Boolean=true,var surface: android.view.Surface?=null)
  private val motion=linkedMapOf<Entity,Motion>() // ECS thread only
  private var graphOpen=false
  private val captureIO by lazy { java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r,"motion-capture").apply { isDaemon=true } } }
  @Volatile var cardDrawn=false; private set
  /** #1002: windows a spoken command closed (view names); "undo" or "open <window>" takes them out. #1034: the dock
   *  toggles the same set, and a calm put-on starts with every dock window in it ([Dock.closedAtPutOn]). */
  val voiceHidden: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
  val startCalm get()=Dock.calm(s.prefs.getBoolean(Dock.START_CALM,true))
  private val dockGuard=Dock.Guard()
  /** A window is open when it exists and no voice command or dock press closed it. */
  private fun isOpen(view: String)=if(view=="build") s.interaction.site.drawn else l.specs.first { it.view==view }.id in l.entities && view !in voiceHidden
  fun isWindowOpen(view: String)=isOpen(view)
  /** Lag check progress stays in the log after removal of the orb. */
  fun showProgress(text: String?) { Log.i(TAG,"dock event=progress text=${text ?: "idle"}") }
  /** Any thread: one dock press (acted on at pinch close, like the orb, [Dock.pressOnClose]): opens or closes [view]. */
  fun dockToggle(view: String,why: String="dock") { s.commands.add {
    if(!dockGuard.accept(android.os.SystemClock.elapsedRealtime())) return@add
    if(view=="build") { s.interaction.pressYard();return@add }
    if(isOpen(view)) { voiceHidden.add(view);Log.i(TAG,"dock event=close view=$view why=$why");if(view=="blueprint") s.poseLog.capture(PoseLog.Moment.BLUEPRINT_CLOSE) } else openNow(view,why)
  } }
  /** Any thread: open [view] (dock or "open <window>"), at its remembered pose for this project, else its default slot. */
  fun open(view: String,why: String) { s.commands.add { if(!isOpen(view)) openNow(view,why) } }
  /** #1389: open [view]'s own window even when "build" is counted open because the site is drawn (the briefing's Done row). */
  fun openText(view: String,why: String) { s.commands.add { openNow(view,why) } }
  private fun openNow(view: String,why: String) {
    voiceHidden.remove(view)
    val sp=l.specs.first { it.view==view }
    if(sp.id !in l.entities) l.createWindow(sp)
    Log.i(TAG,"dock event=open view=$view why=$why remembered=${s.prefs.contains(l.layoutKey(sp))}")
    if(view=="blueprint") s.poseLog.capture(PoseLog.Moment.BLUEPRINT_OPEN)
  }
  /** Settings "Start calm": on closes the dock windows now; off restores the pre-#1034 layout (every window open). */
  fun setStartCalm(on: Boolean) {
    s.prefs.edit().putBoolean(Dock.START_CALM,on).apply()
    Log.i(TAG,"dock event=start_calm on=$on")
    if(on) voiceHidden.addAll(Dock.closedAtPutOn(true))
    else Dock.ITEMS.forEach { if(!s.opening.active) open(it.view,"start_calm_off") }
  }

  override fun onCreate() {
    voiceHidden.addAll(Dock.closedAtPutOn(startCalm))
    Log.i(TAG,"dock event=put_on calm=$startCalm closed=${voiceHidden.sorted()}")
    Log.i(TAG,"motion_timeline event=create elapsed_ms=${android.os.SystemClock.elapsedRealtime()} mode=${if(s.isRecorded()) "recorded" else "live"}")
    // Open the lightweight native surface before MRUK, stations and dashboards finish.
    // Recorded opening starts with no card (OpeningFeature); Decisions opens it from the palm menu.
    if(!s.opening.active) s.ui.post { if(!s.stopped) s.interaction.decisions.open() }
  }

  val stations by lazy {
    Stations(s.activity, { s.server() }, s.commands, { if(s.isRecorded()) s.recordedContent else null }, { if(s.interaction.classicView) null else l.headOrigin }) { project, pose ->
      val changed=l.stationProject!=project
      if(changed && l.stationProject.isNotEmpty()) l.entities.forEach { (id,e) ->
        val state=l.encode(e)
        if(s.prefs.contains(l.layoutKey(id)) || l.last[id]!=state) {
          s.prefs.edit().putString(l.layoutKey(id),state).apply()
        }
      }
      l.stationProject=project;l.stationPose=pose
      val oldOrigin=l.origin
      // t765: the ring sits at the wearer's eye height, never at a (possibly moved) station's height + 0.75 m.
      val o=WindowPose.upright(pose)*Pose(Vector3(0f,0.75f,-2.7f))
      if(s.interaction.classicView) l.origin=if(l.eyeY.isFinite()) Pose(Vector3(o.t.x,l.eyeY,o.t.z),o.q) else o
      if(changed) {
        // t945: Settings is world-locked where it was opened or put; a station change never moves it.
        l.specs.filter { (!StageWindows.keepsPlaced(it.id in l.entities,s.interaction.classicView)) && (!s.opening.active || it.id in l.entities) && it.view!=SettingsPanel.VIEW }.forEach { sp -> l.createWindow(sp) }
        s.ui.post {
          if(s.isRecorded()) s.recordedWindows.refresh(project)
          d.panels.values.forEach { p ->
            p.loaded=false;p.web.onPause();p.web.stopLoading();p.web.loadUrl("about:blank")
            p.bitmap?.recycle();p.bitmap=null;p.still.setImageDrawable(null)
            p.still.visibility=View.GONE;p.web.visibility=View.INVISIBLE
            p.status.text="Paused - look here to open"
          }
          d.active=R.id.fleet_panel
          d.panels[d.active]?.let { it.web.onResume();it.web.loadUrl(s.server()+FirstMinutes.DASHBOARD_PATH) }
        }
      } else if(s.interaction.classicView) {
        val delta=l.origin*l.inverse(oldOrigin)
        l.entities.forEach { (id,e) ->
          val sp=l.specs.first { it.id==id }
          if(sp.view==SettingsPanel.VIEW) return@forEach
          if(!(l.near && sp.view=="fleet")) e.windowPose(s.recenter.fit(WindowPose.follow(sp,e.getComponent<Transform>().transform,
            delta,l.origin,s.mr.deskFrame,l.eye(),s.prefs.contains(l.layoutKey(sp)) || id in l.held)),"WindowsFeature_onCreate")
          // Localization is not a user edit. Float roundoff must not pin this default
          // to the previous room and prevent the next MR surface upgrade.
          l.last[id]=l.encode(e)
        }
      }
    }
  }

  fun panels(): List<PanelRegistration> = l.specs.map { sp ->
    LayoutXMLPanelRegistration(sp.id, layoutIdCreator = { R.layout.web_panel },
      settingsCreator = {
        val saved = l.saved(sp)
        com.overhand.app.windows.Theme.panel(UIPanelSettings(shape = QuadShapeOptions(width = saved[7], height = saved[8]),
          display = DpDisplayOptions(width = if(sp.view=="settings") PolishRules.SETTINGS_W_DP else WindowLayout.PANEL_W_DP, height = WindowLayout.heightDp(sp.view))))
      }, panelSetupWithRootView = { root, _, _ ->
        val column = root.findViewById<LinearLayout>(R.id.panel_root)
        l.roots[sp.id]=column
        com.overhand.app.windows.Theme.applyPanelBackground(column)
        if(s.isRecorded()) column.addView(s.label(column,s.recordedSource.badge,22f))
        column.addView(s.label(column, sp.title, 36f).apply { tag=com.overhand.app.windows.RecordedWindows.TITLE_TAG })
        if(sp.view=="fleet") column.addView(s.label(column,FirstMinutes.PURPOSE,32f).apply {
          tag="launch-purpose";watchFirstContent(this)
        })
        val primaryAction=if(sp.view=="code" && !s.isRecorded()) Button(column.context).apply {
          text=if(s.isRecorded()) "Architecture snapshot · ${s.recordedSource.planLabel}" else "Architecture graph"
          textSize=28f;setOnClickListener { s.interaction.openGraph(l.stationProject.ifBlank { if(s.isRecorded()) s.recordedContent.defaultProject else "" }) }
        } else if(sp.view=="chat") Button(column.context).apply {
          text="Decisions";textSize=28f;setOnClickListener { s.interaction.decisions.open() }
        } else null
        if (sp.view == "settings") s.settings.build(column) else d.dashboard(column, sp)
        // The primary action shares the page's nav row, so the page keeps its lines (#816).
        primaryAction?.let { action -> column.findViewWithTag<LinearLayout>("recorded-nav")?.addView(action,LinearLayout.LayoutParams(0,-2,2f)) ?: column.addView(action) }
        if(sp.view=="settings") Unit // Settings owns its exact Dock.dc.html control sizes.
        else PanelPolish.install(column,WindowLayout.target(sp.view),PolishRules.WINDOW_GAP_DP,WindowLayout.type(sp.view))
      })
  } + stations.registrations()

  private fun watchFirstContent(purpose: View) {
        purpose.viewTreeObserver.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
          override fun onDraw() {
            if(l.fleetPlaced && purpose.width>0 && purpose.height>0 && purpose.isShown) {
              s.launchTrace.firstDraw(android.os.SystemClock.elapsedRealtime())?.let { Log.i(TAG,it) }
              purpose.post { if(purpose.viewTreeObserver.isAlive) purpose.viewTreeObserver.removeOnDrawListener(this) }
            }
          }
        })
  }

  override fun onSceneReady() {
    Log.i(TAG,"motion_timeline event=scene_ready elapsed_ms=${android.os.SystemClock.elapsedRealtime()}")
    s.activity.systemManager.registerSystem(object : SystemBase() {
      private var first=true
      override fun execute() {
        if(s.paused || s.stopped) return
        val now=android.os.SystemClock.elapsedRealtime()
        val alive=Query.where { has(Panel.id) }.eval().toSet()
        motion.keys.retainAll(alive)
        if(first) { first=false;Log.i(TAG,"motion_timeline event=first_ecs_frame elapsed_ms=$now") }
        // The shell's three-second tracking grace is for the full window cluster.
        // A native card needs no scene scan and can consume commands immediately.
        if(s.ready>0 && System.currentTimeMillis()<s.ready) {
          while(true) (s.commands.poll() ?: break).invoke()
          s.interaction.decisionEntity?.let {
            // Tracking can acquire a floor-relative head pose after the first frame.
            // Upgrade the bootstrap card immediately instead of freezing its zero pose
            // until the full cluster's three-second grace period expires.
            it.windowPose(WindowPose.upright(s.interaction.decisionPose()),"WindowsFeature_execute")
            lifetime(it,s.interaction.decisionShowing,now)
          }
        }
        val objects=s.activity.systemManager.findSystem<SceneObjectSystem>()
        motion.forEach { (entity,m) ->
          if(m.root==null) {
            val f=objects.getSceneObject(entity)
            if(f!=null && f.isDone && !f.isCompletedExceptionally) {
              val panel=f.getNow(null) as? PanelSceneObject
              m.root=panel?.rootView;m.surface=panel?.surface
              val card=entity.getComponent<Panel>().panelRegistrationId==R.id.decision_panel
              m.root?.let { root -> s.ui.post {
                val show=m.curve.showing
                PanelPolish.lifetime(root,show,true) { if(m.curve.showing==show) m.hidden=!show }
                if(card) {
                  root.viewTreeObserver.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
                    override fun onDraw() {
                      if(!cardDrawn && root.width>0 && root.height>0 && root.alpha>0.9f) {
                        cardDrawn=true
                        Log.i(TAG,"motion_timeline event=card_surface_draw elapsed_ms=${android.os.SystemClock.elapsedRealtime()} compositor_verified=false")
                        root.post { if(root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnDrawListener(this) }
                      }
                    }
                  })
                }
              } }
            }
          }
          // #1373 (q2 L729-732): the question card does not fade out; Close hides it once.
          val visible=com.overhand.app.decisions.QuestionCard.entityVisible(m.curve.showing,m.hidden,entity.getComponent<Panel>().panelRegistrationId==R.id.decision_panel)
          if(entity.tryGetComponent<Visible>()?.isVisible!=visible) entity.windowVisible(visible,"WindowsFeature_onDraw")
        }
      }
    })
    stations.start()
    s.ui.post(object : Runnable {
      override fun run() {
        if(s.stopped) return
        d.panels.values.filter { it.spec.id!=d.active }.forEach { d.freeze(it) }
        s.ui.postDelayed(this,1000)
      }
    })
  }

  /** Gaze picks the window to activate; drops persist the moved/resized layout. */
  override fun onFrame(now: Long) {
    val scene=s.activity.scene
    stations.frame(s.head(),now)
    var selected = 0
    var best = cos(Math.toRadians(12.0)).toFloat()
    val viewer = scene.getViewerPose()
    val tick=android.os.SystemClock.elapsedRealtime()
    val graph=Query.where { has(Panel.id) }.eval().firstOrNull { it.getComponent<Panel>().panelRegistrationId==R.id.graph_panel }
    graph?.let { e ->
      val m=motion[e]
      // Interaction writes the requested visibility before this feature runs.
      // During the closing fade our own Visible(true) is not a new show request.
      val closing=m!=null && !m.curve.showing && !m.hidden
      val requested=!closing && (e.tryGetComponent<Visible>()?.isVisible ?: false)
      lifetime(e,requested,tick)
      graphOpen=requested || motion[e]?.hidden==false
      e.windowPose(WindowPose.upright(e.getComponent<Transform>().transform),"WindowsFeature_onFrame")
    }
    s.interaction.decisionEntity?.let { e ->
      lifetime(e,s.interaction.decisionShowing && !graphOpen,tick)
      e.windowPose(WindowPose.upright(e.getComponent<Transform>().transform),"WindowsFeature_onFrame")
    }
    val dashboardIds=l.specs.map { it.id }.toSet()
    Query.where { has(Panel.id) }.eval().filter {
      ControlDock.windowsOwnsPanel(it.getComponent<Panel>().panelRegistrationId,
        dashboardIds+setOf(R.id.decision_panel,R.id.graph_panel)+s.opening.panelIds)
    }.forEach { e ->
      val p=e.getComponent<Transform>().transform
      val n=p.forward();val toEye=viewer.t-p.t
      lifetime(e,ControlDock.panelVisible(graphOpen,PanelFacing.front(n.x,n.y,n.z,toEye.x,toEye.y,toEye.z)),tick)
    }
    val enabled=s.prefs.getBoolean("head-gaze",false)
    var gazeHit=0
    var gazeDistance=Float.POSITIVE_INFINITY
    fun consider(id: Int,e: Entity,w: Float,h: Float) {
      if(e.tryGetComponent<Visible>()?.isVisible!=true) return
      HeadGaze.hit(viewer,e.getComponent<Transform>().transform,w,h)?.let { distance ->
        if(distance<gazeDistance) { gazeDistance=distance;gazeHit=id }
      }
    }
    val forward = viewer.forward()
    for ((id,e) in l.entities) {
      val panelPose=e.getComponent<Transform>().transform
      val normal=panelPose.forward()
      val toEye=viewer.t-panelPose.t
      // t945: Settings shows only while open.
      lifetime(e,SettingsPanel.visible(l.specs.first { it.id==id }.view,s.settings.open,
        l.specs.first { it.id==id }.view !in voiceHidden && ControlDock.panelVisible(graphOpen,PanelFacing.front(normal.x,normal.y,normal.z,toEye.x,toEye.y,toEye.z))),tick)
      if(!SettingsPanel.visible(l.specs.first { it.id==id }.view,s.settings.open,true)) continue
      // t799: a grab-bar hold counts as grabbed, so the pose is saved once, on release.
      val grabbed = e.tryGetComponent<IsdkGrabbable>()?.grabState == IsdkGrabState.Grabbed || s.grab.holding(l.specs.first { it.id==id }.view)
      if (grabbed) { l.held.add(id); selected = id }
      val state = l.encode(e)
      if ((!grabbed && l.last[id] != state) || (!grabbed && l.held.remove(id))) {
        s.prefs.edit().putString(l.layoutKey(l.specs.first { it.id==id }), state).apply()
        l.last[id] = state
        Log.i(TAG, "layout saved $id $state")
      }
      val dimensions=e.panelSize()
      if(!graphOpen && !s.interaction.decisionShowing) consider(id,e,dimensions.x,dimensions.y)
      if (!grabbed && selected == 0) {
        val p = e.getComponent<Transform>().transform.t
        val dx=p.x-viewer.t.x; val dy=p.y-viewer.t.y; val dz=p.z-viewer.t.z
        val length=sqrt(dx*dx+dy*dy+dz*dz)
        val dot=(dx*forward.x+dy*forward.y+dz*forward.z)/length.coerceAtLeast(0.01f)
        if (dot>best) { best=dot; selected=id }
      }
    }
    if(!graphOpen && s.interaction.decisionShowing) s.interaction.decisionEntity?.let { e ->
      val dimensions=e.panelSize()
      consider(R.id.decision_panel,e,dimensions.x,dimensions.y)
    }
    gaze.update(enabled,gazeHit,tick)?.let { id ->
      gazeAt=tick
      Log.i(TAG,"head_gaze focus=$id elapsed_ms=$tick dwell_ms=${gaze.focusedAfterMs} action=focus_only")
      s.ui.post { if(id!=R.id.decision_panel) d.activate(id) }
    }
    val nextHighlight=if(enabled) gaze.target else 0
    if(nextHighlight!=highlighted) {
      highlighted=nextHighlight
      val roots=l.roots.toMap()+listOfNotNull(s.interaction.decisionEntity?.let { e -> motion[e]?.root?.let { R.id.decision_panel to it } }).toMap()
      s.ui.post { roots.forEach { (id,root) ->
        root.foreground=if(id==nextHighlight && com.overhand.app.windows.Theme.ENABLED) com.overhand.app.windows.Theme.focusRim(root.resources.displayMetrics.density)
        else if(id==nextHighlight) android.graphics.drawable.GradientDrawable().apply {
          setColor(android.graphics.Color.TRANSPARENT)
          setStroke((5*root.resources.displayMetrics.density).toInt(),android.graphics.Color.CYAN)
          cornerRadius=16f
        } else null
      } }
    }
    if (selected != candidate) { candidate=selected; candidateSince=now }
    if (selected != 0 && selected != d.active && (l.held.contains(selected) || (!enabled && now-candidateSince>650))) {
      val next = selected
      s.ui.post { d.activate(next) }
    }
  }

  private fun lifetime(e: Entity,show: Boolean,now: Long) {
    val m=motion.getOrPut(e) { Motion() }
    if(m.curve.target(show,now)) {
      m.hidden=m.root==null && !show
      m.root?.let { root -> s.ui.post {
        PanelPolish.lifetime(root,show) { if(m.curve.showing==show) m.hidden=!show }
      } }
      Log.i(TAG,"motion_timeline event=panel_${if(show) "show" else "hide"} id=${e.getComponent<Panel>().panelRegistrationId} elapsed_ms=$now")
    }
    val visible=com.overhand.app.decisions.QuestionCard.entityVisible(show,m.hidden,e.getComponent<Panel>().panelRegistrationId==R.id.decision_panel)
    if(e.tryGetComponent<Visible>()?.isVisible!=visible) e.windowVisible(visible,"WindowsFeature_lifetime")
  }

  override fun onPause() { gaze.update(false,0,0);d.panels.values.forEach { it.web.onPause() } }
  override fun onResume() {
    d.panels[d.active]?.web?.onResume()
    // Recorded pages retain their state across resume. Receipts and station changes
    // refresh through their own callbacks; replaying the page fade here hides its body.
  }
  override fun onDestroy() {
    captureIO.shutdown()
    stations.close()
    d.panels.values.forEach { it.web.destroy(); it.bitmap?.recycle() }
  }

  override fun onDebugIntent(intent: Intent) {
    intent.getStringExtra("near_gaze_setting")?.let { name -> s.ui.post {
      fun find(v: View): List<android.widget.CheckBox> = (if(v is android.widget.CheckBox) listOf(v) else emptyList())+
        (if(v is android.view.ViewGroup) (0 until v.childCount).flatMap { find(v.getChildAt(it)) } else emptyList())
      l.roots[R.id.settings_panel]?.let { root -> find(root).firstOrNull { it.text.toString()==name }?.performClick() }
    } }
    if(intent.getBooleanExtra("near_gaze_snapshot",false)) s.ui.postDelayed({ s.commands.add {
      val eye=s.activity.scene.getViewerPose()
      val rows=org.json.JSONArray()
      val entries=l.entities.toMap()+listOfNotNull(s.interaction.decisionEntity?.let { R.id.decision_panel to it }).toMap()
      entries.forEach { (id,e) ->
        val p=e.getComponent<Transform>().transform
        val delta=p.t-eye.t
        val dimensions=e.panelSize()
        rows.put(org.json.JSONObject().put("name",l.specs.firstOrNull { it.id==id }?.view ?: "decision")
          .put("distance_m",sqrt(delta.dot(delta).toDouble())).put("below_eye_m",eye.t.y-p.t.y)
          .put("pose",org.json.JSONArray(listOf(p.t.x,p.t.y,p.t.z,p.q.w,p.q.x,p.q.y,p.q.z)))
          .put("size_m",org.json.JSONArray(listOf(dimensions.x,dimensions.y)))
          .put("entity_scale",e.tryGetComponent<Scale>()?.scale?.let { org.json.JSONArray(listOf(it.x,it.y,it.z)) }))
      }
      val data=org.json.JSONObject().put("utc",java.time.Instant.now().toString()).put("elapsed_ms",android.os.SystemClock.elapsedRealtime())
        .put("touch_near",l.near).put("head_gaze",s.prefs.getBoolean("head-gaze",false))
        .put("focus",d.active).put("ray_target",gaze.target).put("highlighted",highlighted).put("focus_elapsed_ms",gazeAt)
        .put("viewer",org.json.JSONArray(listOf(eye.t.x,eye.t.y,eye.t.z,eye.q.w,eye.q.x,eye.q.y,eye.q.z))).put("panels",rows)
      s.activity.openFileOutput("near_gaze.json",0).use { it.write(data.toString(2).toByteArray()) }
      s.ui.post {
        val glyphs=org.json.JSONArray()
        fun texts(v: View): List<android.widget.TextView> = (if(v is android.widget.TextView) listOf(v) else emptyList())+
          (if(v is android.view.ViewGroup) (0 until v.childCount).flatMap { texts(v.getChildAt(it)) } else emptyList())
        val card=s.interaction.decisionEntity?.let { motion[it]?.root?.findViewById<LinearLayout>(R.id.panel_root) }
        listOf("fleet" to l.roots[R.id.fleet_panel],"decision" to card).forEach { (name,root) ->
          if(root!=null && root.height>0) texts(root).filter { it.text.isNotEmpty() && it.visibility==View.VISIBLE }.forEach { t ->
            val bounds=android.graphics.Rect();t.paint.getTextBounds("Hx",0,2,bounds)
            val height=if(name=="fleet") { if(l.near) NearTouch.HEIGHT else 0.75f } else { if(l.near) NearTouch.CARD_HEIGHT else 0.72f }
            glyphs.put(org.json.JSONObject().put("panel",name).put("sample","Hx").put("text",t.text.toString())
              .put("ink_px",bounds.height()).put("panel_height_px",root.height).put("panel_height_m",height)
              .put("ink_m",bounds.height()*height/root.height))
          }
        }
        s.activity.openFileOutput("near_glyphs.json",0).use { it.write(glyphs.toString(2).toByteArray()) }
      }
    } },700)

    intent.getStringExtra("motion_capture")?.let { name -> s.commands.add {
      val id=when(name) { "decision" -> R.id.decision_panel;"graph" -> R.id.graph_panel;else -> l.specs.first { it.view==name }.id }
      val e=Query.where { has(Panel.id) }.eval().firstOrNull { it.getComponent<Panel>().panelRegistrationId==id }
      val root=e?.let { motion[it]?.root }
      val surface=e?.let { motion[it]?.surface }
      if(root!=null && surface!=null) s.ui.post {
        val show=intent.getBooleanExtra("motion_show",true)
        // Exercise the same open/Close handlers as normal UI, then capture their real surface properties.
        fun buttons(v: View): List<Button> = (if(v is Button) listOf(v) else emptyList())+
          (if(v is android.view.ViewGroup) (0 until v.childCount).flatMap { buttons(v.getChildAt(it)) } else emptyList())
        fun closeCard() {
          s.interaction.decisionEntity?.let { motion[it]?.root }?.let { card ->
            buttons(card).firstOrNull { it.text=="Close" }?.performClick()
          }
        }
        when(name) {
          "decision" -> if(show) s.interaction.decisions.open() else closeCard()
          "graph" -> if(show) s.interaction.openGraph(l.stationProject,true) else s.interaction.architecture.hide()
          else -> if(show) closeCard() else s.interaction.decisions.open()
        }
        // Start timestamps at the actual ECS lifetime edge, rather than at adb intent arrival.
        s.commands.add {
          lifetime(e,show,android.os.SystemClock.elapsedRealtime())
          s.ui.post {
            val began=android.os.SystemClock.elapsedRealtime()
            fun capture(delay: Long) {
              val at=android.os.SystemClock.elapsedRealtime()
              val alpha=root.alpha;val scale=root.scaleX
              val bmp=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
              val stem="motion_${name}_${if(show) "show" else "hide"}_$delay"
              val state=org.json.JSONObject().put("alpha",alpha).put("scale",scale)
                .put("elapsed_ms",at).put("actual_delay_ms",at-began).put("requested_delay_ms",delay)
                .put("capture","PixelCopy SDK panel surface")
              android.view.PixelCopy.request(surface,bmp,{ result ->
                state.put("pixel_copy_result",result).put("copy_completed_ms",android.os.SystemClock.elapsedRealtime())
                captureIO.execute {
                  if(result==android.view.PixelCopy.SUCCESS) s.activity.openFileOutput("$stem.png",0).use { bmp.compress(Bitmap.CompressFormat.PNG,100,it) }
                  bmp.recycle()
                  s.activity.openFileOutput("$stem.json",0).use { it.write(state.toString().toByteArray()) }
                }
              },s.ui)
            }
            capture(0)
            listOf(120L,240L).forEach { delay ->
              root.postDelayed({ capture(delay) },(began+delay-android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0))
            }
          }
        }
      }
    } }
    if(intent.getBooleanExtra("motion_snapshot",false)) s.commands.add {
      val rows=org.json.JSONArray()
      Query.where { has(Panel.id) }.eval().forEach { e ->
        val p=e.tryGetComponent<Transform>()?.transform ?: return@forEach
        val up=p.q*Vector3(0f,1f,0f)
        val id=e.getComponent<Panel>().panelRegistrationId
        val spec=l.specs.firstOrNull { it.id==id }
        val dimensions=e.tryGetComponent<IsdkPanelDimensions>()?.dimensions ?: e.tryGetComponent<PanelDimensions>()?.dimensions
        val size=dimensions ?: when(id) { R.id.decision_panel -> Vector2(1f,0.72f);R.id.graph_panel -> Vector2(1f,0.8f);else -> Vector2(0.6f,0.22f) }
        rows.put(org.json.JSONObject().put("id",id).put("name",spec?.view ?: when(id) { R.id.decision_panel -> "decision";R.id.graph_panel -> "graph";else -> "station" })
          .put("size",org.json.JSONArray(listOf(size.x,size.y)))
          .put("pose",org.json.JSONArray(listOf(p.t.x,p.t.y,p.t.z,p.q.w,p.q.x,p.q.y,p.q.z)))
          .put("up_dot",up.y).put("visible",e.tryGetComponent<Visible>()?.isVisible ?: true))
      }
      s.activity.openFileOutput("motion_poses.json",0).use { it.write(rows.toString(2).toByteArray()) }
      s.ui.post {
        val pages=org.json.JSONArray()
        l.roots.forEach { (id,root) ->
          root.findViewWithTag<android.widget.TextView>("recorded-page")?.let { page ->
            pages.put(org.json.JSONObject().put("id",id).put("text",page.text).put("alpha",page.alpha)
              .put("attached",page.isAttachedToWindow).put("window_visibility",page.windowVisibility)
              .put("height",page.height).put("width",page.width).put("paused",s.paused)
              .put("shown",page.isShown).put("root_alpha",root.alpha).put("visibility",page.visibility))
          }
        }
        s.activity.openFileOutput("motion_pages.json",0).use { it.write(pages.toString(2).toByteArray()) }
      }
    }
    l.specs.firstOrNull { it.view==intent.getStringExtra("windows_focus") }?.let { d.activate(it.id) }
    if(intent.getBooleanExtra("stations_move",false)) s.commands.add { stations.debugMove() }
    if(intent.getBooleanExtra("windows_move",false)) s.commands.add {
      val e=l.entities[R.id.fleet_panel] ?: return@add
      val p=e.getComponent<Transform>().transform
      e.windowPose(Pose(Vector3(p.t.x+0.12f,p.t.y+0.08f,p.t.z),p.q),"WindowsFeature_capture")
    }
    if(intent.getBooleanExtra("windows_reset",false)) l.resetLayout()
    if(intent.getBooleanExtra("windows_snapshot",false)) s.ui.post {
      stations.snapshot()
      l.specs.forEach { sp -> l.roots[sp.id]?.let { root ->
        if(root.width>0 && root.height>0) {
          val image=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
          root.draw(Canvas(image))
          s.activity.openFileOutput("windows_native_${sp.view}.png",android.content.Context.MODE_PRIVATE).use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
          image.recycle()
        }
      } }
    }
  }
}
