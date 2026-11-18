package com.overhand.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * What the client knows about one Live answer. A commit is only CONFIRMED by an authoritative
 * read of the same decision on the same server; an `ok:true` reply alone is not decision-scoped.
 */
enum class Delivery(val definite: Boolean) {
  CONFIRMED(true),        // server row for this decision is answered with exactly this label
  NOT_SENT(true),         // nothing reached the server (stale session, connect failure, preflight unreachable)
  NOT_RECORDED(true),     // row still open after the settle period following a failed send
  NOT_RECORDED_YET(false),// row open, but the failed send is recent: a delayed request may still commit
  CONFLICT(false),        // row is answered with a different answer
  WITHDRAWN(false),       // row is withdrawn
  CLOSED(false),          // row closed with another status
  MISSING(false),         // decision id not present in the server's scoped list
  UNREACHABLE(false),     // request may have committed; the check could not reach the server
  CHANGED_SERVER(false);  // card belongs to another server or decision identity differs
}

/** A non-confirmed result. Extends IllegalStateException so existing failure paths still see a failure. */
class DeliveryException(val outcome: Delivery, val serverAnswer: String?=null, val sent: Boolean=false,
  detail: String=outcome.name): IllegalStateException(detail)

/** Identity captured when the card was read: server origin, project, decision id and question text. */
object DecisionScope {
  const val ORIGIN="overhand_origin"
  fun id(d: JSONObject)=d.optString("id")
  /** Identity key: a reused id with a different question is a different decision. */
  fun key(d: JSONObject)=d.optString(ORIGIN)+"|"+d.optString("project")+"|"+id(d)+"|"+d.optString("question")
  fun sameDecision(card: JSONObject,row: JSONObject)=id(card)==id(row) &&
    card.optString("project")==row.optString("project") && card.optString("question")==row.optString("question")
  /** Pure classification of an authoritative row against the card and label the user confirmed. */
  fun classify(card: JSONObject,label: String,row: JSONObject?,afterFailure: Boolean,settled: Boolean=true): Pair<Delivery,String?> {
    if(row==null) return Delivery.MISSING to null
    if(!sameDecision(card,row)) return Delivery.CHANGED_SERVER to null
    val answer=if(row.isNull("answer")) null else row.optString("answer")
    return when(row.optString("status","open")) {
      "open" -> (if(!afterFailure) Delivery.NOT_SENT else if(settled) Delivery.NOT_RECORDED else Delivery.NOT_RECORDED_YET) to null
      "answered" -> (if(answer==label.trim()) Delivery.CONFIRMED else Delivery.CONFLICT) to answer
      "withdrawn" -> Delivery.WITHDRAWN to answer
      else -> Delivery.CLOSED to answer
    }
  }
}

/** Live answers whose outcome is not yet known, persisted so a restart neither forgets nor blindly resends them. */
class LivePendingStore(private val file: File) {
  // Re-read on every operation under one process-wide lock: an old and a new activity may hold separate instances.
  private fun load(): List<JSONObject> = (if(!file.exists()) JSONArray() else runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray()))
    .let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
  fun all(): List<JSONObject> = synchronized(LOCK) { load() }
  fun put(card: JSONObject,label: String,sentUtcMs: Long=System.currentTimeMillis()) { synchronized(LOCK) {
    val entry=JSONObject().put("id",DecisionScope.id(card)).put("project",card.optString("project"))
      .put("question",card.optString("question")).put(DecisionScope.ORIGIN,card.optString(DecisionScope.ORIGIN))
      .put("label",label).put("device_utc_ms",sentUtcMs)
    persist(JSONArray(load().filter { DecisionScope.key(it)!=DecisionScope.key(entry) }.plus(entry)))
  } }
  fun sentAt(card: JSONObject): Long? = all().firstOrNull { DecisionScope.key(it)==DecisionScope.key(card) }?.optLong("device_utc_ms")
  fun remove(card: JSONObject) { synchronized(LOCK) {
    val rows=load();if(rows.any { DecisionScope.key(it)==DecisionScope.key(card) }) persist(JSONArray(rows.filter { DecisionScope.key(it)!=DecisionScope.key(card) }))
  } }
  companion object { private val LOCK=Any() }
  private fun persist(next: JSONArray) {
    file.parentFile?.mkdirs()
    val temp=File(file.path+".tmp")
    try {
      FileOutputStream(temp).use { it.write(next.toString().toByteArray(Charsets.UTF_8));it.fd.sync() }
      Files.move(temp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
    } finally { temp.delete() }
  }
}
