package com.overhand.app.features

import android.graphics.Color
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.overhand.app.FirstMinutes
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal fun targetPx(root: View)=(com.overhand.app.windows.PolishRules.SETTINGS_TARGET_DP*root.resources.displayMetrics.density).roundToInt()

/** The shared Settings button style. */
internal fun settingsButton(root: LinearLayout, text: String, action: () -> Unit) {
  root.addView(Button(root.context).apply { this.text=text;isAllCaps=false; textSize=21f
    gravity=android.view.Gravity.CENTER;setTextColor(0xfff2f6fa.toInt())
    background=settingsEdge(root,false);setOnClickListener { action() }
  },LinearLayout.LayoutParams(-1,(64*root.resources.displayMetrics.density).roundToInt()))
}

// Dock.dc.html: 2 dp edge, pill controls; no filled Settings action.
internal fun settingsEdge(root: View,selected: Boolean)=android.graphics.drawable.GradientDrawable().apply {
  setColor(Color.TRANSPARENT);cornerRadius=999f
  setStroke((2*root.resources.displayMetrics.density).roundToInt(),if(selected) 0xff5ce1e6.toInt() else 0x38ffffff)
}

// Short labels preserve the feature's canonical control name for accessibility and replay.
internal fun settingsLabel(text: CharSequence)=when(text.toString()) {
  "Steady frame rate (72 Hz)" -> "Frame rate"
  "Lag check (2 min)" -> "Lag"
  "Smooth build site" -> "Smooth site"
  "Pose trials (practice only)" -> "Pose trials"
  else -> text
}

/** Keeps the feature-owned checked listener and persisted value; only its presentation changes. */
internal class SettingsSwitch(private val source: android.widget.CompoundButton): android.widget.CompoundButton(source.context) {
  private val pen=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
  private var syncing=false
  init {
    text=settingsLabel(source.text);tag=source.tag;contentDescription=source.text
    textSize=21f;setTextColor(0xfff2f6fa.toInt());buttonDrawable=null;background=null
    gravity=android.view.Gravity.CENTER_VERTICAL;isEnabled=source.isEnabled;isChecked=source.isChecked
    setPadding(0,0,(84*resources.displayMetrics.density).roundToInt(),0)
    setOnCheckedChangeListener { _,on -> if(!syncing) { source.isChecked=on;invalidate() } }
    viewTreeObserver.addOnPreDrawListener {
      syncing=true;isChecked=source.isChecked;isEnabled=source.isEnabled;syncing=false;true
    }
  }
  override fun onDraw(c: android.graphics.Canvas) {
    super.onDraw(c)
    val d=resources.displayMetrics.density;val x=width-68*d;val y=(height-38*d)/2
    pen.style=android.graphics.Paint.Style.FILL;pen.color=if(isChecked) 0x405ce1e6 else Color.TRANSPARENT
    c.drawRoundRect(x,y,x+68*d,y+38*d,19*d,19*d,pen)
    pen.style=android.graphics.Paint.Style.STROKE;pen.strokeWidth=2*d
    pen.color=if(isChecked) 0xff5ce1e6.toInt() else 0x4dffffff;c.drawRoundRect(x,y,x+68*d,y+38*d,19*d,19*d,pen)
    pen.style=android.graphics.Paint.Style.FILL;pen.color=if(isChecked) 0xff5ce1e6.toInt() else 0xb3ffffff.toInt()
    c.drawCircle(x+(if(isChecked) 47 else 17)*d,y+19*d,12*d,pen)
  }
}

/**
 * Settings is closed at launch. It opens from the side dock or palm menu and closes from its own Close.
 * One press is one toggle: it acts on pinch close (#1034, [Dock.pressOnClose]; #997 acted on release), with a [GUARD_MS] guard. First open: [DISTANCE_M] from the head,
 * [BEARING_DEG] to the side, [ELEVATION_DEG] below eye level, facing him, world-locked. It is moved and resized through
 * the same grab path as every window, and is visible and grabbable whenever open (a decision card or the graph no
 * longer hides it). Reopening restores where he left it unless that spot is over [FAR_M] away or more than
 * [OFF_VIEW_DEG] off his view. No code puts a Settings surface nearer than [MIN_M] (Meta: indirect UI 0.8 to 3 m,
 * never nearer than 0.8 m). Automatic placement (resume/mount resettles, stations, comfort ease-back) never moves it.
 * Pure rules here, host-tested.
 */
