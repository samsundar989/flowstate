@file:OptIn(com.meta.spatial.core.SpatialSDKExperimentalAPI::class)
package com.overhand.app.features

import android.content.Intent
import android.graphics.Color
import android.util.Log
import android.widget.CheckBox
import com.meta.spatial.mruk.MRUKFeature
import com.meta.spatial.toolkit.Transform
import com.overhand.app.mr.AnchorLog
import com.overhand.app.mr.AnchorStore
import com.overhand.app.mr.DeskPlacement
import com.overhand.app.mr.MixedRealityMode
import com.overhand.app.mr.MrStations
import org.json.JSONArray
import org.json.JSONObject

/** Passthrough and MRUK desk anchoring: room mode, desk frame, Settings toggle, mr_* probes. */
class MixedRealityFeature(private val s: Shell) : Feature {
  override val name = "mr"
  private lateinit var mruk: MRUKFeature
  var roomMode: MixedRealityMode?=null
  var deskFrame: DeskPlacement.Frame?=null // ECS thread only
  private var passthroughActive=false

  fun register(): MRUKFeature {
    mruk=MRUKFeature(s.activity,s.activity.systemManager)
    // Anchor-relative placements and their lifecycle log; the worn session pulls both.
    val dir=s.activity.filesDir
    MrStations.store=AnchorStore(java.io.File(dir,"anchors.json"),
      AnchorLog(java.io.File(dir,"anchor_log.jsonl")) { Log.i("OverhandAnchor",it) })
    return mruk
  }

  override fun onSceneReady() {
    val scene=s.activity.scene; val l=s.layout
    // Compositor fallback is immediate; optional scene loading upgrades the existing card.
    passthroughActive=s.prefs.getBoolean("passthrough",true)
    scene.enablePassthrough(passthroughActive)
    roomMode=MixedRealityMode(s.activity,mruk,{ s.commands.add(it) },{ scene.getViewerPose() }) { passthrough, frame ->
      deskFrame=frame
      scene.enablePassthrough(passthrough)
      passthroughActive=passthrough
      MrStations.surface=frame
      l.specs.forEach { sp -> l.entities[sp.id]?.let { e ->
        if(!s.prefs.contains(l.layoutKey(sp)) && sp.id !in l.held) {
          e.windowPose(l.defaultPose(sp),"MixedRealityFeature_onSceneReady");l.last[sp.id]=l.encode(e)
        }
      } }
      s.interaction.decisionEntity?.windowPose(s.interaction.decisionPose(),"MixedRealityFeature_onSceneReady")
    }
    val began=android.os.SystemClock.elapsedRealtime()
    s.ui.post(object : Runnable {
      override fun run() {
        if(s.stopped) return
        if(!s.windows.cardDrawn && android.os.SystemClock.elapsedRealtime()-began<2000) {
          s.ui.postDelayed(this,50);return
        }
        Log.i("OverhandMR","motion_timeline event=optional_room_start elapsed_ms=${android.os.SystemClock.elapsedRealtime()}")
        roomMode?.start()
      }
    })
  }
  override fun onFrame(now: Long) { roomMode?.tick() }
  override fun onResume() { roomMode?.resumed() }
  override fun onDestroy() { roomMode?.close() }
  fun permissionResult(requestCode: Int) { if(requestCode==MixedRealityMode.REQUEST) roomMode?.permissionResult() }

  override fun onDebugIntent(intent: Intent) {
    val scene=s.activity.scene; val l=s.layout
    intent.getStringExtra("mr_test_room")?.let { roomMode?.debugRoom(it) }
    if(intent.hasExtra("mr_enabled")) roomMode?.toggle(intent.getBooleanExtra("mr_enabled",true))
    if(intent.hasExtra("mr_passthrough_probe")) s.commands.add {
      // Compositor-only QA when the device has no scan. Never claim this as desk anchoring.
      passthroughActive=intent.getBooleanExtra("mr_passthrough_probe",false)
      scene.enablePassthrough(passthroughActive)
      Log.i("OverhandMR","DEBUG compositor probe passthrough=$passthroughActive anchored=${deskFrame!=null}")
    }
    if(intent.getBooleanExtra("mr_snapshot",false)) s.commands.add {
      val snapshot=JSONObject().put("passthrough",passthroughActive).put("anchored",deskFrame!=null)
        .put("not_anchored",MrStations.notAnchored).put("anchor",MrStations.anchor?.uuid)
        .put("anchor_records",JSONObject().also { o -> MrStations.store?.let { st -> st.keys().forEach { k -> st.record(k)?.let { r ->
          o.put(k,JSONObject().put("anchor",r.anchor).put("local",r.local?.let { JSONArray(it) }).put("world",JSONArray(r.world))) } } } })
        .put("anchor_log",MrStations.store?.log?.tail())
        .put("placement",roomMode?.snapshot()).put("status",roomMode?.status).put("windows",JSONArray(l.specs.map { sp ->
          JSONObject().put("view",sp.view).put("saved",s.prefs.contains(l.layoutKey(sp)))
            .put("pose",l.entities[sp.id]?.let { JSONArray(l.encode(it)) })
        }))
      s.activity.openFileOutput("mr_state.json",android.content.Context.MODE_PRIVATE).use { it.write(snapshot.toString(2).toByteArray()) }
    }
  }

  override fun settingsRows() = listOf(SettingsRow(60,SettingsTab.ROOM) { root, _ ->
    val mrStatus=s.label(root,roomMode?.status ?: "Virtual room",32f)
    val anchoring=CheckBox(root.context).apply {
      text="Passthrough / desk anchoring";textSize=40f;setTextColor(Color.WHITE)
      isChecked=s.prefs.getBoolean("passthrough",true)
      // #1111: saved here too, so a tap before onSceneReady (roomMode null) is not lost on restart.
      setOnCheckedChangeListener { _,checked -> s.prefs.edit().putBoolean("passthrough",checked).apply();roomMode?.toggle(checked) }
    }
    root.addView(anchoring)
    // Checking the box already asks, so an unchecked box only needs checking.
    settingsButton(root,"Allow room access") { if(anchoring.isChecked) roomMode?.requestRoom() else anchoring.isChecked=true }
    val allowRoom=root.getChildAt(root.childCount-1)
    root.addView(mrStatus)
    // Panel registration can precede onSceneReady; retain the observer until it exists.
    s.ui.post(object : Runnable {
      override fun run() {
        if(s.stopped) return
        val mode=roomMode
        if(mode==null) { s.ui.postDelayed(this,100);return }
        val update={ text: String -> mrStatus.text=text;allowRoom.visibility=if(mode.roomAccessGranted) android.view.View.GONE else android.view.View.VISIBLE }
        update(mode.status);mode.statusChanged=update
      }
    })
  })
}
