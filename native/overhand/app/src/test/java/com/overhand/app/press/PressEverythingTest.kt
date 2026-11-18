package com.overhand.app.press

import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.meta.spatial.toolkit.LayoutXMLPanelRegistration
import com.meta.spatial.toolkit.PanelRegistration
import com.overhand.app.OverhandActivity
import com.overhand.app.R
import com.overhand.app.features.ControlDock
import com.overhand.app.features.ControlDockFeature
import com.overhand.app.features.Shell
import com.overhand.app.features.WindowLayout
import com.overhand.app.windows.ControlWalker
import java.io.File
import java.time.Duration
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Check H (#1387): press every control the app can show, once, on the JVM, and judge each press.
 *
 * Every panel is built by its production builder on a production [Shell] (an attached, never-created
 * OverhandActivity, Recorded mode: no scene). Each press runs on a freshly built panel and Shell, in two states:
 * `default` (put-on done: first placement made) and `after_grab` (a window was moved by hand, the state a wearer is
 * in after a few seconds). After the click the Shell's command queue is drained (the next ECS frame) and the main
 * looper idles 600 ms. A press is
 *   OK      some state changed: the panel's view tree, a feature's state, a preference, or an app log row
 *           (`event=`/`outcome=`) other than the press echo and `*_skipped` rows;
 *   DEAD    no handler, or none of the above (session_monitor's dead press, Head's `*_skipped` rule);
 *   SLOW    click + queued commands + 600 ms of posted work held the thread for more than 50 ms (best of two);
 *   THROWS  an exception that is not a missing scene/device;
 *   SCENE   the press reached app code that needs the 3D scene or device (Entity, keystore): not judged here.
 * Fails on DEAD, SLOW or THROWS not in runs/fix17/H/allow.tsv. Writes controls.tsv and presses.tsv.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class PressEverythingTest {
  private val out=File(System.getenv("FIX17_H_OUT") ?: "../../../runs/fix17/H").apply { mkdirs() }
  private val allowFile=File(System.getenv("FIX17_H_ALLOW") ?: "../../../runs/fix17/H/allow.tsv")
  private val looper get()=shadowOf(Looper.getMainLooper())
  private val prefNames=listOf("windows-v1","hands-onboarding-v1","hostlink-v1","overhand-audio","voice-v1")

  /** One panel: how to build it on a fresh Shell, and who owns its file. */
  class Surface(val name: String,val file: String,val owner: String,val build: (Shell) -> View)
  class Built(val shell: Shell,val root: View)

  private fun freshShell(afterGrab: Boolean): Shell {
    val a=Robolectric.buildActivity(OverhandActivity::class.java).get()
    prefNames.forEach { a.getSharedPreferences(it,0).edit().clear().commit() }
    a.deleteFile("judge-receipts.json")
    val s=Shell(a)
    s.prefs // Complete lazy preference migration before measuring a press.
    com.overhand.app.places.Places.store=com.overhand.app.places.Places.MemoryStore()
    s.fakeHead=com.meta.spatial.core.Pose()
    // Put-on done: the first placement happened (nothing is pressable before it on the headset).
    val gate=s.recenter.javaClass.getDeclaredField("gate").apply { isAccessible=true }.get(s.recenter)
    gate.javaClass.getDeclaredField("placedOnce").apply { isAccessible=true }.setBoolean(gate,true)
    if(afterGrab) s.recenter.markMovedByHand()
    // No real network or keystore: the same HostLink with a deterministic refused transport.
    val client=okhttp3.OkHttpClient.Builder().addInterceptor { throw java.io.IOException("fixture transport refused") }.build()
    val link=com.overhand.app.hostlink.HostLink(com.overhand.app.hostlink.InMemoryPairedHostStore(),mayTransmit={ false },baseClient=client)
    val lazy=s.javaClass.getDeclaredField("hostLinkLazy").apply { isAccessible=true }.get(s)
    set(lazy,"_value",link)
    return s
  }

  @Suppress("UNCHECKED_CAST")
  private fun setup(reg: PanelRegistration): (View) -> Unit {
    val f=LayoutXMLPanelRegistration::class.java.getDeclaredField("panelSetupWithRootView").apply { isAccessible=true }
    val fn=f.get(reg) as Function3<View,Any?,Any?,Unit>
    return { root -> fn.invoke(root,bare(com.meta.spatial.runtime.PanelSceneObject::class.java),bare(com.meta.spatial.core.Entity::class.java)) }
  }
  private fun bare(c: Class<*>): Any {
    val f=Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible=true }
    val unsafe=f.get(null)
    return unsafe.javaClass.getMethod("allocateInstance",Class::class.java).invoke(unsafe,c)
  }
  /** A registration's own panelSetupWithRootView on an inflated web_panel, as the Spatial SDK calls it. */
  private fun registered(s: Shell,regs: List<PanelRegistration>,id: Int): View {
    val frame=FrameLayout(s.activity)
    s.activity.layoutInflater.inflate(R.layout.web_panel,frame,true)
    val root=frame.getChildAt(0).also { frame.removeView(it) }
    setup(regs.first { it.registrationId==id })(root)
    return root
  }

  private fun set(o: Any,name: String,value: Any?) {
    o.javaClass.getDeclaredField(name).apply { isAccessible=true }.set(o,value)
  }

  private fun surfaces(): List<Surface> {
    val list=mutableListOf<Surface>()
    list+=Surface("dock","features/ControlDockFeature.kt","#1386") { s ->
      val feature=ControlDockFeature(s)
      val pick=feature.javaClass.getDeclaredMethod("pick",String::class.java).apply { isAccessible=true }
      LinearLayout(s.activity).also { ControlDock.build(it) { v -> pick.invoke(feature,v) } }
    }
    for(sp in WindowLayout.defaultSpecs) {
      if(sp.view=="settings") {
        for(t in com.overhand.app.features.SettingsTab.shown()) list+=Surface("settings_${t.key}","features/SettingsFeature.kt","") { s ->
          registered(s,s.windows.panels(),sp.id).also {
            s.settings.javaClass.getDeclaredField("state").apply { isAccessible=true }.set(s.settings,com.overhand.app.features.SettingsPanel.State(open=true,placed=true))
            s.settings.select(t)
          }
        }
      } else list+=Surface(sp.view,if(sp.view=="code") "windows/RecordedWindows.kt (Code)" else "windows/RecordedWindows.kt",
        if(sp.view=="code") "#1385" else "") { s -> registered(s,s.windows.panels(),sp.id) }
    }
    list+=Surface("chat_history","windows/RecordedWindows.kt","") { s ->
      val root=registered(s,s.windows.panels(),WindowLayout.defaultSpecs.first { it.view=="chat" }.id)
      val web=root.findViewWithTag<android.webkit.WebView>(com.overhand.app.windows.LookK8.WEB_TAG)
      val host=Robolectric.buildActivity(android.app.Activity::class.java).setup()
      host.get().setContentView(root)
      val bridge=shadowOf(web).getJavascriptInterface("AfK8")!!
      bridge.javaClass.getMethod("act",String::class.java,String::class.java).apply { isAccessible=true }.invoke(bridge,"history","")
      looper.idleFor(Duration.ofMillis(200))
      (root.parent as ViewGroup).removeView(root)
      host.pause().stop().destroy()
      root
    }
    list+=Surface("decision_card","decisions/DecisionCards.kt","#1381") { s ->
      registered(s,s.interaction.panels(),R.id.decision_panel).also { s.interaction.decisions.open() }
    }
    list+=Surface("architecture","graph/ArchitectureGraph.kt","") { s ->
      registered(s,s.interaction.panels(),R.id.graph_panel).also { s.interaction.architecture.open(s.recordedContent.defaultProject) }
    }
    list+=Surface("assistant_strip","voice/VoiceController.kt","#1376") { s ->
      val intents=com.overhand.app.voice.VoiceIntents.parse(s.activity.resources.openRawResource(R.raw.voice_answers).bufferedReader().use { it.readText() })
      val c=com.overhand.app.voice.VoiceController(s.activity,intents,{ _,_ -> "" },{ ShortArray(160) },16000)
      c.worn={ false }
      s.voice.javaClass.getDeclaredField("controller").apply { isAccessible=true }.set(s.voice,c)
      c.view
    }
    for((k,sc) in com.overhand.app.hostlink.ui.ConnectScreenTest.all()) list+=Surface("connect_$k","features/connect/ConnectFeature.kt","") { s ->
      LinearLayout(s.activity).apply { orientation=LinearLayout.VERTICAL }.also { col ->
        val f=s.connect
        val fixtures=com.overhand.app.hostlink.ui.ConnectScreenTest
        set(f,"column",col);set(f,"showing",true)
        set(f,"hosts",when(k) { "searching" -> null;"hosts" -> listOf(fixtures.PC,fixtures.OTHER);else -> listOf(fixtures.PC) })
        set(f,"keypadFor",if(k=="keypad") fixtures.PC else null)
        set(f,"typed",if(k=="keypad") "472" else "")
        set(f,"entry",when(k) {
          "other" -> com.overhand.app.hostlink.ui.AddressEntry("other")
          "address" -> com.overhand.app.hostlink.ui.AddressEntry("address","studio-laptop.example.net:47821")
          else -> null
        })
        val linkState=when(k) {
          "pairing" -> com.overhand.app.hostlink.LinkState.PAIRING
          "confirm" -> com.overhand.app.hostlink.LinkState.CONFIRM_ON_COMPUTER
          "connected" -> com.overhand.app.hostlink.LinkState.CONNECTED
          "reconnecting" -> com.overhand.app.hostlink.LinkState.RECONNECTING
          "refused" -> com.overhand.app.hostlink.LinkState.REFUSED
          else -> com.overhand.app.hostlink.LinkState.UNPAIRED
        }
        set(s.hostLink,"status",com.overhand.app.hostlink.LinkStatus(linkState,"",digits=if(k=="confirm") "472913" else null))
        if(k in listOf("pairing","confirm")) set(s.hostLink,"cancelQueue",java.util.concurrent.LinkedBlockingQueue<Any>())
        f.javaClass.getDeclaredMethod("listen").apply { isAccessible=true }.invoke(f)
        com.overhand.app.hostlink.ui.ConnectPanelView.render(col,sc,f)
      }
    }
    val projectScreens=com.overhand.app.hostlink.project.NewProjectFlowTest.all()+com.overhand.app.hostlink.project.FollowUpFlowTest.screens()
    for((k,sc) in projectScreens.distinctBy { it.first }) list+=Surface("project_$k","features/project/ProjectWindowFeature.kt","") { s ->
      LinearLayout(s.activity).apply { orientation=LinearLayout.VERTICAL }.also { col ->
        val f=s.project
        set(f,"column",col);set(f,"showing",true)
        val state=when(k) {
          "dictating" -> com.overhand.app.hostlink.project.NewProjectFlow.State.Dictating
          "chip","chip_sends_off" -> com.overhand.app.hostlink.project.NewProjectFlow.State.Chip("a counter")
          "sending" -> com.overhand.app.hostlink.project.NewProjectFlow.State.Sending("a counter","c1")
          "idle" -> com.overhand.app.hostlink.project.NewProjectFlow.State.Idle
          else -> com.overhand.app.hostlink.project.NewProjectFlow.State.Tracking("a counter","p_web","t1")
        }
        set(f.flow,"state",state)
        if(k.startsWith("followup_")) {
          f.followUp.heard("make the buttons bigger","counter","p_web")
          if(k=="followup_sending") f.followUp.confirm()
        }
        com.overhand.app.hostlink.project.ProjectPanelView.render(col,sc) { key -> f.action(key) }
      }
    }
    return list
  }

  private fun attach(s: Shell,root: View) {
    s.activity.setContentView(root,ViewGroup.LayoutParams(-1,-1))
    root.measure(View.MeasureSpec.makeMeasureSpec(1800,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2160,View.MeasureSpec.EXACTLY))
    root.layout(0,0,1800,2160)
    looper.idleFor(Duration.ofMillis(600))
  }
  private fun build(surface: Surface,afterGrab: Boolean): Built {
    val s=freshShell(afterGrab);val root=surface.build(s);attach(s,root)
    // The panel callbacks queue ECS-only placement; SDK entities do not exist on the JVM.
    // Keep production UI construction, but omit scene placement during setup only.
    if(surface.name=="decision_card") {
      val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
      while(s.interaction.decisions.openIds()==null && System.nanoTime()<deadline) {
        Thread.sleep(2);looper.idleFor(Duration.ofMillis(10))
      }
      assertTrue("decision fixture must finish loading before enumerating controls",s.interaction.decisions.openIds()!=null)
    }
    s.commands.clear()
    looper.idleFor(Duration.ofMillis(600))
    return Built(s,root)
  }
  private fun drain(s: Shell) { var n=0;while(n++<200) (s.commands.poll() ?: break).invoke() }

  // ---- handlers ----
  private fun listenerInfo(v: View): Any?=runCatching { View::class.java.getDeclaredField("mListenerInfo").apply { isAccessible=true }.get(v) }.getOrNull()
  private fun field(o: Any?,name: String): Any?=o?.let { runCatching { it.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(it) }.getOrNull() }
  private fun clickListener(v: View)=field(listenerInfo(v),"mOnClickListener")
  private fun touchListener(v: View)=field(listenerInfo(v),"mOnTouchListener")
  private fun checkListener(v: View)=if(v is CompoundButton) runCatching { CompoundButton::class.java.getDeclaredField("mOnCheckedChangeListener").apply { isAccessible=true }.get(v) }.getOrNull() else null
  private fun handler(v: View): String=if(v is EditText) "editable:focus+entry" else clickListener(v)?.javaClass?.name ?: checkListener(v)?.javaClass?.name?.let { "checked:$it" }
    ?: touchListener(v)?.javaClass?.name?.let { "touch:$it" } ?: ""
  private fun controls(root: View)=ControlWalker.descendants(root).filter { it.isClickable || it.hasOnClickListeners() || touchListener(it)!=null }
  // isShown also requires an attached window, absent for our never-created SpatialActivity.
  private fun visible(v: View): Boolean = v.visibility==View.VISIBLE && ((v.parent as? View)?.let { visible(it) } ?: true)
  private fun key(v: View)=(v.tag?.toString()?.takeIf { it.isNotBlank() } ?: ControlWalker.idName(v).takeIf { it.isNotBlank() } ?: "")
  private fun label(v: View)=ControlWalker.label(v).ifBlank { ControlWalker.descendants(v).filterIsInstance<TextView>().joinToString(" ") { it.text.toString() } }
    .replace(Regex("\\s+")," ").trim().take(60)

  // ---- state ----
  private fun treeSig(root: View): List<String> = ControlWalker.descendants(root).map { v ->
    listOf(v.javaClass.simpleName,v.visibility,(v as? TextView)?.text?.toString() ?: "",v.isSelected,v.isActivated,v.isEnabled,
      (v as? CompoundButton)?.isChecked ?: "",(v as? ViewGroup)?.childCount ?: "",v.alpha>0.01f).joinToString("|")
  }
  private val noisy=Regex("(?i)(ms|millis|nanos|time|At|clock|elapsed|seq|token|frame)$")
  private fun simple(x: Any?): String?=when(x) {
    null -> "null"
    is Number,is Boolean,is CharSequence,is Enum<*> -> x.toString()
    is Collection<*> -> if(x.size<=40 && x.all { it==null || it is Number || it is Boolean || it is CharSequence || it is Enum<*> }) x.toString() else "size=${x.size}"
    is Map<*,*> -> "size=${x.size}:"+x.keys.filter { it is CharSequence || it is Number || it is Enum<*> }.take(40)
    else -> if(x.javaClass.methods.any { it.name=="component1" }) x.toString().replace(Regex("\\w*(Ms|At|Time)=[^,)]*"),"") else null
  }
  private fun objState(prefix: String,o: Any,into: MutableMap<String,String>) {
    var c: Class<*>?=o.javaClass
    while(c!=null && c!=Any::class.java) {
      for(f in c.declaredFields) {
        if(java.lang.reflect.Modifier.isStatic(f.modifiers) || noisy.containsMatchIn(f.name) || f.name.contains("$")) continue
        val v=runCatching { f.isAccessible=true;f.get(o) }.getOrNull()
        simple(v)?.let { into["$prefix.${f.name}"]=it }
      }
      c=c.superclass
    }
  }
  private fun state(b: Built): Map<String,String> {
    val m=linkedMapOf<String,String>()
    m["Places.current"]=com.overhand.app.places.Places.current.name
    m["focus"]=b.root.findFocus()?.let { key(it)+":"+label(it) } ?: ""
    treeSig(b.root).forEachIndexed { i,s -> m["tree.$i"]=s }
    prefNames.forEach { n -> b.shell.activity.getSharedPreferences(n,0).all.toSortedMap().forEach { (k,v) -> m["pref.$n.$k"]=v.toString() } }
    (b.shell.features+listOf(b.shell.layout,b.shell.interaction.decisions,b.shell.interaction.architecture,b.shell.project.flow,b.shell.project.followUp)).forEach { f -> runCatching { objState(f.javaClass.simpleName,f,m) } }
    if(b.shell.hostLinkMade) objState("HostLink",b.shell.hostLink,m)
    runCatching { m["windows.voiceHidden"]=b.shell.windows.voiceHidden.sorted().toString() }
    runCatching { m["layout.entities"]=b.shell.layout.entities.keys.sorted().toString() }
    return m
  }
  private fun appRows(from: Int)=ShadowLog.getLogs().drop(from).filter { it.msg.contains("event=") || it.msg.contains("outcome=") }
  private val echo=Regex("(_skipped\\b|\\bevent=press\\b|^(up|down) panel=|event=tab_?click)")

  // ---- one press ----
  data class Press(val panel: String,val state: String,val index: Int,val key: String,val label: String,val handler: String,
    val cls: String,val ms: Double,val changes: String,val rows: String,val error: String,val reached: String)

  private fun sceneBound(e: Throwable): Boolean {
    var t: Throwable?=e
    while(t!=null) {
      if(t is UnsatisfiedLinkError || t is NoClassDefFoundError || t is ExceptionInInitializerError) return true
      if(t is NotImplementedError && t.stackTrace.any { it.className.startsWith("com.meta.spatial") }) return true

      if((t.message ?: "").contains("AndroidKeyStore") || t.stackTrace.take(6).any { it.className.startsWith("com.meta.spatial") }) return true
      t=t.cause
    }
    return false
  }
  private fun reached(e: Throwable): String {
    var t: Throwable=e;while(t.cause!=null && t.cause!==t) t=t.cause!!
    return t.stackTrace.firstOrNull { it.className.startsWith("com.overhand.app") }?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } ?: ""
  }

  private fun pressOnce(surface: Surface,afterGrab: Boolean,index: Int): Press {
    val b=build(surface,afterGrab)
    val v=controls(b.root)[index]
    val st=if(afterGrab) "after_grab" else "default"
    val h=handler(v)
    val visibleBefore=visibleTexts(b.root)
    val before=state(b);val logFrom=ShadowLog.getLogs().size
    var err: Throwable?=null
    val t0=System.nanoTime()
    try {
      if(v is EditText) {
        v.clearFocus()
        assertTrue("editable field must accept focus",v.requestFocus())
        v.setText("http://fixture.example:47821")
        assertTrue("editable field must accept text",v.hasFocus() && v.text.toString()=="http://fixture.example:47821")
      } else if(clickListener(v)!=null || checkListener(v)!=null || touchListener(v)==null) v.performClick()
      else {
        val now=SystemClock.uptimeMillis();val x=v.width/2f;val y=v.height/2f
        v.dispatchTouchEvent(MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0))
        v.dispatchTouchEvent(MotionEvent.obtain(now,now+50,MotionEvent.ACTION_UP,x,y,0))
      }
      drain(b.shell)
      looper.idleFor(Duration.ofMillis(600))
      drain(b.shell)
    } catch(e: Throwable) { err=e }
    val ms=(System.nanoTime()-t0)/1e6
    if((surface.name=="connect_hosts" && key(v).startsWith("connect-host-")) || key(v)=="connect-pair") {
      // Wait for a visible attached result, not just a private field or the press echo.
      val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
      while(visibleTexts(b.root)==visibleBefore && System.nanoTime()<deadline) {
        Thread.sleep(2);looper.idleFor(Duration.ofMillis(10))
      }
      assertTrue("pairing press must change visible text",visibleTexts(b.root)!=visibleBefore)
    }
    val after=runCatching { state(b) }.getOrElse { emptyMap() }
    val diff=(before.keys+after.keys).filter { before[it]!=after[it] }
    val rows=appRows(logFrom)
    val meaningful=rows.filter { !echo.containsMatchIn(it.msg) }
    val changed=diff.isNotEmpty() || meaningful.isNotEmpty()
    b.root.viewTreeObserver.dispatchOnPreDraw()
    val cls=when {
      err!=null -> if(sceneBound(err)) "SCENE" else "THROWS"
      surface.name=="settings_room" && label(v)=="Allow room access" -> "SCENE" // roomMode is created only by onSceneReady.
      ms>50 -> "SLOW"
      h.isEmpty() -> "DEAD"
      changed -> "OK"
      err!=null -> "SCENE"
      else -> "DEAD"
    }
    runCatching { b.shell.stopped=true;b.shell.voice.onDestroy();if(b.shell.hostLinkMade) b.shell.hostLink.shutdown();b.shell.interaction.architecture.close();b.shell.interaction.decisions.close() }
    return Press(surface.name,st,index,key(v).ifBlank { "index:$index" },label(v),h,cls,ms,diff.take(4).joinToString(";") { it.substringBefore('|') }.take(200),
      rows.joinToString(" || ") { "${it.tag}: ${it.msg}" }.take(300),err?.let { (it.cause ?: it).toString().take(160) } ?: "",err?.let { reached(it) } ?: "")
  }

  private fun visibleTexts(root: View)=ControlWalker.descendants(root).filterIsInstance<TextView>()
    .filter { visible(it) && it.parent!=null }.map { it.text.toString() }

  private fun clean(s: String)=s.replace('\t',' ').replace('\n',' ')

  /** Profile the original handler sequence, retained verbatim as the comparison for the fixed press. */
  @Test fun profileOriginalRestartSequence() {
    val rows=mutableListOf("run\trestart_ms\trender_ms\tdump_ms\ttotal_ms")
    repeat(2) { run ->
      val b=build(surfaces().first { it.name=="architecture" },false)
      try {
        val graph=b.shell.interaction.architecture
        val render=graph.javaClass.getDeclaredMethod("render").apply { isAccessible=true }
        val dump=graph.javaClass.getDeclaredMethod("dump").apply { isAccessible=true }
        val began=System.nanoTime()
        b.shell.recordedContent.taskReplay.restart()
        val restarted=System.nanoTime()
        render.invoke(graph)
        val rendered=System.nanoTime()
        dump.invoke(graph)
        val dumped=System.nanoTime()
        rows+=listOf(run,(restarted-began)/1e6,(rendered-restarted)/1e6,(dumped-rendered)/1e6,(dumped-began)/1e6).joinToString("\t")
      } finally {
        b.shell.stopped=true;b.shell.hostLink.shutdown();b.shell.interaction.architecture.close()
      }
    }
    File(out,"restart-original.tsv").writeText(rows.joinToString("\n")+"\n")
  }

  @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
  @Test fun checkConnectionChangesAttachedVisibleTextAndSurvivesRebuild() {
    val surface=surfaces().first { it.name=="settings_run" }
    val b=build(surface,false)
    fun visibleText()=ControlWalker.descendants(b.root).filterIsInstance<TextView>()
      .filter { visible(it) && it.rootView===b.root.rootView && it.parent!=null }.map { it.text.toString() }
    val container=b.root.findViewById<LinearLayout>(R.id.panel_root)
    fun capture(name: String) {
      val renderer=com.overhand.app.layoutlint.LayoutLintRenderer(b.shell.activity,out)
      renderer.captureExisting(com.overhand.app.layoutlint.Panel(name,1000f,1000f,1f,1f,1f,"features/SettingsFeature.kt") {},container)
    }
    capture("settings_before_check")
    val before=visibleText()
    controls(b.root).first { label(it)=="Check connection" }.performClick()
    val expected="Recorded snapshot \u00b7 no server connection"
    capture("settings_after_check")
    assertTrue("Check connection must change attached visible text: $before -> ${visibleText()}",
      expected !in before && expected in visibleText())
    b.shell.settings.build(container)
    b.shell.settings.select(com.overhand.app.features.SettingsTab.RUN)
    assertTrue("Connection result lost on rebuild",expected in visibleText())
    capture("settings_after_rebuild")
    b.shell.stopped=true;b.shell.hostLink.shutdown();b.shell.interaction.architecture.close()
  }

  @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
  @Test fun incompletePairingCodeShowsVisibleRefusal() {
    val b=build(surfaces().first { it.name=="connect_keypad" },false)
    try {
      val renderer=com.overhand.app.layoutlint.LayoutLintRenderer(b.shell.activity,out)
      fun capture(name: String) = renderer.captureExisting(com.overhand.app.layoutlint.Panel(name,
        com.overhand.app.hostlink.ui.ConnectPanelView.W_DP,com.overhand.app.hostlink.ui.ConnectPanelView.H_DP,
        1f,1f,1f,"features/connect/ConnectFeature.kt") {},b.root as LinearLayout)
      capture("connect_before_pair")
      b.root.findViewWithTag<View>("connect-pair").performClick()
      val expected=com.overhand.app.hostlink.Refusal.BAD_CODE.words
      val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
      while(expected !in visibleTexts(b.root) && System.nanoTime()<deadline) {
        Thread.sleep(2);looper.idleFor(Duration.ofMillis(10))
      }
      assertTrue("incomplete code must show its refusal beside the keypad",expected in visibleTexts(b.root))
      capture("connect_after_pair")
    } finally { b.shell.stopped=true;b.shell.hostLink.shutdown() }
  }

  @Test fun pressEveryControlOnce() {
    ShadowLog.stream=null
    val controlRows=mutableListOf("panel\tindex\tid_or_tag\tlabel\tclass\thandler\thas_handler\tshown\tenabled\tfile\towner")
    val pressRows=mutableListOf("panel\tstate\tindex\tid_or_tag\tlabel\tresult\tms\thandler\tchanges\tlog_rows\terror\treached\tfile\towner\tallowed")
    val unbuilt=mutableListOf("panel\terror")
    val allow=if(allowFile.isFile) allowFile.readLines().drop(1).filter { it.isNotBlank() && !it.startsWith("#") }.map { it.trimEnd('\r').split('\t') } else emptyList()
    fun allowed(p: Press)=allow.firstOrNull { a -> a.size>=4 && Regex(a[0]).matches(p.panel) && (a[1]=="*" || a[1]==p.state) &&
      (a[2]==p.key || a[2]==p.label || a[2]=="*") && (a[3]=="*" || a[3]==p.cls) }
    val failures=mutableListOf<String>()
    for(surface in surfaces()) {
      val built=runCatching { build(surface,false) }.getOrElse { e ->
        unbuilt+="${surface.name}\t${clean((e.cause ?: e).toString().take(200))} at ${reached(e)}";null } ?: continue
      val cs=controls(built.root)
      cs.forEachIndexed { i,v ->
        val h=handler(v)
        controlRows+=listOf(surface.name,i,clean(key(v).ifBlank { "index:$i" }),clean(label(v)),v.javaClass.simpleName,h,if(h.isEmpty()) "no" else "yes",
          visible(v),v.isEnabled,surface.file,surface.owner).joinToString("\t")
      }
      runCatching { built.shell.stopped=true;built.shell.voice.onDestroy();if(built.shell.hostLinkMade) built.shell.hostLink.shutdown();built.shell.interaction.architecture.close();built.shell.interaction.decisions.close() }
      for(afterGrab in listOf(false,true)) for(i in cs.indices) {
        if(!visible(cs[i]) || !cs[i].isEnabled) continue
        var p=runCatching { pressOnce(surface,afterGrab,i) }.getOrElse { e ->
          Press(surface.name,if(afterGrab) "after_grab" else "default",i,key(cs[i]).ifBlank { "index:$i" },label(cs[i]),handler(cs[i]),"THROWS",0.0,"","",clean(e.toString().take(160)),reached(e)) }
        if(p.cls=="SLOW") runCatching { pressOnce(surface,afterGrab,i) }.getOrNull()?.let { r -> if(r.ms<p.ms) p=r }
        val a=allowed(p)
        pressRows+=listOf(p.panel,p.state,p.index,clean(p.key),clean(p.label),p.cls,"%.1f".format(p.ms),p.handler,clean(p.changes),clean(p.rows),clean(p.error),p.reached,
          surface.file,surface.owner,a?.getOrNull(4) ?: "").joinToString("\t")
        if(p.cls in setOf("DEAD","SLOW","THROWS") && a==null) failures+="${p.panel}/${p.state} #${p.index} '${p.label}' (${p.key}) ${p.cls} ${p.error}"
      }
    }
    File(out,"controls.tsv").writeText(controlRows.joinToString("\n")+"\n")
    File(out,"presses.tsv").writeText(pressRows.joinToString("\n")+"\n")
    File(out,"unbuilt.tsv").writeText(unbuilt.joinToString("\n")+"\n")
    val byCls=pressRows.drop(1).groupingBy { it.split('\t')[5] }.eachCount()
    File(out,"summary.txt").writeText("controls=${controlRows.size-1}\npresses=${pressRows.size-1}\n"+byCls.toSortedMap().map { "${it.key}=${it.value}" }.joinToString("\n")+
      "\nunbuilt_panels=${unbuilt.size-1}\nunallowed_failures=${failures.size}\n")
    println("press_everything ${byCls} unbuilt=${unbuilt.size-1} failures=${failures.size} out=${out.absolutePath}")
    assertTrue("no controls were pressed",pressRows.size>1)
    assertTrue("panels could not be built: $unbuilt",unbuilt.size==1)
    assertTrue("dead/slow/throwing controls not on the allow-list:\n"+failures.joinToString("\n"),failures.isEmpty())
  }
}
