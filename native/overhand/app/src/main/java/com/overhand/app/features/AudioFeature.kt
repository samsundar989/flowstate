package com.overhand.app.features

import android.content.Intent
import com.meta.spatial.core.SystemBase
import com.overhand.app.audio.SpatialEarcons

/** Spatial earcons: ticked every scene, paused with the activity, one Settings row. */
class AudioFeature(private val s: Shell) : Feature {
  override val name = "audio"
  val earcons by lazy { SpatialEarcons(s.activity,{ s.activity.scene },{ s.interaction.decisionEntity },{ s.layout.entities[it] }) }

  override fun onSceneStart() {
    s.activity.systemManager.registerSystem(object : SystemBase() { override fun execute() { earcons.tick() } })
  }
  override fun onPause() { earcons.pause(true) }
  override fun onResume() { earcons.pause(false) }
  override fun onDestroy() { earcons.close() }
  override fun onDebugIntent(intent: Intent) { earcons.debug(intent) }
  override fun settingsRows() = listOf(SettingsRow(10, SettingsTab.VOICE) { root, _ -> earcons.settings(root) })
}
