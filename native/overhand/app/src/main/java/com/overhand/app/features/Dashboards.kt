package com.overhand.app.features

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.overhand.app.BuildConfig
import com.overhand.app.FirstMinutes
import com.overhand.app.R
import com.overhand.app.features.Shell.Companion.TAG
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.sqrt

class WebPanel(val spec: Spec, val web: WebView, val still: ImageView,
  val status: TextView, var bitmap: Bitmap? = null, var loaded: Boolean = false)

/** Live dashboard WebViews: one active, the rest frozen to stills. Android thread only. */
class Dashboards(private val s: Shell) {
  val panels = linkedMapOf<Int, WebPanel>() // Android thread only
  @Volatile var active = R.id.fleet_panel
  private val script by lazy { s.activity.assets.open("windows.js").bufferedReader().use { it.readText() } }

  @SuppressLint("SetJavaScriptEnabled")
  fun dashboard(root: LinearLayout, sp: Spec) {
    if(s.isRecorded()) {
      // look K8 (#1379): the briefing's rows open the question card, Fleet and the Yard window's finished work.
      if(sp.view==com.overhand.app.windows.LookK8.VIEW) com.overhand.app.windows.LookK8.paths=com.overhand.app.windows.LookK8.Paths(
        needs={ s.ui.post { s.interaction.decisions.let { d -> com.overhand.app.windows.LookK8.raise(d::open,d::openIds,d::showDecision) } } },
        building={ s.windows.open("fleet","briefing") },done={ s.windows.openText("build","briefing") })
      s.recordedWindows.attach(root,sp.view,s.layout.stationProject);return
    }
    panels.remove(sp.id)?.let { it.web.destroy(); it.bitmap?.recycle() }
    WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
    val frame=FrameLayout(root.context)
    root.addView(frame,LinearLayout.LayoutParams(-1,0,1f))
    val web=WebView(root.context)
    web.settings.javaScriptEnabled=true; web.settings.domStorageEnabled=true
    val still=ImageView(root.context).apply { scaleType=ImageView.ScaleType.FIT_XY; visibility=View.GONE
      contentDescription="Paused ${sp.title}"; setOnClickListener { activate(sp.id) } }
    frame.addView(web,FrameLayout.LayoutParams(-1,-1)); frame.addView(still,FrameLayout.LayoutParams(-1,-1))
    val status=s.label(root,"Connecting")
    root.addView(status)
    val p=WebPanel(sp,web,still,status); panels[sp.id]=p
    web.webChromeClient=com.overhand.app.windows.WebGuard.client { m -> status.text=m.take(160);status.setTextColor(Color.WHITE) }
    web.webViewClient=object: WebViewClient() {
      override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        Uri.parse(s.server()).let { it.host!=request.url.host || it.port!=request.url.port || it.scheme!=request.url.scheme }
      override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if(!request.isForMainFrame || request.method!="GET") return null
        return try {
          val connection=URL(request.url.toString()).openConnection() as HttpURLConnection
          connection.connectTimeout=5000; connection.readTimeout=5000
          try {
            if(connection.responseCode!=200 || !connection.contentType.orEmpty().contains("text/html")) return null
            val html=FirstMinutes.dashboardDocument(connection.inputStream.bufferedReader().use { it.readText() })
            val distance=sqrt(sp.ringX*sp.ringX+sp.ringY*sp.ringY+sp.ringZ*sp.ringZ) // t799: zoom keyed to the original ring, not the nearer default
            val injected="<script>"+com.overhand.app.windows.WebGuard.SCRIPT+com.overhand.app.windows.Theme.webScript()+"window.AF_WINDOW="+JSONObject.quote(sp.view)+
              ";window.AF_PROJECT="+JSONObject.quote(s.layout.stationProject)+";window.AF_ZOOM="+(3.5f*distance/1.1f)+";"+script+"</script>"
            val head=Regex("<head[^>]*>",RegexOption.IGNORE_CASE).find(html)
              ?: throw IllegalStateException("Missing document head")
            val source=html.substring(0,head.range.last+1)+injected+html.substring(head.range.last+1)
            WebResourceResponse("text/html","UTF-8",source.byteInputStream())
          } finally { connection.disconnect() }
        } catch(e: Exception) { Log.w(TAG,"dashboard fetch failed",e); null }
      }
      override fun onPageStarted(view: WebView, url: String, icon: Bitmap?) {
        p.loaded=false; web.visibility=View.INVISIBLE; status.text="Connecting"; status.setTextColor(Color.WHITE)
      }
      override fun onPageFinished(view: WebView, url: String) {
        // A place may change while the document is in flight.
        if(com.overhand.app.windows.Theme.ENABLED) view.evaluateJavascript(com.overhand.app.windows.Theme.webRefreshScript(),null)
        view.evaluateJavascript("Boolean(window.afWindowAudit && document.documentElement.dataset.afWindow && window.AF_PROJECT==="+
          JSONObject.quote(s.layout.stationProject)+")") { result ->
          if(result!="true") {
            status.text="Unable to prepare dashboard"; status.setTextColor(Color.RED); return@evaluateJavascript
          }
          p.loaded=true; web.visibility=View.VISIBLE
          if(sp.id==active) p.still.visibility=View.GONE
          checkConnection(status)
          if(sp.id!=active) s.ui.postDelayed({ if(!s.stopped && sp.id!=active) freeze(p) },600)
          Log.i(TAG,"window ready ${sp.view}")
        }
      }
    }
    if(sp.id==active) web.loadUrl(s.server()+FirstMinutes.DASHBOARD_PATH) else status.text="Paused - look here to open"
  }
  fun freeze(p: WebPanel) {
    if(!p.loaded || p.web.width==0 || p.still.visibility==View.VISIBLE) return
    p.web.evaluateJavascript("(()=>{if(!document.querySelector('#fleetN')?.textContent || (window.AF_WINDOW==='chat' && !window.afChatReady))return false;window.afFreeze?.();return true})()") {
      if(it!="true" || p.spec.id==active || s.stopped) return@evaluateJavascript
      val image=Bitmap.createBitmap(p.web.width,p.web.height,Bitmap.Config.RGB_565)
      p.web.draw(Canvas(image)); p.still.setImageBitmap(image)
      p.bitmap?.recycle(); p.bitmap=image
      p.still.visibility=View.VISIBLE; p.web.visibility=View.INVISIBLE; p.web.onPause()
      p.status.text="Paused · look here or select to resume"
    }
  }
  /** UI thread, once per place change; paused windows also need a fresh visible still. */
  fun refreshTheme() {
    if(!com.overhand.app.windows.Theme.ENABLED) return
    val script=com.overhand.app.windows.Theme.webRefreshScript()
    panels.values.filter { it.loaded }.forEach { p ->
      p.web.evaluateJavascript(script) {
        if(p.still.visibility==View.VISIBLE) p.web.postVisualStateCallback(0,object : WebView.VisualStateCallback() {
          override fun onComplete(requestId: Long) {
            if(s.stopped || panels[p.spec.id]!==p || p.still.visibility!=View.VISIBLE || p.web.width==0 || p.web.height==0) return
            val image=Bitmap.createBitmap(p.web.width,p.web.height,Bitmap.Config.RGB_565)
            p.web.draw(Canvas(image));p.still.setImageBitmap(image)
            p.bitmap?.recycle();p.bitmap=image
          }
        })
      }
    }
  }
  fun activate(id: Int) {
    if(active!=id) s.audio.earcons.window(id)
    if(s.isRecorded()) { active=id;if(id==R.id.code_panel) s.recordedWindows.openCodeFor("",null,"dock");s.recordedWindows.refresh();return }
    if(s.stopped || active==id) return
    active=id
    panels.values.forEach { p ->
      if(p.spec.id==id) {
        p.web.onResume(); p.web.visibility=View.INVISIBLE
        // Keep frozen still over navigation until freshly scrubbed content is ready.
        p.web.loadUrl(s.server()+FirstMinutes.DASHBOARD_PATH)
      } else freeze(p)
    }
    Log.i(TAG,"active window $id")
  }
  fun checkConnection(status: TextView) {
    if(s.isRecorded()) return
    val token=s.generation.token
    val address=s.server()
    Thread {
      val good=runCatching {
        val c=URL("$address/api/state").openConnection() as HttpURLConnection
        c.connectTimeout=3000; c.readTimeout=3000
        try { c.responseCode==200 && JSONObject(c.inputStream.bufferedReader().use{it.readText()}).has("projects") }
        finally { c.disconnect() }
      }.getOrDefault(false)
      s.ui.post { if(!s.stopped && s.generation.accepts(token)) {
        val paused=panels.values.any { it.status===status && it.still.visibility==View.VISIBLE }
        status.text=(if(good) "Connected" else "Server unavailable")+(if(paused) " · paused" else "")
        status.setTextColor(if(good) Color.GREEN else Color.RED) } }
    }.apply { name="server-check"; isDaemon=true; start() }
  }
}
