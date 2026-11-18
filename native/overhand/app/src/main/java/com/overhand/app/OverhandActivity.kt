package com.overhand.app

import android.content.Intent
import com.meta.spatial.core.*
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.toolkit.*
import com.meta.spatial.vr.VRFeature
import com.overhand.app.features.Shell

/**
 * Six app-owned immersive panels. SDK supplies handles; we own layout and WebView lifecycle.
 * Feature wiring lives in `features/` (see native/overhand/ARCHITECTURE.md): this class only
 * fans Android/SDK callbacks out to [Shell.features]. New features add a file, not lines here.
 */
@OptIn(com.meta.spatial.core.SpatialSDKExperimentalAPI::class)
class OverhandActivity : AppSystemActivity() {
  private val shell = Shell(this)
  private val features get() = shell.features
  /** Read by SpatialEarcons.of(activity). */
  val earcons get() = shell.audio.earcons

  override fun onCreate(savedInstanceState: android.os.Bundle?) {
    shell.launchTrace=LaunchTrace(android.os.SystemClock.elapsedRealtime(),System.currentTimeMillis())
    features.forEach { it.onPreCreate() }
    super.onCreate(savedInstanceState)
    features.forEach { it.onCreate() }
    com.overhand.app.diag.MainStall.start { shell.paused }  // #1385: any main-thread freeze names itself in the log
  }

  override fun registerFeatures(): List<SpatialFeature> {
    val mruk=shell.mr.register() // constructed before VRFeature, as before the split
    return listOf(VRFeature(this),mruk)
  }

  override fun registerPanels(): List<PanelRegistration> {
    if (shell.ladder.step != null) return shell.ladder.registrations()
    return shell.windows.panels() + shell.interaction.panels() + shell.opening.panels()
  }

  override fun onSceneReady() {
    super.onSceneReady()
    features.forEach { it.onSceneStart() }
    scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
    scene.setLightingEnvironment(Vector3(0.6f), Vector3(1f), -Vector3(1f,3f,-2f), 0.3f)
    if (shell.ladder.onSceneReady()) return
    features.forEach { it.onSceneReady() }
    // Sample viewer after tracking settles; first launch follows current heading, without pitch/roll.
    val ready = System.currentTimeMillis() + 3000
    shell.ready = ready
    systemManager.registerSystem(object : SystemBase() {
      var previous = 0L
      override fun execute() {
        val now = System.currentTimeMillis()
        if (shell.paused || shell.stopped || now < ready || now - previous < 100) return
        previous = now
        val layout = shell.layout
        // t765: nothing is placed until the head is tracked, level and steady (RecenterFeature / HeadSettle).
        if (!shell.recenter.ready(now)) { while (true) (shell.commands.poll() ?: break).invoke(); return }
        // Recorded opening: no windows at start; the palm menu creates them (OpeningFeature).
        if (layout.entities.isEmpty() && !shell.opening.active) layout.specs.forEach { layout.createWindow(it) }
        while (true) (shell.commands.poll() ?: break).invoke()
        features.forEach { it.onFrame(now) }
      }
    })
  }

  // Reproducible debug probes exercise app paths. They are never reported as real-hand input.
  // One dispatch entry per feature file, in Shell.features order.
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    if(!BuildConfig.DEBUG) return
    shell.debugIntents.values.forEach { it(intent) }
  }

  override fun onPause() {
    shell.paused=true
    features.forEach { it.onPause() }
    com.overhand.app.features.EventLog.flush()
    super.onPause()
  }

  override fun onStop() {
    com.overhand.app.features.EventLog.flush()
    super.onStop()
  }

  override fun onResume() {
    shell.paused=false
    super.onResume()
    features.forEach { it.onResume() }
  }

  /** System recenter (long press of the system button): re-place everything in front of the wearer. */
  override fun onRecenter(isUserInitiated: Boolean) {
    super.onRecenter(isUserInitiated)
    shell.recenter.systemRecenter(isUserInitiated)
    features.filterIsInstance<com.overhand.app.features.ControlDockFeature>().forEach { if(isUserInitiated) it.systemRecenter() else it.mounted() }
  }

  /** Headset put on again: re-place once the head is steady, unless a window was moved by hand. */
  override fun onHMDMounted() {
    super.onHMDMounted()
    shell.recenter.resettle("mounted")
    features.filterIsInstance<com.overhand.app.features.ControlDockFeature>().forEach { it.mounted() }
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    super.onRequestPermissionsResult(requestCode,permissions,grantResults)
    shell.mr.permissionResult(requestCode)
    features.filterIsInstance<com.overhand.app.features.VoiceFeature>().forEach { it.permissionResult(requestCode, grantResults) }
  }

  override fun onDestroy() {
    shell.generation.advance();shell.stopped=true
    features.forEach { it.onDestroy() }
    com.overhand.app.features.EventLog.stop()
    shell.ui.removeCallbacksAndMessages(null)
    super.onDestroy()
  }
}
