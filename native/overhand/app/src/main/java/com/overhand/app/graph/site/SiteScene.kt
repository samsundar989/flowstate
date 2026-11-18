@file:OptIn(com.meta.spatial.core.SpatialSDKExperimentalAPI::class)
package com.overhand.app.graph.site

import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.meta.spatial.core.*
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.runtime.ControllerPose
import com.meta.spatial.runtime.Scene
import com.meta.spatial.toolkit.*
import com.overhand.app.hostlink.HostLink
import com.overhand.app.hostlink.LinkState
import com.overhand.app.hostlink.LinkStatus
import com.overhand.app.windows.RecordedContent
import org.json.JSONObject
import java.io.File
import com.overhand.app.graph.site.floor.FloorPlacement
import com.overhand.app.graph.site.floor.FloorDrawing

/**
 * #1029 the build site in the scene: the default view at put-on (Settings "Classic view" restores the graph first).
 * One pose root at table height ([SitePutOn]) holding a buffered mesh child and Text3D signs. Events reach [SiteFeed]
 * from any thread; a 1 Hz pump applies them and redraws only when what the wearer sees changed. Nothing is created
 * or written per frame except cached label rotations; [tick] also watches for a pinch and import completion.
 */
class SiteScene(private val activity: Activity,private val enqueue: (() -> Unit) -> Unit,val selection: SiteSelection) {
  private val ui=Handler(Looper.getMainLooper())
  private var play: SitePump?=null
  private var recorded: SiteRecorded?=null
  private var clockOf: (Long) -> String={ "" }
  private var link: HostLink?=null
  private var connected=true
  private val onEvent: (JSONObject) -> Unit={ e -> SiteEvents.host(e,System.currentTimeMillis())?.let { ev -> play?.let { p -> p.feed.offer(ev,SystemClock.uptimeMillis()) } } }
  private val onStatus: (LinkStatus) -> Unit={ st -> connected=st.state==LinkState.CONNECTED }
  val mode get()=play?.mode ?: SiteMode.RECORDED
  @Volatile var showing=false;private set
  /** Published after ECS visibility/import work, rather than when opening was merely requested. */
  @Volatile var drawn=false;private set
  private var root: Entity?=null
  /** ECS observer; only the root handle and fixed moment names leave this class. */
  fun poseEntity()=root
  var observePose: ((com.overhand.app.features.PoseLog.Moment) -> Unit)?=null
  private val modelFiles=HashMap<Entity,File>()
  private val models=SiteSwap<Entity>({ SystemClock.uptimeMillis() },{ e,on ->
    // Direct object visibility makes ordering explicit in this ECS tick; the component keeps SDK state consistent.
    val o=sceneObjects()?.getSceneObject(e)?.getNow(null)
    o?.setIsVisible(on);e.setComponent(Visible(on))
  },{ e -> e.destroy();modelFiles.remove(e)?.delete() },{ Log.i(TAG,it) },failed={ ui.post { draw.retryMesh() } })
  private fun sceneObjects()=(activity as? AppSystemActivity)?.systemManager?.findSystem<SceneObjectSystem>()
  private fun pollModel() {
    val e=models.pending ?: return
    val f=sceneObjects()?.getSceneObject(e)
    if(f?.isCompletedExceptionally==true) { models.fail("import");return }
    val obj=if(f?.isDone==true) f.getNow(null) else null
    if(obj?.entity==e) meshProbe.observe(f) { seq,load,error ->
      if(error==null) Log.i(TAG,"site event=mesh_ready seq=$seq load_ms=$load owner=new")
    }
    runCatching { models.poll(obj?.entity,obj!=null) { obj!!.shouldFrustumCull(false) } }
      .onFailure { models.fail("activate") }
  }
  private val meshProbe=SiteMeshLoadProbe { SystemClock.uptimeMillis() }
  private var grounding: Entity?=null
  private var groundingFile: File?=null
  private var groundKey: Any?=null
  @Volatile private var placementVersion=0
  private var shownDay=0L
  /** ECS thread: the label entities, by [SiteDraw]'s ids. */
  private val labels=LinkedHashMap<Int,Entity>()
  private class Facing(val id: Int,val entity: Entity,val facing: SiteLabelFacing,val transform: Transform)
  private val facing=ArrayList<Facing>()
  private val draw=SiteDraw(ONCE)
  /** #1232: the one owner of the site's world pose and open state; every write logs its why. */
  val placement=SitePlacement { Log.i(TAG,it) }
  /** ECS thread: #1202 once, the piece entities (children of the root), by [SiteDraw]'s piece ids. */
  private val pieces=HashMap<Int,Entity>()
  /** #1185 Settings "Smooth build site" (on by default): off is #1029's full redraw. Read at each redraw. */
  @Volatile var smooth=true
  private var held=false
  private var version=0
  private var pick: SiteSelection.Pick?=null
  /** ECS thread reads it for the pinch ray; replaced (never mutated) on the UI thread. */
  @Volatile var layout: SiteLayout?=null;private set
  @Volatile var signs: List<SiteSign> = emptyList();private set
  private val wasDown=HashMap<Boolean,Boolean>()
  /** #1035: the agent under the ray, locked at speech start ([SiteTalk]); the state last drawn, for the per-frame aim. */
  val talk=SiteTalk { SystemClock.uptimeMillis() }
  @Volatile private var shown: SiteState?=null
  private var talkDrawnAt=0L
  private val layerDraw=SiteLayerDraw { Log.i(TAG,it) }
  val layerInteraction=SiteLayerInteraction { Log.i(TAG,it) }
  private var layerInfo: Map<SiteLayers.Id,SiteLayers.Info> = emptyMap()
  private var content: RecordedContent?=null
  var openCode: (String,String)->Unit = { _,_ -> }
  var openAsk: ()->Unit = {}
  /** Single route for lane E's Code window; preserves the actual module association. */
  fun openCodeFor(project: String,module: String) {
    val c=content
    val part=c?.components(project)?.firstOrNull { it.optString("id")==module }
    val title=(c?.projectName(project) ?: project)+" / "+module
    val code=if(c==null) "No code snapshot for this live module yet." else if(part==null) "No module code recorded." else c.componentDetail(project,part)
    ui.post { openCode(title,code) }
  }
  private fun askLayer(id: SiteLayers.Id) {
    val st=shown ?: return
    val l=layerDraw.layers.firstOrNull { it.id==id } ?: return
    val agent=st.agents.firstOrNull { it.project==id.project && (it.part==id.module || id.module=="tasks") }
    // No worker is invented: an idle module carries its own status into the same Recorded talk path.
    talk.subject(agent?.copy(name=l.name,part=id.module) ?: SiteAgent("layer:${id.project}/${id.module}",l.name,id.project,0,id.module,
      "Module",SiteLayers.line(l,layout!!,st),emptyList(),0,"${l.name}: ${SiteLayers.line(l,layout!!,st)}"))
    ui.post { openAsk() }
  }
  /** #1382: Blueprint's Ask: the layer card's path (lock [module] in [talk], then listen) without the site drawn. */
  fun askPart(project: String,module: String,name: String,status: String) {
    val agent=shown?.agents?.firstOrNull { it.project==project && it.part==module }
    talk.subject(agent?.copy(name=name,part=module) ?: SiteAgent("layer:$project/$module",name,project,0,module,"Module",status,emptyList(),0,"$name: $status"))
    ui.post { openAsk() }
  }
  /** Live (or no computer): the host link a sentence to an agent goes through; null in Recorded. */
  val liveLink: HostLink? get()=if(recorded==null) link else null

