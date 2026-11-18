package com.overhand.app.windows

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import com.overhand.app.features.Shell.Companion.TAG
import org.json.JSONObject

/**
 * #1374 look K3: the recorded day's Fleet and Blueprint drawn by windows.js's `look K3` block (look_nov17 s5). The text
 * page [RecordedWindows] builds stays in the panel, hidden. The page is created once per panel and only re-rendered.
 * #1382: every action reaches a real path ([Hooks]): a Fleet row selects its project and shows Blueprint
 * (WindowsFeature.open); Code shows the Code window at that part (RecordedWindows.openCodeFor, why=blueprint); Ask locks
 * the part in SiteTalk and starts listening (SiteScene.askPart, the Yard layer card's path); the round close button
 * closes the window (WindowsFeature.dockToggle).
 */
object LookK3 {
  val VIEWS=setOf("fleet","blueprint")

  /** The real paths behind the K3 buttons. */
  interface Hooks {
    fun select(project: String)
    fun show(view: String)
    fun close(view: String)
    fun code(project: String,part: String)
    fun ask(project: String,part: String,name: String,status: String)
  }

  /** [w]'s hooks: selection through its refreshers, windows and Ask through what Shell set on it. */
  fun hooks(w: RecordedWindows)=object : Hooks {
    override fun select(project: String)=w.refresh(project)
    override fun show(view: String)=w.showWindow(view,"look_k3")
    override fun close(view: String)=w.closeWindow(view,"look_k3_close")
    override fun code(project: String,part: String) { w.showWindow("code","blueprint");w.openCodeFor(project,part,"blueprint") }
    override fun ask(project: String,part: String,name: String,status: String)=w.askPart(project,part,name,status)
  }

  @SuppressLint("SetJavaScriptEnabled","AddJavascriptInterface")
  fun mount(root: LinearLayout,view: String,shell: String,content: RecordedContent,project: String,hooks: Hooks): ((String) -> Unit)? = runCatching {
    val began=SystemClock.uptimeMillis()
    var current=project
    val web=WebView(root.context)
    web.setBackgroundColor(Color.TRANSPARENT)
    web.settings.javaScriptEnabled=true
    var mounted=false
    web.webViewClient=object : WebViewClient() {
      override fun onPageFinished(v: WebView?,url: String?) { if(!mounted) { mounted=true
        Log.i(TAG,"look_k3 event=mounted view=$view ms=${SystemClock.uptimeMillis()-began}") } }
    }
    web.addJavascriptInterface(object {
      @JavascriptInterface fun act(name: String,id: String) { web.post { act(view,name,id,current,content,hooks)?.let { Log.i(TAG,it) } } }
    },"AfK3")
    val data=JSONObject().put("view",view).put("project",project)
      .put("snapshot",content.source.snapshot).put("architecture",content.source.architecture)
    val html="<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=1280\">"+
      "<script>window.AF_WINDOW='$view';window.AF_ZOOM=1;window.AF_K3="+data.toString().replace("</","<\\/")+";</script>"+
      "<script>"+shell.replace("</script","<\\/script")+"</script></head><body></body></html>"
    (0 until root.childCount).forEach { root.getChildAt(it).visibility=View.GONE }
    root.addView(web,LinearLayout.LayoutParams(-1,0,1f))
    web.loadDataWithBaseURL("file:///android_asset/",html,"text/html","utf-8",null)
    val show: (String) -> Unit={ id -> if(id.isNotBlank()) current=id
      web.evaluateJavascript("window.afLookK3&&afLookK3.show&&afLookK3.show(${JSONObject.quote(id)})",null) }
    show
  }.onFailure { Log.w(TAG,"look_k3 event=mount_failed view=$view",it) }.getOrNull()

  /**
   * One K3 button press: calls its real path on [hooks] and returns the log line (null: not an action of [view]).
   * `pick` only redraws inside the page, so it is the one action without a hook.
   */
  fun act(view: String,name: String,id: String,project: String,content: RecordedContent,hooks: Hooks): String? {
    if(name=="close") { hooks.close(view);return "$view event=close" }
    if(view=="fleet") {
      if(name!="open_project" || id.isBlank()) return null
      hooks.show("blueprint");hooks.select(id)
      return "fleet event=open_project project=$id then=blueprint"
    }
    if(view!="blueprint") return null
    val c=content.components(project).firstOrNull { it.optString("id")==id }
    when(name) {
      "pick" -> {}
      "code" -> hooks.code(project,id)
      "ask" -> { if(c==null) return null
        hooks.ask(project,id,c.optString("name",id),c.optString("shown",c.optString("status","planned"))) }
      else -> return null
    }
    return "blueprint event=$name part=$id"
  }
}
