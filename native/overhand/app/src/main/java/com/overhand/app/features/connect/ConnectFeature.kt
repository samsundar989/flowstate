package com.overhand.app.features.connect
import com.overhand.app.features.windowPose
import com.overhand.app.features.windowVisible

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.DpDisplayOptions
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.ViewPanelRegistration
import com.meta.spatial.toolkit.Visible
import com.overhand.app.BuildConfig
import com.overhand.app.features.Feature
import com.overhand.app.features.SettingsRow
import com.overhand.app.features.Shell
import com.overhand.app.features.settingsButton
import com.overhand.app.hostlink.Endpoint
import com.overhand.app.hostlink.LinkState
import com.overhand.app.hostlink.LinkStatus
import com.overhand.app.hostlink.ManualAddress
import com.overhand.app.hostlink.PairRefused
import com.overhand.app.hostlink.ui.AddressEntry
import com.overhand.app.hostlink.ui.ConnectPanelView
import com.overhand.app.hostlink.ui.ConnectScreen
import com.overhand.app.hostlink.ui.ConnectScreens
import com.overhand.app.hostlink.ui.Diagnosis
import com.overhand.app.windows.Theme
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.math.atan2

/**
 * "Connect your computer" (#814): find the host on Wi-Fi, confirm six digits on the computer (typed code as the fallback),
 * then Live runs over the host link (Shell.sessionSource, mode "host"). Opened from one Settings row.
 * Every state change is traced to files/connect_trace.json and each screen the panel shows is drawn to
 * files/connect_frames/, so a device run can be checked without anyone wearing the headset.
 */
class ConnectFeature(private val s: Shell) : Feature, ConnectPanelView.Actions {
  override val name = "connect"
  private var column: LinearLayout?=null
  private var entity: Entity?=null
  private var registered=false
  private var showing=false
  private var hosts: List<Endpoint>?=emptyList()
  private var keypadFor: Endpoint?=null
  private var typed=""
  /** #954: "Other ways to connect" and the typed computer address; null on the first-minute path. */
  private var entry: AddressEntry?=null
  private var why: Diagnosis?=null
  /** A search started after a refusal: show its result, not the old refusal. Cleared by the next refusal. */
  private var fresh=false
  private var lastDiagnosis=0L
  private var listening=false
  private var lastKey=""
  private var frameNo=0
  private val trace=JSONArray()
  private var netCallback: ConnectivityManager.NetworkCallback?=null
  private val hostMode get()=s.prefs.getString("session-mode","recorded")=="host"

  override fun onSceneReady() {
    if(hostMode) { listen();registerNetworkCallback() }
    if(s.prefs.getBoolean(REOPEN,false)) { s.prefs.edit().putBoolean(REOPEN,false).apply();open("after pairing") }
  }
  override fun onResume() { if(hostMode && s.hostLinkMade) s.hostLink.reconnectNow() }
  override fun onDestroy() {
    netCallback?.let { runCatching { cm().unregisterNetworkCallback(it) } };netCallback=null
    if(s.hostLinkMade) s.hostLink.shutdown()
  }

  override fun settingsRows()=listOf(SettingsRow(45,com.overhand.app.features.SettingsTab.LINK) { root,_ ->
    settingsButton(root,ConnectScreens.TITLE) { open("settings row") }
    root.getChildAt(root.childCount-1).tag=ROW_TAG
  })

