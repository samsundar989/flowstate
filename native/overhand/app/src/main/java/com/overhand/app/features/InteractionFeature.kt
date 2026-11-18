@file:OptIn(com.meta.spatial.core.SpatialSDKExperimentalAPI::class)
package com.overhand.app.features

import android.content.Intent
import android.widget.Button
import com.meta.spatial.core.*
import com.meta.spatial.isdk.IsdkGrabbable
import com.meta.spatial.toolkit.*
import com.overhand.app.R
import com.overhand.app.decisions.DecisionCards
import com.overhand.app.graph.ArchitectureGraph
import com.overhand.app.hands.SdkHandSource
import com.overhand.app.mr.PanelFacing
import com.overhand.app.windows.PanelPolish
import com.overhand.app.windows.PolishEvidence
import com.overhand.app.windows.WindowExit
import java.util.concurrent.atomic.AtomicBoolean
import com.overhand.app.graph.site.floor.FloorPlacement

/** ISDK interaction: hand-pose decision cards, the architecture graph, and quit. */
class InteractionFeature(private val s: Shell) : Feature {
  override val name = "interaction"
  private var handSource: SdkHandSource?=null
  var decisionEntity: Entity?=null
  @Volatile var decisionShowing=false
  private var graphEntity: Entity?=null
  @Volatile private var graphShowing=false
  private var graphCentred=false
  private var graphDetail=true
  /** t799: joints per hand sampled now (every render frame, for grabs); null where the hand is not tracked. */
  fun sampleHands(now: Long): Map<String,com.overhand.app.hands.HandJoints?> {
    handSource?.sample(now) ?: return emptyMap()
    return hands.mapValues { it.value.first }
  }
  /** Latest joints per hand ("left"/"right"), from the 20 Hz sampler. */
  val hands get()=handSource?.lastFrame ?: emptyMap()
  private val handFramePending=AtomicBoolean(false)
  private val frameLog by lazy { com.overhand.app.hands.HandFrameLog(java.io.File(s.activity.filesDir,"hand_frames.jsonl")) }
  private val scene get()=s.activity.scene

