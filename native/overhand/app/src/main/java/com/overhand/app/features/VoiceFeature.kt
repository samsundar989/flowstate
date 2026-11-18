@file:OptIn(com.meta.spatial.core.SpatialSDKExperimentalAPI::class)
package com.overhand.app.features

import android.content.Context
import android.content.Intent
import android.util.Log
import com.meta.spatial.core.*
import com.meta.spatial.toolkit.*
import com.overhand.app.BuildConfig
import com.overhand.app.R
import com.overhand.app.hands.SdkHandSource
import com.overhand.app.voice.SlotName
import com.overhand.app.voice.Transcriber
import com.overhand.app.voice.UiActions
import com.overhand.app.voice.UiCommands
import com.overhand.app.voice.Voice
import com.overhand.app.voice.VoiceController
import com.overhand.app.voice.VoiceGate
import com.overhand.app.voice.VoiceIntents
import com.overhand.app.voice.Wav
import com.overhand.app.voice.HttpRelayTransport
import com.overhand.app.voice.RelayClient
import com.overhand.app.voice.RelayState
import com.overhand.app.voice.ScreenSummary
import com.overhand.app.voice.SmartVoice
import com.overhand.app.voice.TtsSpeaker

/**
 * Voice C (task 756), always listening since #1001: while the headset is worn and voice is not muted, ask about the
 * recorded day and hear a pre-rendered answer; a small caption window shows what was heard and answered.
 * The recogniser library is only in -PoverhandVoice builds, so it is reached by reflection; without it, or
 * without the model on the device, nothing here runs.
 */