  private val pump=object : Runnable { override fun run() { if(!showing) return;step();ui.postDelayed(this,TICK_MS) } }

  init {
    // A new target redraws the highlight at once (at most 4 times a second); the 1 Hz pump catches the rest.
    talk.listeners.add { a -> ui.post {
      Log.i(TAG,com.overhand.app.PrivateLog.siteTalkTarget(a?.name ?: "none",a?.project ?: "",a?.taskId ?: ""))
      val now=SystemClock.uptimeMillis();if(showing && now-talkDrawnAt>=250) { talkDrawnAt=now;step() } } }
    selection.listeners.add { p,from -> ui.post {
      pick=p;draw.invalidate()
      if(from=="graph") Log.i(TAG,com.overhand.app.PrivateLog.siteShowOnSite(p.project,p.part,layout?.part(p.project,p.part)!=null))
      if(showing) step()
    } }
  }

  /** Recorded: the loaded recording's manifest, its day replayed by events, then the ended day, then again. UI thread. */
  fun startRecorded(at: Pose,content: RecordedContent,why: String="open",floor: FloorPlacement.Result?=null,eyeY: Double=Double.NaN) {
    stopFeeds()
    this.content=content;layerInfo=SiteLayers.catalog(content.source.architecture,content.source::code)
    val r=SiteRecorded(content.source.architecture,content.source.events,content::projectName,content.source.membership)
    recorded=r;clockOf={ t -> content.source.clock(t) }
    restart();place(at,"recorded",why,floor,eyeY)
  }
  /** Live: every project the computer reports, from its whole event log, then live events. UI thread. */
  fun startLive(at: Pose,host: HostLink,why: String="open",floor: FloorPlacement.Result?=null,eyeY: Double=Double.NaN) {
    stopFeeds()
    content=null;layerInfo=emptyMap()
    recorded=null;clockOf={ t -> java.text.SimpleDateFormat("HH:mm",java.util.Locale.US).format(java.util.Date(t)) }
    val b=SiteBoard(strict=false);play=SitePump(null,b,SystemClock.uptimeMillis())
    host.events.forEach { e -> SiteEvents.host(e,System.currentTimeMillis())?.let { b.apply(it) } }
    connected=host.status.state==LinkState.CONNECTED
    host.eventListeners.add(onEvent);host.statusListeners.add(onStatus);link=host
    place(at,"live",why,floor,eyeY)
  }
  private fun restart() { val r=recorded ?: return;play=SitePump(r,null,SystemClock.uptimeMillis()).also { if(held) it.hold(true,SystemClock.uptimeMillis()) } }
  private fun stopFeeds() { link?.let { it.eventListeners.remove(onEvent);it.statusListeners.remove(onStatus) };link=null }

