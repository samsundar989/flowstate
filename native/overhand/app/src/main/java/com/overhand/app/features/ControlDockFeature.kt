@file:OptIn(com.meta.spatial.core.SpatialSDKExperimentalAPI::class)
package com.overhand.app.features

import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.Gravity
import android.view.View
import com.meta.spatial.core.*
import com.meta.spatial.toolkit.*
import com.overhand.app.R
import com.overhand.app.windows.Theme
import kotlin.math.atan2

/** A separate world window: deliberately absent from WindowLayout.specs/entities and stations.
 * The production state machine is also the JVM replay seam. Head motion and resume are observations,
 * never placement requests. Only put-on, an explicit system recenter, and the grab owner may write. */
class ControlDock(private val write: (SettingsPanel.Place,String) -> Unit) {
  var place: SettingsPanel.Place?=null; private set
  /** Fix G (q4 wear: a saved place behind his left shoulder was restored): a saved place is kept only while in view. */
  fun putOn(head: V3,yaw: Double,saved: SettingsPanel.Place?,oldSaved: Boolean=false) {
    val restore=saved?.takeIf { validSaved(head,yaw,it) }
    set(restore ?: default(head,yaw),if(restore!=null) "saved" else if(saved!=null) "saved_out_of_view" else if(oldSaved) "ignored_old" else "default")
  }
  fun systemRecenter(head: V3,yaw: Double,saved: SettingsPanel.Place?=null) {
    val restore=saved?.takeIf { validSaved(head,yaw,it) }
    set(restore ?: default(head,yaw),if(restore!=null) "saved" else if(saved!=null) "saved_out_of_view" else "system_recenter")
  }
  /** While held there is no comfort correction. End-of-grab is the only clamp. */
  fun grab(p: SettingsPanel.Place)=set(p,"grab")
  fun grabEnd(head: V3,yaw: Double,p: SettingsPanel.Place) {
    val c=Comfort.clamp(head,p.centre,yaw)
    set(if(c==p.centre) p else SettingsPanel.Place(c,Comfort.facingYawDeg(head,c)),"grab_end")
  }
  private fun set(p: SettingsPanel.Place,why: String) { place=p;write(p,why) }
  companion object {
    // look_nov17 s4: about 45 degrees left, within a 90 degree view; Dock.dc.html: 136 dp capsule.
    const val DISTANCE_M=1.2
    const val BEARING_DEG=-40.0
    const val ELEVATION_DEG=-15.0
    const val WIDTH_M=0.136f
    const val HEIGHT_M=0.66f
    const val DP=136f
    const val HEIGHT_DP=660f
    const val OLD_PREF="control-dock/world-v1"
    const val PREF="control-dock/world-v2"
    /** Ownership rule used by WindowsFeature's unknown-panel visibility loop. */
    fun windowsOwnsPanel(id: Int,known: Set<Int>,excludeDock: Boolean=true)=
      (!excludeDock || id!=ControlDockFeature.PANEL) && id !in known
    /** #1341: windows and the project labels stay drawn while a question card is up (his nov17a card was up all
     *  session and hid everything); the graph still covers them, and the back face is culled. */
    fun panelVisible(graphOpen: Boolean,frontFacing: Boolean)=!graphOpen && frontFacing
    fun default(head: V3,yaw: Double): SettingsPanel.Place {
      val c=head+SettingsPanel.offset(BEARING_DEG+yaw,ELEVATION_DEG,DISTANCE_M)
      return SettingsPanel.Place(c,Comfort.facingYawDeg(head,c))
    }
    /** Fix G: from the head now, the saved centre is within [VIEW_DEG] of facing, [MIN_M]..[MAX_M] away, between
     *  [BELOW_M] below and [ABOVE_M] above the eyes, and its face turns to the head within [VIEW_DEG]. */
    const val VIEW_DEG=60.0
    const val MIN_M=0.5
    const val MAX_M=1.6
    const val BELOW_M=0.5
    const val ABOVE_M=0.3
    private fun wrap(a: Double): Double { var b=a%360;if(b>180) b-=360;if(b< -180) b+=360;return b }
    fun validSaved(head: V3,yaw: Double,p: SettingsPanel.Place): Boolean {
      if(!listOf(p.centre.x,p.centre.y,p.centre.z,p.yawDeg).all { it.isFinite() }) return false
      val d=p.centre-head
      return d.length() in MIN_M..MAX_M && d.y in -BELOW_M..ABOVE_M && kotlin.math.hypot(d.x,d.z)>1e-6 &&
        kotlin.math.abs(Comfort.bearing(d,yaw))<=VIEW_DEG && kotlin.math.abs(wrap(p.yawDeg-Comfort.facingYawDeg(head,p.centre)))<=VIEW_DEG
    }
    /** Fix G: the SDK bar's hit box under the dock, at least [BAR_TARGET_DP] tall, its top below the dock's bottom edge
     *  so the dock's own panel never covers it. Offset from the dock centre, metres up. */
    const val BAR_TARGET_DP=64f
    val BAR_TARGET_H_M get()=(BAR_TARGET_DP*WIDTH_M/DP).toDouble()
    val BAR_OFFSET_M get()=-HEIGHT_M/2.0-0.004-BAR_TARGET_H_M/2
    fun items()=Dock.ITEMS+Dock.Item("settings","Settings")
    fun build(root: LinearLayout,pick: (String)->Unit) {
      root.orientation=LinearLayout.VERTICAL
      root.removeAllViews()
      val d=root.resources.displayMetrics.density
      fun dp(n: Int)=(n*d).toInt()
      root.setPadding(dp(0),dp(30),dp(0),dp(30))
      root.background=InsetDrawable(GradientDrawable().apply {
        setColor(Color.argb(166,12,18,26));com.overhand.app.windows.Theme.trackFill(this);cornerRadius=dp(68).toFloat();setStroke(dp(2).coerceAtLeast(1),0x26ffffff)
      },0)
      items().forEach { item ->
        val small=item.view=="center"
        root.addView(LinearLayout(root.context).apply {
          orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;tag="control-dock-${item.view}"
          val icon=DockIcon(root.context,item.view).apply {
            contentDescription=item.label;tag="dock-icon-${item.view}"
            setOnClickListener { pick(item.view) }
          }
          addView(icon,LinearLayout.LayoutParams(dp(if(small) 48 else 72),dp(if(small) 48 else 72)).apply { bottomMargin=dp(6) })
          if(!small) addView(TextView(root.context).apply { text=item.label;gravity=Gravity.CENTER;textSize=17f;setTextColor(0xb3ffffff.toInt());tag="dock-label-${item.view}" })
          setOnClickListener { pick(item.view) }
        },LinearLayout.LayoutParams(-1,0,if(small) 0.7f else 1f))
      }
    }
    fun paint(root: LinearLayout,open: Set<String>) {
      items().forEach { item ->
        val active=item.view in open
        root.findViewWithTag<View>("control-dock-${item.view}")?.isSelected=active
        root.findViewWithTag<View>("dock-icon-${item.view}")?.apply { isSelected=active;invalidate() }
        root.findViewWithTag<TextView>("dock-label-${item.view}")?.setTextColor(if(active) 0xff5ce1e6.toInt() else 0xb3ffffff.toInt())
      }
    }
    fun encode(p: SettingsPanel.Place)=listOf(p.centre.x,p.centre.y,p.centre.z,p.yawDeg).joinToString(",")
    fun decode(raw: String?): SettingsPanel.Place?=runCatching {
      val v=raw!!.split(',').map { it.toDouble() };require(v.size==4)
      SettingsPanel.Place(V3(v[0],v[1],v[2]),v[3])
    }.getOrNull()
  }
}

