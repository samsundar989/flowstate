package com.overhand.app.graph.site

import org.json.JSONObject

/**
 * #1035 talk to an agent on the build site. Pure (no Android), so every rule is tested on the JVM.
 * - Target: the agent under the hand ray ([aim], from [SiteScene]) is highlighted and named in the voice caption.
 *   It is fixed when speech starts ([lock], at the pinch that opens the mic), not when it ends, and released
 *   [RELEASE_MS] after the answer ([end]): a follow-up asked within that window goes to the same agent.
 * - Recorded: the answer is that agent's task and last three steps, [MAX_WORDS] words at most ([recordedAnswer]).
 * - Live: the sentence goes to that agent's session through the host (`say`); the first two sentences of its next
 *   message are read back ([reply]); a failed send says so ([failed]).
 * With "Talk to agents" off, or no agent targeted, [lock] returns null and speech behaves exactly as before.
 */
class SiteTalk(private val dwellMs: Long=DWELL_MS,private val leaveMs: Long=LEAVE_MS,private val now: () -> Long) {
  @Volatile var enabled=true
  /** The agent under the ray now (null: none). Written by the ECS thread. */
  @Volatile var hover: SiteAgent?=null;private set
  /** The agent this turn talks to. */
  @Volatile var locked: SiteAgent?=null;private set
  @Volatile private var releaseAt=Long.MAX_VALUE
  /** Sticky aim: the ray left [hover] at this time (null: on it); another agent seen since [candSince]. */
  private var offSince: Long?=null
  private var cand: SiteAgent?=null
  private var candSince=0L
  private var subject: SiteAgent?=null
  /** Explicit Yard Ask selection holds the module through speech, including an idle module. */
  fun subject(a: SiteAgent) {
    subject=a;hover=a;locked=a;releaseAt=Long.MAX_VALUE;offSince=null;cand=null
    listeners.forEach { runCatching { it(a) } }
  }
  /** Called (any thread) when what is highlighted changes. */
  val listeners=java.util.concurrent.CopyOnWriteArrayList<(SiteAgent?) -> Unit>()

  /** What the site highlights and the caption names: the locked agent while it holds, else the one under the ray. */
  fun target(): SiteAgent? { expire();leave();return if(enabled) locked ?: hover else null }

  /** The ray's agent this frame; true when the highlight changed. */
  fun aim(a: SiteAgent?): Boolean {
    expire();val before=(if(enabled) locked ?: hover else null)?.id // as last shown, before the sticky timeout below
    stick(a)
    val after=target()
    if(after?.id==before) return false
    listeners.forEach { runCatching { it(after) } };return true
  }

  /** Speech starts: the agent targeted now is the one this turn talks to (null = speech as before). */
  fun lock(): SiteAgent? {
    expire();leave()
    if(!enabled) { locked=null;return null }
    val t=subject ?: hover ?: locked
    locked=t;releaseAt=Long.MAX_VALUE
    return t
  }

  /** The turn is over (answered, interrupted or silent): the target is let go [RELEASE_MS] later. */
  fun end() { if(locked!=null && releaseAt==Long.MAX_VALUE) releaseAt=now()+RELEASE_MS }

  /**
   * The ray's raw agent this frame, made sticky (#1035: a crane mast is a degree or two wide, and his hand drifts
   * while it moves): from nothing the first agent is taken at once; a different agent only after the ray has stayed
   * on it [dwellMs]; nothing only after the ray has been off [leaveMs].
   */
  private fun stick(a: SiteAgent?) {
    val t=now();val h=hover
    when {
      a==null -> { cand=null;if(h!=null && offSince==null) offSince=t;leave() }
      h==null || a.id==h.id -> { hover=a;offSince=null;cand=null }
      else -> { if(cand?.id!=a.id) { cand=a;candSince=t }
        if(t-candSince>=dwellMs) { hover=a;offSince=null;cand=null } else if(offSince==null) offSince=t;leave() }
    }
  }

  private fun leave() { val o=offSince ?: return;if(hover!=null && now()-o>=leaveMs) { hover=null;offSince=null } }

  private fun expire() {
    if(locked!=null && now()>=releaseAt) { val was=locked;locked=null;subject=null;releaseAt=Long.MAX_VALUE
      if(hover?.id!=was?.id) listeners.forEach { runCatching { it(hover) } } }
  }

