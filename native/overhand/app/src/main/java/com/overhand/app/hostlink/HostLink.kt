package com.overhand.app.hostlink

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.security.cert.X509Certificate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.random.Random

/** Knobs, defaults for a headset on home Wi-Fi. Tests shorten them. */
data class LinkTiming(val backoffBaseMs: Long=500,val backoffMaxMs: Long=30_000,val pingMs: Long=20_000,
  val connectTimeoutMs: Long=4_000,val pairTimeoutMs: Long=10_000,val confirmTimeoutMs: Long=125_000,val rediscoverAfterFailures: Int=2,val discoveryMs: Long=3_000)

/** How one answer ended, as seen in the host's own event log. */
enum class AnswerOutcome { CONFIRMED, CONFLICT, CLOSED }

/**
 * The headset's end of the host link (docs/host/PROTOCOL.md v1): pairs, pins the certificate, holds one
 * resumable WebSocket, and keeps the host's event log in order. Every event is applied exactly once, in seq
 * order: duplicates are dropped, a gap forces a resume from the last applied seq.
 *
 * Answers are sent at least once with an id and are confirmed only by the host's `answered` event (or closed by
 * `finished`/`unknown_decision`). After a drop they are resent only once the replay has caught up to the head the
 * host announced, so an answer the host already took is never sent again. [mayTransmit] (the "Send real answers"
 * switch) gates every answer frame, including resends.
 *
 * All state changes run on one internal thread. Pairing calls block; call them off the UI thread.
 */
