package com.overhand.app.layoutlint

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.LinearLayout
import com.meta.spatial.toolkit.LayoutXMLPanelRegistration
import com.overhand.app.features.Shell
import com.overhand.app.features.WindowLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #1111 (freeze gate "every Settings switch tested off and on"): the real Settings window, built by its own registration
 * on the production Shell (as [LayoutLintCapture] does), with every switch (CompoundButton) it shows. For each switch: flip it
 * away from its default, build a FRESH Shell and Settings window from the saved state (the app starting with it flipped)
 * and read the switch back; flip it back, start again and read the default back. Runs under debug and release classpaths
 * (the release Settings is shorter). Prints one `SWITCH` row per switch and `settings_switches=N off_on_ok=M`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w1600dp-h800dp-288dpi")
class SettingsSwitchesOffOnTest {
  private val activity by lazy { Robolectric.buildActivity(com.overhand.app.OverhandActivity::class.java).get() }

  @Suppress("UNCHECKED_CAST")
  private var shell: Shell?=null
  private fun settings(): LinearLayout {
    val shell=Shell(activity).also { this.shell=it }
    val sp=WindowLayout.defaultSpecs.first { it.view=="settings" }
    val reg=shell.windows.panels().first { it.registrationId==sp.id }
    val f=LayoutXMLPanelRegistration::class.java.getDeclaredField("panelSetupWithRootView").apply { isAccessible=true }
    val fn=f.get(reg) as Function3<View,Any?,Any?,Unit>
    // The window's root is the inflated web_panel layout (its panel_root column), as on the headset and in LayoutLintRenderer.
    val frame=android.widget.FrameLayout(activity)
    activity.layoutInflater.inflate(com.overhand.app.R.layout.web_panel,frame,true)
    val column=(frame.getChildAt(0) as LinearLayout).also { frame.removeView(it) }
    fn.invoke(column,bare(com.meta.spatial.runtime.PanelSceneObject::class.java),bare(com.meta.spatial.core.Entity::class.java))
    com.overhand.app.features.SettingsTab.shown().forEach { shell.settings.select(it) }
    shadowOf(Looper.getMainLooper()).idle()
    return column
  }
  private fun bare(c: Class<*>): Any {
    val f=Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible=true }
    val unsafe=f.get(null)
    return unsafe.javaClass.getMethod("allocateInstance",Class::class.java).invoke(unsafe,c)
  }
  private fun boxes(v: View): List<CompoundButton> = (if(v is CompoundButton) listOf(v) else emptyList())+
    ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { boxes(g.getChildAt(it)) } } ?: emptyList())
  private fun box(label: String)=boxes(settings()).firstOrNull { it.contentDescription?.toString()==label }
  /** Flip a switch and run what it queued for the ECS thread (Touch (near) saves there), as the next frame would. A queued
   *  step that needs the scene (window rebuilds) may throw here after its pref write; that is the scene's part, not the switch's. */
  private fun set(label: String,value: Boolean) {
    box(label)!!.isChecked=value;shadowOf(Looper.getMainLooper()).idle()
    val q=shell!!.commands
    while(true) { val c=q.poll() ?: break;runCatching { c() } }
    shadowOf(Looper.getMainLooper()).idle()
  }
  // Session-only by design: the debug practice control starts off at every launch (DeveloperSettings.addPoseTrials).
  private val sessionOnly=setOf("Pose trials (practice only)")

  @Test fun everySettingsSwitchStartsFlippedAndBackAndTheSteadyFrameRateSwitchIsOn() {
    val all=boxes(settings())
    val defaults=all.map { it.contentDescription.toString() to it.isChecked }
    // A greyed-out switch (Smart answers with no relay) shows its gate, not its pref: it must survive the flips, and is
    // read back only when enabled.
    val enabled=all.associate { it.contentDescription.toString() to it.isEnabled }
    assertTrue("no switches found",defaults.isNotEmpty())
    var ok=0
    for((label,default) in defaults) {
      val steps=mutableListOf<String>()
      val live=enabled[label]==true
      if(!live) steps+="disabled"
      val pass=runCatching {
        box(label) ?: error("missing at start")
        set(label,!default)
        val flipped=box(label)?.isChecked ?: error("missing after restart flipped")
        steps+="restart_flipped=$flipped"
        set(label,default)
        val back=box(label)?.isChecked ?: error("missing after restart back")
        steps+="restart_back=$back"
        if(label in sessionOnly) { steps+="session_only";flipped==default && back==default }
        else !live || (flipped==!default && back==default)
      }.getOrElse { steps+="error=${it.javaClass.simpleName}:${it.message?.take(120)}";false }
      if(pass) ok++
      println("SWITCH\t$label\tdefault=${if(default) "on" else "off"}\t${if(pass) "pass" else "FAIL"}\t${steps.joinToString(" ")}")
    }
    println("settings_switches=${defaults.size} off_on_ok=$ok build_type=${com.overhand.app.BuildConfig.BUILD_TYPE}")
    // #1095: the 72 Hz switch exists; R388 (#1182): it is on by default.
    val steady=defaults.firstOrNull { it.first=="Steady frame rate (72 Hz)" }
    println("steady_72hz_switch_present=${if(steady!=null) 1 else 0} steady_72hz_default_on=${if(steady?.second==true) 1 else 0}")
    assertNotNull("Steady frame rate (72 Hz) switch missing",steady)
    assertTrue("Steady frame rate (72 Hz) must be on by default (R388)",steady!!.second)
    assertEquals("switches failing off/on",defaults.size,ok)
  }
}
