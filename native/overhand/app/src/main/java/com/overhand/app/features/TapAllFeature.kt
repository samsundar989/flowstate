package com.overhand.app.features

import android.content.Intent
import android.util.Log
import android.view.View
import com.overhand.app.windows.ControlWalker
import com.overhand.app.windows.PolishEvidence
import org.json.JSONArray
import org.json.JSONObject

/**
 * tap_every_control (scripts/native_smoke.mjs). Debug intents only:
 *   --es tapall_action list [--es tapall_view <name>]   writes files/tapall_list.json (every clickable view)
 *   --es tapall_action click --es tapall_view <name> --ei tapall_index <i> [--es tapall_text <label>]
 * Panel names: the six window views, "decision" (the card) and "graph". Clicks are performClick, the same
 * handler a pinch reaches; they are not real-hand evidence.
 */
class TapAllFeature(private val s: Shell) : Feature {
  override val name = "tapall"
  private fun panels(): Map<String,View> {
    val l=s.layout
    return l.roots.entries.associate { (id,root) -> l.specs.first { it.id==id }.view to (root as View) }+PolishEvidence.registered
  }
  override fun onDebugIntent(intent: Intent) {
    val action=intent.getStringExtra("tapall_action") ?: return
    val only=intent.getStringExtra("tapall_view")
    val panels=panels().filterKeys { only==null || it==only }
    when(action) {
      "list" -> {
        val rows=JSONArray()
        panels.forEach { (name,root) -> val d=ControlWalker.describe(name,root);for(i in 0 until d.length()) rows.put(d.get(i)) }
        val out=JSONObject().put("panels",JSONArray(panels.keys.toList())).put("controls",rows)
        s.activity.openFileOutput("tapall_list.json",0).use { it.write(out.toString(2).toByteArray()) }
        Log.i(TAG,"list panels=${panels.keys.joinToString(",")} controls=${rows.length()}")
      }
      "click" -> {
        val root=panels[only] ?: run { Log.w(TAG,"click view=$only missing");return }
        val index=intent.getIntExtra("tapall_index",-1)
        val text=intent.getStringExtra("tapall_text")
        val hit=ControlWalker.click(root,index,text)
        Log.i(TAG,com.overhand.app.PrivateLog.tapClick(only,index,text,hit!=null))
      }
    }
  }
  companion object { const val TAG="OverhandTapAll" }
}