  /** #1232: [at] is used only at the first placement; a reopened site keeps its last world pose ([SitePlacement]). */
  private fun place(at: Pose,mode: String,why: String,floor: FloorPlacement.Result?,eyeY: Double) {
    showing=true;draw.invalidate()
    val write=placement.open(at,why,floor,eyeY);val where=placement.pose ?: at
    enqueue {
      // #1232: not grabbable (a hand grab was a pose writer outside the rule); the pinch ray in [tick] picks blocks.
      val existing=root
      val r=existing ?: Entity.create(listOf(Transform(where),Scale(Vector3(placement.scale.toFloat())))).also { root=it;noCull(it,"root") }
      if(existing!=null && write!=null) r.setComponent(Transform(write))
      r.setComponent(Visible(true))
      groundVisible(placement.floorMode,"open_$why")
      observePose?.invoke(com.overhand.app.features.PoseLog.Moment.SITE_OPEN)
    }
    Log.i(TAG,"site event=put_on view=site mode=$mode why=$why placed=${write!=null} at=%.2f,%.2f,%.2f".format(where.t.x,where.t.y,where.t.z))
    ui.removeCallbacks(pump);ui.post(pump)
  }
  /** ECS thread, centerAll: only a system recenter re-poses the site ([SitePlacement.recenter]). */
  fun recenter(at: Pose,why: String,floor: FloorPlacement.Result?=null,eyeY: Double=Double.NaN,preserveEntities: Boolean=false) {
    val wasFloor=placement.floorMode;val old=placement.pose
    placement.recenter(at,why,floor,eyeY)?.let { p ->
      if(preserveEntities) {
        placementVersion++
        root?.setComponent(Transform(p));root?.setComponent(Scale(Vector3(placement.scale.toFloat())))
        refreshLayers()
        // Grounding is in world space; carry its retained mesh with the site's root.
        if(old!=null) grounding?.let { e ->
          val prior=e.tryGetComponent<Transform>()?.transform ?: Pose()
          e.setComponent(Transform(p*old.inverse()*prior))
        }
      } else applyPlacement(p,wasFloor,why)
    }
  }
  fun changePlace(at: Pose,floor: FloorPlacement.Result?,eyeY: Double) {
    val wasFloor=placement.floorMode
    placement.changePlace(at,floor,eyeY)?.let { p -> applyPlacement(p,wasFloor,if(floor==null) "place_leave" else "place_enter") }
  }
  /** ECS thread; mode changes invalidate meshes, but never restart the feed or move windows. */
  private fun applyPlacement(at: Pose,wasFloor: Boolean,why: String) {
    root?.setComponent(Transform(at));root?.setComponent(Scale(Vector3(placement.scale.toFloat())))
    refreshLayers()
    observePose?.invoke(com.overhand.app.features.PoseLog.Moment.PLACE)
    if(!wasFloor && !placement.floorMode) return
    placementVersion++
    groundVisible(false,why);groundKey=null
    ui.post {
      if(wasFloor!=placement.floorMode) {
        enqueue { pieces.values.forEach { it.destroy() };pieces.clear();labels.values.forEach { it.destroy() };labels.clear();facing.clear() }
        draw.forget()
      }
      draw.invalidate()
      if(showing) { val p=play; if(p!=null) show(p.board,shownDay,null,SystemClock.uptimeMillis()) }
    }
  }
  private fun groundVisible(on: Boolean,why: String) { grounding?.let {
    Log.i(TAG,"site event=visible_set value=$on why=$why target=grounding")
    it.setComponent(Visible(on))
  } }
  /**
   * #1232 suspect (d): the renderer frustum-culls each scene object against its bounds (SceneObject.shouldFrustumCull,
   * the SDK's RenderableCullJob). The site's root mesh is swapped at 1 Hz and its pieces and signs hang under a scaled
   * root, so stale bounds could cull it as the head turns. The site is one table-sized object: never frustum-cull it.
   */
  private fun noCull(e: Entity,what: String) {
    runCatching { (activity as? AppSystemActivity)?.systemManager?.findSystem<SceneObjectSystem>()?.getSceneObject(e)
      ?.thenAccept { it.shouldFrustumCull(false) } }.onFailure { Log.w(TAG,"site event=cull_off_failed target=$what $it") }
  }

