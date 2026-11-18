package com.overhand.app.windows

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.overhand.app.hostlink.project.PreviewFiles
import com.overhand.app.hostlink.project.PreviewFetch
import com.overhand.app.hostlink.project.PreviewModel
import kotlin.math.roundToInt

/**
 * #973: the Preview window. The app the agents just committed runs in a 1280×800 dp WebView; under it, the commit's
 * receipt line and one status line. The page gets JS but no network, no files and no navigation. #977: it runs at the
 * fake origin [PreviewFiles.ORIGIN], and a request for one of the app's own files there is answered with that file at
 * the shown commit ([PreviewFiles]); every other request gets an empty 403 here. Its alerts/pickers go through [WebGuard]. Views change only on events
 * (a new commit, a chunk, a refusal), never per frame. Tags: `preview-web`, `preview-receipt`, `preview-status`.
 */
object PreviewPanelView {
  const val WEB_W_DP=1280f; const val WEB_H_DP=800f
  const val W_DP=WEB_W_DP; const val H_DP=WEB_H_DP+168f
  /** 1250 dp per metre, like the project window. */
  const val W_M=W_DP/1250f; const val H_M=H_DP/1250f; const val DIST_M=1.0f
  const val VIEW="preview"
  private val BG=Color.rgb(16,22,30); private val MUTED=Color.rgb(200,210,220); private val BAD=Color.rgb(255,170,150)

  class Parts(val web: View,val receipt: TextView,val status: TextView)

  /** Lays out [column]: [web] at 1280×800 dp on top, the receipt and status lines under it. */
  fun build(column: LinearLayout,web: View): Parts {
    val ctx=column.context;val dp=ctx.resources.displayMetrics.density
    fun px(v: Float)=(v*dp).roundToInt()
    column.removeAllViews()
    column.orientation=LinearLayout.VERTICAL;column.setBackgroundColor(BG);column.tag="preview-panel"
    val frame=FrameLayout(ctx)
    (web.parent as? android.view.ViewGroup)?.removeView(web)
    web.tag="preview-web";web.setBackgroundColor(Color.WHITE)
    frame.addView(web,FrameLayout.LayoutParams(-1,-1))
    column.addView(frame,LinearLayout.LayoutParams(px(WEB_W_DP),px(WEB_H_DP)))
    fun line(tag: String,size: Float,color: Int)=TextView(ctx).apply { this.tag=tag;textSize=size;setTextColor(color);maxLines=1
      ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(px(32f),0,px(32f),0) }
    val receipt=line("preview-receipt",32f,Color.WHITE)
    val status=line("preview-status",28f,MUTED)
    column.addView(receipt,LinearLayout.LayoutParams(-1,-2).apply { topMargin=px(24f) })
    column.addView(status,LinearLayout.LayoutParams(-1,-2).apply { topMargin=px(16f) })
    return Parts(web,receipt,status)
  }

  /** Shows [s]; the web view stays visible with the last good app while a new one loads or is refused. */
  fun show(p: Parts,s: PreviewModel.State,receipt: String,hasApp: Boolean) {
    p.receipt.text=receipt.ifBlank { "No commit yet" }
    p.status.text=PreviewModel.status(s)
    p.status.setTextColor(if(s is PreviewModel.State.Refused) BAD else MUTED)
    p.web.visibility=if(hasApp) View.VISIBLE else View.INVISIBLE
  }

  /** The page as loaded: [WebGuard.SCRIPT] and a tap counter run before the app's own scripts. */
  fun document(html: String): String {
    val guard="<script>"+WebGuard.SCRIPT+TAP_COUNTER+"</script>"
    val head=Regex("<head[^>]*>",RegexOption.IGNORE_CASE).find(html)
    return if(head!=null) html.substring(0,head.range.last+1)+guard+html.substring(head.range.last+1)
    else Regex("<html[^>]*>",RegexOption.IGNORE_CASE).find(html)?.let { html.substring(0,it.range.last+1)+"<head>"+guard+"</head>"+html.substring(it.range.last+1) }
      ?: (guard+html)
  }
  /** Counts the taps the page itself received (`window.__afTaps`), for the device scripts' `preview_tap`. */
  const val TAP_COUNTER="window.__afTaps=0;window.__afLastTap='';document.addEventListener('click',function(e){window.__afTaps++;" +
    "var t=e.target;window.__afLastTap=(t&&(t.id||t.tagName))||'';},true);"

  /** A WebView for the preview: JS on, no network, no file or content access, no navigation away. */
  @SuppressLint("SetJavaScriptEnabled")
  fun webView(ctx: Context,files: () -> PreviewFiles?={ null },message: (String) -> Unit): WebView=WebView(ctx).apply {
    settings.javaScriptEnabled=true
    settings.domStorageEnabled=true
    settings.allowFileAccess=false
    settings.allowContentAccess=false
    settings.blockNetworkLoads=true
    settings.blockNetworkImage=true
    settings.setGeolocationEnabled(false)
    settings.javaScriptCanOpenWindowsAutomatically=false
    isFocusable=true;isFocusableInTouchMode=true
    webChromeClient=WebGuard.client(message)
    webViewClient=object: WebViewClient() {
      override fun shouldOverrideUrlLoading(view: WebView,request: WebResourceRequest)=true
      override fun shouldInterceptRequest(view: WebView,request: WebResourceRequest): WebResourceResponse? {
        // #1009: loadDataWithBaseURL's own document arrives here as a main-frame data: request; a 403 on it is a blank page
        if(request.isForMainFrame && request.url.scheme=="data") return null
        return files()?.takeIf { request.method.equals("GET",true) }?.let { respond(it.serve(request.url.toString())) } ?: blocked()
      }
    }
  }
  /** [PreviewFiles]' answer as the WebView takes it. */
  fun respond(s: PreviewFiles.Served): WebResourceResponse=
    if(s.status!=200) blocked()
    else WebResourceResponse(s.mime,if(PreviewFiles.isText(s.mime)) "utf-8" else null,200,"OK",
      mapOf("Cache-Control" to "no-store","Content-Length" to s.bytes.size.toString()),s.bytes.inputStream())
  fun blocked()=WebResourceResponse("text/plain","utf-8",403,"Blocked",emptyMap(),"".byteInputStream())

  /** Loads [html] at [PreviewFiles.ORIGIN], so its relative references (style.css, app.js, images) resolve there. */
  fun load(web: WebView,html: String) { web.loadDataWithBaseURL(PreviewFiles.ORIGIN,document(html),"text/html","utf-8",null) }
  /**
   * #1009: loads [shown]'s page as committed, not [PreviewModel.State.Ready.html] (inlined, which drops `defer` from
   * `<script src defer>` so the app runs before its body exists); its style.css/app.js then come through the intercept.
   */
  fun load(web: WebView,shown: PreviewModel.State.Ready) =
    load(web,shown.files.values.firstOrNull { PreviewFetch.sha256Hex(it)==shown.sha256 }?.toString(Charsets.UTF_8) ?: shown.html)
}
