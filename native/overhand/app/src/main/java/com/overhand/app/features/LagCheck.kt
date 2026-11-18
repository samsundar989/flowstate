package com.overhand.app.features

import android.content.Intent
import android.util.Log
import com.overhand.app.places.Place
import com.overhand.app.places.Places

/**
 * #1182 lag check (debug builds only). One press runs twelve steps of [LagCheck.STEP_MS]; each step removes ONE load
 * (and gives back the previous one) through the same setter its Settings switch uses, and logs
 * `lagcheck step=<n> name=<slug> state=start`, a segment mark `tools/travel/worn_fps.py` reads. The end restores every
 * value it found and logs `lagcheck done restored=1`. A pause, the headset taken off or a quit mid-run leaves the
 * "before" values saved; the next resume or launch restores them. Pure rules here, host-tested (LagCheckTest).
 * #1187: the build site is four steps (rebuilt the old way, the smooth way, drawn but held, hidden), and while a run is on
 * heard speech is still decoded (the load stays) but acts on nothing ([World.hush]).
 */
object LagCheck {
  /** #1187: 12 steps of 12 s = 144 s (the card's 150 s cap); 13 s would be 156 s. */
  const val STEP_MS = 12_000L
  const val PREF = "lagcheck-before"
  val NAMES = listOf("as_launched", "voice_off", "sky_off", "site_smooth_off", "site_smooth_on", "site_held", "site_hidden",
    "windows_hidden", "rate_90", "perf_boost", "all_off", "as_launched_again")
  val STEPS get() = NAMES.size
  /** What draws least: no sky, no passthrough (Places "Plain"). */
  val LIGHT_PLACE = Place.PLAIN.name
  /** Every window but the main one: the dock windows ([Dock.ITEMS]); the main window has no dock target. */
  val ALL_DOCK: Set<String> get() = Dock.ITEMS.map { it.view }.toSet()

  /** The loads the check steps through. [hidden] is the dock windows closed; [sitePref] the "Show build site" switch;
   *  [smooth] the "Smooth build site" switch (#1185); [held] the site's replay hold (`SiteScene.holdReplay`). */
  data class Snapshot(val voice: Boolean, val place: String, val sitePref: Boolean, val site: Boolean,
                      val hidden: Set<String>, val steady: Boolean, val boost: Boolean,
                      val smooth: Boolean = true, val held: Boolean = false) {
    fun encode() = "v=1;voice=${b(voice)};place=$place;sitepref=${b(sitePref)};site=${b(site)};" +
      "hidden=${hidden.sorted().joinToString(",")};steady=${b(steady)};boost=${b(boost)};smooth=${b(smooth)};held=${b(held)}"
    /** The values a fresh launch keeps (windows, the site's showing and its replay hold are rebuilt by every launch). */
    fun persisted() = copy(site = sitePref, hidden = emptySet(), held = false)
    private fun b(v: Boolean) = if (v) "1" else "0"
    companion object {
      fun decode(s: String?): Snapshot? = runCatching {
        val m = s!!.split(';').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        require(m["v"] == "1")
        Snapshot(m.getValue("voice") == "1", m.getValue("place"), m.getValue("sitepref") == "1", m.getValue("site") == "1",
          m.getValue("hidden").split(',').filter { it.isNotEmpty() }.toSet(), m.getValue("steady") == "1", m.getValue("boost") == "1",
          // A value saved by a #1182 build has neither: the switch's default, and no hold.
          (m["smooth"] ?: "1") == "1", m["held"] == "1")
      }.getOrNull()
    }
  }