object SettingsPanel {
  const val VIEW="settings"
  /** First-open pose, from the level eye: to the right of the gaze, below eye level, facing the wearer. */
  // t1234: under the main window's right half, 4 degrees or more clear of it and of Code (WindowLayoutSpreadTest).
  const val BEARING_DEG=30.0
  const val ELEVATION_DEG=-21.0
  const val DISTANCE_M=1.3
  /** Type and targets are sized (PolishRules, layout_lint) for the 0.6 m quad at the 1.12 m legibility slot
   *  (WindowLayout.slots). Opened farther away, the quad grows by the same ratio, so it subtends exactly that. */
  const val LEGIBLE_AT_M=1.12
  const val SCALE=DISTANCE_M/LEGIBLE_AT_M
  /** No Settings surface (panel or orb) is ever placed nearer than this to the head. */
  const val MIN_M=1.0
  /** A kept pose farther than this, or more than [OFF_VIEW_DEG] off the view, is placed afresh on reopen. */
  const val FAR_M=3.0
  const val OFF_VIEW_DEG=100.0
  /** One pinch is one toggle: a second release within this window is ignored. */
  const val GUARD_MS=400L
  const val MAIN_VIEW="chat"
  /** Re-place reasons the wearer asked for. Resume, mount and first-minute settles are automatic and skip Settings. */
  val EXPLICIT=setOf("palm","voice","debug","system_recenter")
  fun followsRecenter(why: String)=why in EXPLICIT

  /** Offset from a level eye pose (+z ahead, positive bearing toward +x), as WindowLayout.slots compute it. */
  fun offset(bearingDeg: Double,elevationDeg: Double,distanceM: Double): V3 {
    val b=Math.toRadians(bearingDeg);val e=Math.toRadians(elevationDeg);val hz=distanceM*cos(e)
    return V3(hz*sin(b),distanceM*sin(e),hz*cos(b))
  }
  fun panelOffset()=offset(BEARING_DEG,ELEVATION_DEG,DISTANCE_M)

  /** A world pose: centre and yaw (degrees, 0 = facing +z, the same convention as PanelGrab). */
  data class Place(val centre: V3,val yawDeg: Double)

  /** Degrees between the head's view direction [forward] (unit, 3D) and the direction to [p]. */
  fun offAxisDeg(head: V3,forward: V3,p: V3): Double {
    val d=p-head;val n=d.length().coerceAtLeast(1e-9)
    return Math.toDegrees(acos(((d.x*forward.x+d.y*forward.y+d.z*forward.z)/n).coerceIn(-1.0,1.0)))
  }
  /** Facing the head; pushed out along the head-to-centre line to at least [MIN_M]. Never pulls anything nearer. */
  fun atLeastMin(head: V3,centre: V3): Place {
    val d=centre-head;val n=d.length()
    val c=if(n>=MIN_M) centre else if(n<1e-6) head+V3(0.0,0.0,MIN_M) else head+V3(d.x*MIN_M/n,d.y*MIN_M/n,d.z*MIN_M/n)
    return Place(c,Comfort.facingYawDeg(head,c))
  }
  /** First open from the head position [head] and its level heading [yawDeg]. */
  fun firstOpen(head: V3,yawDeg: Double): Place {
    val o=offset(BEARING_DEG+yawDeg,ELEVATION_DEG,DISTANCE_M)
    return atLeastMin(head,head+o)
  }
  /** Reopen keeps [kept] unless it is lost (too far, or too far off the view); either way never nearer than [MIN_M]. */
  fun keeps(head: V3,forward: V3,kept: V3)=(kept-head).length()<=FAR_M && offAxisDeg(head,forward,kept)<=OFF_VIEW_DEG
  fun reopen(head: V3,forward: V3,yawDeg: Double,kept: Place?): Place =
    if(kept!=null && keeps(head,forward,kept.centre)) { val p=atLeastMin(head,kept.centre);if(p.centre==kept.centre) kept else p }
    else firstOpen(head,yawDeg)
  /** Open/closed, whether it was placed this session, and the last accepted press (for the one-press guard). */
  data class State(val open: Boolean=false,val placed: Boolean=false,val lastPressMs: Long=Long.MIN_VALUE/2) {
    /** A press, acted on at pinch release: toggles, unless it comes within [GUARD_MS] of the last accepted one (null). */
    fun press(nowMs: Long): State?=if(nowMs-lastPressMs<GUARD_MS) null else copy(open=!open,lastPressMs=nowMs)
    fun openIt()=copy(open=true)
    fun close()=copy(open=false)
  }
  /** Settings shows (and is a grab target) exactly while open; every other window follows its own rules. */
  fun visible(view: String,open: Boolean,otherwise: Boolean)=if(view==VIEW) open else otherwise
  /** The worn-check line: `settings event=open|close why=.. dist_m=.. off_axis_deg=..`. */
  fun logLine(event: String,why: String,head: V3,forward: V3,p: V3)=
    "settings event=$event why=$why dist_m=%.2f off_axis_deg=%.0f".format(java.util.Locale.ROOT,(p-head).length(),offAxisDeg(head,forward,p))
}