  val architecture: ArchitectureGraph by lazy {
    ArchitectureGraph(s.activity,{ s.server() },{ s.commands.add(it) },{ if(s.isRecorded()) s.recordedContent else null },
      { title,code -> if(s.isRecorded()) s.recordedWindows.openAt("code",title,code) }) { visible ->
      graphShowing=visible
      s.commands.add {
        if(!visible) { graphEntity?.windowVisible(false,"InteractionFeature_sampleHands");return@add }
        val l=s.layout
        val at=graphAt()
        architecture.sceneGraph.place(at)
        // Opening: the graph alone, without its text panel.
        if(!graphDetail) { graphEntity?.windowVisible(false,"InteractionFeature_sampleHands");return@add }
        val detailPose=s.recenter.fit(at*Pose(Vector3(0f,-0.95f,-0.25f)))
        val e=graphEntity ?: Entity.create(listOf(Panel(R.id.graph_panel))).also { it.windowPose(detailPose,"InteractionFeature_create") }.also { graphEntity=it }
        e.windowPose(detailPose,"InteractionFeature_sampleHands")
        e.windowVisible(true,"InteractionFeature_sampleHands")
      }
    }
  }
  val decisions by lazy {
    DecisionCards(s.activity, { s.server() }, { s.sessionSource },s.generation) { show ->
      val already=decisionShowing
      decisionShowing=show
      s.commands.add {
        if(show) {
          val pose=decisionPose()
          val e=decisionEntity ?: Entity.create(listOf(Panel(R.id.decision_panel))).also { it.windowPose(pose,"InteractionFeature_create");it.windowVisible(true,"InteractionFeature_create") }.also { decisionEntity=it }
          // t1234 (worn nov15d: the card jumped 20 cm, with no grab, after he had put it): pressing Decisions while the
          // card is up leaves it where it is; only opening it places it.
          if(!already) e.windowPose(pose,"InteractionFeature_sampleHands")
          e.windowVisible(true,"InteractionFeature_sampleHands")
          e.sizePanel(if(s.layout.near) Vector2(NearTouch.CARD_WIDTH,NearTouch.CARD_HEIGHT) else Vector2(StageWindows.CARD_W,StageWindows.CARD_H))
        } else decisionEntity?.windowVisible(false,"InteractionFeature_sampleHands")
      }
    }.also { it.compose=composeCardOn }
  }
  /** #1029: the one (project, part) the build site and the graph share. */
  val siteSelection=com.overhand.app.graph.site.SiteSelection()
  val site by lazy { com.overhand.app.graph.site.SiteScene(s.activity,{ s.commands.add(it) },siteSelection).also {
    it.smooth=smoothSite;it.observePose={ moment -> s.poseLog.capture(moment) }
    it.openCode={ title,code -> s.recordedWindows.openAt("code",title,code) }
    it.openAsk={ s.voice.setListening(true) }
  } }
  /** #1185: Settings "Smooth build site" (on by default): off redraws the whole site at every change, as #1029 did. */
  val smoothSite get()=!com.overhand.app.BuildConfig.DEBUG || s.prefs.getBoolean(SMOOTH_SITE,true)
  fun setSmoothSite(on: Boolean) {
    s.prefs.edit().putBoolean(SMOOTH_SITE,on).apply();site.smooth=on
    android.util.Log.i(com.overhand.app.graph.site.SiteScene.TAG,"site event=smooth on=$on")
  }
  /** #1029: Settings "Classic view" (off by default) is the emergency fallback to the graph-first put-on. */
  val classicView get()=s.prefs.getBoolean(CLASSIC_VIEW,false)
  /** Where the site stands at put-on: a table top ahead of the level head ([com.overhand.app.graph.site.SitePutOn]). */
  private fun tablePose()=WindowPose.upright(s.head())*Pose(Vector3(0f,-com.overhand.app.graph.site.SitePutOn.DROP.toFloat(),
    com.overhand.app.graph.site.SitePutOn.DISTANCE.toFloat()))
  private val floorPlace get()=com.overhand.app.places.Places.current.passthrough && s.prefs.getBoolean("passthrough",true)
  private fun sitePose(): FloorPlacement.Result? {
    if(!floorPlace) return null
    val h=s.head();val f=h.forward()
    return FloorPlacement.place(FloorPlacement.Head(h.t.x.toDouble(),h.t.y.toDouble(),h.t.z.toDouble(),f.x.toDouble(),f.z.toDouble()),
      room=s.mr.roomMode?.floorRoom())
  }
  /** Any thread: the build site in front (the default view), Recorded or Live. */
  fun showSite(why: String) { showQueued++;s.commands.add { try {
    if(!site.showing && !com.overhand.app.graph.site.YardPress.keepSaved(site.placement.pose,s.head())) recenterSite(com.overhand.app.graph.site.YardPress.WHY)
    val at=tablePose();val floor=sitePose();val eye=s.head().t.y.toDouble();s.poseLog.capture(PoseLog.Moment.PLACE);s.ui.post {
    showRan++
    android.util.Log.i(com.overhand.app.graph.site.SiteScene.TAG,"site event=open why=$why classic=$classicView")
    // #1351: a failed open is logged and left to [retryNeverOpened], never thrown into the frame loop or the UI thread.
    try { if(site.resume(why)) return@post; if(s.isRecorded()) site.startRecorded(at,s.recordedContent,why,floor,eye) else site.startLive(at,s.hostLink,why,floor,eye) }
    catch(e: Exception) { openFailed(why,"ui",e) } } }
    catch(e: Exception) { openFailed(why,"command",e) } } }
  private fun openFailed(why: String,stage: String,e: Exception) {
    if(why==com.overhand.app.graph.site.YardPress.WHY) yardReason="Site data unavailable"
    android.util.Log.w(com.overhand.app.graph.site.SiteScene.TAG,
      "site event=open_failed why=$why stage=$stage error=${e.javaClass.simpleName} message=${e.message}")
  }
  /** #1351: never-opened retry (recenter, resume, put-on timer); a site the wearer closed is never reopened. */
  private val openRetry=SiteOpenRetry { android.util.Log.i(com.overhand.app.graph.site.SiteScene.TAG,it) }
  /** Any thread. */
  fun retryNeverOpened(why: String) {
    if(openRetry.due(why,siteSwitch,classicView,site.placement.pose!=null)) showSite(SiteOpenRetry.WHY)
  }
  /** #1344 diagnosis (nov17a: the site never opened, and the launch rows were lost): showSite calls queued and run. */
  @Volatile private var showQueued=0
  @Volatile private var showRan=0
  @Volatile private var putOnGate="none"
  /** #1344: the gate state, also repeated at each recenter while the site was never opened (those rows survive the flood). */
  private fun gateLine(why: String)="site event=open_gate why=$why put_on=$putOnGate classic=$classicView switch=$siteSwitch queued=$showQueued ran=$showRan"
  fun hideSite(why: String="close") { openRetry.closed();site.hide(why) }
  /** #1182: Settings "Show build site" (on by default: the site at put-on, as before). Off hides it now and at put-on;
   *  on shows it again (unless Classic view). [show] overrides what happens to the site now (null: leave it). */
  val siteSwitch get()=s.prefs.getBoolean(SITE_PREF,true)
  val siteShowing get()=site.showing
  private var yardButton: android.widget.TextView?=null
  @Volatile private var yardReason: String?=null
  private var yardLabel=""
  fun bindYard(button: android.widget.TextView?) { yardButton=button;yardLabel="" }
  /** The dock's build target is the 3D site, never the flat build window. */
  fun pressYard() { s.commands.add {
    val why=com.overhand.app.graph.site.YardPress.WHY
    val requestedShown=!site.showing
    yardReason=try { com.overhand.app.graph.site.YardPress.press(site.showing,{
      // A Yard press is explicit visibility intent; hiding does not change startup preferences.
      setSiteSwitch(true,null)
      if(classicView) { s.prefs.edit().putBoolean(CLASSIC_VIEW,false).apply();architecture.hide() }
      android.util.Log.i(com.overhand.app.graph.site.SiteScene.TAG,gateLine(why))
    },{ if(!com.overhand.app.graph.site.YardPress.keepSaved(site.placement.pose,s.head())) recenterSite(why) },
      { showSite(why) },{ hideSite(why) }); }
    catch(e: Exception) { openFailed(why,"yard",e);"Site data unavailable" }
    android.util.Log.i(com.overhand.app.graph.site.SiteScene.TAG,
      "site event=yard_press shown=${if(requestedShown && yardReason==null) 1 else 0} why=${(yardReason ?: if(requestedShown) "show" else "hide").replace(' ','_')}")
  } }
  fun setSiteSwitch(on: Boolean,show: Boolean?=on) {
    if(on!=siteSwitch) { s.prefs.edit().putBoolean(SITE_PREF,on).apply()
      android.util.Log.i(com.overhand.app.graph.site.SiteScene.TAG,"site event=site_switch on=$on") }
    when(show) { true -> if(!site.showing && !classicView) showSite("site_switch"); false -> if(site.showing) hideSite("site_switch"); null -> {} }
  }
  /** ECS thread, centerAll: #1232 only a system recenter re-poses the site; Center windows moves windows, not the site. */
  fun recenterSite(why: String) {
    if(site.placement.pose==null) android.util.Log.i(com.overhand.app.graph.site.SiteScene.TAG,gateLine("never_opened_$why"))
    if(SettingsPanel.followsRecenter(why) || why==com.overhand.app.graph.site.YardPress.WHY)
      site.recenter(tablePose(),com.overhand.app.graph.site.SitePlacement.SYSTEM_RECENTER,sitePose(),s.head().t.y.toDouble(),
        preserveEntities=why==com.overhand.app.graph.site.YardPress.WHY)
    if(why!=com.overhand.app.graph.site.YardPress.WHY) retryNeverOpened("recenter_$why")   // #1351
  }
  /** ECS thread, the first placement (head settled): the default view, unless Classic view is on. */
  fun putOn() {
    putOnGate=if(classicView) "classic" else if(siteSwitch) "show" else "switch_off"
    android.util.Log.i(com.overhand.app.graph.site.SiteScene.TAG,gateLine("put_on"))
    if(classicView) return
    if(siteSwitch) { showSite("put_on");s.ui.postDelayed({ retryNeverOpened("timer") },SiteOpenRetry.TIMER_MS) }   // #1351
    // The main window floats over the site (WindowLayout.defaultPose); Recorded opens it here, Live opens all windows.
    if(s.opening.active) s.layout.specs.firstOrNull { it.view==WindowLayout.FOCUSED }?.let { s.layout.createWindow(it) }
  }
  fun setClassicView(on: Boolean) {
    s.prefs.edit().putBoolean(CLASSIC_VIEW,on).apply()
    android.util.Log.i(com.overhand.app.graph.site.SiteScene.TAG,"site event=classic_view on=$on")
    if(on) { hideSite("classic_view");s.ui.post { openGraph(if(s.isRecorded()) s.recordedContent.defaultProject else architecture.project,centred=true,detail=false,autoplay=s.isRecorded()) } }
    else { architecture.hide();showSite("classic_off") }
    s.recenter.request("classic_view")
  }
  /** #1029: a block pinched on the site opens the graph focused on that part (the graph stays beside the site). */
  private val siteToGraph=siteSelection.listeners.add { p,from -> if(from=="site") s.ui.post {
    if(architecture.project!=p.project || !graphShowing) openGraph(p.project)
    s.ui.post { architecture.select(p.part);android.util.Log.i(com.overhand.app.graph.site.SiteScene.TAG,
      "site event=graph_open project=${p.project} part=${p.part} focused=${architecture.selectedPart==p.part}") } } }
  /** #874: the Compose decision card. Debug builds only, off by default (Settings "Compose card", pref [COMPOSE_CARD]). */
  val composeCardOn get()=com.overhand.app.BuildConfig.DEBUG && s.prefs.getBoolean(COMPOSE_CARD,false)
  fun setComposeCard(on: Boolean) {
    if(!com.overhand.app.BuildConfig.DEBUG) return
    s.prefs.edit().putBoolean(COMPOSE_CARD,on).apply();decisions.compose=on
  }
  /** t765: level (yaw only) and clamped; the head's pitch never lifts the card above the wearer. */
  fun decisionPose()=if(!classicView && !s.layout.near && s.mr.deskFrame==null) StageWindows.decision(s.layout.headOrigin ?: WindowPose.upright(s.head())) else s.recenter.fit(if(s.layout.near) s.layout.nearPose() else s.mr.deskFrame?.card(s.layout.eye())?.pose()
    ?: (if(!classicView) StageWindows.decision(s.layout.headOrigin ?: WindowPose.upright(s.head())) else WindowPose.upright(s.head())*Pose(Vector3(0f,-0.08f,0.85f))))
  /** Graph root: level in front of the head when centred, else above the Code window; always clamped (t765). */
  private fun graphAt(): Pose {
    val l=s.layout
    val at=if(graphCentred && !graphDetail) WindowPose.upright(s.head())*Pose(Vector3(0f,-0.05f,1.3f)) // opening: level, arm's length
      else if(graphCentred) WindowPose.upright(s.head())*Pose(Vector3(0f,0.2f,1.5f))
      else (l.entities[R.id.code_panel]?.getComponent<Transform>()?.transform ?: l.defaultPose(l.specs.first { it.id==R.id.code_panel }))*Pose(Vector3(0f,0.5f,-0.15f))
    return s.recenter.fit(at)
  }
  /** ECS thread: the card in front of the level head (first placement and centerAll). */
  fun placeCard() { decisionEntity?.windowPose(decisionPose(),"InteractionFeature_placeCard") }
  /** ECS thread, centerAll: an open graph comes back in front of the wearer. */
  fun recenterGraph() {
    if(!graphShowing) return
    graphCentred=true
    val at=graphAt()
    architecture.sceneGraph.place(at)
    if(graphDetail) graphEntity?.windowPose(s.recenter.fit(at*Pose(Vector3(0f,-0.95f,-0.25f))),"InteractionFeature_recenterGraph")
  }
  val graphPanel get()=graphEntity
  val graphVisible get()=graphShowing
  fun openGraph(project: String, centred: Boolean=false, detail: Boolean=true, autoplay: Boolean=false) {
    graphCentred=centred;graphDetail=detail;architecture.autoplay=autoplay;architecture.open(project)
  }