  /** The 1 Hz pump: feed due events, flush, redraw if anything visible changed. */
  private fun step() {
    val p=play ?: return
    val now=SystemClock.uptimeMillis()
    val s=p.step(now,System.currentTimeMillis(),connected) ?: return
    show(p.board,s.day,s.arrivals,now)
  }
  /** #1185 for the lag check: on, the site stays drawn as it is and the pump feeds and redraws nothing; off resumes. */
  fun holdReplay(on: Boolean) { ui.post {
    held=on;play?.hold(on,SystemClock.uptimeMillis())
    Log.i(TAG,"site event=replay_hold on=$on")
    if(!on && showing) step()
  } }

  private fun show(b: SiteBoard,day: Long,arrivals: LongArray?,now: Long) {
    shownDay=day
    val revision=placementVersion;val floorMode=placement.floorMode
    val current=SiteFrame.of(b,day,mode,clockOf(day),null,null)
    shown=current.state;layout=current.layout;signs=current.signs
    val layerScale=placement.scale
    val boxes=SiteLayers.boxes(current.layout,current.state,layerScale,layerInfo)
    enqueue { if(revision==placementVersion) root?.let { layerDraw.update(it,boxes,current.layout,current.state,layerScale)
      if(showing) layerDraw.focus(layerInteraction.hover,layerInteraction.card) else layerDraw.hide() } }
    // Only the static ground enters the legacy importer. Crew, status, hover and cards never swap meshes.
    val f=SiteLayers.ground(current,placement.scale)
    val t0=SystemClock.uptimeMillis()
    val plan=draw.plan(f,smooth) ?: return
    val out=plan.mesh?.let { FloorDrawing.site(it,floorMode) }?.takeIf { it.indices.isNotEmpty() }?.let { m ->
      File(activity.cacheDir,"site_${version++}.glb").also { SiteMesh.write(it,m) } }
    val built=if(plan.mesh!=null) SystemClock.uptimeMillis()-t0 else 0L
    val late=arrivals?.maxOfOrNull { now-it } ?: -1
    val files=plan.ops.filterIsInstance<SiteDraw.Op.Load>().mapNotNull { op ->
      val m=FloorDrawing.site(op.built,floorMode)
      if(m.indices.isEmpty()) null else op.id to File(activity.cacheDir,"site_piece_${op.id}.glb").also { SiteMesh.write(it,m) } }.toMap()
    val result=placement.floor
    val key=if(placement.floorMode) listOf(result,f.layout) else null
    val ground=if(key!=null && key!=groundKey) {
      groundKey=key
      val footprints=boxes.groupBy { it.id.project }.values.map { ls -> val p=ls.first().box
        FloorPlacement.Rect(p.x0,-p.z1,p.x1,-p.z0) }
      val shapes=FloorPlacement.grounding(result!!,footprints)
      File(activity.cacheDir,"site_ground_${version++ % 2}.glb").also { SiteMesh.write(it,FloorDrawing.ground(shapes),blend=true) }
    } else null
    val reason=draw.meshReason
    enqueue {
      if(!showing || revision!=placementVersion) { draw.invalidate();out?.delete();files.values.forEach { it.delete() };ground?.delete();return@enqueue }
      apply(plan.ops,out,files,reason)
      if(ground!=null && placement.floorMode) {
        grounding?.destroy();if(groundingFile!=ground) groundingFile?.delete();groundingFile=ground
        Log.i(TAG,"site event=pose_set why=grounding target=grounding space=world")
        Log.i(TAG,"site event=visible_set value=true why=grounding target=grounding")
        grounding=Entity.create(listOf(Mesh(Uri.fromFile(ground),MeshCollision.NoCollision),Transform(Pose()),Visible(true))).also { noCull(it,"grounding") }
      }
    }
    Log.i(TAG,"site event=shown mode=${mode.name.lowercase()} projects=${f.layout.plots.size} lit=${f.lit.size} agents=${f.state.agents.size} " +
      "triangles=${draw.triangles} events=${arrivals?.size ?: 0} wait_ms=$late build_ms=$built " +
      "mesh_reload=${if(plan.mesh!=null) 1 else 0} labels_made=${plan.made} labels_kept=${plan.kept}"+(if(draw.once) " piece_loads=${plan.loads}" else ""))
  }
  private fun apply(ops: List<SiteDraw.Op>,mesh: File?,files: Map<Int,File> = emptyMap(),reason: String="geometry_inputs") {
    val r=root ?: return
    for(op in ops) when(op) {
      // #1202 once: loaded once at start (hidden), then only Visible writes.
      is SiteDraw.Op.Load -> {
        Log.i(TAG,"site event=pose_set why=piece_load target=piece_${op.id} space=local")
        Log.i(TAG,"site event=visible_set value=false why=piece_load target=piece_${op.id}")
        if(op.id in files) pieces[op.id]=Entity.create(listOf(Mesh(Uri.fromFile(files.getValue(op.id)),MeshCollision.NoCollision),Transform(Pose()),TransformParent(r),Visible(false))).also { noCull(it,"piece") } // rebuild-ok: once per piece, at start
      }
      is SiteDraw.Op.Show -> pieces[op.id]?.let {
        Log.i(TAG,"site event=visible_set value=${op.on} why=picture target=piece_${op.id}")
        it.setComponent(Visible(op.on))
      }
      is SiteDraw.Op.Drop -> { pieces.remove(op.id)?.destroy();File(activity.cacheDir,"site_piece_${op.id}.glb").delete() }
      is SiteDraw.Op.Mesh -> {
        if(mesh!=null) {
          // The root is pose-only. A fresh entity has no previous load future to reuse.
          models.request(reason,{
            val child=Entity.create(listOf(Mesh(Uri.fromFile(mesh),MeshCollision.NoCollision),Transform(Pose()),TransformParent(r),Visible(false)))
            modelFiles[child]=mesh
            meshProbe.write(null)
            Log.i(TAG,"site event=mesh_write seq=${meshProbe.sequence} uptime_ms=${SystemClock.uptimeMillis()} bytes=${mesh.length()}")
            child
          },{ mesh.delete();Unit })
        }
      } // rebuild-ok: event-driven (1 Hz pump, only when the mesh changed), not per frame
      is SiteDraw.Op.Destroy -> { labels.remove(op.id)?.destroy();facing.removeAll { it.id==op.id } }
      is SiteDraw.Op.Create -> {
        Log.i(TAG,"site event=pose_set why=label_create target=label_${op.id} space=local")
        Log.i(TAG,"site event=visible_set value=true why=label_create target=label_${op.id}")
        labels[op.id]=label(r,op.label,op.id).also { noCull(it,"label") }
      }
      is SiteDraw.Op.Retext -> labels[op.id]?.let {
        it.setComponent(Mesh(Text3D.create(text=op.label.text,fontSize=size(op.label),depth=0.0005f),MeshCollision.NoCollision));noCull(it,"label_retext") } // rebuild-ok: event-driven, its text changed
    }
  }
  private fun size(l: SiteMesh.Label)=if(l.size>0) l.size.toFloat() else when(l.kind) { "header" -> SiteMesh.HEADER;"sub" -> SiteMesh.SUB;"sign","sign_dim" -> 0.06;"agent" -> 0.03;else -> 0.04 }.toFloat()
  private fun label(r: Entity,l: SiteMesh.Label,id: Int): Entity {
    val size=size(l)
    val colour=when(l.kind) {
      "agent","sign_crew" -> SiteLook.rgba(SiteLook.CYAN).let { Color4(it[0],it[1],it[2],1f) };"sign_dim","sign_dim2" -> Color4(.55f,.6f,.66f,1f)
      "sub","sign_latest" -> Color4(.72f,.8f,.86f,1f);else -> Color4(.94f,.96f,.98f,1f) }
    val n=SiteMesh.native(l.x,l.y,l.z)
    val turn=SiteLabelFacing(n[0].toFloat(),n[1].toFloat(),n[2].toFloat())
    val transform=Transform(turn.pose)
    return Entity.create(listOf(Mesh(Text3D.create(text=l.text,fontSize=size,depth=0.0005f),MeshCollision.NoCollision),
      Material().apply { unlit=true;baseColor=colour },
      transform,TransformParent(r))).also { facing.add(Facing(id,it,turn,transform)) }
  }

