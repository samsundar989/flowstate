package com.overhand.app.decisions

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File

/** JSONL matches scripts/pose_trials.mjs. Cues, never detections, define trial denominators. */
class PoseTrialLogger(private val context: Context) {
  var enabled=false; private set
  private var resting=false
  private var falsePositives=0
  private var cueTime=0L
  private val file get()=File(context.filesDir,"pose_trials.log")
  fun enable(value: Boolean) {
    if(value==enabled) return
    enabled=value
    if(value) {
      file.writeText("")
      event("session", "source" to "real_hands", "device" to android.os.Build.MODEL,
        "recognizer" to "joint-angles-v2 / Spatial SDK 0.15.0 body joints", "dry_run" to true)
    }
  }
  fun event(event: String,vararg fields: Pair<String,Any?>) {
    if(!enabled) return
    val json=JSONObject().put("event",event).put("t",SystemClock.elapsedRealtime())
    fields.forEach { json.put(it.first,it.second ?: JSONObject.NULL) }
    val line=json.toString()
    file.appendText(line+"\n");Log.i("OverhandPose",line)
  }
  fun cue(id: String,pose: String,hand: String) {
    cueTime=SystemClock.elapsedRealtime()
    event("trial_start","trial" to id,"pose" to pose,"hand" to hand,"card_id" to "pose-fixture")
  }
  fun pose(hand: String,pose: String,card: String,action: String) {
    if(resting && action!="none") falsePositives++
    event("pose","hand" to hand,"pose" to pose,"confidence" to null,"card_id" to card,
      "action" to action,"latency_ms" to (SystemClock.elapsedRealtime()-cueTime))
  }
  fun idleStart() { resting=true;falsePositives=0;event("idle_start","hands_visible" to true) }
  fun idleEnd() { event("idle_end","false_positive_count" to falsePositives);resting=false }
}