class ControlDockFeature(private val s: Shell): Feature {
  override val name="control_dock"
  private var entity: Entity?=null
  private var registered=false
  private var root: LinearLayout?=null
  private var shown: Set<String>?=null
  private var putOnPending=true
  private var recenterPending=false
  private val settle=HeadSettle()
  private val dock=ControlDock { p,why ->
    entity?.setComponent(Transform(p.pose()))
    // Continuous hand-owned writes are explicitly marked; all ungrabbed placement reasons are enumerated.
    Log.i(Shell.TAG,"dock event=pose_set why=$why grabbed=${why=="grab"} pose=${ControlDock.encode(p)}")
    if(why in setOf("default","saved","ignored_old","saved_out_of_view")) Log.i(Shell.TAG,"dock event=place why=$why pose=${ControlDock.encode(p)}")
  }
  private fun V3.f()=Vector3(x.toFloat(),y.toFloat(),z.toFloat())
  private fun Vector3.v()=V3(x.toDouble(),y.toDouble(),z.toDouble())
  private fun SettingsPanel.Place.pose()=Pose(centre.f(),Quaternion.fromEuler(0f,yawDeg.toFloat(),0f))
  private fun head(): Pair<V3,Double> { val h=s.head();val f=h.q*Vector3(0f,0f,1f)
    return h.t.v() to Math.toDegrees(atan2(f.x,f.z).toDouble()) }
  /** Fix G: GrabFeature's grab target while shown. The dock has no grab path of its own: the SDK bar is the only one. */
  val grabEntity: Entity? get()=entity?.takeIf { dock.place!=null && !putOnPending && !recenterPending && it.tryGetComponent<Visible>()?.isVisible==true }
  /** The SDK bar carries the dock: [c] is the carried centre, the dock turns to face the head. */
  fun barMove(c: V3) { val (h,_)=head();val next=SettingsPanel.Place(c,Comfort.facingYawDeg(h,c));if(next!=dock.place) dock.grab(next) }
  /** Native release: the end-of-grab clamp, then the place is saved (put-on keeps it only while in view). */
  fun barRelease() {
    val p=dock.place ?: return;val (h,yaw)=head()
    dock.grabEnd(h,yaw,p)
    s.prefs.edit().putString(ControlDock.PREF,ControlDock.encode(dock.place!!)).apply()
  }