class HostLink(private val store: PairedHostStore,private val discovery: HostDiscovery=NoDiscovery,
  private val deviceName: String="Headset",private val mayTransmit: () -> Boolean,
  private val timing: LinkTiming=LinkTiming(),private val random: Random=Random.Default,
  baseClient: OkHttpClient?=null) {

  // After shutdown() (the activity is destroyed, e.g. the session restarts right after pairing), OkHttp callbacks still in
  // flight must not throw RejectedExecutionException on its dispatcher thread, which kills the app (#814, seen on device).
  private val exec=java.util.concurrent.ScheduledThreadPoolExecutor(1,{ r -> Thread(r,"hostlink").apply { isDaemon=true } },
    java.util.concurrent.ThreadPoolExecutor.DiscardPolicy())
  private val base=(baseClient ?: OkHttpClient()).newBuilder().retryOnConnectionFailure(false)
    .connectTimeout(timing.connectTimeoutMs,TimeUnit.MILLISECONDS).readTimeout(0,TimeUnit.MILLISECONDS).build()

  @Volatile var status=LinkStatus(if(store.load()==null) LinkState.UNPAIRED else LinkState.RECONNECTING,"")
    private set
  val statusListeners=CopyOnWriteArrayList<(LinkStatus) -> Unit>()
  val eventListeners=CopyOnWriteArrayList<(JSONObject) -> Unit>()
  /** #973: replies that are not log events (`file_chunk`, `ok`, `error`), on the link thread. */
  val frameListeners=CopyOnWriteArrayList<(JSONObject) -> Unit>()
  /** Fires on the link thread each time a (re)connection has replayed everything up to the host's head. */
  val caughtUpListeners=CopyOnWriteArrayList<() -> Unit>()

  /** Highest seq applied. In memory: a new process resumes from 0 and rebuilds from the host's log. */
  @Volatile var lastSeq=0L
    private set
  private val applied=CopyOnWriteArrayList<JSONObject>()
  val events: List<JSONObject> get()=applied
  /** decision id -> question event; option chosen per answered decision; finished runs. */
  private val questions=ConcurrentHashMap<String,JSONObject>()
  private val answeredWith=ConcurrentHashMap<String,String>()
  private val finishedRuns=ConcurrentHashMap.newKeySet<String>()
  @Volatile var hostName=""
    private set
  /** Project rows from the last `welcome`. */
  @Volatile var projects=JSONArray()
    private set

  // ---- connection state (link thread only) ----
  private var running=false
  private var socket: WebSocket?=null
  private var connId=0
  private var authed=false
  private var headSeq=0L
  private var caughtUp=false
  private var failures=0
  private var retry: ScheduledFuture<*>?=null
  private class Outgoing(val decisionId: String,val optionId: String,val reqId: String,val done: CompletableFuture<AnswerOutcome>,var sentOn: Int=-1)
  private val outbox=LinkedHashMap<String,Outgoing>()
  /** #815: confirmed new-project requests not yet seen in the host's log, by req_id. */
  private class Creating(val reqId: String,val frame: JSONObject,val done: CompletableFuture<JSONObject>,var sentOn: Int=-1)
  private val creating=LinkedHashMap<String,Creating>()
  /** req_id -> {project_id, task_id}, from the log (the host writes req_id on the project it made). */
  private val createdBy=ConcurrentHashMap<String,JSONObject>()
  /** create_project frames written to a socket (each try counts). */
  @Volatile var createFramesSent=0
    private set
  /** Answer frames written to a socket (each try counts). */
  @Volatile var answerFramesSent=0
    private set

  val paired: PairedHost? get()=store.load()
  fun isOpen(decisionId: String): Boolean {
    val q=questions[decisionId] ?: return false
    return !answeredWith.containsKey(decisionId) && q.optString("run_id") !in finishedRuns
  }
  fun question(decisionId: String): JSONObject?=questions[decisionId]
  fun answeredOption(decisionId: String): String?=answeredWith[decisionId]
  fun openQuestions(): List<JSONObject> = questions.values.filter { isOpen(it.getString("decision_id")) }.sortedBy { it.getLong("seq") }

  // ---- pairing ----
  fun pairWithQr(text: String): Paired {
    val invite=try { PairingInvite.parse(text) } catch(e: PairRefused) { setStatus(LinkState.REFUSED,e.refusal.words);throw e }
    return pair(invite.endpoint,Frames.pairToken(invite.token,deviceName),invite.token)
  }
  fun pairWithCode(endpoint: Endpoint,code: String): Paired {
    val digits=code.filter { !it.isWhitespace() }
    if(!Regex("^\\d{6}$").matches(digits)) { setStatus(LinkState.REFUSED,Refusal.BAD_CODE.words);throw PairRefused(Refusal.BAD_CODE) }
    return pair(endpoint,Frames.pairCode(digits,deviceName),digits)
  }
  private fun pair(ep: Endpoint,frame: JSONObject,secret: String): Paired {
    stop()
    setStatus(LinkState.PAIRING,"Pairing with ${ep.name}")
    val tm=PinningTrustManager(ep.fp)
    val reply=CompletableFuture<JSONObject>()
    val ws=client(tm).newWebSocket(Request.Builder().url(ep.url).build(),object: WebSocketListener() {
      override fun onOpen(webSocket: WebSocket,response: Response) { webSocket.send(frame.toString()) }
      override fun onMessage(webSocket: WebSocket,text: String) { runCatching { JSONObject(text) }.onSuccess { reply.complete(it) }.onFailure { reply.completeExceptionally(it) } }
      override fun onFailure(webSocket: WebSocket,t: Throwable,response: Response?) { reply.completeExceptionally(t) }
      override fun onClosing(webSocket: WebSocket,code: Int,reason: String) { reply.completeExceptionally(IllegalStateException("closed $code")) }
    })
    val f=try { reply.get(timing.pairTimeoutMs,TimeUnit.MILLISECONDS) } catch(e: Exception) {
      ws.cancel()
      val refusal=if(tm.seen!=null && tm.seen!=ep.fp) Refusal.WRONG_CERTIFICATE else Refusal.UNREACHABLE
      setStatus(LinkState.REFUSED,refusal.words);throw PairRefused(refusal,e)
    }
    ws.close(1000,"paired")
    when(f.optString("type")) {
      "paired" -> {}
      "error" -> { val r=Refusal.ofCode(f.optString("code"));setStatus(LinkState.REFUSED,r.words);throw PairRefused(r) }
      else -> { setStatus(LinkState.REFUSED,Refusal.PROTOCOL.words);throw PairRefused(Refusal.PROTOCOL) }
    }
    val host=PairedHost(ep.copy(name=f.optString("host_name").ifBlank { ep.name }.take(32)),f.getString("device_id"),f.getString("device_secret"))
    store.save(host)
    // Shown to the wearer: computed from the certificate this headset actually saw, not from the host's claim.
    val paired=Paired(host,Pin.check(tm.seen!!,secret))
    exec.execute { lastSeq=0;applied.clear();questions.clear();answeredWith.clear();finishedRuns.clear() }
    start()
    return paired
  }
  // ---- pairing by confirming six digits on the computer (PROTOCOL.md 2d) ----
  private object Cancelled
  private class Closed(val code: Int)
  /**
   * Pairs with a host found by discovery without typing anything: shows [LinkState.CONFIRM_ON_COMPUTER] with six
   * digits computed from the certificate this headset saw, and returns once the person at the computer types y.
   * Blocks up to [LinkTiming.confirmTimeoutMs]; call it off the UI thread. Nothing is stored unless the host
   * answers `paired`. An older host that does not know this exchange gives [Refusal.USE_CODE]: offer the keypad.
   */
  fun pairByConfirm(ep: Endpoint): Paired {
    stop()
    setStatus(LinkState.PAIRING,"Pairing with ${ep.name}")
    val tm=PinningTrustManager(ep.fp)
    val q=java.util.concurrent.LinkedBlockingQueue<Any>().also { cancelQueue=it }
    val pub=Confirm.newDevicePub();val myNonce=Confirm.newNonce()
    val ws=client(tm).newWebSocket(Request.Builder().url(ep.url).build(),object: WebSocketListener() {
      override fun onOpen(webSocket: WebSocket,response: Response) { webSocket.send(Frames.pairRequest(pub,deviceName).toString()) }
      override fun onMessage(webSocket: WebSocket,text: String) { q.put(runCatching { JSONObject(text) }.getOrElse { Closed(4400) }) }
      override fun onFailure(webSocket: WebSocket,t: Throwable,response: Response?) { q.put(t) }
      override fun onClosing(webSocket: WebSocket,code: Int,reason: String) { q.put(Closed(code)) }
    })
    fun refuse(r: Refusal): Nothing { ws.cancel();cancelQueue=null;setStatus(LinkState.REFUSED,r.words);throw PairRefused(r) }
    fun next(ms: Long,want: Set<String>): JSONObject {
      while(true) {
        val x=q.poll(ms,TimeUnit.MILLISECONDS) ?: refuse(if(want.contains("paired")) Refusal.CONFIRM_EXPIRED else Refusal.UNREACHABLE)
        when(x) {
          Cancelled -> { ws.cancel();setStatus(LinkState.UNPAIRED,"Not paired with a computer");throw PairRefused(Refusal.CANCELLED) }
          is Throwable, is Closed -> refuse(if(tm.seen!=null && tm.seen!=ep.fp) Refusal.WRONG_CERTIFICATE else if(x is Closed && x.code==4400) Refusal.USE_CODE else Refusal.UNREACHABLE)
          is JSONObject -> when(x.optString("type")) {
            in want -> return x
            "pair_pending" -> {}
            "pair_refused" -> refuse(Refusal.ofReason(x.optString("reason")))
            // A host from before this exchange rejects pair_request as an unknown frame: fall back to the typed code.
            "error" -> refuse(if(x.optString("code")=="bad_request") Refusal.USE_CODE else Refusal.ofCode(x.optString("code")))
            else -> refuse(Refusal.PROTOCOL)
          }
        }
      }
    }
    val c=next(timing.pairTimeoutMs,setOf("pair_commit")).getString("c")
    ws.send(Frames.pairNonce(myNonce).toString())
    val hostNonce=next(timing.pairTimeoutMs,setOf("pair_reveal")).getString("nonce")
    val seen=tm.seen ?: refuse(Refusal.PROTOCOL)
    // The host committed to its nonce before it saw ours. A reveal that does not open the commitment, or a commitment
    // made with another certificate (a relay), means someone in between: stop before showing any digits.
    if(Confirm.commitment(seen,pub,hostNonce)!=c) refuse(Refusal.NOT_THIS_COMPUTER)
    val digits=Confirm.digits(seen,pub,hostNonce,myNonce)
    setStatus(LinkState.CONFIRM_ON_COMPUTER,"Check that the computer shows ${Confirm.spaced(digits)}, then press y on its keyboard.",digits)
    val f=next(timing.confirmTimeoutMs,setOf("paired"))
    if(f.optString("check")!=Pin.check(seen,digits)) refuse(Refusal.NOT_THIS_COMPUTER)
    ws.close(1000,"paired");cancelQueue=null
    val host=PairedHost(ep.copy(name=f.optString("host_name").ifBlank { ep.name }.take(32)),f.getString("device_id"),f.getString("device_secret"))
    store.save(host)   // the fingerprint is stored only now, after `paired`
    exec.execute { lastSeq=0;applied.clear();questions.clear();answeredWith.clear();finishedRuns.clear() }
    start()
    return Paired(host,Pin.check(seen,digits))
  }
  /** The wearer pressed Cancel, or saw different digits on the two screens: the pairing in flight ends, nothing is kept. */
  fun cancelPairing() { cancelQueue?.put(Cancelled) }
  @Volatile private var cancelQueue: java.util.concurrent.LinkedBlockingQueue<Any>?=null

  /** "Not my computer", or unpair from Settings: drops the socket and the stored credential. */
  fun forget() { stop();store.clear();setStatus(LinkState.UNPAIRED,"Not paired with a computer") }

  // ---- connection ----
  fun start() { exec.execute { if(!running) { running=true;failures=0;connect() } } }
  /** App resumed, or the wearer pressed Retry: try now instead of waiting out the backoff. */
  fun reconnectNow() { exec.execute { if(running && !authed) { retry?.cancel(false);socket?.cancel();connect() } } }
  fun stop() {
    if(exec.isShutdown) return
    exec.submit {
      running=false;retry?.cancel(false);socket?.close(1000,"bye");socket=null;authed=false
      if(store.load()!=null) setStatus(LinkState.RECONNECTING,"Paused")
    }.get()
  }
  /** Simulates a Wi-Fi drop: kills the TCP connection with no close handshake. */
  fun dropForTest() { exec.execute { socket?.cancel() } }
  fun shutdown() { runCatching { stop() };exec.shutdownNow();base.dispatcher.executorService.shutdown();base.connectionPool.evictAll() }

  private fun client(tm: PinningTrustManager)=base.newBuilder()
    .sslSocketFactory(tm.context().socketFactory,tm)
    // The certificate names no IP: the pin is the identity, so the hostname check re-checks the pin.
    .hostnameVerifier { _,session -> runCatching { Pin.of(session.peerCertificates[0] as X509Certificate)==tm.pinned }.getOrDefault(false) }
    .pingInterval(timing.pingMs,TimeUnit.MILLISECONDS).build()

  private fun connect() {
    if(!running) return
    val host=store.load() ?: run { running=false;setStatus(LinkState.UNPAIRED,"Not paired with a computer");return }
    val id=++connId;authed=false;caughtUp=false
    if(status.state!=LinkState.SEARCHING) setStatus(LinkState.RECONNECTING,if(failures==0) "Connecting to ${host.name}" else "Reconnecting to ${host.name}")
    val tm=PinningTrustManager(host.fp)
    socket=client(tm).newWebSocket(Request.Builder().url(host.endpoint.url).build(),object: WebSocketListener() {
      override fun onOpen(webSocket: WebSocket,response: Response) {
        webSocket.send(Frames.resume(host.deviceId,host.deviceSecret,lastSeq).toString())
      }
      override fun onMessage(webSocket: WebSocket,text: String) { exec.execute { if(id==connId) onFrame(text) } }
      override fun onClosing(webSocket: WebSocket,code: Int,reason: String) { webSocket.close(1000,null);exec.execute { if(id==connId) lost(code,tm) } }
      override fun onFailure(webSocket: WebSocket,t: Throwable,response: Response?) { exec.execute { if(id==connId) lost(0,tm) } }
    })
  }

  private fun onFrame(text: String) {
    val f=runCatching { JSONObject(text) }.getOrNull() ?: return resync()
    when(f.optString("type")) {
      "welcome" -> {
        authed=true;failures=0;headSeq=f.optLong("head_seq");hostName=f.optString("host_name")
        projects=f.optJSONArray("projects") ?: JSONArray()
        setStatus(LinkState.CONNECTED,"Connected to ${hostName.ifBlank { store.load()?.name.orEmpty() }}")
        checkCaughtUp()
      }
      "event" -> {
        val seq=f.optLong("seq",-1)
        if(seq<=lastSeq) return                       // replayed twice: already applied
        if(seq!=lastSeq+1) return resync()            // a gap: resume from what was applied
        apply(f)
        checkCaughtUp()
      }
      "ok" -> {
        // #973: a follow-up `prompt` is done when the host says it queued the task.
        val req=f.optString("req_id")
        creating[req]?.takeIf { it.frame.optString("type")=="prompt" && f.has("task_id") }?.let { c ->
          val made=JSONObject().put("project_id",c.frame.optString("project_id")).put("task_id",f.optString("task_id"))
          createdBy[req]=made;creating.remove(req);c.done.complete(made)
        }
        frameListeners.forEach { runCatching { it(f) } }
      }
      "file_chunk" -> frameListeners.forEach { runCatching { it(f) } }
      "error" -> {
        frameListeners.forEach { runCatching { it(f) } }
        val req=f.optString("req_id")
        val out=outbox.values.firstOrNull { it.reqId==req }
        if(out!=null && f.optString("code")=="unknown_decision") {
          outbox.remove(out.decisionId)
          out.done.complete(answeredWith[out.decisionId]?.let { if(it==out.optionId) AnswerOutcome.CONFIRMED else AnswerOutcome.CONFLICT } ?: AnswerOutcome.CLOSED)
        }
      }
    }
  }

  private fun apply(e: JSONObject) {
    lastSeq=e.getLong("seq");applied.add(e)
    val did=e.optString("decision_id")
    when(e.optString("kind")) {
      "question" -> if(did.isNotEmpty()) questions[did]=e
      "answered" -> if(did.isNotEmpty()) {
        val opt=e.optString("option_id");answeredWith[did]=opt
        outbox.remove(did)?.let { it.done.complete(if(it.optionId==opt) AnswerOutcome.CONFIRMED else AnswerOutcome.CONFLICT) }
      }
      "task_state" -> e.optString("req_id").takeIf { it.isNotEmpty() && e.optString("state")=="queued" }?.let { req ->
        val made=JSONObject().put("project_id",e.optString("project_id")).put("task_id",e.optString("task_id"))
        createdBy[req]=made;creating.remove(req)?.done?.complete(made)
      }
      "finished" -> {
        val run=e.optString("run_id");finishedRuns.add(run)
        outbox.values.filter { questions[it.decisionId]?.optString("run_id")==run }.forEach { outbox.remove(it.decisionId);it.done.complete(AnswerOutcome.CLOSED) }
      }
    }
    eventListeners.forEach { runCatching { it(e) } }
  }

  private fun checkCaughtUp() {
    if(!authed || caughtUp || lastSeq<headSeq) return
    caughtUp=true
    caughtUpListeners.forEach { runCatching { it() } }
    flush()
  }

  /** Re-checks the switch; sends every unconfirmed answer once on this connection. */
  fun flush() { exec.execute { flushNow() } }
  private fun flushNow() {
    if(!authed || !caughtUp || !mayTransmit()) return
    val ws=socket ?: return
    for(o in outbox.values) if(o.sentOn!=connId) {
      if(!ws.send(Frames.answer(o.reqId,o.decisionId,o.optionId).toString())) return
      o.sentOn=connId;answerFramesSent++
    }
    // Sent only once caught up: if the host already made this project, its log said so above and it is not resent.
    for(c in creating.values) if(c.sentOn!=connId) {
      if(!ws.send(c.frame.toString())) return
      c.sentOn=connId;if(c.frame.optString("type")=="prompt") promptSent++ else createFramesSent++
    }
  }

  /**
   * #815: asks the computer to start a new project with [prompt] (a transcript the wearer has seen and confirmed).
   * Completes with {project_id, task_id} when the host's log shows the queued task. Survives drops: it is sent after
   * each catch-up until the log shows it, and the host answers a repeated [reqId] without making a second project.
   * Gated by [mayTransmit] like every other frame.
   */
  fun createProject(prompt: String,reqId: String="c_"+java.util.UUID.randomUUID().toString().replace("-","").take(12)): CompletableFuture<JSONObject> {
    val done=CompletableFuture<JSONObject>()
    exec.execute {
      val made=createdBy[reqId]
      if(made!=null) done.complete(made)
      else { creating[reqId]=Creating(reqId,Frames.createProject(reqId,prompt),done);flushNow() }
    }
    return done
  }
  /**
   * #973: a follow-up change to [projectId] (a transcript the wearer saw on its chip and confirmed). Sent and resent
   * like [createProject], gated by [mayTransmit]; completes with {project_id, task_id} on the host's `ok` or log.
   */
  fun followUp(projectId: String,text: String,reqId: String): CompletableFuture<JSONObject> {
    val done=CompletableFuture<JSONObject>()
    exec.execute {
      val made=createdBy[reqId]
      if(made!=null) done.complete(made)
      else { creating[reqId]=Creating(reqId,Frames.prompt(reqId,projectId,text),done);flushNow() }
    }
    return done
  }
  /** Follow-up `prompt` frames written to a socket (each try counts). */
  val promptFramesSent get()=promptSent
  @Volatile private var promptSent=0
  /**
   * #973: a read (`get_file`): sent now if the link is up, never queued (the caller asks again on the next preview).
   * Not gated by [mayTransmit]: it changes nothing on the computer. Returns false if it could not be written.
   */
  fun request(frame: JSONObject): CompletableFuture<Boolean> {
    val done=CompletableFuture<Boolean>()
    exec.execute { val r=recordedHost;done.complete(if(r!=null) { r(frame);true } else authed && socket?.send(frame.toString())==true) }
    return done
  }
  // ---- #1008: a recorded host in place of the socket (Recorded mode's build-from-nothing replay) ----
  @Volatile private var recordedHost: ((JSONObject) -> Unit)?=null
  /** Reads ([request]) go to [host] instead of a socket. Nothing else is sent: answers stay gated by [mayTransmit]. */
  fun attachRecorded(host: (JSONObject) -> Unit) { exec.execute { recordedHost=host } }
  /** One host-to-headset frame, through the same handler the socket's frames go through. */
  fun feed(text: String) { exec.execute { onFrame(text) } }

  /**
   * #1035: one spoken sentence for one running task's own agent (`say`). Completes with the host's `ok` or `error`
   * frame, or a local `error` (code `offline` or `not_allowed`) when it could not be written. Never queued or resent:
   * words meant for now are not delivered later. Gated by [mayTransmit] like every frame that changes something.
   */
  fun say(projectId: String,taskId: String,text: String,reqId: String="s_"+java.util.UUID.randomUUID().toString().replace("-","").take(12)): CompletableFuture<JSONObject> {
    val done=CompletableFuture<JSONObject>()
    val l: (JSONObject) -> Unit={ f -> if(f.optString("req_id")==reqId && f.optString("type") in setOf("ok","error")) done.complete(f) }
    frameListeners.add(l);done.whenComplete { _,_ -> frameListeners.remove(l) }
    fun local(code: String)=JSONObject().put("type","error").put("code",code).put("req_id",reqId)
    exec.execute {
      if(!mayTransmit()) done.complete(local("not_allowed"))
      else if(!(authed && socket?.send(Frames.say(reqId,projectId,taskId,text).toString())==true)) done.complete(local("offline"))
    }
    return done
  }
  /** Whether a confirmed new project is still waiting to reach the computer. */
  val creatingCount get()=creating.size

  /**
   * Queues an answer; it is sent now if connected and caught up, else after the next resume. The future completes
   * when the host's log shows the outcome. Nothing is sent while [mayTransmit] is false.
   */
  fun answer(decisionId: String,optionId: String): CompletableFuture<AnswerOutcome> {
    val done=CompletableFuture<AnswerOutcome>()
    exec.execute {
      val prior=answeredWith[decisionId]
      when {
        prior!=null -> done.complete(if(prior==optionId) AnswerOutcome.CONFIRMED else AnswerOutcome.CONFLICT)
        !isOpen(decisionId) -> done.complete(AnswerOutcome.CLOSED)
        else -> {
          val existing=outbox[decisionId]
          if(existing!=null && existing.optionId==optionId) existing.done.whenComplete { r,t -> if(t!=null) done.completeExceptionally(t) else done.complete(r) }
          else { existing?.done?.complete(AnswerOutcome.CONFLICT);outbox[decisionId]=Outgoing(decisionId,optionId,"a_$decisionId",done);flushNow() }
        }
      }
    }
    return done
  }
  fun awaitAnswer(f: CompletableFuture<AnswerOutcome>,ms: Long): AnswerOutcome?=try { f.get(ms,TimeUnit.MILLISECONDS) } catch(e: TimeoutException) { null }

  private fun resync() { socket?.cancel() }

  private fun lost(code: Int,tm: PinningTrustManager) {
    socket=null;val wasAuthed=authed;authed=false
    if(!running) return
    when {
      code==4401 && !wasAuthed -> { running=false;setStatus(LinkState.REFUSED,Refusal.FORGOTTEN.words);return }
      code==4426 -> { running=false;setStatus(LinkState.REFUSED,Refusal.VERSION.words);return }
    }
    if(tm.seen!=null && tm.seen!=tm.pinned) setStatus(LinkState.RECONNECTING,Refusal.WRONG_CERTIFICATE.words)
    failures++
    if(failures>=timing.rediscoverAfterFailures && discovery!==NoDiscovery) {
      val host=store.load() ?: return
      setStatus(LinkState.SEARCHING,"Looking for ${host.name} on this Wi-Fi")
      val found=runCatching { discovery.resolve(host.fp,timing.discoveryMs) }.getOrNull()
      if(found!=null && (found.address!=host.endpoint.address || found.port!=host.endpoint.port))
        store.save(host.copy(endpoint=found.copy(name=host.name)))
    }
    val cap=minOf(timing.backoffMaxMs,timing.backoffBaseMs shl minOf(failures-1,16))
    val delay=cap/2+random.nextLong(cap/2+1)   // exponential, with jitter so many headsets do not retry in step
    if(status.state!=LinkState.SEARCHING) setStatus(LinkState.RECONNECTING,"Reconnecting to ${store.load()?.name.orEmpty()}")
    retry=exec.schedule({ connect() },delay,TimeUnit.MILLISECONDS)
  }

  private fun setStatus(state: LinkState,detail: String,digits: String?=null) {
    val s=LinkStatus(state,detail,digits)
    if(s==status) return
    status=s;statusListeners.forEach { runCatching { it(s) } }
  }
}