  /** Step [n]'s state: [before] with one load changed (10: all removed, 72 Hz on; 0 and 11: as launched). The site
   *  steps 3-6 show it rebuilt the old way, rebuilt the smooth way, drawn with nothing rebuilt, then not at all. */
  fun target(n: Int, before: Snapshot): Snapshot = when (n) {
    1 -> before.copy(voice = false)
    2 -> before.copy(place = LIGHT_PLACE)
    3 -> before.copy(smooth = false)
    4 -> before.copy(smooth = true)
    5 -> before.copy(held = true)
    6 -> before.copy(sitePref = false, site = false)
    7 -> before.copy(hidden = ALL_DOCK)
    8 -> before.copy(steady = false)
    9 -> before.copy(boost = true)
    10 -> before.copy(voice = false, place = LIGHT_PLACE, sitePref = false, site = false, hidden = ALL_DOCK, steady = true, boost = true)
    else -> before
  }
  fun stepAt(elapsedMs: Long) = (elapsedMs / STEP_MS).toInt().coerceIn(0, STEPS)
  fun markLine(n: Int) = "lagcheck step=$n name=${NAMES[n]} state=start"
  /** The segment mark `tools/travel/worn_fps.py` reads (PERF_MARK `mark NAME` on an OverhandPerf line). */
  fun markSegment(n: Int) = "mark lagcheck${n}_${NAMES[n]}"

  /** The app's loads, read and set through the Settings switches' own setters. */
  interface World {
    fun read(): Snapshot
    /** [launch]: a fresh process, before the scene: only the [Snapshot.persisted] values are set. */
    fun apply(t: Snapshot, launch: Boolean = false)
    fun log(line: String)
    fun progress(step: Int?, done: Boolean)
    /** #1187: true while a run is on: heard speech is decoded as usual but acts on nothing (no command, question or reply). */
    fun hush(on: Boolean)
    /** #1233: once a second while stepping: step [step] (0-based) of [STEPS], [secondsLeft] in the whole run. */
    fun countdown(step: Int, secondsLeft: Int) {}
    /** #1233: the run's result as JSON (`lagcheck.json`), at done or abort. */
    fun result(json: String) {}
  }
  /** #1233: seconds left in the whole run, [elapsedMs] after its start (rounded up). */
  fun secondsLeft(elapsedMs: Long) = ((STEPS * STEP_MS - elapsedMs).coerceAtLeast(0) + 999) / 1000
  /** #1233: `lagcheck.json`. [steps] are (step, wall ms at its mark). */
  fun resultJson(outcome: String, restored: Int?, why: String?, startWall: Long, endWall: Long, steps: List<Pair<Int, Long>>, before: String) =
    "{\"result\":\"$outcome\",\"restored\":${restored ?: "null"},\"why\":${why?.let { "\"$it\"" } ?: "null"},\"steps_total\":$STEPS," +
      "\"step_ms\":$STEP_MS,\"steps_done\":${steps.size},\"start_epoch_ms\":$startWall,\"end_epoch_ms\":$endWall,\"steps\":[" +
      steps.joinToString(",") { (n, t) -> "{\"step\":$n,\"name\":\"${NAMES[n]}\",\"epoch_ms\":$t}" } + "],\"before\":\"$before\"}"
  interface Store { fun load(): String?; fun save(v: String?) }

  /** The run. Not thread-safe: one thread (the UI thread in the app). */
  class Machine(private val world: World, private val store: Store, private val wall: () -> Long = System::currentTimeMillis) {
    var before: Snapshot? = null; private set
    var step = -1; private set
    private var startMs = 0L
    /** A finished run waiting for its one-tick-later restore check. */
    private var checking = false
    private var pending = -1
    private var startWall = 0L
    private val marks = mutableListOf<Pair<Int, Long>>()
    val running get() = before != null && !checking

    fun start(now: Long): Boolean {
      if (before != null) return false
      recover("start")
      val b = world.read()
      before = b; store.save(b.encode()); startMs = now; step = -1; checking = false; pending = -1
      startWall = wall(); marks.clear()
      world.hush(true)
      world.log("lagcheck begin steps=$STEPS step_ms=$STEP_MS before=${b.encode()}")
      tick(now); return true
    }

    /** Call about once a second while [before] is set. Returns false once the run is over. */
    fun tick(now: Long): Boolean {
      val b = before ?: return false
      if (checking) {
        val ok = world.read() == b
        world.log("lagcheck done restored=${if (ok) 1 else 0}"); world.log("mark lagcheck_done")
        world.result(resultJson("done", if (ok) 1 else 0, null, startWall, wall(), marks, b.encode()))
        world.progress(null, true)
        before = null; checking = false; store.save(null); world.hush(false)
        return false
      }
      // The marks follow one tick after the step's change, so a switch's own `switch k=v` line (logged when its queued
      // command runs) never starts a segment after ours, and the change's first second is left out of the step.
      if (pending >= 0) { world.log(markLine(pending)); world.log(markSegment(pending)); marks.add(pending to wall()); pending = -1 }
      val n = stepAt(now - startMs)
      if (n < STEPS) world.countdown(n, secondsLeft(now - startMs).toInt())
      if (n == step) return true
      step = n
      if (n >= STEPS) { world.apply(b); checking = true; return true }
      world.apply(target(n, b))
      pending = n
      world.progress(n, false)
      return true
    }

    /** Pause, headset off, quit: stop stepping; the saved "before" values stay for [recover]. */
    fun abort(why: String) {
      if (before == null) return
      world.log("lagcheck abort step=$step why=$why"); world.log("mark lagcheck_abort")
      world.result(resultJson("aborted", null, why, startWall, wall(), marks, before!!.encode()))
      before = null; checking = false; step = -1; pending = -1; world.hush(false)
    }

    /** Resume or launch: a run that did not finish is restored from its saved "before" values, which are returned
     *  (null: nothing to restore). Setters that queue work finish later, so the caller checks [Snapshot] equality then. */
    fun recover(why: String, launch: Boolean = false): Snapshot? {
      if (before != null) return null
      val saved = Snapshot.decode(store.load() ?: return null)
      if (saved == null) { store.save(null); return null }
      world.apply(saved, launch)
      world.log("lagcheck recovered why=$why launch=${if (launch) 1 else 0}")
      store.save(null)
      world.progress(null, true)
      return saved
    }
  }
}

