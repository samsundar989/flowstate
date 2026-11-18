package com.overhand.app.design

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.graphics.Color
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.DpDisplayOptions
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.ViewPanelRegistration
import com.meta.spatial.toolkit.Visible
import com.overhand.app.BuildConfig
import com.overhand.app.features.Feature
import com.overhand.app.features.SettingsRow
import com.overhand.app.features.Shell
import com.overhand.app.windows.ControlWalker
import com.overhand.app.windows.PanelPolish
import com.overhand.app.features.WindowPose
import org.json.JSONObject
import kotlin.math.atan
import kotlin.math.sqrt

/**
 * t811 device spike, debug builds only: FlowstateDecisionCard in its own Spatial panel, beside (never instead of)
 * the live View card. #863: off the judged path. The panel opens only while the Settings switch "Compose preview"
 * (debug builds only, off by default, pref [PREF]) is on; switching it off closes the panel. Debug intents:
 *   --ez compose_preview true             turn that switch on (launch or onNewIntent), which opens the panel
 *   --ez compose_preview_noshim true      same, without the ViewTree owner shim (hazard 1 reproduction)
 *   --es compose_click option_a|confirm|next   inject DOWN/UP at the node's centre into the panel root
 *   --ez compose_snapshot true            files/compose_preview_panel.png (the panel view drawn to a bitmap)
 * Injected events take the View dispatch path a panel pinch is delivered on; they are not real-hand evidence.
 */
class ComposePreview(private val s: Shell) : Feature {
  override val name = "compose_preview"
  private var entity: Entity? = null
  private var root: FrameLayout? = null
  private var compose: ComposeView? = null
  private val owner = PanelOwner()
  private var base: DecisionCardUi? = null
  private val state = mutableStateOf<DecisionCardUi?>(null)

  // PanelOwner (design/PanelOwner.kt): what Meta's ComposeFeature/PanelViewLifecycleOwner provide (#874 moved it out).

  /** The Settings switch: the only way the panel opens. Debug builds only, off by default. */
  val enabled get() = BuildConfig.DEBUG && s.prefs.getBoolean(PREF, false)

  fun setEnabled(on: Boolean, shim: Boolean = true) {
    if (!BuildConfig.DEBUG) return
    s.prefs.edit().putBoolean(PREF, on).apply()
    Log.i(TAG, "switch on=$on")
    if (on) open(shim) else close()
  }

  override fun settingsRows() = if (!BuildConfig.DEBUG) emptyList() else listOf(SettingsRow(55, com.overhand.app.features.SettingsTab.DEBUG) { root, _ ->
    root.addView(CheckBox(root.context).apply {
      text = "Compose preview"; textSize = 32f; setTextColor(Color.WHITE); tag = "compose-preview-switch"
      isChecked = enabled
      // Qualified: inside apply{} a bare setEnabled() is the CheckBox's View.setEnabled and greys the box out.
      setOnCheckedChangeListener { _, checked -> this@ComposePreview.setEnabled(checked) }
    })
  })

  override fun onSceneReady() {
    if (!BuildConfig.DEBUG) return
    val i = s.activity.intent
    if (i != null && (i.getBooleanExtra("compose_preview", false) || i.getBooleanExtra("compose_preview_noshim", false))) switchOn(i)
    else if (enabled) open(true)
  }

  private fun switchOn(i: Intent) = setEnabled(true, shim = !i.getBooleanExtra("compose_preview_noshim", false))

  override fun onDebugIntent(intent: Intent) {
    if (!BuildConfig.DEBUG) return
    if (intent.getBooleanExtra("compose_preview", false) || intent.getBooleanExtra("compose_preview_noshim", false)) switchOn(intent)
    intent.getStringExtra("compose_click")?.let { target -> s.ui.post { click(target) } }
    if (intent.getBooleanExtra("compose_snapshot", false)) s.ui.post { snapshot() }
  }

  override fun onPause() { if (owner.created && entity != null) s.ui.post { owner.move(Lifecycle.Event.ON_PAUSE) } }
  override fun onResume() { if (owner.created && entity != null) s.ui.post { owner.move(Lifecycle.Event.ON_RESUME) } }