/**
 * #997 replay model of a press: pinch with PanelGrab's hysteresis (on below [PanelGrab.PINCH_ON_M], off above
 * [PanelGrab.PINCH_OFF_M]); the press fires on release. The panel click the orb receives is a release too.
 */
class PinchRelease {
  private var down=false
  /** One frame's thumb-index gap (null: hand not tracked). True on the frame the pinch is released. */
  fun frame(gapM: Double?): Boolean {
    val was=down
    down=if(gapM==null) false else if(was) gapM<PanelGrab.PINCH_OFF_M else gapM<PanelGrab.PINCH_ON_M
    return was && !down && gapM!=null
  }
}

/**
 * Settings registry. Each feature adds its own rows via [Feature.settingsRows]; rows are built onto their [SettingsTab]
 * in ascending order. Current order: 10 audio, 20 onboarding, 30 about text, 40 session/server,
 * 50 about + reset layout, 60 passthrough, 70 decisions + quit, 90 first connection check.
 */
class SettingsFeature(private val s: Shell) : Feature {
  override val name = "settings"
  /** ECS thread state (t945). Closed at launch. */
  var state=SettingsPanel.State(); private set
  val open get()=state.open

  private fun Vector3.v()=V3(x.toDouble(),y.toDouble(),z.toDouble())
  private fun V3.f()=Vector3(x.toFloat(),y.toFloat(),z.toFloat())
  private fun pose(p: SettingsPanel.Place)=Pose(p.centre.f(),Quaternion.fromEuler(0f,p.yawDeg.toFloat(),0f))
  private fun place(p: Pose)=SettingsPanel.Place(p.t.v(),(p.q*Vector3(0f,0f,1f)).let { Math.toDegrees(kotlin.math.atan2(it.x,it.z).toDouble()) })
  private fun headNow(): Triple<V3,V3,Double> { val h=s.head();val f=h.q*Vector3(0f,0f,1f)
    return Triple(h.t.v(),f.v(),Math.toDegrees(kotlin.math.atan2(f.x,f.z).toDouble())) }

