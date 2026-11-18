package com.overhand.app.features

import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import com.overhand.app.places.Places
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1182 on the production Shell: the two new wearer switches start in today's position and building them changes
 * nothing; the lag check's control exists in debug only; and a run stopped mid-way by a quit is restored at the next
 * launch through the real setters (the prefs a launch reads).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LagCheckFeatureTest {
  private val activity = Robolectric.buildActivity(com.overhand.app.OverhandActivity::class.java).get()
  private fun shell() = Shell(activity).also { sh -> sh.features.filterIsInstance<com.overhand.app.places.PlacesFeature>().single().onPreCreate() }
  private fun rows(sh: Shell, f: Feature) = LinearLayout(activity).also { root -> f.settingsRows().forEach { it.build(root, LinearLayout(activity)) } }
  @org.junit.After fun unhush() { com.overhand.app.voice.VoiceController.lagCheck = false }
  private fun prefs() = activity.getSharedPreferences("windows-v1", 0).all.toMap() + activity.getSharedPreferences("voice-v1", 0).all.mapKeys { "voice/" + it.key }

  @Test fun newSwitchesStartAsTodayAndBuildingThemChangesNothing() {
    val sh = shell(); sh.commands.clear(); val before = prefs()
    val listen = rows(sh, sh.voice).findViewWithTag<CheckBox>("listen-voice-switch")
    val site = rows(sh, sh.interaction).findViewWithTag<CheckBox>("show-build-site-switch")
    assertTrue("Listen for voice shows not muted (today's default)", listen.isChecked)
    assertTrue("Show build site is on (the site at put-on, as today)", site.isChecked)
    assertTrue(sh.interaction.siteSwitch); assertTrue(sh.voice.listening)
    assertEquals("building Settings queues nothing", 0, sh.commands.size)
    assertEquals("building Settings writes no pref", before, prefs())
    sh.lagCheck.onCreate()
    assertEquals("a launch with no saved run changes nothing", before, prefs())
  }

  @Test fun theLagCheckControlIsDebugOnly() {
    val sh = shell()
    val b = (0 until rows(sh, sh.lagCheck).childCount).map { rows(sh, sh.lagCheck).getChildAt(it) }.filterIsInstance<Button>()
    if (com.overhand.app.BuildConfig.DEBUG) assertEquals(listOf("Lag check (2 min)"), b.map { it.text.toString() })
    else assertTrue("release shows nothing", b.isEmpty())
    assertEquals(if (com.overhand.app.BuildConfig.DEBUG) "Lag check step 3 of 12" else null, DeveloperSettings.lagCheckText(2, false))
  }

  /** #1233: the counter's text at every step of a real run (Machine ticks once a second), and the done line. */
  @Test fun theLagCheckCounterShowsStepAndSecondsLeftEverySecond() {
    val shown = mutableListOf<String>()
    val w = object : LagCheck.World {
      var s = LagCheck.Snapshot(true, "HOME", true, true, emptySet(), true, false)
      override fun read() = s
      override fun apply(t: LagCheck.Snapshot, launch: Boolean) { s = t }
      override fun log(line: String) {}
      override fun hush(on: Boolean) {}
      override fun progress(step: Int?, done: Boolean) { if (done) DeveloperSettings.lagCheckText(null, true, restored = true)?.let { shown += it } }
      override fun countdown(step: Int, secondsLeft: Int) { DeveloperSettings.lagCheckText(step, false, secondsLeft)?.let { shown += it } }
    }
    val m = LagCheck.Machine(w, object : LagCheck.Store { var v: String? = null; override fun load() = v; override fun save(v: String?) { this.v = v } })
    assertTrue(m.start(0)); var t = 1000L; while (m.tick(t)) t += 1000
    if (!com.overhand.app.BuildConfig.DEBUG) { assertTrue("release shows nothing", shown.isEmpty()); return }
    val total = (LagCheck.STEPS * LagCheck.STEP_MS / 1000).toInt()
    assertEquals("Lag check step 1 of 12\n$total s left", shown.first())
    assertEquals("Lag check step 1 of 12\n${total - 1} s left", shown[1])
    for (n in 1..LagCheck.STEPS) {
      val first = shown.first { it.startsWith("Lag check step $n of 12\n") }
      assertEquals("step $n starts with its own seconds left", "Lag check step $n of 12\n${total - (n - 1) * (LagCheck.STEP_MS / 1000).toInt()} s left", first)
      println("lagcheck_screen step=$n text=${first.replace('\n', '|')}")
    }
    assertEquals("Lag check step 12 of 12\n1 s left", shown[shown.size - 2])
    assertEquals("Lag check done\nsettings restored", shown.last())
    assertEquals("one line per second of the run, then done", total + 1, shown.size)
    println("lagcheck_screen done text=${shown.last().replace('\n', '|')}")
    assertEquals("Lag check done\nrestore FAILED", DeveloperSettings.lagCheckText(null, true, restored = false))
  }

  /** #1187: the site steps through the real setters: smooth off and on ("Smooth build site"), held, hidden; each restored. */
  @Test fun theSiteStepsGoThroughTheRealSettersAndRestore() {
    val sh = shell()
    val before = sh.lagCheck.read(); val m = sh.lagCheck.machine
    assertTrue(before.smooth); assertFalse(before.held)
    assertTrue(m.start(0))
    val seen = mutableMapOf<Int, LagCheck.Snapshot>()
    var t = 1000L; while (m.tick(t)) { if (m.running && m.step in 3..6) seen[m.step] = sh.lagCheck.read(); t += 1000 }
    assertEquals("3 smooth off (release: fixed on, R395 #1191)", !com.overhand.app.BuildConfig.DEBUG, seen.getValue(3).smooth); assertFalse(seen.getValue(3).held)
    assertTrue("4 smooth on", seen.getValue(4).smooth)
    assertTrue("5 held", seen.getValue(5).held); assertEquals(before.sitePref, seen.getValue(5).sitePref)
    assertFalse("6 hidden, not held", seen.getValue(6).sitePref || seen.getValue(6).held)
    assertEquals(before.persisted(), sh.lagCheck.read().persisted()); assertFalse(sh.lagCheck.read().held)
    assertTrue(sh.interaction.smoothSite); assertFalse(com.overhand.app.voice.VoiceController.lagCheck)
  }

  @Test fun aQuitAtStepTenIsRestoredAtTheNextLaunchThroughTheRealSetters() {
    val sh = shell()
    val before = sh.lagCheck.read()
    println("lagcheck_real_before=${before.encode()}")
    val m = sh.lagCheck.machine
    assertTrue(m.start(0))
    var t = 1000L; while (t <= 10 * LagCheck.STEP_MS + 2000) { m.tick(t); t += 1000 }
    assertEquals(10, m.step)
    assertTrue("voice hushed mid-run", com.overhand.app.voice.VoiceController.lagCheck)
    val mid = sh.lagCheck.read()
    assertFalse(mid.voice); assertEquals("PLAIN", mid.place); assertFalse(mid.sitePref); assertTrue(mid.steady)
    assertEquals("boost: debug, or a build with the boost cut (#1201)", com.overhand.app.BuildConfig.DEBUG || LaunchCuts.boost, mid.boost)
    assertNotNull(activity.getSharedPreferences("windows-v1", 0).getString(LagCheck.PREF, null))
    // Quit: a fresh Shell over the same prefs; its launch restores before the scene reads them.
    val sh2 = shell(); sh2.lagCheck.onCreate()
    val after = sh2.lagCheck.read()
    println("lagcheck_real_after=${after.encode()}")
    assertEquals(before.persisted(), after.persisted())
    assertEquals(before.place, Places.current.name)
    assertNull(activity.getSharedPreferences("windows-v1", 0).getString(LagCheck.PREF, null))
    sh.lagCheck.machine.abort("test"); assertFalse(com.overhand.app.voice.VoiceController.lagCheck)
  }
}