  companion object {
    const val RELEASE_MS=2_000L
    /** Sticky aim (see [stick]); chosen on his replayed pointing (SiteTalkTest's sweep, site_talk/target.json). */
    const val DWELL_MS=250L
    const val LEAVE_MS=600L
    const val MAX_WORDS=25
    /** Live: how long to wait for the agent's reply before saying it will show on the site. */
    const val REPLY_WAIT_MS=60_000L

    /** The voice caption while an agent is targeted. */
    fun caption(a: SiteAgent)="Talk to ${a.displayName} · ${a.verb}"

    /** Recorded: "<name> here. On <task>. Last: <step>; <step>; <step>." in [MAX_WORDS] words at most. */
    fun recordedAnswer(a: SiteAgent): String {
      if(a.id.startsWith("layer:")) return clip("${a.name}. ${a.now.trimEnd('.') }.",MAX_WORDS)
      val task=BuildSite.words(a.title.ifBlank { a.now },8).trimEnd('.',' ')
      val steps=a.steps.takeLast(3).map { BuildSite.words(it,6).trimEnd('.',';',' ') }.filter { it.isNotBlank() }.distinct()
      val text=buildString {
        append("${a.displayName} here.")
        if(task.isNotBlank()) append(" On ${task.replaceFirstChar { it.lowercase() }}.")
        if(steps.isNotEmpty()) append(" Last: ${steps.joinToString("; ")}.")
        else if(a.now.isNotBlank() && a.now!=a.title) append(" Now: ${BuildSite.words(a.now,8).trimEnd('.')}.")
      }
      return clip(text,MAX_WORDS)
    }

    /** At most [n] words, ending on a full stop. */
    fun clip(text: String,n: Int): String {
      val w=text.split(Regex("\\s+")).filter { it.isNotEmpty() }
      if(w.size<=n) return text.trim()
      return w.take(n).joinToString(" ").trimEnd('.',';',',',':',' ')+"."
    }

    /** Smart answers on (Recorded): the agent's own events, as the screen summary's events. */
    fun events(a: SiteAgent): List<String> = (listOf("agent ${a.name}: ${a.title.ifBlank { a.verb }}")+a.steps.takeLast(3)+listOfNotNull(a.now.takeIf { it.isNotBlank() })).distinct()

    /** Live: the first two sentences of the agent's next message on its task after [afterSeq], or null if none yet. */
    fun reply(events: List<JSONObject>,taskId: String,afterSeq: Long): String? =
      events.firstOrNull { it.optString("kind")=="message" && it.optString("task_id")==taskId && it.optLong("seq")>afterSeq }
        ?.let { e -> firstSentences(e.optString("detail").ifBlank { e.optString("title") },2) }?.takeIf { it.isNotBlank() }

    /** The first [n] whole sentences (. ! or ? then a space, the relay's rule); text with no stop counts as one. */
    fun firstSentences(text: String,n: Int): String {
      val t=text.replace(Regex("\\s+")," ").trim()
      if(t.startsWith("{")) return ""   // a closing JSON answer is not something to read aloud
      val out=ArrayList<String>();var start=0
      for(m in Regex("[.!?](\\s|$)").findAll(t)) { out.add(t.substring(start,m.range.first+1).trim());start=m.range.last+1;if(out.size==n) break }
      if(out.size<n && start<t.length) out.add(t.substring(start).trim())
      return clip(out.filter { it.isNotEmpty() }.take(n).joinToString(" "),45)
    }

    /** A failed send says so, in one line. */
    fun failed(a: SiteAgent,code: String)=when(code) {
      "offline" -> "Not sent: the computer is not connected."
      "not_allowed" -> "Not sent: sending is off in Settings."
      "not_found" -> "Not sent: ${a.name} is not working on that any more."
      "unknown_project" -> "Not sent: the computer does not know that project."
      else -> "Not sent: ${a.name} could not take it just now."
    }
    fun sent(a: SiteAgent)="Sent to ${a.name}."
    fun noReplyYet(a: SiteAgent)="Sent to ${a.name}. Its answer will show on the site."
  }
}