  fun panels(): List<PanelRegistration> = listOf(
    LayoutXMLPanelRegistration(R.id.decision_panel, layoutIdCreator={ R.layout.web_panel },
      settingsCreator={ com.overhand.app.windows.Theme.panel(UIPanelSettings(shape=QuadShapeOptions(width=StageWindows.CARD_W,height=StageWindows.CARD_H),
        display=DpDisplayOptions(width=StageWindows.CARD_W_DP,height=StageWindows.CARD_H_DP))) },
      panelSetupWithRootView={ root,_,_ -> decisions.attach(root.findViewById(R.id.panel_root));PanelPolish.install(root,StageWindows.CARD_TARGET,com.overhand.app.windows.PolishRules.NEAR_GAP_DP,StageWindows.CARD_TYPE);PolishEvidence.register("decision",root,StageWindows.CARD_W,StageWindows.CARD_H) }),
    LayoutXMLPanelRegistration(R.id.graph_panel,layoutIdCreator={ R.layout.web_panel },
      settingsCreator={ com.overhand.app.windows.Theme.panel(UIPanelSettings(shape=QuadShapeOptions(width=1.0f,height=0.8f),
        display=DpDisplayOptions(width=1000f,height=800f))) },
      panelSetupWithRootView={ root,_,_ -> architecture.attach(root.findViewById(R.id.panel_root));PanelPolish.install(root,128,com.overhand.app.windows.PolishRules.NEAR_GAP_DP);PolishEvidence.register("graph",root,1f,0.8f) }))

