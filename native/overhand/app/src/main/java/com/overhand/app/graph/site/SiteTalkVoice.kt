package com.overhand.app.graph.site

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.overhand.app.hostlink.HostLink
import com.overhand.app.voice.AgentVoice
import com.overhand.app.voice.ScreenSummary
import com.overhand.app.voice.SmartVoice
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * #1035: the voice side of talking to an agent on the site. Recorded: the agent's task and last three steps (or, with
 * Smart answers on, the relay with that agent's events as the screen summary). Live: the sentence goes to that
 * agent's session through the host (`say`) and nowhere else; the first two sentences of its reply are read back.
 */
class SiteTalkVoice(private val talk: SiteTalk,private val live: () -> HostLink?,private val smart: () -> SmartVoice?) : AgentVoice {
  private val ui=Handler(Looper.getMainLooper())
  private val relay=Executors.newSingleThreadExecutor()

  override fun lock(): String? = talk.lock()?.also { a ->
    Log.i(SiteScene.TAG,com.overhand.app.PrivateLog.siteTalkLock(a.name,a.project,a.taskId,a.part)) }?.let(SiteTalk::caption)
  override fun hint(): String? = talk.target()?.let(SiteTalk::caption)
  override fun end() = talk.end()

  override fun take(text: String,speak: (String) -> Unit,done: () -> Unit): Boolean {
    val a=talk.locked ?: return false
    val link=live()
    if(link==null) { recorded(a,text,speak,done);return true }
    if(a.taskId.isBlank()) { speak("No agent is working on ${a.part ?: a.name} right now.");done();return true }
    val req="s_"+java.util.UUID.randomUUID().toString().replace("-","").take(12)
    Log.i(SiteScene.TAG,com.overhand.app.PrivateLog.siteTalk("live",a.name,a.project,a.taskId,"chars=${text.length} req=$req"))
    link.say(a.project,a.taskId,text,req).whenComplete { f,err -> ui.post {
      val code=when { err!=null -> "offline";f.optString("type")=="ok" -> null;else -> f.optString("code").ifBlank { "busy" } }
      if(code!=null) {
        Log.i(SiteScene.TAG,"site event=talk_failed agent=${a.name} task=${a.taskId} code=$code")
        speak(SiteTalk.failed(a,code));done();return@post
      }
      Log.i(SiteScene.TAG,"site event=talk_sent agent=${a.name} task=${a.taskId} req=$req")
      awaitReply(link,a,req,speak,done)
    } }
    return true
  }

  /** The agent's next message on its task after the host logged our words; or, after [SiteTalk.REPLY_WAIT_MS], a line saying so. */
  private fun awaitReply(link: HostLink,a: SiteAgent,req: String,speak: (String) -> Unit,done: () -> Unit) {
    var finished=false
    lateinit var listener: (JSONObject) -> Unit
    fun finish(line: String,why: String) { if(finished) return;finished=true;link.eventListeners.remove(listener)
      Log.i(SiteScene.TAG,"site event=talk_reply agent=${a.name} task=${a.taskId} got=$why words=${line.split(' ').size}");speak(line);done() }
    fun check() {
      val said=link.events.firstOrNull { it.optString("kind")=="answered" && it.optString("req_id")==req } ?: return
      SiteTalk.reply(link.events,a.taskId,said.optLong("seq"))?.let { r -> ui.post { finish(r,"reply") } }
    }
    listener={ _ -> check() }
    link.eventListeners.add(listener)
    check()
    ui.postDelayed({ finish(SiteTalk.noReplyYet(a),"timeout") },SiteTalk.REPLY_WAIT_MS)
  }

  private fun recorded(a: SiteAgent,text: String,speak: (String) -> Unit,done: () -> Unit) {
    val answer=SiteTalk.recordedAnswer(a)
    val v=smart()
    if(v==null || !v.on()) {
      Log.i(SiteScene.TAG,com.overhand.app.PrivateLog.siteTalk("recorded",a.name,a.project,a.taskId,"route=recorded words=${answer.split(' ').size}"))
      speak(answer);done();return
    }
    // Smart answers on: the relay gets this agent's events as the screen summary; nothing else of the site.
    val screen=ScreenSummary("recorded day",a.project,emptyList(),a.part,null,null,SiteTalk.events(a)).text()
    relay.execute {
      val res=runCatching { v.relay.ask(text,screen) { s -> ui.post { speak(s) } } }.getOrNull()
      ui.post {
        val ok=res!=null && res.spoken.isNotEmpty()
        Log.i(SiteScene.TAG,com.overhand.app.PrivateLog.siteTalk("recorded",a.name,a.project,a.taskId,"route=${if(ok) "relay" else "recorded"}"))
        if(!ok) speak(answer)
        done()
      }
    }
  }
}