  override fun onSceneReady() {
    s.activity.registerPanel(LayoutXMLPanelRegistration(PANEL,layoutIdCreator={ R.layout.control_dock },
      settingsCreator={ Theme.panel(UIPanelSettings(shape=QuadShapeOptions(width=ControlDock.WIDTH_M,height=ControlDock.HEIGHT_M),
        display=DpDisplayOptions(width=ControlDock.DP,height=ControlDock.HEIGHT_DP))) },
      panelSetupWithRootView={ view,_,_ ->
        root=view.findViewById<LinearLayout>(R.id.control_dock_root).also { ControlDock.build(it,::pick) }
        shown=null
        s.interaction.bindYard(view.findViewWithTag("dock-label-build"))
      }))
    registered=true
  }
  /** Activity callbacks enqueue intent only; the next settled frame samples the new head/forward. */
  fun systemRecenter() { s.commands.add { recenterPending=true;settle.reset(System.currentTimeMillis()) } }
  fun mounted() { s.commands.add { putOnPending=true;settle.reset(System.currentTimeMillis()) } }

  override fun onFrame(now: Long) {
    if(!registered || !s.recenter.placedOnce) return
    // Yard's press shows the 3D site (lane B), so its cyan follows the site.
    val open=Dock.ITEMS.filter { if(it.view=="build") s.interaction.siteShowing else s.windows.isWindowOpen(it.view) }.map { it.view }.toSet()+
      (if(s.settings.open) setOf("settings") else emptySet())
    if(open!=shown) { shown=open;s.ui.post { root?.let { ControlDock.paint(it,open) } } }
    if(putOnPending || recenterPending) {
      val h=s.head();settle.sample(now,h.t.v(),(h.q*Vector3(0f,0f,1f)).v())
      if(!settle.settled) return
    }
    if(entity==null) {
      // Entity creation has no initial Transform: every dock pose goes through the logged writer above.
      // Fix G: no strip of its own (it looked like a handle and grabbed nothing); GrabFeature draws the dock's SDK bar.
      entity=Entity.create(listOf(Panel(PANEL),Visible(true),PanelDimensions(Vector2(ControlDock.WIDTH_M,ControlDock.HEIGHT_M)))) // create-once: guarded by entity==null
    }
    val (h,yaw)=head()
    if(recenterPending) { dock.systemRecenter(h,yaw,ControlDock.decode(s.prefs.getString(ControlDock.PREF,null)));recenterPending=false;putOnPending=false }
    else if(putOnPending) { dock.putOn(h,yaw,ControlDock.decode(s.prefs.getString(ControlDock.PREF,null)),s.prefs.contains(ControlDock.OLD_PREF));s.prefs.edit().remove(ControlDock.OLD_PREF).putString(ControlDock.PREF,ControlDock.encode(dock.place!!)).apply();putOnPending=false;s.poseLog.capture(PoseLog.Moment.PUT_ON) }
  }

