package com.overhand.app.design

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * #811 hazard 1: VrActivity is a plain Activity, not a LifecycleOwner, so a ComposeView in a Spatial panel finds no
 * ViewTree owner and crashes on attach. This is what Meta's ComposeFeature/PanelViewLifecycleOwner provide.
 */
class PanelOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
  private val registry = LifecycleRegistry.createUnsafe(this)
  private val saved = SavedStateRegistryController.create(this)
  override val lifecycle: Lifecycle get() = registry
  override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
  override val viewModelStore = ViewModelStore()
  var created = false
    private set
  fun move(event: Lifecycle.Event) {
    if (!created) { saved.performRestore(null); created = true }
    registry.handleLifecycleEvent(event)
  }
  /** Resumed and set as [v]'s ViewTree owner, so a ComposeView at or under [v] can compose. */
  fun install(v: View) {
    if (!created) { saved.performRestore(null); created = true }
    registry.currentState = Lifecycle.State.RESUMED
    v.setViewTreeLifecycleOwner(this); v.setViewTreeSavedStateRegistryOwner(this); v.setViewTreeViewModelStoreOwner(this)
  }
}