  override fun onSceneReady() {
    val systems=s.activity.systemManager
    handSource=SdkHandSource(scene)
    decisions.start()
    systems.registerSystem(object : SystemBase() {
      override fun execute() { architecture.sceneGraph.tick(scene,s.paused || s.stopped,s.head(),hands.mapValues { it.value.first }) }
    })
    architecture.showOnSite={ p,part -> if(!site.showing) showSite("show_on_site");siteSelection.select(com.overhand.app.graph.site.SiteSelection.Pick(p,part),"graph") }
    systems.registerSystem(object : SystemBase() {
      override fun execute() { if(!s.paused && !s.stopped) {
        site.tick(scene)
        val label=com.overhand.app.graph.site.YardPress.label(site.drawn,yardReason ?: if(site.showing && !site.drawn) "Site loading now" else null)
        if(label!=yardLabel) { yardLabel=label;s.ui.post { yardButton?.text=label } }
      } }
    })
    systems.registerSystem(object : SystemBase() {
      var previous=0L
      override fun execute() {
        val now=android.os.SystemClock.elapsedRealtime()
        if(s.paused || now-previous<50 || s.stopped) return
        previous=now
        val poses=handSource?.sample(now) ?: return
        val trackedHands=handSource?.trackedHands ?: 0
        val viewer=scene.getViewerPose()
        val card=decisionEntity?.getComponent<Transform>()?.transform?.t
        // t745: 0.90 (26 degrees) dropped the thumb whenever the head pointed at the lower button row; 0.80 is 37 degrees.
        val aimDot=card?.let { (it-viewer.t).normalize().dot(viewer.forward()) } ?: -1f
        val aimed=aimDot>AIM_COS
        val hands=handSource?.lastFrame ?: emptyMap()
        if(handFramePending.compareAndSet(false,true)) s.ui.post {
          handFramePending.set(false)
          if(!s.paused && !s.stopped) {
            decisions.frame(poses,aimed,trackedHands)
            // #800: whenever a hand is tracked, not only with the card open (grab, resize and window pinches too).
            // Bounded by HandFrameLog: 8 MiB plus one rotated file.
            if(com.overhand.app.BuildConfig.DEBUG && (decisionShowing || trackedHands>0))
              frameLog.write(com.overhand.app.hands.HandFrameLog.record(now,hands,aimed,aimDot.toDouble(),trackedHands,decisions.gateState))
          }
        }
      }
    })
  }
  override fun onFrame(now: Long) {
    if(site.placement.pose!=null && floorPlace!=site.placement.inPassthrough)
      site.changePlace(tablePose(),sitePose(),s.head().t.y.toDouble())
    // t765: the card's first placement happens when the head settles (RecenterFeature.ready); head motion never drags it.
    val panelViewer=scene.getViewerPose()
    if(decisionShowing) decisionEntity?.let { cullPanelBack(it,panelViewer) }
    if(graphShowing && graphDetail) graphEntity?.let { cullPanelBack(it,panelViewer) }
  }
  private fun cullPanelBack(entity: Entity,viewer: Pose) {
    val pose=entity.getComponent<Transform>().transform
    val n=pose.forward();val e=viewer.t-pose.t
    // #944: write on change only (was every frame).
    val v=PanelFacing.front(n.x,n.y,n.z,e.x,e.y,e.z)
    if(entity.tryGetComponent<Visible>()?.isVisible!=v) entity.windowVisible(v,"InteractionFeature_cullPanelBack")
  }
  override fun onPause() { decisions.pause() }
  override fun onDestroy() { decisions.close(); architecture.close(); runCatching { site.close() }; handSource?.close(); frameLog.close() }