  /** Per frame (ECS thread): poll the pending import, face labels, and cast the pinch ray. */
  fun tick(scene: Scene) {
    val r=root ?: return
    if(!showing) { layerDraw.step();return }
    pollModel()
    drawn=root?.tryGetComponent<Visible>()?.isVisible==true &&
      (layerDraw.layers.isNotEmpty() || models.active!=null || pieces.values.any { it.tryGetComponent<Visible>()?.isVisible==true &&
        sceneObjects()?.getSceneObject(it)?.getNow(null)!=null })
    faceLabels(scene,r)
    layerDraw.step()
    if(layerDraw.visibilityWaiting==0) hiddenVisibility.settled()
    val body=Query.where { has(AvatarBody.id) }.eval().firstOrNull { it.isLocal() && it.getComponent<AvatarBody>().isPlayerControlled }?.getComponent<AvatarBody>() ?: return
    var layerAim: SiteLayers.Id?=null
    var cardAim=false
    var pinched=false
    listOf(true to body.leftHand,false to body.rightHand).forEach { (left,hand) ->
      val c=hand.tryGetComponent<Controller>()
      val p=scene.getControllerPoseAtTime(left,0L)
      val mask=ControllerPose.TrackedBits or ControllerPose.ValidBits
      val down=c!=null && c.isActive && c.type==ControllerType.HAND && (p.flags and mask)==mask &&
        (c.buttonState and (if(left) ButtonBits.ButtonX else ButtonBits.ButtonA))!=0
      val was=wasDown.put(left,down)==true
      val tracked=c!=null && c.isActive && c.type==ControllerType.HAND && (p.flags and mask)==mask
      // #1035: which agent this hand points at (pure maths on the last drawn state; nothing is written per frame).
      if(tracked) {
        val (o,d)=local(r.getComponent<Transform>().transform,p.pose.t,p.pose.forward())
        val hit=SiteLayers.hit(layerDraw.layers,o,d)
        val card=layerDraw.cardHit(layerInteraction.card,o,d)?.takeIf { hit==null || it.first<=hit.distance }
        if(card!=null) cardAim=true else if(layerAim==null) layerAim=hit?.id
        if(down && !was && !pinched) {
          pinched=true
          when(card?.second) { "Code" -> layerInteraction.code(::openCodeFor);"Ask" -> layerInteraction.ask(::askLayer);"Card" -> Unit;else -> {
            layerInteraction.pinch(hit?.id)
            hit?.id?.let { selection.select(SiteSelection.Pick(it.project,it.module),"site") }
          } }
        }
      }
    }
    layerInteraction.aim(layerAim,cardAim,SystemClock.uptimeMillis())
    layerDraw.focus(layerInteraction.hover,layerInteraction.card)
    talk.aim(null)
  }
  /** Local head translation, computed in scalars; no per-label/per-frame pose or collection allocation. */
  private fun faceLabels(scene: Scene,r: Entity) {
    val head=scene.getViewerPose().t;val pose=r.getComponent<Transform>().transform
    val q=pose.q;val x=(head.x-pose.t.x).toDouble();val y=(head.y-pose.t.y).toDouble();val z=(head.z-pose.t.z).toDouble()
    val tx=2*(-q.y*z+q.z*y);val ty=2*(-q.z*x+q.x*z);val tz=2*(-q.x*y+q.y*x)
    val hx=(x+q.w*tx-q.y*tz+q.z*ty)/placement.scale
    val hz=(z+q.w*tz-q.x*ty+q.y*tx)/placement.scale
    var i=0
    while(i<facing.size) {
      val f=facing[i++];f.facing.face(hx,hz)
      val qLocal=f.transform.transform.q;val target=f.facing.pose.q
      qLocal.w=target.w;qLocal.y=target.y
      f.entity.setComponent(f.transform)
    }
  }
  private fun local(rootPose: Pose,origin: Vector3,direction: Vector3): Pair<DoubleArray,DoubleArray> {
    val inv=rootPose.inverse();val k=1.0/placement.scale
    val o=inv*origin;val d=inv.q*direction
    return SiteMesh.native(o.x*k,o.y*k,o.z*k) to SiteMesh.native(d.x.toDouble(),d.y.toDouble(),d.z.toDouble())
  }
  /** Placement scale changes keep the same ids and the physical hit-target/text floors. ECS thread. */
  private fun refreshLayers() {
    val r=root ?: return;val l=layout ?: return;val st=shown ?: return
    layerDraw.update(r,SiteLayers.boxes(l,st,placement.scale,layerInfo),l,st,placement.scale)
    if(showing) layerDraw.focus(layerInteraction.hover,layerInteraction.card) else layerDraw.hide()
  }
  /** Debug: same layer boxes and card path as a hand, from just outside the front face. */
  fun debugPick(project: String,part: String) {
    val l=layerDraw.layers.firstOrNull { it.id==SiteLayers.Id(project,part) } ?: return
    val hit=SiteLayers.pick(layerDraw.layers,doubleArrayOf(l.box.x,l.box.y,l.box.z1+.001),doubleArrayOf(0.0,0.0,-1.0)) ?: return
    layerInteraction.pinch(hit);selection.select(SiteSelection.Pick(hit.project,hit.module),"site")
  }

