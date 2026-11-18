package com.overhand.app.windows

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.LinearLayout
import com.overhand.app.hostlink.project.PreviewFiles
import com.overhand.app.hostlink.project.PreviewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #973: the Preview window's WebView: the committed page loads with no base URL, JS on, no network, files or
 * navigation; a pinch or ray click (a DOWN/UP pair delivered to the panel's root, as the panel input does) reaches it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w1600dp-h1000dp-288dpi")
class PreviewPanelViewTest {
  private val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
  private fun laidOut(column: LinearLayout) {
    val d=activity.resources.displayMetrics.density
    val w=(PreviewPanelView.W_DP*d).toInt();val h=(PreviewPanelView.H_DP*d).toInt()
    column.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY))
    column.layout(0,0,w,h)
  }

  @Test fun pageLoadsAtThePreviewOriginAndNothingElseToReach() {
    val web=PreviewPanelView.webView(activity) {}
    val s=web.settings
    assertTrue(s.javaScriptEnabled);assertFalse(s.allowFileAccess);assertFalse(s.allowContentAccess)
    assertTrue(s.blockNetworkLoads)
    val html="<!doctype html><html><head><title>t</title></head><body><button id=b>Plus</button><script>var x=1</script></body></html>"
    PreviewPanelView.load(web,html)
    val loaded=shadowOf(web).lastLoadDataWithBaseURL
    assertEquals(PreviewFiles.ORIGIN,loaded.baseUrl);assertEquals("text/html",loaded.mimeType);assertEquals("utf-8",loaded.encoding);assertNull(loaded.historyUrl)
    // the guard runs before the app's own scripts, inside its head, and the app is otherwise unchanged
    assertTrue(loaded.data.startsWith("<!doctype html><html><head><script>"+WebGuard.SCRIPT))
    assertEquals(html,loaded.data.replace("<script>"+WebGuard.SCRIPT+PreviewPanelView.TAP_COUNTER+"</script>",""))
    // every request the page makes is refused here, and it cannot navigate away
    val client=shadowOf(web).webViewClient
    val req=object: WebResourceRequest {
      override fun getUrl()=Uri.parse("https://example.invalid/x.js"); override fun isForMainFrame()=false
      override fun isRedirect()=false; override fun hasGesture()=false; override fun getMethod()="GET"
      override fun getRequestHeaders()=emptyMap<String,String>()
    }
    assertEquals(403,client.shouldInterceptRequest(web,req)!!.statusCode)
    assertTrue(client.shouldOverrideUrlLoading(web,req))
  }

  private fun get(url: String): WebResourceRequest=request(url,"GET")
  private fun request(url: String,method: String): WebResourceRequest=object: WebResourceRequest {
    override fun getUrl()=Uri.parse(url); override fun isForMainFrame()=false
    override fun isRedirect()=false; override fun hasGesture()=false; override fun getMethod()=method
    override fun getRequestHeaders()=emptyMap<String,String>()
  }

  /** #977: the WebView's own request path: style.css and app.js come back 200 with their bytes and type; the rest 403. */
  @Test fun theWebViewGetsTheAppsOwnFilesFromTheShownCommit() {
    val css="body{color:#fff}".toByteArray();val js="document.title='x'".toByteArray()
    val files=PreviewFiles("p_web",{ error("all cached: nothing is fetched") })
    files.show("c1",mapOf("style.css" to css,"app.js" to js))
    val web=PreviewPanelView.webView(activity,{ files }) {}
    val client=shadowOf(web).webViewClient
    val c=client.shouldInterceptRequest(web,get(PreviewFiles.ORIGIN+"style.css"))!!
    assertEquals(200,c.statusCode);assertEquals("text/css",c.mimeType);assertEquals("utf-8",c.encoding)
    assertTrue(css.contentEquals(c.data.readBytes()))
    val j=client.shouldInterceptRequest(web,get(PreviewFiles.ORIGIN+"app.js"))!!
    assertEquals(200,j.statusCode);assertEquals("text/javascript",j.mimeType);assertTrue(js.contentEquals(j.data.readBytes()))
    for(u in listOf("https://example.com/style.css","file:///sdcard/style.css","data:text/css,b{}",PreviewFiles.ORIGIN+"style.css?v=1"))
      assertEquals(u,403,client.shouldInterceptRequest(web,get(u))!!.statusCode)
    assertEquals("only GET",403,client.shouldInterceptRequest(web,request(PreviewFiles.ORIGIN+"style.css","POST"))!!.statusCode)
  }

  @Test fun aNewCommitReloadsTheWebView() {
    val web=PreviewPanelView.webView(activity) {}
    PreviewPanelView.load(web,"<html><body>one</body></html>")
    PreviewPanelView.load(web,"<html><body>two</body></html>")
    assertTrue(shadowOf(web).lastLoadDataWithBaseURL.data.contains("two"))
  }

  /** A WebView that records what reaches it, at which point of its own area. */
  class RecordingWeb(ctx: Context): WebView(ctx) {
    val got=mutableListOf<Triple<Int,Float,Float>>()
    override fun dispatchTouchEvent(e: MotionEvent): Boolean { got.add(Triple(e.actionMasked,e.x,e.y));super.dispatchTouchEvent(e);return true }
    /** Robolectric's WebView provider measures nothing; on a device it takes its exact slot, as here. */
    override fun onMeasure(w: Int,h: Int) { setMeasuredDimension(MeasureSpec.getSize(w),MeasureSpec.getSize(h)) }
    /** WebView hands its frame to the provider (hidden setFrame); Robolectric's sets none, a device's takes it. */
    override fun onLayout(changed: Boolean,l: Int,t: Int,r: Int,b: Int) { super.onLayout(changed,l,t,r,b);left=l;top=t;right=r;bottom=b }
  }

  @Test fun pinchAndRayClicksReachTheWebViewAsTaps() {
    val column=LinearLayout(activity)
    val web=RecordingWeb(activity)
    val parts=PreviewPanelView.build(column,web)
    PreviewPanelView.show(parts,PreviewModel.State.Ready("c1","<html></html>","00"),"Commit c1: Add a counter",true)
    laidOut(column)
    val d=activity.resources.displayMetrics.density
    assertEquals((PreviewPanelView.WEB_W_DP*d).toInt(),web.width);assertEquals((PreviewPanelView.WEB_H_DP*d).toInt(),web.height)
    // a tap at (640, 400) dp of the app, as the panel delivers it to its root view
    val t=SystemClock.uptimeMillis()
    for((dt,a) in listOf(0L to MotionEvent.ACTION_DOWN,80L to MotionEvent.ACTION_UP))
      MotionEvent.obtain(t,t+dt,a,640*d,400*d,0).also { column.dispatchTouchEvent(it);it.recycle() }
    assertEquals(listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP),web.got.map { it.first })
    assertEquals(640*d,web.got[0].second,0.5f);assertEquals(400*d,web.got[0].third,0.5f)
    // the receipt line under the panel does not take the app's taps
    assertEquals("Commit c1: Add a counter",parts.receipt.text.toString());assertEquals("Pinch to play",parts.status.text.toString())
    assertTrue(parts.receipt.top>=web.bottom)
  }

  @Test fun loadingAndRefusedKeepTheLastAppAndSayWhy() {
    val column=LinearLayout(activity);val parts=PreviewPanelView.build(column,View(activity))
    PreviewPanelView.show(parts,PreviewModel.State.Loading("c2",0,0),"Commit c2: Rename",false)
    assertEquals(View.INVISIBLE,parts.web.visibility);assertTrue(parts.status.text.startsWith("Loading"))
    PreviewPanelView.show(parts,PreviewModel.State.Refused("c2","the check did not match (got 1234abcd)"),"Commit c2: Rename",true)
    assertEquals(View.VISIBLE,parts.web.visibility)
    assertEquals("Not shown: the check did not match (got 1234abcd)",parts.status.text.toString())
  }
}