  private fun open(shim: Boolean) {
    if (entity != null) { Log.i(TAG, "open: already open"); return }
    s.commands.add {
      if (entity != null) return@add
      val d = recordedQuestion()
      base = d; s.ui.post { state.value = d }
      val id = View.generateViewId()
      s.activity.registerPanel(ViewPanelRegistration(id,
        { _, ctx -> build(ctx, shim) },
        { com.overhand.app.windows.Theme.panel(UIPanelSettings(shape = QuadShapeOptions(width = 1.0f, height = 0.72f),
          display = DpDisplayOptions(width = 1000f, height = 720f))) },
        { v, _, _ -> setup(v) }))
      // Level and 1.0 m ahead (FlowstateTokens.CARD distance), a little left so it never sits on the live card.
      val pose = WindowPose.upright(s.head()) * Pose(Vector3(-0.35f, 0f, DISTANCE_M))
      entity = Entity.create(listOf(Panel(id), Transform(pose), Visible(true)))
      val h = s.head().t; val p = pose.t
      val dist = sqrt(((p.x - h.x) * (p.x - h.x) + (p.y - h.y) * (p.y - h.y) + (p.z - h.z) * (p.z - h.z)).toDouble())
      Log.i(TAG, com.overhand.app.PrivateLog.composeOpen(shim, id, d.question, d.options.size, dist))
      for ((label, dp) in listOf("body1" to 22f, "body2" to 18f)) {
        val deg = Math.toDegrees(2 * atan(dp / 1000f / 2 / dist))
        Log.i(TAG, "angular $label dp=$dp size_m=${dp / 1000f} deg=${"%.3f".format(deg)} floor=1.0")
      }
    }
  }

  /** Switch off: remove the panel; the shim owner stops (not destroyed, so the switch can reopen it). */
  private fun close() {
    s.commands.add {
      val e = entity ?: return@add
      e.destroy(); entity = null
      s.ui.post {
        if (owner.created) { owner.move(Lifecycle.Event.ON_PAUSE); owner.move(Lifecycle.Event.ON_STOP) }
        root = null; compose = null
      }
      Log.i(TAG, "close")
    }
  }

  private fun recordedQuestion(): DecisionCardUi {
    val all = s.recordedSource.decisions()
    val d: JSONObject = (0 until all.length()).map { all.getJSONObject(it) }.firstOrNull { it.optString("status", "open") == "open" }
      ?: all.getJSONObject(0)
    val opts = d.optJSONArray("options")?.let { a -> (0 until a.length()).map { a.optJSONObject(it)?.optString("label") ?: a.optString(it) } }.orEmpty()
    return DecisionCardUi(position = 0, total = 1, question = d.optString("question", d.optString("title")),
      project = d.optString("project_name", d.optString("project")), agent = d.optString("agent_name", "Agent"),
      options = opts, replayChip = "Replay")
  }

