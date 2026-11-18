package com.overhand.app.audio

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import android.widget.*
import com.meta.spatial.core.Entity
import com.meta.spatial.runtime.Scene
import com.meta.spatial.runtime.SceneAudioAsset
import com.overhand.app.OverhandActivity
import org.json.JSONObject

/** All SDK calls and source lookups run on the ECS queue. Entity playback follows its transform. */
class SpatialEarcons(private val activity: Activity,
  private val scene: ()->Scene,private val card: ()->Entity?,private val window: (Int)->Entity?) {
  private val pending=java.util.concurrent.ConcurrentLinkedQueue<()->Unit>()
  private fun enqueue(action: ()->Unit) { pending.add(action) }
  fun tick() { while(true) (pending.poll() ?: break).invoke() }
  private val prefs=activity.getSharedPreferences("overhand-audio",0)
  private val assets=mutableMapOf<Earcon,SceneAudioAsset>()
  private val stations=mutableMapOf<String,Entity>()
  private val events=AudioEvents()
  private var last=0L
  @Volatile private var paused=false
  @Volatile private var closed=false
  @Volatile var muted=prefs.getBoolean("muted",false)
    private set
  fun play(cue: Earcon,source: ()->Entity?) {
    if(com.overhand.app.BuildConfig.DEBUG) Log.i("OverhandAudio","request=${cue.file} paused=$paused muted=$muted closed=$closed stations=${stations.size}")
    if(closed || paused || muted) return
    enqueue {
    val now=SystemClock.elapsedRealtime()
    if(closed || paused || muted || now-last<90) return@enqueue
    val entity=source() ?: run { Log.w("OverhandAudio","Missing source for ${cue.file}");return@enqueue }
    runCatching {
      val asset=assets.getOrPut(cue) { SceneAudioAsset.loadLocalFile(cue.path).also { check(it.canBeSpatialized) } }
      scene().stopSound("overhand-earcon")
      scene().playSound(asset,entity,0.65f,"overhand-earcon",false)
      last=now
      Log.i("OverhandAudio","event=${cue.file} source=${entity.id} spatial=true gain=0.65")
    }.onFailure { Log.e("OverhandAudio","Playback failed: ${cue.file}",it) }
  } }
  fun card(cue: Earcon)=play(cue,card)
  fun window(id: Int)=play(Earcon.SELECT) { window.invoke(id) }
  fun station(id: String,entity: Entity,needs: Boolean) {
    stations[id]=entity
    events.station(id,needs)?.let { play(it) { stations[id] } }
  }
  fun choose(entity: Entity)=play(Earcon.SELECT) { entity }
  /** t1282: [cue] at [entity] (a grab start plays [com.overhand.app.features.GrabFeature.START_CUE]). */
  fun at(cue: Earcon,entity: Entity)=play(cue) { entity }
  fun live(state: JSONObject) { enqueue {
    val tasks=state.optJSONArray("tasks")
    for(i in 0 until (tasks?.length() ?: 0)) {
      val task=tasks!!.getJSONObject(i)
      events.task(task.optString("id"),task.optString("status"))?.let { cue ->
        play(cue) { stations[task.optString("project")] }
      }
    }
    events.finishSnapshot()
  } }
  fun mute(value: Boolean) {
    if(com.overhand.app.BuildConfig.DEBUG) Log.i("OverhandAudio","muted=$value")
    muted=value;prefs.edit().putBoolean("muted",value).apply()
    enqueue { if(!closed) scene().setAudioEnabled(!muted && !paused) }
  }
  fun pause(value: Boolean) { if(com.overhand.app.BuildConfig.DEBUG) Log.i("OverhandAudio","pause=$value");paused=value;enqueue { if(!closed) scene().setAudioEnabled(!muted && !paused) } }
  fun close() { closed=true;enqueue { scene().setAudioEnabled(false);assets.clear();stations.clear() } }
  private var muteToggle: CheckBox?=null
  private var cardRoot: LinearLayout?=null
  fun controls(root: LinearLayout) { cardRoot=root }
  fun settings(root: LinearLayout) { root.addView(CheckBox(root.context).apply {
    muteToggle=this
    text="Mute event sounds";textSize=40f;setTextColor(Color.WHITE);isChecked=muted
    setOnCheckedChangeListener { _,value -> mute(value) }
  }) }
  /** User-stepped real recording: no timer, fabricated events, or background loop. */
  fun replay(root: LinearLayout,rows: List<JSONObject>,projects: Set<String>,day: String="Nov 3") {
    val audible=rows.filter { it.optString("project") in projects && AudioEvents.replay(it.optString("kind"))!=null }
    if(audible.isEmpty()) return
    var index=0
    val label=TextView(root.context).apply { text="Hear the recorded events | $day";textSize=24f;setTextColor(Color.WHITE) }
    val deny=com.overhand.app.stations.StationRules.brandPattern(activity.assets.open("windows.js").bufferedReader().use { it.readText() })
    val next=Button(root.context).apply {
      text="Next recorded event";textSize=28f
      setOnClickListener {
        val event=audible[index];index=(index+1)%audible.size
        label.text=deny.replace("Recorded | "+com.overhand.app.windows.RecordedContent.stamp(event)+" | "+event.optString("project_name")+" | "+event.optString("title"),"agent")
        play(AudioEvents.replay(event.optString("kind"))!!) { stations[event.optString("project")] }
      }
    }
    root.addView(label);root.addView(next)
    replayNext={ next.performClick() }
  }
  private var replayNext: ()->Unit={}
  fun debug(intent: Intent) {
    if(!com.overhand.app.BuildConfig.DEBUG) return
    android.os.Handler(android.os.Looper.getMainLooper()).post { debugActions(intent) }
  }
  private fun debugActions(intent: Intent) {
    if(closed) return
    if(intent.getBooleanExtra("audio_replay_next",false)) replayNext()
    if(intent.hasExtra("audio_muted")) {
      val value=intent.getBooleanExtra("audio_muted",false)
      muteToggle?.let { it.isChecked=value } ?: mute(value)
    }
    fun buttons(view: android.view.View): List<Button> = when(view) {
      is Button -> listOf(view)
      is android.view.ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
      else -> emptyList()
    }
    val buttons=cardRoot?.let { buttons(it) }.orEmpty()
    if(intent.getBooleanExtra("audio_card_select",false)) buttons.firstOrNull { it.isSelected }?.performClick()
    if(intent.getBooleanExtra("audio_card_confirm",false)) buttons.firstOrNull { it.text=="Confirm selected option" }?.performClick()
  }
  companion object {
    fun of(activity: Activity)=(activity as OverhandActivity).earcons
  }
}