  /** Any thread: a press of the orb or the palm menu's Settings item, delivered on pinch release. */
  fun toggle(why: String="orb") { s.commands.add { press(why,android.os.SystemClock.elapsedRealtime()) } }
  /** ECS thread. One press is one toggle; a second release within [SettingsPanel.GUARD_MS] is dropped. */
  private fun press(why: String,now: Long) {
    val next=state.press(now) ?: run { android.util.Log.i(Shell.TAG,"settings event=press_ignored why=$why guard_ms=${SettingsPanel.GUARD_MS}");return }
    state=next.copy(open=state.open)
    if(state.open) close(why) else openNow(why)
  }
  /** t947 debug builds: `--ez settings_press true` runs the orb's own click handler ([toggle]). */
  override fun onDebugIntent(intent: android.content.Intent) {
    if(com.overhand.app.BuildConfig.DEBUG && intent.getBooleanExtra("settings_press",false)) toggle("debug_press")
  }
  /** Any thread: open only (no toggle). */
  fun open(why: String) { s.commands.add { if(!state.open) openNow(why) } }
  /** Any thread: the panel's Close control. */
  fun requestClose() { s.commands.add { if(state.open) close("close_button") } }
  private fun entity()=s.layout.specs.first { it.view==SettingsPanel.VIEW }.let { s.layout.entities[it.id] }
  private fun close(why: String) {
    state=state.close()
    val (h,f,_)=headNow()
    val p=entity()?.getComponent<com.meta.spatial.toolkit.Transform>()?.transform?.t?.v() ?: h
    android.util.Log.i(Shell.TAG,SettingsPanel.logLine("close",why,h,f,p))
  }
  /** ECS thread. First open places it; reopening restores where he left it ([SettingsPanel.reopen]). */
  private fun openNow(why: String) {
    val l=s.layout
    val sp=l.specs.first { it.view==SettingsPanel.VIEW }
    val created=sp.id !in l.entities
    if(created) l.createWindow(sp)
    val e=l.entities[sp.id] ?: return
    val (h,f,yaw)=headNow()
    val kept=if(state.placed && !created) place(e.getComponent<com.meta.spatial.toolkit.Transform>().transform) else null
    val p=SettingsPanel.reopen(h,f,yaw,kept)
    if(p!=kept) {
      e.setComponent(com.meta.spatial.toolkit.Transform(pose(p)))
      if(kept==null || !SettingsPanel.keeps(h,f,kept.centre)) e.sizePanel(l.defaultSize(sp))
      l.last[sp.id]=l.encode(e)
    }
    state=state.openIt().copy(placed=true)
    android.util.Log.i(Shell.TAG,SettingsPanel.logLine("open",why,h,f,p.centre))
  }

  /** ECS thread: a released Settings panel nearer than 1.0 m goes back out to 1.0 m; the side dock keeps its own pose. */
  override fun onFrame(now: Long) {
    if(!s.recenter.placedOnce) return
    if(!state.open) return
    val e=entity() ?: return
    if(s.grab.holding(SettingsPanel.VIEW) || e.tryGetComponent<com.meta.spatial.isdk.IsdkGrabbable>()?.grabState==com.meta.spatial.isdk.IsdkGrabState.Grabbed) return
    val (h,_,_)=headNow()
    val cur=e.getComponent<com.meta.spatial.toolkit.Transform>().transform
    if((cur.t.v()-h).length()>=SettingsPanel.MIN_M) return
    e.setComponent(com.meta.spatial.toolkit.Transform(pose(SettingsPanel.atLeastMin(h,cur.t.v()))))
    android.util.Log.i(Shell.TAG,"settings event=push_out dist_m=%.2f".format(java.util.Locale.ROOT,SettingsPanel.MIN_M))
  }

  /** UI thread: the page Settings shows (#1191). Kept while the app runs, so reopening shows the last page. */
  var tab=SettingsTab.shown().first(); private set
  private val pages=mutableMapOf<SettingsTab,LinearLayout>()
  private val tabButtons=mutableMapOf<SettingsTab,View>()
  private val pending=mutableListOf<Pair<Set<SettingsTab>,()->Unit>>()
  /** UI thread: show [t]'s page (the tab buttons' action). */
  fun select(t: SettingsTab) {
    if(t !in pages) return
    pending.filter { t in it.first }.forEach { pending.remove(it);it.second() }
    tab=t
    pages.forEach { (k,p) -> p.visibility=if(k==t) View.VISIBLE else View.GONE }
    tabButtons.forEach { (k,b) -> b.isSelected=k==t;b.background=settingsEdge(b,k==t) }
    android.util.Log.i(Shell.TAG,"settings event=tab name=${t.label}")
  }

