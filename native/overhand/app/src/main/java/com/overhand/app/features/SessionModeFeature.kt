package com.overhand.app.features

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.overhand.app.BuildConfig
import com.overhand.app.data.LiveSource
import com.overhand.app.data.RecordedSource
import com.overhand.app.features.Shell.Companion.USB
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Live/Recorded session mode: debug prep request, mode restarts, Settings server rows. */
class SessionModeFeature(private val s: Shell) : Feature {
  override val name = "session"
  private var requested: String?=null
  private var connectionAttempt=0L
  private var connectionStatus: TextView?=null

  /** Explicit prep request only, debug builds only. Always begin safely in Recorded. */
  override fun onPreCreate() {
    requested=if(BuildConfig.DEBUG) s.activity.intent.getStringExtra("session_mode") else null
    if(requested!=null) s.prefs.edit().putString("session-mode","recorded").commit()
  }
  override fun onCreate() {
    val requested=requested ?: return
    s.activity.intent.removeExtra("session_mode")
    Thread {
      val live=requested=="live" && runCatching {
        val c=URL("$USB/api/state").openConnection() as HttpURLConnection
        c.connectTimeout=5000;c.readTimeout=5000
        try {
          c.responseCode==200 && JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            .optJSONArray("projects")!=null
        } finally { c.disconnect() }
      }.getOrDefault(false)
      s.ui.post {
        if(!s.stopped) {
          if(live) s.prefs.edit().putString("server",USB).putString("session-mode","live").commit()
          val result=JSONObject().put("mode",if(live) "live" else "recorded")
            .put("validated",live).put("requested",requested)
          s.activity.openFileOutput("session_mode.json",android.content.Context.MODE_PRIVATE).use { it.write(result.toString().toByteArray()) }
          if(live) s.activity.recreate()
        }
      }
    }.apply { name="session-prep-check";isDaemon=true;start() }
  }

  fun restartSession(mode: String) {
    s.generation.advance()
    s.interaction.decisions.close();s.windows.stations.close();s.interaction.architecture.close()
    s.dashboards.panels.values.forEach { it.web.stopLoading();it.web.loadUrl("about:blank");it.web.onPause() }
    s.prefs.edit().putString("session-mode",mode).commit()
    s.activity.recreate()
  }

  override fun onDebugIntent(intent: Intent) {
    intent.getStringExtra("windows_connect")?.let { address ->
      s.layout.roots[com.overhand.app.R.id.settings_panel]?.findViewWithTag<LinearLayout>("settings-content")?.let { root ->
        val children=(0 until root.childCount).map { root.getChildAt(it) }
        children.filterIsInstance<EditText>().first().setText(address)
        children.filterIsInstance<Button>().first { it.text=="Connect to live server" }.performClick()
      }
    }
  }

  override fun settingsRows(): List<SettingsRow> {
    var status: TextView?=null
    return listOf(SettingsRow(40,SettingsTab.LIVE) { root, _ ->
      val url=EditText(root.context).apply { setText(s.server()); setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(28,38,50))
        textSize=32f; inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        setSingleLine(true) }
      root.addView(url)
      val label=connectionStatus ?: s.label(root,if(s.isRecorded()) "Recorded snapshot \u00b7 ${s.recordedSource.dayLabel}" else "Live server",21f)
        .apply { tag="session-connection-status" }.also { connectionStatus=it }
      (label.parent as? android.view.ViewGroup)?.removeView(label)
      status=label
      settingsTab(root,SettingsTab.DEBUG)
      if(DeveloperSettings.serverPresets.isNotEmpty()) {
        val target=targetPx(root)
        val row=LinearLayout(root.context)
        DeveloperSettings.serverPresets.forEach { (name,address) ->
          row.addView(Button(root.context).apply { text=name; textSize=32f; minHeight=target
            setTextColor(Color.WHITE); backgroundTintList=android.content.res.ColorStateList.valueOf(Color.rgb(38,53,70))
            setOnClickListener { url.setText(address) } },LinearLayout.LayoutParams(0,target,1f))
        }
        root.addView(row)
      }
      settingsTab(root,SettingsTab.LIVE)
      settingsButton(root,"Connect to live server") {
        val value=url.text.toString().trim().trimEnd('/')
        val uri=Uri.parse(value)
        if(uri.scheme !in listOf("http","https") || uri.host.isNullOrBlank() || uri.userInfo!=null ||
            !uri.query.isNullOrEmpty() || !uri.fragment.isNullOrEmpty() || !uri.path.isNullOrEmpty()) {
          label.text="Use a server base URL"; label.setTextColor(Color.RED)
        } else {
          val token=s.generation.token
          val attempt=++connectionAttempt
          label.text="Validating live server…"
          Thread {
            val result=runCatching { LiveSource.at(value).validate() }
            s.ui.post {
              if(!s.stopped && s.generation.accepts(token) && attempt==connectionAttempt) {
                result.fold({
                  s.prefs.edit().putString("server",value).commit()
                  restartSession("live")
                }, { label.text="Server unavailable. Current mode retained." })
              }
            }
          }.apply { name="live-validation";isDaemon=true;start() }

        }
      }
      // Which recording Recorded mode plays: the recorded day or a demo history (#759). In-panel chips, never a picker.
      // #1008: then "Build from nothing", the real host run replayed through the live path; choosing it (again) plays it.
      val build=com.overhand.app.hostlink.recorded.RecordedBuildBundle.let { b -> if(s.isRecorded() && s.recordedBuild.bundle!=null) listOf(b.KEY to b.LIST_NAME) else emptyList() }
      val sets=RecordedSource.datasets { path -> s.activity.assets.open(path).bufferedReader().use { it.readText() } }+build
      settingsTab(root,SettingsTab.RUN)
      root.addView(s.label(root,"Recording to play",28f))
      val chosen=sets.indexOfFirst { it.first==s.recordedSource.dataset }.coerceAtLeast(0)
      root.addView(com.overhand.app.windows.ChipRow(root.context,sets.map { it.second },chosen) { i ->
        if(sets[i].first==com.overhand.app.hostlink.recorded.RecordedBuildBundle.KEY) { s.recordedBuild.start();return@ChipRow }
        s.prefs.edit().putString("recorded-dataset",sets[i].first).commit()
        restartSession("recorded")
      }.view.apply { tag="recording-chips" })
      settingsButton(root,"Restart recorded run") {
        runCatching { s.recordedSource.restart() }.fold({
          restartSession("recorded")
        }, { label.text="Could not clear practice answers. Current run retained." })
      }
      settingsButton(root,"Recorded run") {
        restartSession("recorded")
      }
      root.addView(label)
      settingsButton(root,"Check connection") { if(!s.isRecorded()) s.dashboards.checkConnection(label) else label.text="Recorded snapshot · no server connection" }
    }, SettingsRow(90,SettingsTab.RUN) { _, _ ->
      // Last, as before the split: the first connection check starts once Settings is built.
      if(!s.isRecorded()) status?.let { s.dashboards.checkConnection(it) }
    })
  }
}