  /** Same public entry points as VoiceFeature's UiCommands.Ui. No palm dispatch or palm state. */
  private fun pick(view: String) { Log.i(Shell.TAG,"dock event=press view=$view from=control_dock");when(view) {
    "center" -> center()
    "settings" -> s.settings.open("control_dock")
    "build" -> s.interaction.pressYard()
    "fleet","blueprint","code" -> s.windows.open(view,"control_dock")
  } }
  /** q4 wear 17:01:11 (`recenter event=center_skipped why=control_dock moved_by_hand=true`): "control_dock" was not
   *  a wearer-asked reason, so after any bar grab, and on an already placed stage, the button did nothing. The dock
   *  is the palm menu's successor, so its press asks with that explicit reason; the dock goes to its default. */
  private fun center() {
    s.recenter.request(CENTER_WHY)
    s.commands.add { if(dock.place!=null && !putOnPending) { val (h,yaw)=head();dock.systemRecenter(h,yaw)
      s.prefs.edit().putString(ControlDock.PREF,ControlDock.encode(dock.place!!)).apply() } }
  }
  companion object { const val PANEL=0x1239_0001
    const val CENTER_WHY="palm" }
}

/** Line icons stay sharp at the panel's native resolution; the circle is the entire button. */
private class DockIcon(context: android.content.Context,private val key: String): View(context) {
  private val pen=Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND }
  override fun onDraw(canvas: Canvas) {
    val c=canvas;c.save();c.scale(width/72f,height/72f)
    pen.style=Paint.Style.FILL;pen.color=if(isPressed) 0x475ce1e6 else if(isSelected) 0x295ce1e6 else Color.TRANSPARENT;c.drawCircle(36f,36f,35f,pen)
    pen.style=Paint.Style.STROKE;pen.strokeWidth=2f;pen.color=if(isSelected) 0xff5ce1e6.toInt() else 0x38ffffff;c.drawCircle(36f,36f,35f,pen)
    pen.strokeWidth=2f;pen.color=if(isSelected) 0xff5ce1e6.toInt() else 0xb3ffffff.toInt()
    fun line(x: Float,y: Float,u: Float,v: Float)=c.drawLine(x,y,u,v,pen)
    when(key) {
      "fleet" -> for(x in listOf(22f,40f)) for(y in listOf(22f,40f)) c.drawRoundRect(x,y,x+10,y+10,2f,2f,pen)
      "blueprint" -> { c.drawRect(22f,22f,50f,50f,pen);line(22f,36f,50f,36f);line(36f,22f,36f,50f) }
      "code" -> { line(28f,25f,18f,36f);line(18f,36f,28f,47f);line(44f,25f,54f,36f);line(54f,36f,44f,47f);line(39f,23f,33f,49f) }
      "build" -> { line(28f,22f,28f,51f);line(20f,25f,52f,25f);line(28f,17f,48f,25f);line(48f,25f,48f,40f);line(21f,51f,35f,51f);line(43f,40f,48f,44f) }
      "settings" -> { c.drawCircle(36f,36f,11f,pen);c.drawCircle(36f,36f,4f,pen);repeat(8) { c.save();c.rotate(it*45f,36f,36f);line(36f,19f,36f,24f);c.restore() } }
      "center" -> { c.drawCircle(36f,36f,10f,pen);line(36f,18f,36f,28f);line(36f,44f,36f,54f);line(18f,36f,28f,36f);line(44f,36f,54f,36f) }
    }
    c.restore()
  }
}