  /**
   * #1191: no scrolling. Under the title one row of tab buttons with Close at its end, under it one page; Quit stays
   * the footer. Every control is on exactly one page, and a page holds what fits whole (layout lint scroll_slit and
   * control_clipped, one lint window per page). Short controls go three to a line; text stays under its control.
   */
  fun build(container: LinearLayout) {
    val ctx=container.context
    container.removeAllViews()
    val d=container.resources.displayMetrics.density
    container.background=android.graphics.drawable.GradientDrawable().apply {
      setColor(0xa60c121a.toInt());cornerRadius=32*d;setStroke((2*d).roundToInt(),0x26ffffff)
    }
    container.setPadding((30*d).roundToInt(),(26*d).roundToInt(),(30*d).roundToInt(),(26*d).roundToInt())
    val title=LinearLayout(ctx).apply { gravity=android.view.Gravity.CENTER_VERTICAL }
    title.addView(android.widget.TextView(ctx).apply { text="Settings";textSize=30f;setTextColor(0xfff2f6fa.toInt()) },LinearLayout.LayoutParams(0,-2,1f))
    title.addView(object: View(ctx) {
      val pen=android.graphics.Paint(3).apply { color=0xfff2f6fa.toInt();strokeWidth=2*d }
      override fun onDraw(c: android.graphics.Canvas) { c.drawLine(22*d,22*d,38*d,38*d,pen);c.drawLine(38*d,22*d,22*d,38*d,pen) }
    }.apply { contentDescription="Close Settings";tag="settings-close";background=settingsEdge(this,false);setOnClickListener { requestClose() } },LinearLayout.LayoutParams((60*d).roundToInt(),(60*d).roundToInt()))
    container.addView(title)
    val tabs=SettingsTab.shown()
    pages.clear();tabButtons.clear();pending.clear()
    val bar=LinearLayout(ctx).apply { tag="settings-tabs" }
    container.addView(bar,LinearLayout.LayoutParams(-1,(64*d).roundToInt()))
    tabs.forEach { t ->
      val v=android.widget.TextView(ctx).apply { text=t.label;textSize=19f;gravity=android.view.Gravity.CENTER;setTextColor(0xfff2f6fa.toInt());tag="settings-tab-${t.key}";setOnClickListener { select(t) } }
      bar.addView(v,LinearLayout.LayoutParams(0,(50*d).roundToInt(),1f));tabButtons[t]=v
      val page=LinearLayout(ctx).apply { orientation=LinearLayout.VERTICAL;tag="settings-page-${t.key}" }
      pages[t]=page;container.addView(page,LinearLayout.LayoutParams(-1,0,1f))
    }
    fun display(v: View): View = when(v) {
      is android.widget.CompoundButton -> SettingsSwitch(v)
      is Button -> android.widget.TextView(ctx).apply {
        text=settingsLabel(v.text);contentDescription=v.text;textSize=21f;tag=v.tag;visibility=v.visibility;gravity=android.view.Gravity.CENTER_VERTICAL
        minimumHeight=(64*d).roundToInt() // Nested feature buttons also keep the Settings row target.
        setTextColor(0xfff2f6fa.toInt());background=settingsEdge(this,false);isEnabled=v.isEnabled
        setOnClickListener { v.performClick();text=settingsLabel(v.text);isEnabled=v.isEnabled }
        viewTreeObserver.addOnPreDrawListener { text=settingsLabel(v.text);visibility=v.visibility;isEnabled=v.isEnabled;isSelected=v.isSelected;true }
      }
      is android.view.ViewGroup -> if(v.tag=="recording-chips") {
        fun leaves(g: View): List<Button> = if(g is Button) listOf(g) else if(g is android.view.ViewGroup) (0 until g.childCount).flatMap { leaves(g.getChildAt(it)) } else emptyList()
        LinearLayout(ctx).apply { orientation=LinearLayout.VERTICAL;tag=v.tag
          leaves(v).forEach { addView(display(it),LinearLayout.LayoutParams(-1,(64*d).roundToInt())) }
        }
      } else v.apply {
        val children=(0 until childCount).map { getChildAt(it) };removeAllViews()
        children.forEach { child -> addView(display(child),child.layoutParams) }
      }
      else -> v
    }
    val run=pages.getValue(SettingsTab.RUN)
    val recordings=LinearLayout(ctx).apply { orientation=LinearLayout.VERTICAL;visibility=View.GONE;tag="settings-recordings" }
    val debugging=LinearLayout(ctx).apply { orientation=LinearLayout.VERTICAL;visibility=View.GONE;tag="settings-debugging" }
    val ordinary=LinearLayout(ctx).apply { orientation=LinearLayout.VERTICAL;tag="settings-run-controls" }
    run.addView(ordinary);run.addView(recordings);run.addView(debugging)
    fun showRun(which: View) {
      listOf(ordinary,recordings,debugging).forEach { it.visibility=if(it===which) View.VISIBLE else View.GONE }
      android.util.Log.i(Shell.TAG,"settings event=page name=${which.tag}")
    }
    fun back(group: LinearLayout) {
      group.addView(object: View(ctx) {
        val pen=android.graphics.Paint(3).apply { color=0xfff2f6fa.toInt();strokeWidth=2*d }
        override fun onDraw(c: android.graphics.Canvas) { c.drawLine(40*d,20*d,24*d,32*d,pen);c.drawLine(24*d,32*d,40*d,44*d,pen) }
      }.apply { tag="settings-page-nav";contentDescription="Back";background=settingsEdge(this,false);setOnClickListener { showRun(ordinary) } },LinearLayout.LayoutParams((64*d).roundToInt(),(64*d).roundToInt()))
    }
    back(recordings);back(debugging)
    fun runButton(label: String,action: () -> Unit) {
      val staging=LinearLayout(ctx);settingsButton(staging,label,action)
      val button=staging.getChildAt(0);staging.removeAllViews()
      ordinary.addView(display(button).apply { tag="settings-page-nav" },LinearLayout.LayoutParams(-1,(64*d).roundToInt()))
    }
    runButton("Recording") { showRun(recordings) }
    if(com.overhand.app.BuildConfig.DEBUG) {
      runButton("Debug") { showRun(debugging) }
    }
    val order=mutableMapOf<View,Int>()
    fun insert(parent: LinearLayout,v: View,rank: Int,height: Int) {
      val index=(0 until parent.childCount).firstOrNull { (order[parent.getChildAt(it)] ?: Int.MIN_VALUE)>rank } ?: parent.childCount
      order[v]=rank;parent.addView(v,index,LinearLayout.LayoutParams(-1,height))
    }
    s.features.flatMap { it.settingsRows() }.sortedBy { if(it.first) Int.MIN_VALUE else it.order }.forEach { row ->
      if(row.tab==SettingsTab.DEBUG && !com.overhand.app.BuildConfig.DEBUG) return@forEach
      // These mixed rows use settingsTab to contribute to more than their initial page.
      val destinations=when(row.order) {
        12 -> setOf(SettingsTab.LINK,SettingsTab.VOICE)
        40 -> setOf(SettingsTab.LINK,SettingsTab.RUN)
        50 -> setOf(SettingsTab.ABOUT,SettingsTab.VIEW)
        57 -> setOf(SettingsTab.VIEW,SettingsTab.RUN)
        70 -> setOf(SettingsTab.LINK,SettingsTab.VIEW,SettingsTab.RUN,SettingsTab.ROOM,SettingsTab.ABOUT)
        90 -> setOf(SettingsTab.LINK,SettingsTab.RUN)
        else -> setOf(when(row.tab) { SettingsTab.LIVE -> SettingsTab.LINK;SettingsTab.DEBUG -> SettingsTab.RUN;else -> row.tab })
      }
      pending+=destinations to {
      val staging=LinearLayout(ctx).apply { orientation=LinearLayout.VERTICAL }
      row.build(staging,container)
      val marks=SettingsTabs.take(staging)
      val views=(0 until staging.childCount).map { staging.getChildAt(it) };staging.removeAllViews()
      views.forEachIndexed { i,v ->
        val t=marks.lastOrNull { it.first<=i }?.second ?: row.tab
        val page=pages[when(t) { SettingsTab.LIVE -> SettingsTab.LINK;SettingsTab.DEBUG -> SettingsTab.RUN;else -> t }] ?: return@forEachIndexed
        // Feature prose is instruction text; controls, fields and voice slot names remain intact.
        if(v is android.widget.TextView && v !is Button && v !is android.widget.EditText && !v.isClickable && v.tag!="session-connection-status") return@forEachIndexed
        val destination=when {
          t==SettingsTab.DEBUG -> debugging
          v.tag=="recording-chips" -> recordings
          t==SettingsTab.RUN -> ordinary
          else -> page
        }
        // Align field/status ink with this page's labels, retaining their existing vertical insets.
        if(v is android.widget.EditText || v.tag=="session-connection-status") v.setPadding(0,v.paddingTop,0,v.paddingBottom)
        insert(destination,display(v),if(row.first) Int.MIN_VALUE else row.order,if(v.tag=="recording-chips") -2 else (64*d).roundToInt())
      }
    // InteractionFeature contributes the exit footer to the container, outside its staging rows.
    val extra=(0 until container.childCount).map { container.getChildAt(it) }.filter { it!==title && it!==bar && it !in pages.values }
    extra.forEach { container.removeView(it);insert(pages.getValue(SettingsTab.ABOUT),display(it),Int.MAX_VALUE,(64*d).roundToInt()) }
      }
    }
    select(if(tab in pages) tab else tabs.first())
  }

