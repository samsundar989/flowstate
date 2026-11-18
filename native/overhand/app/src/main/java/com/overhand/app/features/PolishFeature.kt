package com.overhand.app.features

import android.content.Intent
import com.overhand.app.windows.PolishEvidence

/** Polish evidence probes (polish_action). Dispatched before interaction/windows probes it is combined with. */
class PolishFeature(private val s: Shell) : Feature {
  override val name = "polish"
  override fun onDebugIntent(intent: Intent) {
    val l=s.layout
    PolishEvidence.handle(s.activity,intent,l.roots.mapKeys { entry -> l.specs.first { it.id==entry.key }.view },
      l.specs.associate { it.view to (l.saved(it)[7] to l.saved(it)[8]) },s.recordedContent,s.recordedWindows::preview)
  }
}