  override fun onDebugIntent(intent: Intent) {
    intent.getStringExtra("graph_project")?.let { openGraph(it,intent.getBooleanExtra("graph_centred",false)) }
    intent.getStringExtra("graph_pinch")?.let { architecture.debugPinch(it) }
    intent.getStringExtra("graph_drill")?.let { v -> architecture.debugDrill(v.substringBefore(':'),v.substringAfter(':',"").toIntOrNull()) }  // #999 "<part>[:<row>]"
    if(intent.getBooleanExtra("graph_back",false)) architecture.debugBack()
    intent.getStringExtra("graph_zoom_to")?.let { architecture.zoomTo(it) }
    // #1029 probes: "project/part" pinched on the site; "Show on site" for the graph's focused part; the switch.
    intent.getStringExtra("site_pick")?.split('/',limit=2)?.takeIf { it.size==2 }?.let { (p,part) -> s.ui.post { site.debugPick(p,part) } }
    if(intent.getBooleanExtra("site_show_on_site",false)) architecture.selectedPart?.let { part -> architecture.showOnSite?.invoke(architecture.project,part) }
    if(intent.hasExtra("classic_view")) setClassicView(intent.getBooleanExtra("classic_view",false))
    intent.getStringExtra("graph_zoom")?.toDoubleOrNull()?.let { architecture.debugZoom(it) }  // t869: graph_zoom_fps_p5
    if(intent.hasExtra("graph_replay_step")) architecture.debugReplay(intent.getIntExtra("graph_replay_step",0))
    if(intent.getBooleanExtra("graph_close",false)) architecture.hide()
    if(intent.getBooleanExtra("graph_capture_fixture",false)) s.commands.add {
      s.layout.entities[R.id.fleet_panel]?.windowPose(scene.getViewerPose()*Pose(Vector3(0f,-0.65f,2.2f)),"InteractionFeature_onDebugIntent")
    }
    if(intent.getBooleanExtra("graph_metrics",false)) s.commands.add {
      val metrics=architecture.sceneGraph.metrics(s.activity.systemManager.findSystem<SceneObjectSystem>(),scene.getViewerPose())
      metrics.put("active_window",s.layout.specs.firstOrNull { it.id==s.dashboards.active }?.view)
      s.activity.openFileOutput("graph_metrics.json",android.content.Context.MODE_PRIVATE).use { it.write(metrics.toString(2).toByteArray()) }
    }
    intent.getStringExtra("decision_preview_id")?.let { decisions.previewTest(it) }
    if(intent.getBooleanExtra("decisions_open",false)) decisions.open()
    // Capture isolation only: normal Close still goes through the native card UI.
    if(intent.getBooleanExtra("decisions_hide",false)) { decisionShowing=false;s.commands.add { decisionEntity?.windowVisible(false,"InteractionFeature_onDebugIntent") } }
    if(intent.getBooleanExtra("decisions_snapshot",false)) decisions.snapshot()
    if(intent.getBooleanExtra("decision_confirm",false)) decisions.debugConfirm()
    if(intent.getBooleanExtra("decision_thumb_hold",false)) decisions.debugThumbHold()
    if(intent.hasExtra("compose_card")) setComposeCard(intent.getBooleanExtra("compose_card",false))
    // #800: "x,y,ms" as panel fractions, a real touch down/up on the card (OverhandTouch traces it).
    intent.getStringExtra("decision_tap")?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }?.takeIf { it.size==3 }
      ?.let { (x,y,ms) -> decisions.debugTap(x,y,ms.toLong()) }
    val testId=intent.getStringExtra("decision_test_id")
    if(testId!=null) decisions.debugPose(testId,intent.getStringExtra("decision_pose") ?: "thumbs_up")
  }

  override fun settingsRows() = listOf(SettingsRow(70,SettingsTab.LINK) { root, container ->
    root.addView(liveSwitch(root.context))
    settingsTab(root,SettingsTab.VIEW)
    root.addView(android.widget.CheckBox(root.context).apply {
      text="Classic view";textSize=32f;setTextColor(android.graphics.Color.WHITE);tag="classic-view-switch";isChecked=classicView
      setOnCheckedChangeListener { _,checked -> this@InteractionFeature.setClassicView(checked) }
    })
    // R395 (#1191): debug only; the release build keeps the smooth site on with no switch ([smoothSite]).
    settingsTab(root,SettingsTab.DEBUG)
    if(com.overhand.app.BuildConfig.DEBUG) root.addView(android.widget.CheckBox(root.context).apply {
      text="Smooth build site";textSize=32f;setTextColor(android.graphics.Color.WHITE);tag="smooth-site-switch";isChecked=smoothSite
      setOnCheckedChangeListener { _,checked -> this@InteractionFeature.setSmoothSite(checked) }
    })
    settingsTab(root,SettingsTab.VIEW)
    root.addView(android.widget.CheckBox(root.context).apply {
      text="Show build site";textSize=32f;setTextColor(android.graphics.Color.WHITE);tag="show-build-site-switch";isChecked=siteSwitch
      setOnCheckedChangeListener { _,checked -> this@InteractionFeature.setSiteSwitch(checked) }
    })
    settingsTab(root,SettingsTab.DEBUG)
    DeveloperSettings.addPoseTrials(root,decisions)
    if(com.overhand.app.BuildConfig.DEBUG) root.addView(android.widget.CheckBox(root.context).apply {
      text="Compose card";textSize=32f;setTextColor(android.graphics.Color.WHITE);tag="compose-card-switch";isChecked=composeCardOn
      // Qualified: inside apply{} a bare setX() would be the CheckBox's own (#863).
      setOnCheckedChangeListener { _,checked -> this@InteractionFeature.setComposeCard(checked) }
    })
    settingsTab(root,SettingsTab.ROOM)
    root.addView(Button(root.context).apply { text="Open decision card";textSize=40f
      setOnClickListener { decisions.open() } })
    WindowExit.attach(container,s.activity)
    settingsTab(root,SettingsTab.ABOUT)
    root.addView(s.label(root,"Build "+com.overhand.app.BuildConfig.VERSION_NAME,14f))
  })
  /** Live only sends while this is on; it starts off on every launch and says so in its own text. */
  private fun liveSwitch(context: android.content.Context)=Button(context).apply {
    fun paint() {
      text=com.overhand.app.decisions.LiveSafety.SWITCH+": "+if(com.overhand.app.decisions.LiveSafety.sendReal) "on" else "off"
      textSize=40f
      foreground=if(com.overhand.app.decisions.LiveSafety.sendReal) android.graphics.drawable.GradientDrawable().apply {
        setColor(android.graphics.Color.TRANSPARENT);setStroke(6,android.graphics.Color.rgb(255,170,60));cornerRadius=28f } else null
    }
    paint()
    setOnClickListener { com.overhand.app.decisions.LiveSafety.sendReal=!com.overhand.app.decisions.LiveSafety.sendReal;paint() }
  }
  companion object { const val AIM_COS=0.80f;const val COMPOSE_CARD="compose-card";const val CLASSIC_VIEW="classic-view";const val SMOOTH_SITE="smooth-site";const val SITE_PREF="show-build-site" }
}