/** The thin feature: Settings button and debug intent (debug builds only), a 1 Hz tick only while a run is on. */
class LagCheckFeature(private val s: Shell) : Feature, LagCheck.World {
  override val name = "lagcheck"
  private val store = object : LagCheck.Store {
    override fun load() = s.prefs.getString(LagCheck.PREF, null)
    override fun save(v: String?) { s.prefs.edit().apply { if (v == null) remove(LagCheck.PREF) else putString(LagCheck.PREF, v) }.apply() }
  }
  val machine = LagCheck.Machine(this, store)
  private val tick = object : Runnable { override fun run() {
    if (s.stopped || s.paused) return
    if (machine.tick(android.os.SystemClock.elapsedRealtime())) s.ui.postDelayed(this, 1000)
  } }

  /** UI thread or any: start a run (closes Settings first). */
  fun start(why: String) { s.ui.post {
    if (!com.overhand.app.BuildConfig.DEBUG || s.stopped) return@post
    s.settings.requestClose()
    if (machine.start(android.os.SystemClock.elapsedRealtime())) { EventLog.direct(TAG, "lagcheck why=$why", log = true); s.ui.postDelayed(tick, 1000) }
  } }

  // A launch after a quit mid-run: the saved values go back before the scene reads them (prefs only).
  override fun onCreate() {
    if (com.overhand.app.BuildConfig.DEBUG) runCatching { EventLog.start(s.activity.filesDir) }
    machine.recover("launch", launch = true)?.let { checkRestored(it, "launch", true) }
  }
  override fun onPause() { s.ui.removeCallbacks(tick); machine.abort("pause") }
  override fun onResume() { s.ui.post { if (!s.stopped) machine.recover("resume")?.let { checkRestored(it, "resume", false) } } }
  /** The restore check: the values read back (after queued setters ran) equal the saved ones. */
  private fun checkRestored(saved: LagCheck.Snapshot, why: String, launch: Boolean) {
    if (launch) { log("lagcheck done restored=${if (read().persisted() == saved.persisted()) 1 else 0} why=$why"); return }
    s.ui.postDelayed({ log("lagcheck done restored=${if (read() == saved) 1 else 0} why=$why") }, 1500)
  }
  override fun onDestroy() { s.ui.removeCallbacks(tick); machine.abort("destroy") }