  // ---------- panel ----------
  fun open(why: String) {
    listen()
    if(!registered) {
      registered=true
      val c=LinearLayout(s.activity).also { column=it }
      s.activity.registerPanel(ViewPanelRegistration(PANEL,{ _,_ -> c },
        { Theme.panel(UIPanelSettings(shape=QuadShapeOptions(width=ConnectPanelView.W_M,height=ConnectPanelView.H_M),
          display=DpDisplayOptions(width=ConnectPanelView.W_DP,height=ConnectPanelView.H_DP))) },{ _,_,_ -> }))
    }
    showing=true
    val pose=placement()
    s.commands.add {
      val e=entity ?: Entity.create(listOf(Panel(PANEL))).also { it.windowPose(pose,"ConnectFeature_create");it.windowVisible(false,"ConnectFeature_create") }.also { entity=it }
      e.windowPose(pose,"ConnectFeature_open_close");e.windowVisible(true,"ConnectFeature_open_close")
    }
    log("open why=$why state=${s.hostLink.status.state}")
    if(s.hostLink.paired==null && s.hostLink.status.state==LinkState.UNPAIRED) browse() else render()
  }
  private fun close() { showing=false;keypadFor=null;entry=null;s.commands.add { entity?.windowVisible(false,"ConnectFeature_open_close") };log("close") }

  /** 1 m in front of the head, a little low, level and facing the wearer. */
  private fun placement(): Pose {
    val h=s.head();val f=h.q*Vector3(0f,0f,1f)
    val yaw=atan2(f.x,f.z);val at=Vector3(h.t.x+kotlin.math.sin(yaw)*ConnectPanelView.DIST_M,h.t.y-0.1f,h.t.z+kotlin.math.cos(yaw)*ConnectPanelView.DIST_M)
    return Pose(at,Quaternion.fromEuler(0f,Math.toDegrees(yaw.toDouble()).toFloat(),0f))
  }

  private fun name()=s.hostLink.paired?.name ?: keypadFor?.name ?: hosts?.singleOrNull()?.name ?: "your computer"
  private fun shown(): LinkStatus { val st=s.hostLink.status;return if(fresh && st.state==LinkState.REFUSED) LinkStatus(LinkState.UNPAIRED,"") else st }
  private fun screen(): ConnectScreen=ConnectScreens.of(shown(),hosts,keypadFor,typed,why,s.hostLink.paired!=null,name(),entry)

