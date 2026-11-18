package com.overhand.app.windows

import android.annotation.SuppressLint
import android.graphics.Color
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import com.overhand.app.features.Shell.Companion.TAG
import org.json.JSONArray
import org.json.JSONObject

/**
 * #1379 look K8: the main window ("chat") opens on the briefing "While you were away" drawn by windows.js's `look K8`
 * block (look_nov17 s4). The paged chat list [RecordedWindows] builds stays in the panel, one press away behind
 * "History"; a "Briefing" button under it comes back. The page is created once and only re-rendered.
 */
object LookK8 {
  const val VIEW="chat"
  /** Open paths, set by the shell ([com.overhand.app.features.Dashboards]): needs = the question card on the first
   *  waiting question, building = Fleet, done = the Yard window's list of finished work. */
  class Paths(val needs: () -> Unit,val building: () -> Unit,val done: () -> Unit)
  @Volatile var paths: Paths?=null

  /** What each row opens, as logged: the question card, Fleet, the Yard window's list of finished work. */
  val TARGET=mapOf("needs" to "decision","building" to "fleet","done" to "yard")
  const val WEB_TAG="look-k8-briefing"
  const val BACK_TAG="look-k8-back"
  /** One press: logs `briefing event=open row=<row> target=<decision|fleet|yard>` and calls that row's path. True when a path was called. */
  fun route(row: String,p: Paths?): Boolean {
    val go=when(row) { "needs" -> p?.needs; "building" -> p?.building; "done" -> p?.done; else -> return false }
    Log.i(TAG,"briefing event=open row=$row target=${TARGET.getValue(row)}")
    go?.invoke()
    return go!=null
  }
  /** #1389: the Needs you path. Opens the question card and, once its list is loaded, puts it on the first waiting
   *  question (the list is in judge order). True when a question was put on the card. */
  fun raise(open: () -> Unit,ids: () -> List<String>?,show: (String) -> Boolean): Boolean {
    open()
    return ids()?.firstOrNull()?.let(show) ?: false
  }

  /** The recorded day's done/error events only (the briefing counts `done`); keeps the page small. */
  fun outcomes(events: List<JSONObject>)=JSONArray().also { a ->
    events.filter { it.optString("kind")=="done" || it.optString("kind")=="error" }.forEach { a.put(JSONObject().put("kind",it.optString("kind"))) } }

  @SuppressLint("SetJavaScriptEnabled","AddJavascriptInterface")
  fun mount(root: LinearLayout,shell: String,content: RecordedContent): (() -> Unit)? = runCatching {
    val list=(0 until root.childCount).map { root.getChildAt(it) }.filter { it.visibility!=View.GONE }
    val web=WebView(root.context).apply { tag=WEB_TAG }
    val back=Button(root.context).apply { text="Briefing";tag=BACK_TAG;visibility=View.GONE }
    fun history(on: Boolean) {
      list.forEach { it.visibility=if(on) View.VISIBLE else View.GONE }
      web.visibility=if(on) View.GONE else View.VISIBLE;back.visibility=if(on) View.VISIBLE else View.GONE
    }
    web.setBackgroundColor(Color.TRANSPARENT)
    web.settings.javaScriptEnabled=true
    web.addJavascriptInterface(object {
      @JavascriptInterface fun act(name: String,row: String) { web.post {
        if(name=="history") { Log.i(TAG,"briefing event=history");history(true) } else route(row,paths)
      } }
    },"AfK8")
    back.setOnClickListener { Log.i(TAG,"briefing event=back");history(false);Log.i(TAG,"briefing event=shown why=back") }
    val data=JSONObject().put("snapshot",content.source.snapshot).put("architecture",content.source.architecture)
      .put("decisions",content.source.decisions()).put("events",outcomes(content.source.events))
    val html="<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=1280\">"+
      "<script>window.AF_WINDOW='$VIEW';window.AF_ZOOM=1;window.AF_K8="+data.toString().replace("</","<\\/")+";</script>"+
      "<script>"+shell.replace("</script","<\\/script")+"</script></head><body></body></html>"
    root.addView(web,LinearLayout.LayoutParams(-1,0,1f))
    root.addView(back,LinearLayout.LayoutParams(-1,-2))
    history(false)
    Log.i(TAG,"briefing event=shown why=mount")
    web.loadDataWithBaseURL("file:///android_asset/",html,"text/html","utf-8",null)
    // A practice answer changes the open questions: re-count them.
    val update: () -> Unit={ web.evaluateJavascript("window.afLookK8&&afLookK8.update&&afLookK8.update(${content.source.decisions()})",null) }
    update
  }.onFailure { Log.w(TAG,"look_k8 event=mount_failed",it) }.getOrNull()
}