  // Preserve each child's own visibility (including inactive pieces) across the switch.
  private val hiddenVisibility=SiteVisibilityRestore<Entity>()
  private fun visibility(e: Entity,on: Boolean) {
    sceneObjects()?.getSceneObject(e)?.getNow(null)?.setIsVisible(on)
    e.setComponent(Visible(on))
  }
  private fun queueVisibility(e: Entity,on: Boolean) {
    layerDraw.visibility(e,e.tryGetComponent<Visible>()?.isVisible ?: true,on) { visibility(e,it) }
  }
  /** UI thread: reuse the existing entities and replay/feed state, without rebuilding geometry. */
  fun resume(why: String): Boolean {
    if(play==null || placement.pose==null) return false
    showing=true;placement.open(placement.pose!!,why)
    enqueue {
      layerDraw.resume()
      hiddenVisibility.resume(::queueVisibility)
    }
    ui.removeCallbacks(pump);ui.postDelayed(pump,TICK_MS)
    Log.i(TAG,"site event=shown_again why=$why")
    return true
  }
  /** Hide every entity, including the world-space grounding; retain entity identity and draw caches. */
  fun hide(why: String="close") {
    layerInteraction.close()
    showing=false;drawn=false;ui.removeCallbacks(pump);placement.close(why)
    enqueue {
      hiddenVisibility.hide(listOfNotNull(root,models.active,models.pending,grounding)+labels.values+pieces.values,
        { e -> e.tryGetComponent<Visible>()?.isVisible ?: true },::queueVisibility)
      layerDraw.hide()
      observePose?.invoke(com.overhand.app.features.PoseLog.Moment.SITE_CLOSE)
    }
    talk.aim(null)
    Log.i(TAG,"site event=hidden why=$why")
  }
  fun close() {
    hide("destroy")
    stopFeeds();draw.forget()
    enqueue { labels.values.forEach { it.destroy() };labels.clear();facing.clear();hiddenVisibility.clear();pieces.values.forEach { it.destroy() };pieces.clear();activity.cacheDir.listFiles { f -> f.name.startsWith("site_piece_") }?.forEach { it.delete() }
      layerDraw.close();grounding?.destroy();grounding=null;groundingFile?.delete();groundingFile=null;models.clear();root?.destroy();root=null }
  }

