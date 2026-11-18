package com.overhand.app.features

import com.overhand.app.places.Places
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1201 nov15e launch cuts, on the app's own prefs and getters (debug and release classpaths). A fresh Shell gets the
 * launch: [LaunchCutsFeature] then Places' onPreCreate (the dispatch order), then [launchState] reads what put-on shows.
 * Prints `LAUNCH_DEFAULT` rows (no cut = main), `CUT` rows (`cut_equals_step`), and the counts scripts/freeze_gates.py reads:
 * `launch_cuts=<build list> launch_defaults_main=0|1 cut_equals_step_failures=F cut_equals_step_planted_failures=P`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchCutsTest {
  private val activity by lazy { Robolectric.buildActivity(com.overhand.app.OverhandActivity::class.java).get() }
  private val built = LaunchCuts.active
  @After fun restore() { LaunchCuts.active = built }

  /** A fresh install (no prefs), launched with [cuts] (and [table]); the launch state put-on shows. */
  private fun launch(cuts: Set<String>, table: Map<String, List<LaunchCuts.Write>> = LaunchCuts.WRITES): LagCheck.Snapshot {
    for (f in listOf(LaunchCuts.WINDOWS_PREFS, com.overhand.app.voice.Voice.PREFS))
      activity.getSharedPreferences(f, android.content.Context.MODE_PRIVATE).edit().clear().commit()
    LaunchCuts.active = cuts
    val shell = Shell(activity)
    LaunchCutsFeature(shell, cuts, table).onPreCreate()
    com.overhand.app.places.PlacesFeature(shell).onPreCreate()
    return shell.launchState()
  }
  private fun fields(s: LagCheck.Snapshot) = linkedMapOf("voice" to s.voice, "place" to s.place, "sitePref" to s.sitePref,
    "site" to s.site, "hidden" to s.hidden.sorted(), "steady" to s.steady, "boost" to s.boost, "smooth" to s.smooth, "held" to s.held)
  private fun diff(a: LagCheck.Snapshot, b: LagCheck.Snapshot) =
    fields(a).filter { (k, v) -> fields(b)[k] != v }.map { (k, v) -> "$k=$v want ${fields(b)[k]}" }

  /** The step's state from main's launch, as the lag check applies it. */
  private fun step(cut: String) = LagCheck.target(LagCheck.NAMES.indexOf(LaunchCuts.STEP.getValue(cut)), LaunchCuts.MAIN)

  private fun cutFailures(table: Map<String, List<LaunchCuts.Write>>, print: Boolean): Int {
    var f = 0
    for ((cut, name) in LaunchCuts.STEP) {
      if (name == null) { if (print) println("CUT\t$cut\t(build property only: -PoverhandSiteOnce=true)\tskip"); continue }
      val d = diff(launch(setOf(cut), table), step(cut))
      if (d.isNotEmpty()) f++
      if (print) println("CUT\t$cut\t$name\t${if (d.isEmpty()) "equal" else "DIFF " + d.joinToString(" ")}")
    }
    return f
  }

  @Test fun launchDefaultsWithNoCutAreMainsAndEveryCutEqualsItsStep() {
    // 1. No cut: main's launch defaults, listed (main today: listening, Moon, site shown, dock windows closed, 72 Hz, no boost, smooth).
    val main = launch(emptySet())
    val today = LagCheck.Snapshot(voice = true, place = "MOON", sitePref = true, site = true, hidden = LagCheck.ALL_DOCK,
      steady = true, boost = false, smooth = true, held = false)
    fields(main).forEach { (k, v) -> println("LAUNCH_DEFAULT\t$k\t$v\t${if (fields(today)[k] == v) "main" else "DIFF want ${fields(today)[k]}"}") }
    val mainOk = main == today && LaunchCuts.MAIN == today
    // 2. cut_equals_step for every cut, and a planted wrong default (rate90 writing 72 Hz on) that must be caught.
    val f = cutFailures(LaunchCuts.WRITES, print = true)
    val planted = cutFailures(LaunchCuts.WRITES + ("rate90" to listOf(LaunchCuts.Write(LaunchCuts.WINDOWS_PREFS, SteadyFrameRate.PREF, true))), print = false)
    // 3. The build's own cuts: the launch equals every listed step applied in turn.
    val want = built.fold(LaunchCuts.MAIN) { s, c -> LaunchCuts.STEP[c]?.let { LagCheck.target(LagCheck.NAMES.indexOf(it), s) } ?: s }
    val d = diff(launch(built), want)
    println("BUILD_CUTS\t${built.joinToString(",").ifEmpty { "none" }}\t${if (d.isEmpty()) "equal" else "DIFF " + d.joinToString(" ")}")
    println("launch_cuts=${com.overhand.app.BuildConfig.CUTS.ifEmpty { "none" }} launch_defaults_main=${if (mainOk) 1 else 0} " +
      "build_cuts_equal=${if (d.isEmpty()) 1 else 0} cut_equals_step_failures=$f cut_equals_step_planted_failures=$planted " +
      "build_type=${com.overhand.app.BuildConfig.BUILD_TYPE}")
    assertTrue("no-cut launch must be main's: ${diff(main, today)}", mainOk)
    assertEquals("cut_equals_step failures", 0, f)
    assertTrue("the planted wrong default must be caught", planted > 0)
    assertTrue("the build's cuts: $d", d.isEmpty())
    assertEquals(Places.DEFAULT.name, LaunchCuts.MAIN.place)
  }

  @Test fun unknownCutNamesAreRefused() {
    assertEquals(setOf("voice", "sky"), LaunchCuts.parse(" voice, sky ,"))
    assertTrue(runCatching { LaunchCuts.parse("voice,lasers") }.isFailure)
    assertEquals(emptySet<String>(), LaunchCuts.parse(""))
  }
}
