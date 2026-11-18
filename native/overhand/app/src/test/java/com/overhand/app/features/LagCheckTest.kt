package com.overhand.app.features

import com.overhand.app.features.LagCheck.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1182: the lag check's state machine over a model of the app's loads. A normal run, and an abort (pause / headset off,
 * restored on resume; quit, restored on the next launch) at every step, must each end with every value it found.
 * The planted control is the same harness over a world whose restore of one load is broken: it must count failures.
 * Prints `lagcheck_steps=N lagcheck_restore_failures=F lagcheck_planted_restore_failures=P` for scripts/freeze_gates.py.
 * #1187: twelve steps (the site rebuilt the old way, the smooth way, held, hidden); voice is hushed for exactly the run.
 */
class LagCheckTest {
  /** The app's loads. [brokenVoiceRestore]: a setter that can turn listening off but never back on (planted control). */
  private class World(var s: Snapshot, val brokenVoiceRestore: Boolean = false) : LagCheck.World {
    val logs = mutableListOf<String>(); var progress = mutableListOf<String>(); var hushed = false
    override fun read() = s
    override fun apply(t: Snapshot, launch: Boolean) {
      var n = if (launch) s.copy(voice = t.voice, place = t.place, sitePref = t.sitePref, steady = t.steady, boost = t.boost, smooth = t.smooth) else t
      if (brokenVoiceRestore && n.voice && !s.voice) n = n.copy(voice = false)
      s = n
    }
    override fun log(line: String) { logs += line }
    override fun hush(on: Boolean) { hushed = on }
    override fun progress(step: Int?, done: Boolean) { progress += if (done) "done" else "${step!! + 1}/${LagCheck.STEPS}" }
    /** A fresh process: the persisted values as last written; windows and the site as a launch rebuilds them. */
    fun relaunch() = World(s.copy(site = s.sitePref, hidden = LagCheck.ALL_DOCK, held = false), brokenVoiceRestore)
  }
  private class Store(var v: String? = null) : LagCheck.Store { override fun load() = v; override fun save(v: String?) { this.v = v } }

  /** Everything on, as a wearer meets it after opening two windows. */
  private val heavy = Snapshot(voice = true, place = "MOUNTAIN", sitePref = true, site = true, hidden = setOf("fleet", "build"), steady = true, boost = false)

  private fun runToEnd(m: LagCheck.Machine, from: Long = 0L): Long {
    var t = from
    while (m.tick(t)) { t += 1000; check(t < 400_000) }
    return t
  }

  /** Failures over: one normal run plus, for every step, an abort-then-resume and an abort-then-relaunch. */
  private fun failures(broken: Boolean, report: MutableList<String>? = null, start: Snapshot = heavy): Int {
    var fail = 0
    // Normal run.
    World(start, broken).let { w -> val st = Store(); val m = LagCheck.Machine(w, st); m.start(0); runToEnd(m)
      val ok = w.s == start && st.v == null && "lagcheck done restored=1" in w.logs && !w.hushed
      if (!ok) fail++; report?.add("normal ok=$ok end=${w.s.encode()}") }
    for (k in 0 until LagCheck.STEPS) {
      val at = k * LagCheck.STEP_MS + 2_000
      // Pause / headset off at step k, resume.
      World(start, broken).let { w -> val st = Store(); val m = LagCheck.Machine(w, st); m.start(0)
        var t = 1000L; while (t <= at) { m.tick(t); t += 1000 }
        val hushedMid = w.hushed
        m.abort("pause"); m.recover("resume")
        val ok = w.s == start && st.v == null && hushedMid && !w.hushed
        if (!ok) fail++; report?.add("abort_resume step=$k name=${LagCheck.NAMES[k]} ok=$ok") }
      // Quit at step k, next launch (a new machine over a new world, the same prefs).
      World(start, broken).let { w -> val st = Store(); val m = LagCheck.Machine(w, st); m.start(0)
        var t = 1000L; while (t <= at) { m.tick(t); t += 1000 }
        val w2 = w.relaunch(); LagCheck.Machine(w2, st).recover("launch", launch = true)
        val ok = w2.s.persisted() == start.persisted() && st.v == null
        if (!ok) fail++; report?.add("abort_launch step=$k name=${LagCheck.NAMES[k]} ok=$ok") }
    }
    return fail
  }

