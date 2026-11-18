package com.overhand.app.features

import android.util.Log
import com.overhand.app.places.Places

/**
 * #1201 nov15e launch cuts: a build-time list (`-PoverhandCuts=a,b`, read once in app/build.gradle.kts into
 * [com.overhand.app.BuildConfig.CUTS]) of lag-check steps the app starts in. Each cut puts a fresh launch in exactly the
 * state [LagCheck.target] sets for its step, field by field (LaunchCutsTest.cut_equals_step), by writing the same prefs
 * the step's Settings switch writes, before any feature reads them. It is written at every launch, so a switch flipped in
 * Settings lasts until the app is restarted. No cut: nothing is written and every launch default is main's.
 * `mesh` is build-only: scripts/freeze_gates.ps1 turns it into `-PoverhandSiteOnce=true` and the app changes nothing here.
 */
object LaunchCuts {
  /** Cut name -> the [LagCheck.NAMES] step it equals (null: build property only). */
  val STEP: Map<String, String?> = linkedMapOf("voice" to "voice_off", "sky" to "sky_off", "windows" to "windows_hidden",
    "rate90" to "rate_90", "boost" to "perf_boost", "site_hidden" to "site_hidden", "mesh" to null)

  fun parse(s: String?): Set<String> = (s ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet().also {
    require(STEP.keys.containsAll(it)) { "unknown launch cut ${it - STEP.keys}; known ${STEP.keys}" }
  }
  /** The build's cuts. Settable only so LaunchCutsTest can check every cut in a build made without it. */
  @Volatile var active: Set<String> = parse(com.overhand.app.BuildConfig.CUTS)
    internal set
  /** The boost cut lifts the debug-only guard on Processor boost; it adds no control (the Settings row stays debug-only). */
  val boost get() = "boost" in active

  /** A pref a cut writes: [file] is the SharedPreferences name, [value] a Boolean or a String. */
  data class Write(val file: String, val key: String, val value: Any)
  const val WINDOWS_PREFS = "windows-v1"
  /** The prefs each cut writes: the very prefs the step's switch writes ([LagCheckFeature.apply] with launch=true). */
  val WRITES: Map<String, List<Write>> = mapOf(
    "voice" to listOf(Write(com.overhand.app.voice.Voice.PREFS, VoiceFeature.MUTED_PREF, true)),
    "sky" to listOf(Write(WINDOWS_PREFS, com.overhand.app.places.PlacesFeature.PREF, LagCheck.LIGHT_PLACE)),
    // windows_hidden closes every dock window; a launch closes them all when Start calm is on (Dock.closedAtPutOn).
    "windows" to listOf(Write(WINDOWS_PREFS, Dock.START_CALM, true)),
    "rate90" to listOf(Write(WINDOWS_PREFS, SteadyFrameRate.PREF, false)),
    "boost" to listOf(Write(WINDOWS_PREFS, FrameRateFeature.BOOST_PREF, true)),
    "site_hidden" to listOf(Write(WINDOWS_PREFS, InteractionFeature.SITE_PREF, false)),
  )

  /** Main's launch state with no cut (Start calm on, no Classic view): what as_launched reads at put-on. */
  val MAIN = LagCheck.Snapshot(voice = true, place = Places.DEFAULT.name, sitePref = true, site = true,
    hidden = LagCheck.ALL_DOCK, steady = SteadyFrameRate.DEFAULT_ON, boost = false, smooth = true, held = false)

  /** UI thread, onPreCreate. Writes [cuts]' prefs (synchronously) before any feature reads them. */
  fun write(ctx: android.content.Context, cuts: Set<String>, table: Map<String, List<Write>> = WRITES) {
    for (c in cuts) for (w in table[c].orEmpty()) {
      val e = ctx.getSharedPreferences(w.file, android.content.Context.MODE_PRIVATE).edit()
      when (val v = w.value) { is Boolean -> e.putBoolean(w.key, v); is String -> e.putString(w.key, v); else -> error("pref type") }
      e.commit()
    }
  }
}

/** Dispatched first ([Shell.features]): the cut prefs are written before Places, Windows, Voice or the lag check read them. */
class LaunchCutsFeature(private val s: Shell, private val cuts: Set<String> = LaunchCuts.active,
                        private val table: Map<String, List<LaunchCuts.Write>> = LaunchCuts.WRITES) : Feature {
  override val name = "launch_cuts"
  override fun onPreCreate() {
    if (cuts.isEmpty()) return
    LaunchCuts.write(s.activity, cuts, table)
    Log.i(FrameRateFeature.TAG, "launch_cuts cuts=${cuts.sorted().joinToString(",")}")
  }
}

/** The launch state as the app's own getters give it at put-on (the windows and the site as the launch rules build them). */
fun Shell.launchState() = LagCheck.Snapshot(voice.listening, Places.current.name, interaction.siteSwitch,
  site = interaction.siteSwitch && !interaction.classicView, hidden = Dock.closedAtPutOn(windows.startCalm),
  steady = frameRate.enabled, boost = frameRate.boost, smooth = interaction.smoothSite, held = false)

