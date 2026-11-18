package com.overhand.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class HttpFailure(val status: Int): IllegalStateException("Server HTTP $status")

/**
 * Live answers distinguish commit from acknowledgement. Before sending, a read-only check of the same
 * decision on the same server; after sending (or after any ambiguous failure), a read-only readback.
 * Nothing here ever resends a POST: an uncertain outcome is reported, not retried.
 */
class LiveSource(private val http: HttpClient,val origin: String="",private val pending: LivePendingStore?=null,
  /** After a failed send, an open row counts as "not recorded" only once this long has passed: a request may be in flight. */
  private val settleMs: Long=SETTLE_MS,private val clock: () -> Long={ System.currentTimeMillis() }): SessionSource {
  override val recorded=false
  @Volatile var writable=false
    private set
  fun markStale() { writable=false }
  fun validate(): Boolean {
    writable=false
    val state=JSONObject(http.request("GET","/api/state",null))
    check(state.optJSONArray("projects")!=null && state.optJSONArray("decisions")!=null) { "Invalid state" }
    writable=true;return true
  }
  /** Rows read from this server carry its origin, so an answer can never be sent to another server. */
  fun adopt(rows: JSONArray,base: String=origin): JSONArray {
    for(i in 0 until rows.length()) rows.optJSONObject(i)?.put(DecisionScope.ORIGIN,base)
    return rows
  }
  override fun decisions(): JSONArray = try {
    adopt(list("/api/decisions")).also { writable=true }
  } catch(e: Exception) { writable=false;throw e }
  private fun list(path: String): JSONArray {
    val body=try { http.request("GET",path,null) } catch(e: HttpFailure) {
      if(e.status!=404) throw e
      http.request("GET","/api/state",null)
    }
    return if(body.trimStart().startsWith("[")) JSONArray(body) else JSONObject(body).getJSONArray("decisions")
  }
  /** Authoritative row for the card's decision, scoped to its project on this server; null when absent. */
  private fun authoritative(card: JSONObject): JSONObject? {
    val rows=list("/api/decisions?project="+URLEncoder.encode(card.optString("project"),"UTF-8")+"&limit=500")
    return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.firstOrNull { DecisionScope.id(it)==DecisionScope.id(card) }
  }
  private fun ownedHere(card: JSONObject)=card.optString(DecisionScope.ORIGIN)==origin
  fun pendingAnswers(): List<JSONObject> = pending?.all()?.filter { ownedHere(it) } ?: emptyList()

  override fun answer(decision: JSONObject,label: String,input: String): String {
    if(!writable) throw DeliveryException(Delivery.NOT_SENT,detail="Live data is stale; reconnect before answering")
    if(!ownedHere(decision)) throw DeliveryException(Delivery.CHANGED_SERVER)
    val before=try { authoritative(decision) } catch(e: Exception) { writable=false;throw DeliveryException(Delivery.NOT_SENT,detail=e.toString()) }
    val (pre,preAnswer)=DecisionScope.classify(decision,label,before,afterFailure=false)
    if(pre==Delivery.CONFIRMED) { pending?.remove(decision);return "Answer already recorded on the server" }
    if(pre!=Delivery.NOT_SENT) { pending?.remove(decision);throw DeliveryException(pre,preAnswer) }
    val sentAt=clock();pending?.put(decision,label,sentAt);lastSent[DecisionScope.key(decision)]=sentAt
    val acknowledged=try {
      JSONObject(http.request("POST","/api/decisions/"+URLEncoder.encode(DecisionScope.id(decision),"UTF-8"),
        JSONObject().put("answer",label).toString())).optBoolean("ok")
    } catch(e: ConnectException) {
      // The connection never opened, so no request bytes reached the server.
      writable=false;pending?.remove(decision);throw DeliveryException(Delivery.NOT_SENT,detail=e.toString())
    } catch(e: Exception) { writable=false;false }
    // Anything short of a confirmed row leaves the session stale until a successful reload.
    return try { confirm(decision,label,acknowledged) } catch(e: DeliveryException) { writable=false;throw e }
  }
  /** Read-only. Never posts and does not require a writable session. */
  fun reconcile(decision: JSONObject,label: String): String = confirm(decision,label,false)
  private val lastSent=java.util.concurrent.ConcurrentHashMap<String,Long>()
  private fun confirm(decision: JSONObject,label: String,acknowledged: Boolean): String {
    if(!ownedHere(decision)) throw DeliveryException(Delivery.CHANGED_SERVER,sent=true)
    val row=try { authoritative(decision) } catch(e: Exception) { throw DeliveryException(Delivery.UNREACHABLE,sent=true,detail=e.toString()) }
    // An unknown send time is treated as recent, never as settled.
    val sentAt=lastSent[DecisionScope.key(decision)] ?: pending?.sentAt(decision) ?: decision.optLong("device_utc_ms",clock())
    val (outcome,answer)=DecisionScope.classify(decision,label,row,afterFailure=true,settled=acknowledged || clock()-sentAt>=settleMs)
    // MISSING, UNREACHABLE and CHANGED_SERVER stay pending: the request may have committed somewhere.
    if(outcome.definite || outcome==Delivery.CONFLICT || outcome==Delivery.WITHDRAWN || outcome==Delivery.CLOSED) pending?.remove(decision)
    if(outcome==Delivery.CONFIRMED) return if(acknowledged) "Answer received" else "Answer confirmed by the server after the reply was lost"
    throw DeliveryException(outcome,answer,sent=true)
  }
  companion object {
    const val SETTLE_MS=30_000L
    fun at(base: String,pending: LivePendingStore?=null,settleMs: Long=SETTLE_MS)=LiveSource(HttpClient { method,path,body ->
      val c=URL(base+path).openConnection() as HttpURLConnection
      c.connectTimeout=1000;c.readTimeout=1600;c.requestMethod=method
      try {
        if(body!=null) {
          val bytes=body.toByteArray()
          // A fixed-length body is not retryable, so the platform stack cannot silently resend the POST.
          c.doOutput=true;c.setFixedLengthStreamingMode(bytes.size);c.setRequestProperty("Content-Type","application/json")
          c.outputStream.use { it.write(bytes) }
        }
        if(c.responseCode !in 200..299) throw HttpFailure(c.responseCode)
        c.inputStream.bufferedReader().use { it.readText() }
      } finally { c.disconnect() }
    },base,pending,settleMs)
  }
}