  override fun settingsRows() = listOf(
    // #861: the about text sits with About (50), after the server field, so the field is whole above the fold.
    SettingsRow(55,SettingsTab.ABOUT) { root, _ ->
      root.addView(s.label(root,FirstMinutes.TAGLINE,32f))
      root.addView(s.label(root,FirstMinutes.PURPOSE,28f))
    },
    SettingsRow(50,SettingsTab.ABOUT) { root, _ ->
      settingsButton(root,"About Flowstate") {
        val page=pages[SettingsTab.ABOUT] ?: root
        if(page.findViewWithTag<View>("about-description")==null)
          page.addView(s.label(page,s.activity.assets.open("description.txt").bufferedReader().use { it.readText() },28f).apply { tag="about-description" })
      }
      settingsTab(root,SettingsTab.VIEW)
      root.addView(android.widget.CheckBox(root.context).apply {
        text="Touch (near)";textSize=32f;setTextColor(Color.WHITE)
        isChecked=s.layout.near
        setOnCheckedChangeListener { _,checked -> s.layout.setNear(checked) }
      })
      // #1034: calm first view (site, main window, the blocking question); off restores every window at put-on.
      root.addView(android.widget.CheckBox(root.context).apply {
        text="Start calm";textSize=32f;setTextColor(Color.WHITE);tag="start-calm-switch"
        isChecked=s.windows.startCalm
        setOnCheckedChangeListener { _,checked -> s.windows.setStartCalm(checked) }
      })
      root.addView(android.widget.CheckBox(root.context).apply {
        text="Head-gaze focus (300 ms)";textSize=32f;setTextColor(Color.WHITE)
        isChecked=s.prefs.getBoolean("head-gaze",false)
        setOnCheckedChangeListener { _,checked -> s.prefs.edit().putBoolean("head-gaze",checked).apply() }
      })
      root.addView(s.label(root,"Gaze highlights and focuses only. Use your hand to select or confirm.",28f))
      settingsButton(root,"Reset layout") { s.layout.resetLayout() }
    })
}

