package com.overhand.app.decisions

import com.overhand.app.data.Delivery
import com.overhand.app.data.DeliveryException
import com.overhand.app.data.DecisionScope
import org.json.JSONObject

/**
 * Card delivery status, free of Android Views so its lifecycle is unit-tested. DecisionCards owns the
 * threads and Views; every status it shows and every send it allows comes from here.
 * An answer whose commit is uncertain stays unresolved: it blocks another send of that decision and is
 * re-checked read-only after reconnect or restart. It is never resent.
 */
class DeliveryBoard {
  /** What a notice asserts about the commit; the t407 verifier checks each kind against receiver records. */
  enum class Kind { INFO, PROGRESS, CONFIRMED, NOT_SENT, NOT_RECORDED, CONFLICT, UNKNOWN, BLOCKED }
  data class Notice(val text: String,val error: Boolean,val kind: Kind=Kind.INFO)
  /** What the card should do after a result: drop the card, play the confirm earcon. */
  data class Effect(val removeCard: Boolean,val confirmed: Boolean)
  class Unresolved(val card: JSONObject,val label: String)

  var busy=false
    private set
  var notice: Notice?=null
    private set
  private var token=0
  private val unresolved=linkedMapOf<String,Unresolved>()
  private val checking=mutableSetOf<String>()

  fun unresolvedIds(): Set<String> = unresolved.values.map { DecisionScope.id(it.card) }.toSet()
  fun canSend(card: JSONObject)=!busy && DecisionScope.key(card) !in unresolved
  fun say(text: String,error: Boolean=false,kind: Kind=Kind.INFO) { notice=Notice(text,error,kind) }
  /** Navigation/selection clears transient text, never an unresolved delivery status. */
  fun clear() { notice=if(unresolved.isEmpty()) null else Notice(UNKNOWN_CHECK,true,Kind.UNKNOWN) }

  /** Returns a request token, or null when the send must not happen. */
  fun begin(card: JSONObject,recorded: Boolean): Int? {
    if(busy) return null
    if(DecisionScope.key(card) in unresolved) { say(BLOCKED,true,Kind.BLOCKED);return null }
    busy=true;token++
    say(if(recorded) "Saving practice answer…" else "Sending…",kind=Kind.PROGRESS)
    return token
  }
  /** A late result from an older request (or a reset board) is ignored. */
  fun finish(requestToken: Int,card: JSONObject,label: String,result: Result<String>,recorded: Boolean): Effect? {
    if(requestToken!=token) return null
    busy=false
    if(recorded) return result.fold({ say(it);Effect(true,true) },
      { say("Could not save practice answer. Question remains pending.",true);Effect(false,false) })
    return apply(card,label,result)
  }
  /** Read-only checks to run now (after a successful reload, reconnect or restart). */
  fun checks(): List<Unresolved> = unresolved.filterKeys { it !in checking }.values.toList().also { l -> l.forEach { checking.add(DecisionScope.key(it.card)) } }
  fun reconciled(card: JSONObject,label: String,result: Result<String>): Effect {
    checking.remove(DecisionScope.key(card))
    return apply(card,label,result)
  }
  /** Restart: pending identities persisted by the source come back unresolved. */
  fun restore(pending: List<JSONObject>) {
    pending.forEach { p -> unresolved.getOrPut(DecisionScope.key(p)) { Unresolved(p,p.optString("label")) } }
    if(unresolved.isNotEmpty()) say(UNKNOWN_RESTART,true,Kind.UNKNOWN)
  }
  /** Stream or reload lost: keep an unresolved status visible instead of a generic stale line. */
  fun stale(text: String) { if(unresolved.isEmpty()) say(text,true) else say("$UNKNOWN_CHECK $text",true,Kind.UNKNOWN) }
  fun loadFailed(text: String) { if(!busy) stale(text) }
  /** Session restart or server change: in-flight results are dropped, unresolved identities remain owned by the old source. */
  fun reset() { token++;busy=false;notice=null;unresolved.clear();checking.clear() }

  private fun apply(card: JSONObject,label: String,result: Result<String>): Effect {
    val id=DecisionScope.key(card)
    result.onSuccess { text -> unresolved.remove(id);say(if(text=="Answer received") text else "$text · $label",kind=Kind.CONFIRMED);return Effect(true,true) }
    val d=result.exceptionOrNull() as? DeliveryException
    if(d==null) { unresolved[id]=Unresolved(card,label);say(UNKNOWN_CHECK,true,Kind.UNKNOWN);return Effect(false,false) }
    fun resolved(text: String,remove: Boolean,kind: Kind): Effect { unresolved.remove(id);say(text,true,kind);return Effect(remove,false) }
    fun unknown(text: String): Effect { unresolved[id]=Unresolved(card,label);say(text,true,Kind.UNKNOWN);return Effect(false,false) }
    // Before a send, a closed row means nothing was sent. After a send, ours may have committed and been
    // replaced, so the notice only says ours is not the recorded answer now.
    val sent=d.sent
    return when(d.outcome) {
      Delivery.CONFIRMED -> resolved("Answer confirmed · $label",true,Kind.CONFIRMED)
      Delivery.NOT_SENT -> resolved(if(d.message?.contains("stale")==true) "Not sent. Live data is stale; reconnect before answering."
        else "Not sent: the server could not be reached. Reconnect, then confirm again.",false,Kind.NOT_SENT)
      Delivery.NOT_RECORDED -> resolved("Not recorded: the server still shows this question open. Refresh, then confirm again if you still want to.",false,Kind.NOT_RECORDED)
      Delivery.CONFLICT -> if(sent) resolved("Conflict: the server now records a different answer · ${d.serverAnswer.orEmpty()}. Yours is not the recorded answer.",true,Kind.CONFLICT)
        else resolved("Not sent: this question is already answered on the server · ${d.serverAnswer.orEmpty()}",true,Kind.NOT_SENT)
      Delivery.WITHDRAWN -> if(sent) resolved("Conflict: this question is now withdrawn on the server. Yours is not the recorded answer.",true,Kind.CONFLICT)
        else resolved("Not sent: this question was withdrawn on the server.",true,Kind.NOT_SENT)
      Delivery.CLOSED -> if(sent) resolved("Conflict: this question is now closed on the server. Yours is not the recorded answer.",true,Kind.CONFLICT)
        else resolved("Not sent: this question is closed on the server.",true,Kind.NOT_SENT)
      Delivery.MISSING -> if(sent) unknown("Answer status unknown: this question is not listed on the server. Not resent.")
        else resolved("Not sent: this question is not on the server.",true,Kind.NOT_SENT)
      Delivery.NOT_RECORDED_YET -> unknown("Not recorded yet: the server still shows this question open, but your answer may still arrive. Not resent; checking again.")
      Delivery.UNREACHABLE -> unknown("Answer status unknown: the connection failed after sending began and the server could not be checked. Not resent; checking after reconnect.")
      Delivery.CHANGED_SERVER -> if(sent) unknown("Answer status unknown: the server's decision no longer matches this card. Not resent.")
        else resolved("Not sent: this card came from a different server. Reload decisions.",true,Kind.NOT_SENT)
    }
  }
  companion object {
    const val BLOCKED="This answer's status is still unknown. It will not be sent again until the server is checked."
    const val UNKNOWN_CHECK="Answer status unknown; checking the server before anything is resent."
    const val UNKNOWN_RESTART="An earlier answer's status is unknown; checking the server before anything is resent."
  }
}