  override fun onDebugIntent(intent: Intent) {
    if (!com.overhand.app.BuildConfig.DEBUG) return
    if (intent.getBooleanExtra("lagcheck", false)) start("debug")
    if (intent.getBooleanExtra("lagcheck_abort", false)) s.ui.post { s.ui.removeCallbacks(tick); machine.abort("debug")
      machine.recover("debug")?.let { checkRestored(it, "abort", false) } }
  }

  override fun settingsRows() = listOf(SettingsRow(58, SettingsTab.DEBUG, first = true) { root, _ -> DeveloperSettings.addLagCheck(root) { start("settings") } })

  // World: every value through the setter its Settings switch uses.
  private fun windowOpen(view: String) = s.windows.isWindowOpen(view)
  /** `SiteScene.holdReplay` has no getter and nothing else holds the replay: the last value this check set. */
  private var held = false
  override fun read() = LagCheck.Snapshot(s.voice.listening, Places.current.name, s.interaction.siteSwitch,
    s.interaction.siteShowing, LagCheck.ALL_DOCK.filter { !windowOpen(it) }.toSet(), s.frameRate.enabled, s.frameRate.boost,
    s.interaction.smoothSite, held)
  override fun apply(t: LagCheck.Snapshot, launch: Boolean) {
    val now = read()
    if (now.voice != t.voice) s.voice.setListening(t.voice)
    if (now.place != t.place) Places.parse(t.place)?.let { Places.set(it) }
    if (now.sitePref != t.sitePref || (!launch && now.site != t.site)) s.interaction.setSiteSwitch(t.sitePref, show = if (launch) null else t.site)
    // A launch writes the pref only: the site scene (created lazily) reads it when it is made.
    if (now.smooth != t.smooth) { if (launch) s.prefs.edit().putBoolean(InteractionFeature.SMOOTH_SITE, t.smooth).apply() else s.interaction.setSmoothSite(t.smooth) }
    if (!launch && now.held != t.held) { held = t.held; s.interaction.site.holdReplay(t.held) }
    if (now.steady != t.steady) s.frameRate.setEnabled(t.steady)
    if (now.boost != t.boost) s.frameRate.setBoost(t.boost)
    if (!launch) for (v in LagCheck.ALL_DOCK) {
      val hide = v in t.hidden; val open = v !in now.hidden
      if (hide && open) s.windows.voiceHidden.add(v) else if (!hide && !open) s.windows.open(v, "lagcheck")
    }
  }
  override fun log(line: String) { EventLog.direct(FrameRateFeature.TAG, line, log = true) }
  override fun countdown(step: Int, secondsLeft: Int) { DeveloperSettings.lagCheckText(step, false, secondsLeft)?.let { s.windows.showProgress(it) } }
  override fun result(json: String) {
    restored = if ("\"restored\":1" in json) true else if ("\"restored\":0" in json) false else null
    runCatching { java.io.File(s.activity.filesDir, RESULT_FILE).writeText(json + "\n") }
      .onFailure { Log.w(TAG, "lagcheck result_write_failed error=${it.javaClass.simpleName}") }
  }
  override fun hush(on: Boolean) { com.overhand.app.voice.VoiceController.lagCheck = on }
  /** The last run's restore check, from its result (null after a restore at launch or resume). */
  private var restored: Boolean? = null
  override fun progress(step: Int?, done: Boolean) {
    // #1233: steps are shown by [countdown] (every second); this shows the done line.
    if (!done) return
    val text = DeveloperSettings.lagCheckText(step, true, restored = restored) ?: return
    restored = null
    s.windows.showProgress(text)
    if (done) s.ui.postDelayed({ s.windows.showProgress(null) }, 8000)
  }

  companion object { const val TAG = "OverhandLagCheck"; const val RESULT_FILE = "lagcheck.json" }
}
