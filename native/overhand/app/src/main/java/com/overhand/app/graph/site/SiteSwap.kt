package com.overhand.app.graph.site

/** ECS-thread owner of build-beside/swap. Used by the scene and the delayed-import replay. */
internal class SiteSwap<T>(private val now: () -> Long,
  private val visible: (T,Boolean) -> Unit,private val destroy: (T) -> Unit,
  private val log: (String) -> Unit,private val timeoutMs: Long=10_000,private val failed: () -> Unit={}) {
  var active: T?=null;private set
  var pending: T?=null;private set
  private var began=0L
  private var reason="initial"
  private var sequence=0L
  private class Desired<T>(val why: String,val create: () -> T,val discard: () -> Unit)
  private var queued: Desired<T>?=null
  val queuedCount get()=if(queued==null) 0 else 1
  /** A queued desire owns data only; creating its entity would start a second SDK import. */
  fun request(why: String,create: () -> T,discard: () -> Unit={}) {
    val desired=Desired(why,create,discard)
    if(pending!=null) { queued?.discard?.invoke();queued=desired } else start(desired)
  }
  private fun start(desired: Desired<T>) {
    began=now();reason=desired.why;sequence++
    try { pending=desired.create() } catch(e: Exception) {
      desired.discard();log("site event=load_failed seq=$sequence reason=$reason error=create old_kept=${active!=null}")
      failed()
    }
  }
  private fun advance(): Boolean {
    val desired=queued ?: return false
    queued=null;start(desired);return true
  }
  // Already-created values are for synchronous callers; scene imports use request's lazy factory.
  fun begin(value: T,why: String) = request(why,{ value },{ destroy(value) })
  /** owner must be the completed scene object's entity, never merely the future's lookup key. */
  fun poll(owner: T?,loaded: Boolean,noCull: () -> Unit) {
    val next=pending ?: return
    if(now()-began>=timeoutMs) { fail("timeout");return }
    if(!loaded || owner!=next) return
    noCull() // On THIS object, before its first visible frame.
    val old=active
    visible(next,true)
    val enabledAt=now()
    active=next;pending=null
    if(old!=null) {
      visible(old,false)
      val hiddenAt=now()
      destroy(old)
      log("site event=swap seq=$sequence gap_ms=${(enabledAt-hiddenAt).coerceAtLeast(0)} load_ms=${enabledAt-began} reason=$reason")
    } else log("site event=loaded seq=$sequence load_ms=${enabledAt-began} reason=$reason")
    advance()
  }
  fun fail(why: String) {
    pending?.let(destroy);pending=null
    log("site event=load_failed seq=$sequence reason=$reason error=$why old_kept=${active!=null}")
    if(!advance()) failed()
  }
  fun cancelPending() { queued?.discard?.invoke();queued=null;pending?.let(destroy);pending=null }
  fun clear() { cancelPending();active?.let(destroy);active=null }
}