  @Test fun twelveStepsEachChangeOneLoadAndRestoreThePrevious() {
    assertEquals(12, LagCheck.STEPS)
    assertEquals(heavy, LagCheck.target(0, heavy)); assertEquals(heavy, LagCheck.target(11, heavy))
    fun diff(a: Snapshot, b: Snapshot) = listOf(a.voice != b.voice, a.place != b.place, a.sitePref != b.sitePref || a.site != b.site,
      a.hidden != b.hidden, a.steady != b.steady, a.boost != b.boost, a.smooth != b.smooth, a.held != b.held).count { it }
    // Step 4 (smooth on) is "as launched" when the wearer kept the default; from smooth off it is one change.
    val classic = heavy.copy(smooth = false)
    for (n in 1..9) assertEquals("step $n changes exactly one load", 1, diff(if (n == 4) classic else heavy, LagCheck.target(n, if (n == 4) classic else heavy)))
    assertEquals(listOf("site_smooth_off", "site_smooth_on", "site_held", "site_hidden"), LagCheck.NAMES.subList(3, 7))
    assertFalse(LagCheck.target(3, heavy).smooth); assertTrue(LagCheck.target(3, heavy).site)
    assertTrue(LagCheck.target(4, classic).smooth); assertTrue(LagCheck.target(4, classic).site)
    LagCheck.target(5, heavy).let { assertTrue("held: drawn", it.site && it.held && it.smooth == heavy.smooth) }
    LagCheck.target(6, heavy).let { assertFalse("hidden", it.site || it.sitePref || it.held) }
    val all = LagCheck.target(10, heavy)
    assertEquals(Snapshot(false, "PLAIN", false, false, LagCheck.ALL_DOCK, true, true), all)
    assertTrue("step 10 keeps 72 Hz on", all.steady)
    assertTrue("at most 150 s", LagCheck.STEPS * LagCheck.STEP_MS <= 150_000L)
    assertEquals(144_000L, LagCheck.STEPS * LagCheck.STEP_MS)
  }

  @Test fun voiceIsHushedForExactlyTheRun() {
    val w = World(heavy); val m = LagCheck.Machine(w, Store())
    assertFalse(w.hushed); m.start(0); assertTrue(w.hushed)
    var t = 1000L; while (m.tick(t)) { assertTrue("hushed at $t", w.hushed || !m.running); t += 1000 }
    assertFalse("resumes after lagcheck done", w.hushed)
    assertTrue(w.logs.last() == "mark lagcheck_done")
  }

  @Test fun aNormalRunMarksEveryStepForWornFpsAndRestoresEverything() {
    val w = World(heavy); val st = Store(); val m = LagCheck.Machine(w, st)
    assertTrue(m.start(0)); assertFalse("one run at a time", m.start(10))
    assertEquals("before is saved at once", heavy, Snapshot.decode(st.v))
    val end = runToEnd(m)
    val marks = w.logs.filter { it.startsWith("lagcheck step=") }
    assertEquals((0 until 12).map { LagCheck.markLine(it) }, marks)
    // tools/travel/worn_fps.py PERF_MARK reads `mark NAME` on OverhandPerf lines (the feature logs on FrameRateFeature.TAG).
    val re = Regex("^(?:mark (\\S+)|switch (\\S+=\\S+))")
    val seg = w.logs.mapNotNull { re.find(it)?.groupValues?.get(1) }
    assertEquals((0 until 12).map { "lagcheck${it}_${LagCheck.NAMES[it]}" } + "lagcheck_done", seg)
    assertEquals("OverhandPerf", FrameRateFeature.TAG)
    assertTrue("lagcheck done restored=1" in w.logs)
    assertEquals(heavy, w.s); assertNull(st.v)
    assertEquals((1..12).map { "$it/12" } + "done", w.progress)
    assertTrue("ends at most 150 s in: $end", end in 144_000L..147_000L)
  }