  private fun build(ctx: android.content.Context, shim: Boolean): View {
    val frame = FrameLayout(ctx)
    if (shim) {
      owner.move(Lifecycle.Event.ON_CREATE); owner.move(Lifecycle.Event.ON_START); owner.move(Lifecycle.Event.ON_RESUME)
      frame.setViewTreeLifecycleOwner(owner); frame.setViewTreeSavedStateRegistryOwner(owner); frame.setViewTreeViewModelStoreOwner(owner)
    }
    val cv = ComposeView(ctx)
    cv.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
      override fun onViewAttachedToWindow(v: View) {
        // Hazard 1: does the panel's own window provide an owner? (rootView is the SDK's, above our frame.)
        Log.i(TAG, "hazard1 window_root=${v.rootView.javaClass.simpleName} window_owner=${v.rootView.findViewTreeLifecycleOwner()?.javaClass?.simpleName} " +
          "our_owner=${v.findViewTreeLifecycleOwner()?.javaClass?.simpleName} activity_is_lifecycle_owner=${s.activity is LifecycleOwner}")
      }
      override fun onViewDetachedFromWindow(v: View) {}
    })
    cv.setContent {
      state.value?.let { ui ->
        FlowstateDecisionCard(ui,
          onSelect = { i -> Log.i(TAG, "onClick target=option index=$i"); state.value = ui.copy(selected = i) },
          onConfirm = {
            Log.i(TAG, "onClick target=confirm selected=${ui.selected}")
            val choice = ui.options.getOrNull(ui.selected).orEmpty()
            state.value = ui.copy(receipt = DecisionCardUi.Receipt(ui.question, "${ui.project} · saved just now", choice))
          },
          onAdvance = { Log.i(TAG, "onClick target=next_decision"); state.value = base },
          onClose = { Log.i(TAG, "onClick target=close") })
      }
    }
    frame.addView(cv, FrameLayout.LayoutParams(-1, -1))
    root = frame; compose = cv
    return frame
  }

  private fun setup(v: View) {
    // Hazard 4: the live panels run PanelPolish.install on their root. Run it here too and see whether it breaks Compose.
    PanelPolish.install(v)
    v.post {
      // Hazards 2 and 3: the View walkers (HandsOnboarding.buttons, DecisionCards.debugConfirm, ControlWalker) look for Buttons.
      val views = ControlWalker.descendants(v)
      Log.i(TAG, "hazard23 views=${views.size} buttons=${views.count { it is Button }} card_option_views=${views.count { it.tag == CardTags.OPTION }} " +
        "clickable=${ControlWalker.clickable(v).size} compose_nodes=${nodes().size} compose_options=${nodes().count { it.config.getOrNull(SemanticsProperties.TestTag) == CardTags.OPTION }}")
      Log.i(TAG, "hazard4 polish_installed=true root=${v.width}x${v.height} compose=${compose?.width}x${compose?.height}")
    }
  }

  private fun nodes(): List<SemanticsNode> {
    val r = (compose?.takeIf { it.childCount > 0 }?.getChildAt(0) as? RootForTest) ?: return emptyList()
    fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap { walk(it) }
    return walk(r.semanticsOwner.rootSemanticsNode)
  }

  private fun find(target: String): SemanticsNode? {
    val all = nodes()
    fun tag(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.TestTag)
    fun text(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
    return when (target) {
      "option_a" -> all.filter { tag(it) == CardTags.OPTION }.minByOrNull { it.boundsInRoot.left }
      "confirm" -> all.firstOrNull { tag(it) == CardTags.CONFIRM }
      "next" -> all.firstOrNull { text(it).contains("Next decision") }
      else -> null
    }
  }

  private fun click(target: String) {
    val r = root; val cv = compose
    val n = find(target)
    if (r == null || cv == null || n == null) { Log.w(TAG, "inject target=$target found=false root=${r != null}"); return }
    val b = n.boundsInRoot
    val x = cv.left + b.center.x; val y = cv.top + b.center.y
    val t = SystemClock.uptimeMillis()
    fun ev(action: Int, at: Long) = MotionEvent.obtain(t, at, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
    Log.i(TAG, "inject target=$target found=true x=${x.toInt()} y=${y.toInt()} bounds=$b")
    val down = ev(MotionEvent.ACTION_DOWN, t); r.dispatchTouchEvent(down); down.recycle()
    r.postDelayed({ val up = ev(MotionEvent.ACTION_UP, t + 60); r.dispatchTouchEvent(up); up.recycle() }, 60)
  }

  private fun snapshot() {
    val v = root ?: run { Log.w(TAG, "snapshot root=null"); return }
    if (v.width == 0) { Log.w(TAG, "snapshot size=0"); return }
    val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
    v.draw(Canvas(bmp))
    s.activity.openFileOutput("compose_preview_panel.png", 0).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    Log.i(TAG, "snapshot written ${v.width}x${v.height}")
  }

  companion object {
    const val TAG = "OverhandCompose"
    const val DISTANCE_M = 1.0f
    const val PREF = "compose-preview"
  }
}