class VoiceFeature(private val s: Shell) : Feature {
  override val name = "voice"
  private var controller: VoiceController? = null
  private var entity: Entity? = null
  private var hands: SdkHandSource? = null
  private var placed = false
  private var registered = false
  private var listenSwitch: android.widget.CheckBox? = null
  private var observingMute = false
  private val listenPrefs by lazy { s.activity.getSharedPreferences(Voice.PREFS, Context.MODE_PRIVATE) }
  private val muteObserver = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
    if (key == MUTED_PREF) s.ui.post { listenSwitch?.isChecked = listening }
  }

  override fun onSceneReady() {
    // #815: also on in host mode, where a transcript can only start a new project or answer its question.
    val host = s.project.active
    if (!Voice.ENABLED || !BuildConfig.VOICE || !(s.isRecorded() || host)) return
    val a = s.activity
    Thread({
      val ears = runCatching {
        Class.forName("com.overhand.app.voice.Ears").getMethod("create", Context::class.java).invoke(null, a) as Transcriber?
      }.onFailure { Log.w(Voice.TAG, "recogniser failed to load", it) }.getOrNull()
      if (!Voice.on(s.isRecorded() || host, ears != null)) { Log.i(Voice.TAG, "voice off: no recogniser model on this device"); return@Thread }
      val intents = VoiceIntents.parse(a.resources.openRawResource(R.raw.voice_answers).bufferedReader().use { it.readText() })
      val clips = intents.answers.associate { ans ->
        val id = a.resources.getIdentifier("voice_${ans.id}", "raw", a.packageName)
        ans.id to Wav.read(a.resources.openRawResource(id).use { it.readBytes() })
      }
      val rate = clips.values.first().sampleRate
      val own = intents.answers.map { it.text } +
        VoiceGate.ownLines(a.resources.openRawResource(R.raw.first60_cues).bufferedReader().use { it.readText() })
      s.ui.post { if (!s.stopped) start(VoiceController(a, intents, ears!!, { clips.getValue(it).samples }, rate).also { it.ownLines = own }) }
    }, "overhand-voice-load").start()
  }

  private fun start(c: VoiceController) {
    // #862: one controller and one hand system per process. A second start replaces the first instead of adding to it.
    controller?.close()
    controller = c
    val ui = ShellUiActions(s)
    c.commands = UiCommands(ui)
    c.answerSubject = {
      if (c.machine.talking) s.interaction.site.talk.target()?.let { it.part ?: it.displayName } ?: "Answer"
      else if (c.machine.askedOnline) screen(ui).let { it.selected ?: it.project }.ifBlank { "Recorded day" }
      else c.machine.lastMatch?.answer?.id?.replace('_', ' ') ?: "Recorded day"
    }
    // #1032: Smart answers, reached only by a line the always-on gate passes as for the app (#1056).
    // The relay URL is build config; there is no key on the headset.
    if (BuildConfig.RELAY_URL.isNotEmpty()) runCatching {
      c.smart = SmartVoice(RelayClient(HttpRelayTransport(BuildConfig.RELAY_URL), install()), TtsSpeaker(s.activity),
        { screen(ui).text() }, { s.prefs.getBoolean(SMART_PREF, true) }).also { smart = it }
    }.onFailure { Log.w(Voice.TAG, "smart answers off: ${it.javaClass.simpleName}") }
    if (s.project.active) c.routeTo({ t -> s.project.heard(t) }, com.overhand.app.hostlink.project.ProjectScreens.HINT)
    // #1035: talk to an agent on the build site. The switch is read at every aim and every speech start.
    runCatching {
      val site = s.interaction.site
      site.talk.enabled = s.prefs.getBoolean(TALK_PREF, true)
      c.talkSpeaker = smart?.speaker ?: TtsSpeaker(s.activity)
      c.agents = com.overhand.app.graph.site.SiteTalkVoice(site.talk, { site.liveLink }, { smart })
      site.talk.listeners.add { s.ui.post { controller?.refreshHint() } }
    }.onFailure { Log.w(Voice.TAG, "talk to agents off: ${it.javaClass.simpleName}") }
    // A headset that is not being worn (asleep) still reports hands now and then; those pinches are not turns.
    val power = s.activity.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    c.worn = { power.isInteractive }
    // #1001: the caption window stays where it was put (it is grabbable); it is placed once, below the eyes.
    if (!registered) {
      registered = true
      s.activity.registerPanel(ViewPanelRegistration(PANEL, { _, _ -> controller?.view ?: c.view },
        { com.overhand.app.windows.Theme.panel(UIPanelSettings(shape = QuadShapeOptions(width = WIDTH_M, height = HEIGHT_M), display = DpDisplayOptions(width = 1000f, height = 1000f * HEIGHT_M / WIDTH_M))) },
        { _, _, _ -> }))
      s.commands.add {
        entity = Entity.create(listOf(Panel(PANEL))).also { it.windowPose(pose(),"VoiceFeature_create");it.windowVisible(true,"VoiceFeature_create") }
        val source = SdkHandSource(s.activity.scene).also { hands = it }
        s.activity.systemManager.registerSystem(object : SystemBase() {
          var previous = 0L
          override fun execute() {
            val now = android.os.SystemClock.elapsedRealtime()
            if (s.paused || s.stopped || now - previous < 50) return
            previous = now
            source.sample(now)
            controller?.hands(now, source.lastFrame.mapValues { it.value.first })
          }
        })
      }
    }
    Log.i(Voice.TAG, "voice on: ${c.machine.state} host=${s.project.active} live=${VoiceController.live}")
  }

  private var smart: SmartVoice? = null

  /** #1230: the microphone request's answer, so the window says "Mic off" only after a refusal. */
  fun permissionResult(requestCode: Int, grantResults: IntArray) { controller?.permissionResult(requestCode, grantResults) }

  private fun install(): String = s.prefs.getString(INSTALL_PREF, null) ?: java.util.UUID.randomUUID().toString().replace("-", "").take(16)
    .also { s.prefs.edit().putString(INSTALL_PREF, it).apply() }

  /** #1032: the text that goes with a smart question (project, open windows, selected part, the decision card, five events). */
  private fun screen(ui: ShellUiActions): ScreenSummary {
    val project = ui.currentProject()
    val name = if (s.isRecorded()) runCatching { s.recordedContent.projectName(project) }.getOrDefault(project) else project
    val windows = s.layout.specs.map { it.view }.filter { it != SettingsPanel.VIEW && it !in s.windows.voiceHidden }
    val events = if (s.isRecorded()) runCatching { s.recordedContent.currentWork(project).lines().map { it.trim() }.filter { it.isNotEmpty() }.takeLast(5) }
      .getOrDefault(emptyList()) else emptyList()
    return ScreenSummary(if (s.isRecorded()) "recorded day" else "live", name, windows,
      s.interaction.architecture.selectedPart(), if (s.interaction.decisionShowing) "decision card open" else null, null, events)
  }

  /** #1032: "Smart answers". On only when the relay itself reported it is set up; otherwise shown off and greyed. */
  override fun settingsRows() = listOf(listenRow(), talkRow(), SettingsRow(12, SettingsTab.LINK) { root, _ ->
    val v = smart
    val ready = v != null && v.relay.state == RelayState.CONFIGURED && v.speaker.ready
    root.addView(android.widget.CheckBox(root.context).apply {
      text = "Smart answers"; textSize = 32f; setTextColor(android.graphics.Color.WHITE)
      isEnabled = ready; isChecked = ready && s.prefs.getBoolean(SMART_PREF, true)
      setOnCheckedChangeListener { _, on -> s.prefs.edit().putBoolean(SMART_PREF, on).apply() }
    })
    root.addView(s.label(root, if (ready) "A question the app cannot answer itself is sent as text, with a short text summary of the screen. Never audio or images. Turn off to keep everything on the headset."
      else "Not available: answers come from the headset only, and nothing leaves it.", 28f))
    settingsTab(root, SettingsTab.VOICE)
    // #1070: "Answer to my name" (on by default): a line that starts or ends with "Flowstate" is always for the app.
    val vp = s.activity.getSharedPreferences(Voice.PREFS, android.content.Context.MODE_PRIVATE)
    root.addView(android.widget.CheckBox(root.context).apply {
      text = "Answer to my name"; textSize = 32f; setTextColor(android.graphics.Color.WHITE); tag = "answer-to-name-switch"
      isChecked = controller?.answerToName ?: vp.getBoolean(Voice.NAME_PREF, true)
      setOnCheckedChangeListener { _, on -> controller?.let { it.answerToName = on } ?: vp.edit().putBoolean(Voice.NAME_PREF, on).apply() }
    })
  })

  /** #1182: "Listen for voice" is the caption window's mute control (VoiceController.setMuted, the same pref), in
   *  Settings. It shows the mute state, so leaving it alone changes nothing; it takes effect at once. */
  val listening get() = controller?.muted?.not()
    ?: !s.activity.getSharedPreferences(Voice.PREFS, android.content.Context.MODE_PRIVATE).getBoolean(MUTED_PREF, false)
  fun setListening(on: Boolean) {
    controller?.setMuted(!on, "settings")
      ?: s.activity.getSharedPreferences(Voice.PREFS, android.content.Context.MODE_PRIVATE).edit().putBoolean(MUTED_PREF, !on).apply()
  }
  private fun listenRow() = SettingsRow(11, SettingsTab.VOICE) { root, _ ->
    if (!observingMute) { listenPrefs.registerOnSharedPreferenceChangeListener(muteObserver); observingMute = true }
    root.addView(android.widget.CheckBox(root.context).apply {
      listenSwitch = this
      text = "Listen for voice"; textSize = 32f; setTextColor(android.graphics.Color.WHITE); tag = "listen-voice-switch"
      isChecked = listening
      setOnCheckedChangeListener { _, on -> setListening(on) }
    })
  }

  /** #1035: "Talk to agents" (on by default): point at an agent on the build site and speak to it. */
  private fun talkRow() = SettingsRow(13, SettingsTab.VOICE) { root, _ ->
    root.addView(android.widget.CheckBox(root.context).apply {
      text = "Talk to agents"; textSize = 32f; setTextColor(android.graphics.Color.WHITE); tag = "talk-agents-switch"
      isChecked = s.prefs.getBoolean(TALK_PREF, true)
      setOnCheckedChangeListener { _, on -> s.prefs.edit().putBoolean(TALK_PREF, on).apply(); s.interaction.site.talk.enabled = on
        Log.i(com.overhand.app.graph.site.SiteScene.TAG, "site event=talk_switch on=$on") }
    })
    root.addView(s.label(root, "Point at an agent on the build site and speak: it answers from its own work. With no agent pointed at, speech works as before.", 28f))
  }

  /** #1001: the caption window's own place, low centre: 1.2 m out, 20 degrees below eye level, facing the wearer (yaw only). */
  private fun pose(): Pose {
    val head = s.head()
    val f = head.q * Vector3(0f, 0f, 1f)
    val yaw = Math.toDegrees(kotlin.math.atan2(f.x.toDouble(), f.z.toDouble())).toFloat()
    val flat = Quaternion.fromEuler(0f, yaw, 0f)
    val (dy, dz) = captionOffset()
    return Pose(head.t + flat * Vector3(0f, dy, dz), flat)
  }

  /** The grab feature's handle on the caption window (grabbable like any window). */
  val panel: Entity? get() = entity?.takeIf { it.tryGetComponent<Visible>()?.isVisible == true }

  override fun onFrame(now: Long) {
    s.ui.post { controller?.projectConfirmation(s.project.flow.state, s.project.followUp.state, s.project::action) }
    // The first pose is taken once tracking has settled, like the windows.
    if (!placed && entity != null) { placed = true; entity?.windowPose(pose(),"VoiceFeature_onFrame") }
  }
  override fun onPause() { controller?.pause() }
  override fun onResume() { controller?.resume() }
  override fun onDestroy() {
    controller?.close(); controller = null; hands?.close()
    if (observingMute) listenPrefs.unregisterOnSharedPreferenceChangeListener(muteObserver)
    listenSwitch = null
  }
  override fun onDebugIntent(intent: Intent) {
    intent.getStringExtra("voice_wav")?.let { p -> controller?.injectWav(p) ?: Log.w(Voice.TAG, "voice is off") }
    intent.getStringExtra("voice_text")?.let { q -> controller?.injectText(q) }
  }

  /** #1002: what a spoken command calls. Every line is a function the app already has; voice adds none. */
  private class ShellUiActions(private val s: Shell) : UiActions {
    private val graph get() = s.interaction.architecture
    private fun label(view: String) = when (view) { "build" -> "Yard"; "chat" -> "Chat"; else -> view.replaceFirstChar { it.uppercase() } }
    override fun windows() = s.layout.specs.filter { it.view != SettingsPanel.VIEW }.map { sp ->
      SlotName(sp.view, label(sp.view), if (sp.view == "chat") listOf("decisions", "decision", "chat decisions") else if (sp.view == "build") listOf("yard") else emptyList())
    } + SlotName("architecture", "Architecture", listOf("graph", "architecture graph")) +
      SlotName("site", "Build site", listOf("site", "build site", "building site", "construction site"))   // #1029
    override fun projects() = if (s.isRecorded()) s.recordedContent.labels.map { (id, l) -> SlotName(id, l) } else emptyList()
    override fun currentProject() = s.layout.stationProject.ifBlank { if (s.isRecorded()) s.recordedContent.defaultProject else graph.project }
    override fun parts(): List<SlotName> {
      val shown = graph.parts().takeIf { it.isNotEmpty() && graph.project == currentProject() }
      val rows = shown ?: if (s.isRecorded()) s.recordedContent.plans().optJSONObject(currentProject())?.optJSONArray("components")?.let { a ->
        (0 until a.length()).map { i -> a.getJSONObject(i).let { c -> c.getString("id") to c.optString("short").ifBlank { c.getString("id").replace('_', ' ') } } }
      } ?: emptyList() else emptyList()
      return rows.map { (id, l) -> SlotName(id, l) }
    }
    override fun closeAll(): List<String> {
      val shut = s.layout.specs.map { it.view }.filter { it != SettingsPanel.VIEW && it !in s.windows.voiceHidden }
      s.windows.voiceHidden.addAll(shut);if("blueprint" in shut) s.commands.add { s.poseLog.capture(PoseLog.Moment.BLUEPRINT_CLOSE) }; return shut
    }
    override fun openWindow(key: String) { if (key == "architecture") showArchitecture(currentProject()) else if (key == "site" || key == "build") s.interaction.showSite("voice") else s.windows.open(key, "voice") }   // #1034: as the dock opens it
    override fun closeWindow(key: String) { if (key == "site" || key == "build") s.interaction.hideSite() else { s.windows.voiceHidden.add(key);if(key=="blueprint") s.commands.add { s.poseLog.capture(PoseLog.Moment.BLUEPRINT_CLOSE) } } }
    override fun showArchitecture(project: String) = s.interaction.openGraph(project)
    override fun hideArchitecture() = graph.hide()
    override fun openProject(project: String) { s.commands.add { s.windows.stations.choose(project) } }
    override fun zoomInto(part: String) { if (graph.project != currentProject() || graph.parts().isEmpty()) s.interaction.openGraph(currentProject()); graph.select(part); graph.debugZoom(3.0) }
    override fun zoomOut() = graph.debugZoom(1.0)
    override fun centerWindows() = s.recenter.request("voice")
    override fun openSettings() = s.settings.open("voice")
    override fun closeSettings() = s.settings.requestClose()
    override fun mute(on: Boolean) = com.overhand.app.audio.SpatialEarcons.of(s.activity).mute(on)
  }

  companion object {
    /** Panel id outside the aapt range (0x7f......) so it can never collide with a layout id. */
    const val PANEL = 0x0756_0001
    /** #1001 caption window: 0.5 m wide; tall enough for the header, two lines heard and three answered. */
    const val WIDTH_M = 0.5f
    const val HEIGHT_M = 0.15f
    const val DISTANCE_M = 1.2
    const val BELOW_EYE_DEG = 20.0
    /** (down, forward) metres from the eyes: 1.2 m along a line 20 degrees below level. */
    fun captionOffset(): Pair<Float, Float> {
      val a = Math.toRadians(BELOW_EYE_DEG)
      return Pair((-DISTANCE_M * kotlin.math.sin(a)).toFloat(), (DISTANCE_M * kotlin.math.cos(a)).toFloat())
    }
    const val SMART_PREF = "smart-answers"
    const val TALK_PREF = "talk-agents"
    /** VoiceController's own mute pref (in [Voice.PREFS]). */
    const val MUTED_PREF = "muted"
    const val INSTALL_PREF = "smart-install"
  }
}