  @Test fun snapshotRoundTrips() {
    assertEquals(heavy, Snapshot.decode(heavy.encode()))
    heavy.copy(smooth = false, held = true).let { assertEquals(it, Snapshot.decode(it.encode())) }
    // A value a #1182 build saved (no smooth, no held) still restores: the switch's default, no hold.
    assertEquals(heavy, Snapshot.decode(heavy.encode().substringBefore(";smooth=")))
    assertEquals(heavy.copy(hidden = emptySet()), Snapshot.decode(heavy.copy(hidden = emptySet()).encode()))
    assertNull(Snapshot.decode("garbage")); assertNull(Snapshot.decode(null))
  }

  @Test fun aCorruptSavedValueIsDroppedNotApplied() {
    val w = World(heavy); val st = Store("v=1;voice=")
    assertNull(LagCheck.Machine(w, st).recover("launch", launch = true)); assertNull(st.v); assertEquals(heavy, w.s)
  }

  @Test fun restoresAfterEveryAbortAndThePlantedBrokenRestoreIsCaught() {
    val report = mutableListOf<String>()
    val f = failures(broken = false, report)
    val planted = failures(broken = true)
    report.forEach { println("LAGCHECK\t$it") }
    println("lagcheck_steps=${LagCheck.STEPS} lagcheck_restore_failures=$f lagcheck_planted_restore_failures=$planted")
    assertEquals("restore failures (normal run + abort at every step, resume and relaunch)", 0, f)
    assertTrue("the planted broken restore must be caught", planted > 0)
  }

  /** #1201: under each launch cut, and under all of them, as_launched is the cut state, and a normal run and an abort at
   *  every step (resume and relaunch) end in the cut state. The cut state is [LagCheck.target] of the cut's step from
   *  main's launch state; LaunchCutsTest.cut_equals_step shows the app launches in exactly that state. */
  @Test fun underEveryLaunchCutAsLaunchedIsTheCutStateAndEveryRestoreReturnsToIt() {
    val steps = LaunchCuts.STEP.filterValues { it != null }
    val sets = steps.keys.map { setOf(it) } + listOf(setOf("voice", "sky", "windows", "rate90", "boost"), steps.keys)
    var fail = 0; var plantedCaught = 0; var plantedSets = 0
    for (cuts in sets) {
      val cut = cuts.fold(LaunchCuts.MAIN) { st, c -> LagCheck.target(LagCheck.NAMES.indexOf(steps[c]), st) }
      val asLaunched = LagCheck.target(0, cut) == cut && LagCheck.target(11, cut) == cut
      val report = mutableListOf<String>()
      val f = failures(false, report, cut)
      val okRows = report.count { it.contains("ok=true") }
      // The planted broken restore turns listening off for good: it can only show where listening starts on.
      val p = failures(true, null, cut)
      if (cut.voice) { plantedSets++; if (p > 0) plantedCaught++ }
      if (!asLaunched || f != 0) fail++
      println("CUTLAG	cuts=${cuts.joinToString(",")}	as_launched_is_cut=$asLaunched	restore_failures=$f	ok_rows=$okRows/${report.size}	planted_failures=$p")
    }
    println("cut_lagcheck_sets=${sets.size} cut_lagcheck_failures=$fail cut_lagcheck_planted_caught=$plantedCaught/$plantedSets")
    assertEquals(0, fail)
    assertEquals("the planted broken restore is caught wherever listening starts on", plantedSets, plantedCaught)
    assertTrue(plantedSets > 0)
  }
}