/**
 * #1191: the Settings pages, in tab order. A wearer looks for: what is said and heard (Voice), how the space looks and
 * behaves (View), the room and the scene (Room), what reaches past the headset (Link), the recorded run (Run), the live
 * server (Live), About. Debug exists only in the debug build; its first control is "Lag check (2 min)". Labels are at
 * most five letters, so nine cells fit one row over the 3 degree target.
 */
enum class SettingsTab(val label: String,val debugOnly: Boolean=false) {
  VOICE("Voice"),VIEW("View"),ROOM("Place"),LINK("Link"),RUN("Run"),LIVE("Live"),ABOUT("About"),DEBUG("Debug",true);
  val key get()=name.lowercase(java.util.Locale.ROOT)
  companion object { fun shown()=listOf(VOICE,VIEW,ROOM,LINK,RUN,ABOUT) }
}

/** Views a row adds to [root] after this call go to [tab]'s page (until the next call). Placement only. */
fun settingsTab(root: LinearLayout,tab: SettingsTab)=SettingsTabs.mark(root,tab)

internal object SettingsTabs {
  private val marks=java.util.WeakHashMap<LinearLayout,MutableList<Pair<Int,SettingsTab>>>()
  @Synchronized fun mark(root: LinearLayout,tab: SettingsTab) { marks.getOrPut(root) { mutableListOf() }.add(root.childCount to tab) }
  @Synchronized fun take(root: LinearLayout): List<Pair<Int,SettingsTab>> = marks.remove(root) ?: emptyList()
}
