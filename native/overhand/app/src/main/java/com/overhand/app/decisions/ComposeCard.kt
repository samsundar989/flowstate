package com.overhand.app.decisions

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.overhand.app.design.CardTags
import com.overhand.app.design.DecisionCardUi
import com.overhand.app.design.FlowstateDecisionCard
import com.overhand.app.design.PanelOwner

/**
 * #874: the decision card drawn by FlowstateDecisionCard (UI Set) inside the card's traced layer. It holds no rules:
 * DecisionCards decides every field of [Frame.ui] and every tap goes to the [Handlers] DecisionCards built for the
 * frame that was drawn (so a stale card's tap is still refused as stale, as on the View card).
 */
class ComposeCard(context: Context) {
  class Handlers(val select: (Int) -> Unit, val confirm: () -> Unit, val close: () -> Unit, val previous: () -> Unit,
    val next: () -> Unit, val history: () -> Unit, val advance: () -> Unit, val refresh: () -> Unit)
  data class Frame(val ui: DecisionCardUi, val on: Handlers)

  val frame = mutableStateOf<Frame?>(null)
  private val owner = PanelOwner()
  /** WindowRecomposer is installed at the window content root, above this nested ComposeView. */
  fun prepareWindow(panel: View) {
    val root = (panel.rootView.findViewById<View>(android.R.id.content) as? ViewGroup)?.getChildAt(0)
      ?: panel.rootView
    if (root.findViewTreeLifecycleOwner() == null) owner.install(root)
  }
  val view = ComposeView(context).apply {
    tag = VIEW_TAG
    // #811 hazard 1: the panel window has no ViewTree lifecycle owner.
    owner.install(this)
    setContent {
      frame.value?.let { f ->
        FlowstateDecisionCard(f.ui, onSelect = f.on.select, onConfirm = f.on.confirm, onClose = f.on.close,
          onPrevious = f.on.previous, onNext = f.on.next, onHistory = f.on.history, onAdvance = f.on.advance,
          onRefresh = f.on.refresh)
      }
    }
  }

  /** The merged semantics tree (a button's text is on the button's node), in [view]'s coordinates. */
  fun nodes(): List<SemanticsNode> {
    val r = (view.takeIf { it.childCount > 0 }?.getChildAt(0) as? RootForTest) ?: return emptyList()
    fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap { walk(it) }
    return walk(r.semanticsOwner.rootSemanticsNode)
  }
  fun clickables() = nodes().filter { it.config.contains(SemanticsActions.OnClick) }
  /** Every text node in tree order: the Compose card's card_text.txt. */
  fun texts(): List<String> = nodes().mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text } }

  /** The trace's view= name for a pinch at (x, y): the same names the View card logs (option:A, confirm_selected_option, close…). */
  fun nameAt(x: Float, y: Float): String? {
    val n = clickables().lastOrNull { it.boundsInRoot.contains(androidx.compose.ui.geometry.Offset(x, y)) } ?: return null
    return name(n)
  }
  fun name(n: SemanticsNode): String {
    val tag = n.config.getOrNull(SemanticsProperties.TestTag)
    if (tag == CardTags.OPTION) {
      val i = clickables().filter { it.config.getOrNull(SemanticsProperties.TestTag) == CardTags.OPTION }
        // Each semantics read creates new node wrappers; their stable IDs identify the same target.
        .sortedBy { it.boundsInRoot.left }.indexOfFirst { it.id == n.id }
      val label = frame.value?.ui?.options?.getOrNull(i).orEmpty()
      return "option:" + DecisionCardUi.splitOption(label, i.coerceAtLeast(0)).first
    }
    if (tag == CardTags.CONFIRM) return CardTouchTrace.slug(CardTags.CONFIRM_NAME)
    return CardTouchTrace.slug(label(n)).ifEmpty { tag ?: "compose_node" }
  }
  fun label(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }?.takeIf { it.isNotBlank() }
    ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()

  /** Synthetic QA (DecisionCards.debugConfirm): the semantics click on the node whose trace name is [name]. */
  fun click(name: String): Boolean {
    val n = clickables().firstOrNull { name(it) == name } ?: return false
    return n.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true
  }

  /** Every clickable node with its bounds in [view] (the snapshot's card_bounds.json). */
  fun bounds(): List<Pair<String, Rect>> = clickables().map { n ->
    val b = n.boundsInRoot
    label(n) to Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
  }

  companion object { const val VIEW_TAG = "card-compose" }
}