  companion object {
    const val TICK_MS=1_000L
    const val TAG="OverhandSite"
    /** #1202 `-PoverhandSiteOnce=true`: the site's pieces are loaded once and only shown or hidden. Read only here. */
    val ONCE=com.overhand.app.BuildConfig.SITE_ONCE
  }
}

/**
 * The crane a ray points at (table metres; directions keep their angles, the put-on is rotation plus uniform scale).
 * #1035: the mast box (+-2 cm worn) missed 44% of his replayed pointing, so the pick is the crane whose mast comes
 * angularly nearest the ray, within [TOL_DEG]. Pure, so the pick is tested on the JVM.
 */
object SiteHit {
  const val TOL_DEG=4.0
  fun crane(layout: SiteLayout,state: SiteState,o: DoubleArray,d: DoubleArray,tol: Double=TOL_DEG): SiteAgent? =
    SiteMesh.cranes(layout,state).map { it.agent to angle(it,o,d) }.filter { it.second<=tol }.minByOrNull { it.second }?.first
  /** Smallest angle (degrees) between the ray and any point of the crane's mast, sampled base to top. */
  fun angle(c: SiteMesh.Crane,o: DoubleArray,d: DoubleArray): Double {
    val dl=kotlin.math.sqrt(d[0]*d[0]+d[1]*d[1]+d[2]*d[2]);var best=180.0
    for(i in 0..16) { val y=(SiteMesh.CRANE_H+0.05)*i/16
      val v=doubleArrayOf(c.baseX-o[0],y-o[1],c.baseZ-o[2]);val vl=kotlin.math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2])
      if(vl<1e-9) continue
      val cos=((v[0]*d[0]+v[1]*d[1]+v[2]*d[2])/(vl*dl)).coerceIn(-1.0,1.0)
      best=minOf(best,Math.toDegrees(kotlin.math.acos(cos))) }
    return best
  }
}