  private fun render() {
    val c=column ?: return
    if(!showing) return
    val sc=screen()
    ConnectPanelView.render(c,sc,this)
    // Keep the keypad available after a bad code, with the refusal where the person entered it.
    val st=shown()
    if(keypadFor!=null && st.state==LinkState.REFUSED) {
      c.addView(s.label(c,st.detail,24f).apply { tag="connect-code-error" })
    }
    if(sc.key!=lastKey) { lastKey=sc.key;c.post { c.postDelayed({ frame(c,sc.key) },300) } }
  }
  private fun frame(v: View,key: String) {
    if(v.width<=0 || v.height<=0) return
    runCatching {
      val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b))
      val dir=File(s.activity.filesDir,"connect_frames").apply { mkdirs() }
      File(dir,"%02d_%s.png".format(++frameNo,key)).outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) }
      log("frame n=$frameNo key=$key")
    }
  }

  // ---------- actions (each is one pinch on the panel) ----------
  override fun action(key: String) {
    log("action $key")
    when(key) {
      "cancel","differ" -> s.hostLink.cancelPairing()
      "close" -> close()
      "forget" -> { s.hostLink.forget();if(hostMode) s.session.restartSession("recorded") else browse() }
      "retry" -> if(s.hostLink.paired!=null) { s.hostLink.start();s.hostLink.reconnectNow() } else browse()
      "search" -> browse()
      "code" -> { entry=null;keypadFor=hosts?.firstOrNull();typed="";render() }
      "back" -> { if(entry?.page=="address") entry=AddressEntry("other") else if(entry!=null) entry=null else keypadFor=null;render() }
      "other" -> { keypadFor=null;entry=AddressEntry("other");render() }
      "address" -> { entry=AddressEntry("address",s.prefs.getString(ADDRESS,"").orEmpty());render() }
      "dial" -> entry?.typed?.let { text -> dial(text) }
      "pair" -> keypadFor?.let { ep -> val code=typed;background("pair-code") { pair(ep) { s.hostLink.pairWithCode(ep,code) } } }
    }
  }
  override fun host(ep: Endpoint) { log("host ${ep.address}");background("pair-confirm") { pair(ep) { s.hostLink.pairByConfirm(ep) } } }
  override fun char(c: String) {
    val e=entry ?: return
    entry=e.copy(typed=if(c=="del") e.typed.dropLast(1) else (e.typed+c).take(64),note=null);render()
  }
  /** Reach the typed computer, read its certificate, then the same six-digit confirm as on Wi-Fi. Saved for next time. */
  private fun dial(text: String) {
    s.prefs.edit().putString(ADDRESS,text).apply()
    entry=entry?.copy(note="Reaching $text")
    render()
    background("dial") {
      val ep=try { ManualAddress.endpoint(text) } catch(e: PairRefused) {
        log("dial refused ${e.refusal}");s.ui.post { entry=entry?.copy(note=e.refusal.words);render() };return@background
      }
      log("dial reached port=${ep.port}")
      s.ui.post { entry=entry?.copy(note=null) }
      pair(ep) { s.hostLink.pairByConfirm(ep) }
    }
  }
  override fun key(d: String) { typed=if(d=="del") typed.dropLast(1) else (typed+d).take(6);render() }

  private fun browse() {
    hosts=null;keypadFor=null;entry=null;why=null;fresh=true;render()
    background("browse") {
      val lock=runCatching { (s.activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        .createMulticastLock("connect").apply { setReferenceCounted(false);acquire() } }.getOrNull()
      val found=try { s.hostDiscovery.browse(3000) } finally { runCatching { lock?.release() } }
      val d=if(found.isEmpty()) diagnose(null) else null
      log("browse found=${found.size} why=$d")
      s.ui.post {
        if(s.stopped) return@post
        hosts=found;why=d;render()
        if(found.size==1 && s.hostLink.paired==null && shown().state==LinkState.UNPAIRED) host(found.single())   // one computer: go straight to the digits
      }
    }
  }
  private fun pair(ep: Endpoint,how: () -> Any) {
    try {
      how();log("paired host=${ep.address}")
      s.ui.post { entry=null;if(!s.stopped) { s.prefs.edit().putBoolean(REOPEN,true).commit();s.session.restartSession("host") } }
    } catch(e: PairRefused) { log("refused ${e.refusal}");s.ui.post { render() } }
    catch(e: Exception) { log("pair failed ${e.javaClass.simpleName}");s.ui.post { render() } }
  }

  // ---------- link state, diagnosis, recovery ----------
  private fun listen() {
    if(listening) return
    listening=true
    s.hostLink.statusListeners.add { st -> record(st);s.ui.post { if(!s.stopped) { if(st.state==LinkState.REFUSED || st.state==LinkState.PAIRING) fresh=false;maybeDiagnose(st);render() } } }
    record(s.hostLink.status)
  }
  private fun maybeDiagnose(st: LinkStatus) {
    if(st.state==LinkState.CONNECTED) { why=null;return }
    if(st.state!=LinkState.RECONNECTING && st.state!=LinkState.SEARCHING) return
    val now=SystemClock.elapsedRealtime();if(now-lastDiagnosis<5000) return
    lastDiagnosis=now
    val ep=s.hostLink.paired?.endpoint ?: return
    background("diagnose") { val d=diagnose(ep);s.ui.post { why=d;log("diagnosis $d");render() } }
  }
  /** Wi-Fi present? Same subnet? Then a plain TCP connect to the host's port: refused, silent or unreachable. */
  private fun diagnose(ep: Endpoint?): Diagnosis? {
    val net=cm().activeNetwork
    val wifi=net?.let { cm().getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }==true
    val addr=if(wifi) cm().getLinkProperties(net)?.linkAddresses?.firstOrNull { it.address is Inet4Address } else null
    if(ep==null) return Diagnosis.classify(addr?.address?.hostAddress,0,"",null)
    val probe=if(addr==null) null else try {
      Socket().use { it.connect(InetSocketAddress(ep.address,ep.port),2500) };Diagnosis.Companion.Probe.OPEN
    } catch(e: SocketTimeoutException) { Diagnosis.Companion.Probe.TIMEOUT }
      catch(e: ConnectException) { if(e.message.orEmpty().contains("ECONNREFUSED")) Diagnosis.Companion.Probe.REFUSED else Diagnosis.Companion.Probe.UNREACHABLE }
      catch(e: Exception) { Diagnosis.Companion.Probe.UNREACHABLE }
    return Diagnosis.classify(addr?.address?.hostAddress,addr?.prefixLength ?: 0,ep.address,probe)
  }
  /** Wi-Fi back: try at once instead of waiting out the backoff. */
  private fun registerNetworkCallback() {
    if(netCallback!=null) return
    val cb=object: ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) { log("network available");if(s.hostLinkMade) s.hostLink.reconnectNow() }
      override fun onLost(network: Network) { log("network lost") }
    }
    runCatching { cm().registerDefaultNetworkCallback(cb);netCallback=cb }
  }
  private fun cm()=s.activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

  // ---------- evidence ----------
  private fun record(st: LinkStatus) {
    synchronized(trace) {
      trace.put(JSONObject().put("wall_ms",System.currentTimeMillis()).put("up_ms",SystemClock.elapsedRealtime())
        .put("state",st.state.name).put("detail",st.detail).put("digits",st.digits ?: JSONObject.NULL))
    }
    log("state ${st.state} ${st.detail}")
    dump()
  }
  private fun dump() {
    runCatching {
      val seqs=s.hostLink.events.map { it.optLong("seq") }.sorted()
      val missing=if(seqs.isEmpty()) emptyList() else (seqs.first()..seqs.last()).filter { java.util.Collections.binarySearch(seqs,it)<0 }
      val o=JSONObject().put("written_wall_ms",System.currentTimeMillis()).put("mode",s.prefs.getString("session-mode","recorded"))
        .put("state",s.hostLink.status.state.name).put("last_seq",s.hostLink.lastSeq).put("events_applied",seqs.size)
        .put("first_seq",seqs.firstOrNull() ?: 0).put("max_seq",seqs.lastOrNull() ?: 0).put("missing",JSONArray(missing.take(50)))
        .put("missing_count",missing.size).put("trace",synchronized(trace) { JSONArray(trace.toString()) })
      val f=File(s.activity.filesDir,"connect_trace.json");val tmp=File(f.path+".tmp")
      tmp.writeText(o.toString());tmp.renameTo(f)
    }
  }
  private fun log(m: String)=Log.i(TAG,"connect $m")
  private fun background(what: String,body: () -> Unit)=Thread({ body() },"connect-$what").apply { isDaemon=true;start() }

  override fun onDebugIntent(intent: Intent) {
    if(!BuildConfig.DEBUG) return
    if(intent.getBooleanExtra("connect_open",false)) {
      // The Settings row's own click when Settings is up (the same onClick as a pinch); else the same entry point.
      val row=s.layout.roots[com.overhand.app.R.id.settings_panel]?.findViewWithTag<View>(ROW_TAG)
      if(row!=null) { log("debug click $ROW_TAG");row.performClick() } else open("debug intent")
    }
    intent.getStringExtra("connect_click")?.let { tag ->
      val v=column?.findViewWithTag<View>(tag)
      log("debug click $tag found=${v!=null}");v?.performClick()
    }
    if(intent.getBooleanExtra("connect_dump",false)) dump()
  }

  companion object {
    const val TAG="Overhand"
    const val ROW_TAG="connect-row"
    private const val REOPEN="connect-reopen"
    /** The last computer address typed (#954). */
    private const val ADDRESS="connect-address"
    /** Outside the aapt range, like VoiceFeature.PANEL. */
    const val PANEL=0x0814_0001
  }
}
