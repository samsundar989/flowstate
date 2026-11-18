@file:OptIn(com.meta.spatial.core.SpatialSDKExperimentalAPI::class)
package com.overhand.app.features

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import com.meta.spatial.core.*
import com.meta.spatial.isdk.*
import com.meta.spatial.toolkit.*
import com.overhand.app.BuildConfig
import com.overhand.app.R
import com.overhand.app.features.Shell.Companion.TAG
import com.overhand.app.features.WindowLayout.Companion.PANEL_H_DP
import com.overhand.app.features.WindowLayout.Companion.PANEL_W_DP

/** Explicit debug probes preserve the original matched ladder without changing normal launches. */
class LadderProbe(private val s: Shell) {
  val step: Int?
    get() = if (BuildConfig.DEBUG && s.activity.intent.hasExtra("ladder_step"))
      s.activity.intent.getIntExtra("ladder_step", 4) else null
  private val panelCount: Int
    get() = when (step) { 1 -> 0; 2, 3 -> 1; else -> 2 }

  fun registrations(): List<PanelRegistration> = listOf(
    webPanel(R.id.fleet_panel, "fleet"),
    webPanel(R.id.blueprint_panel, "blueprint"),
  ).take(panelCount)

  /** Returns true when this launch is a ladder probe (the normal scene is then skipped). */
  fun onSceneReady(): Boolean {
    val ladderStep=step ?: return false
    s.activity.scene.setViewOrigin(0f, 0f, 0f, 0f)

    Log.i(TAG, "ladder step=$ladderStep panels=$panelCount display=" +
        if (ladderStep == 6) "960x600 explicit-layer" else "1280x800 SDK-default-layer")
    if (panelCount >= 1) window(R.id.fleet_panel, Vector3(0f, 1.3f, 1.1f), yawDeg = 0f)
    if (panelCount >= 2) window(R.id.blueprint_panel, Vector3(1.05f, 1.3f, 0.75f), yawDeg = 50f)
    return true
  }

  private fun window(id: Int, at: Vector3, yawDeg: Float) {
    Entity.create(
        listOf(
            Panel(id),
            Transform(Pose(at, Quaternion.fromEuler(0f, yawDeg, 0f))),
        ),
    )
  }

  @SuppressLint("SetJavaScriptEnabled")
  private fun webPanel(id: Int, view: String): PanelRegistration =
      LayoutXMLPanelRegistration(
          id,
          layoutIdCreator = { R.layout.web_panel },
          settingsCreator = {
            UIPanelSettings(
                shape = QuadShapeOptions(width = PANEL_W_M, height = PANEL_H_M),
                display = DpDisplayOptions(
                    width = if (step == 6) 960f else PANEL_W_DP,
                    height = if (step == 6) 600f else PANEL_H_DP),
                // 0.15.0 already defaults to Layer. Step 5 explicitly repeats that control;
                // step 8 is an optional mesh diagnostic, not the original spike.
                rendering = when {
                  step == 8 -> UIPanelRenderOptions(PanelRenderMode.Mesh())
                  (step ?: 4) >= 5 -> UIPanelRenderOptions(PanelRenderMode.Layer())
                  else -> UIPanelRenderOptions()
                },
            )
          },
          panelSetupWithRootView = { rootView, _, _ ->
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
            val web = WebView(rootView.context)
            rootView.findViewById<LinearLayout>(R.id.panel_root).addView(
                web, LinearLayout.LayoutParams(-1, -1))
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.webChromeClient = com.overhand.app.windows.WebGuard.client { m -> Log.i(TAG, "page message in $view panel: $m") }
            web.webViewClient =
                object : WebViewClient() {
                  override fun onPageFinished(v: WebView, url: String) {
                    Log.i(TAG, "page finished in $view panel: $url")
                    // The dashboard keeps its view in localStorage (shared by both WebViews),
                    // so pick each window's view by clicking its switcher button.
                    v.evaluateJavascript(
                        "(function(){var b=document.querySelector('[data-view=\"$view\"]');" +
                            "if(b){b.click();return 'ok'}return 'missing'})()"
                    ) { r -> Log.i(TAG, "$view view switch: $r") }
                  }
                }
            Log.i(TAG, "loading $DASHBOARD_URL into $view panel")
            if (step == 2) {
              web.loadDataWithBaseURL("https://static.overhand.invalid/",
                  "<html><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                      "<body style='background:#161b22;color:white;font:13px sans-serif'>" +
                      "<p>Flowstate static panel. One paragraph, no scripts, no network, no timers.</p></body></html>",
                  "text/html", "UTF-8", null)
            } else if(!s.isRecorded()) web.loadUrl(DASHBOARD_URL)
          },
      )

  companion object {
    private const val DASHBOARD_URL = "http://127.0.0.1:8790/"
    const val PANEL_W_M = 1.2f
    const val PANEL_H_M = 0.75f
  }
}
