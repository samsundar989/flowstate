package com.overhand.app.hostlink

import com.overhand.app.data.DecisionScope
import com.overhand.app.data.Delivery
import com.overhand.app.data.DeliveryException
import com.overhand.app.data.LivePendingStore
import com.overhand.app.data.SessionSource
import com.overhand.app.decisions.LiveSafety
import org.json.JSONArray
import org.json.JSONObject

/**
 * The paired computer as the app's session data: its open questions become decision cards, and a confirmed card
 * becomes an `answer` frame. Plugs in where [com.overhand.app.data.LiveSource] is chosen today.
 *
 * Delivery follows the Live path's rules: [LiveSafety] gates every send (here and inside the link, so a queued
 * answer is held, not sent, if the switch goes off); an answer is CONFIRMED only by the host's own log; an
 * outcome not yet known is kept in [pending] so an app restart neither forgets it nor sends it blindly.
 */
class HostSource(val link: HostLink,private val pending: LivePendingStore?=null,
  private val sendReal: () -> Boolean={ LiveSafety.sendReal },private val waitMs: Long=8_000): SessionSource {
  override val recorded=false
  /** Cards carry this, so an answer can never go to a different computer than the one that asked. */
  val origin: String get()="host:"+(link.paired?.fp ?: "")

  init {
    // Answers left unresolved by an earlier process are re-queued once the log has been replayed.
    link.caughtUpListeners.add {
      for(row in pending?.all().orEmpty().filter { it.optString(DecisionScope.ORIGIN)==origin }) {
        val id=DecisionScope.id(row)
        val q=link.question(id)
        val opt=q?.let { optionFor(it,row.optString("label")) }
        if(q==null || opt==null || !link.isOpen(id)) { pending?.remove(row);continue }
        link.answer(id,opt).thenAccept { pending?.remove(row) }
      }
    }
  }

  override fun decisions(): JSONArray {
    val s=link.status
    if(s.state!=LinkState.CONNECTED && link.events.isEmpty()) throw IllegalStateException(s.detail.ifBlank { "Not connected to a computer" })
    link.flush()
    val names=(0 until link.projects.length()).mapNotNull { link.projects.optJSONObject(it) }.associate { it.optString("project_id") to it.optString("name") }
    return JSONArray(link.openQuestions().map { card(it,names[it.optString("project_id")]) })
  }

  private fun card(q: JSONObject,projectName: String?): JSONObject {
    val options=JSONArray()
    q.optJSONArray("options")?.let { a -> for(i in 0 until a.length()) a.optJSONObject(i)?.let { options.put(JSONObject().put("id",it.optString("id")).put("label",it.optString("label"))) } }
    return JSONObject().put("id",q.getString("decision_id")).put("question",q.optString("title")).put("project",q.optString("project_id"))
      .put("project_name",projectName ?: q.optString("project_id").removePrefix("p_").replace('-',' ')).put("agent_name","Agent")
      .put("options",options).put("status","open").put("seq",q.optLong("seq")).put("at",q.optString("at"))
      .put(DecisionScope.ORIGIN,origin)
      .put("blocking",true)   // #815: a question from your coding agent stops its work, so the card opens by itself
  }

  private fun optionFor(q: JSONObject,label: String): String? {
    val a=q.optJSONArray("options") ?: return null
    val want=label.trim()
    return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
      .firstOrNull { it.optString("label").equals(want,true) || it.optString("id").equals(want,true) }?.optString("id")
  }

  override fun answer(decision: JSONObject,label: String,input: String): String {
    if(!LiveSafety.maySend(recorded,sendReal())) throw LiveSafety.PracticeOnly()
    if(decision.optString(DecisionScope.ORIGIN)!=origin) throw DeliveryException(Delivery.CHANGED_SERVER)
    val id=DecisionScope.id(decision)
    val q=link.question(id) ?: throw DeliveryException(Delivery.MISSING)
    if(q.optString("title")!=decision.optString("question")) throw DeliveryException(Delivery.CHANGED_SERVER)
    val option=optionFor(q,label) ?: throw DeliveryException(Delivery.NOT_SENT,detail="That option is not on this question")
    link.answeredOption(id)?.let { prior ->
      if(prior==option) return "Answer already recorded on the computer"
      throw DeliveryException(Delivery.CONFLICT,prior)
    }
    if(!link.isOpen(id)) throw DeliveryException(Delivery.CLOSED)
    pending?.put(decision,label)
    val sent=link.answer(id,option).also { f -> f.thenAccept { pending?.remove(decision) } }
    val outcome=link.awaitAnswer(sent,waitMs)
      ?: throw DeliveryException(Delivery.NOT_RECORDED_YET,sent=true,detail="Saved on the headset; it will be delivered when the computer is reachable")
    return when(outcome) {
      AnswerOutcome.CONFIRMED -> "Answer received"
      AnswerOutcome.CONFLICT -> throw DeliveryException(Delivery.CONFLICT,link.answeredOption(id),sent=true)
      AnswerOutcome.CLOSED -> throw DeliveryException(Delivery.CLOSED,sent=true)
    }
  }
}
