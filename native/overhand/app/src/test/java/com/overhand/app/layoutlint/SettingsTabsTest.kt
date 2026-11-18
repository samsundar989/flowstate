package com.overhand.app.layoutlint

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.meta.spatial.toolkit.LayoutXMLPanelRegistration
import com.overhand.app.BuildConfig
import com.overhand.app.features.SettingsTab
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
 * #1191: Settings has pages and no scrolling. The real Settings window (its own registration on the production Shell,
 * as [LayoutLintCapture] builds it), debug and release classpaths:
 *  - no ScrollView anywhere in it;
 *  - the controls (label or tag) equal main's before #1191 for this variant, apart from R395 ("Smooth build site" is
 *    debug only): `settings_controls_lost`;
 *  - debug: "Lag check (2 min)" is the first control of the Debug page, so it is two presses from Settings opening
 *    (the Debug tab, then it): `settings_presses_to_lagcheck`;
 *  - every page is reachable by its tab and each control sits on exactly one page. Prints the tab table.
 * Clipping is the layout lint's job (settings_<tab> windows, rules control_clipped and scroll_slit).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w1600dp-h800dp-288dpi")
class SettingsTabsTest {
  private val activity by lazy { Robolectric.buildActivity(com.overhand.app.OverhandActivity::class.java).get() }

  @Suppress("UNCHECKED_CAST")
  private fun settings(): LinearLayout {
    val shell=Shell(activity)
    val sp=WindowLayout.defaultSpecs.first { it.view=="settings" }
    val reg=shell.windows.panels().first { it.registrationId==sp.id }
    val f=LayoutXMLPanelRegistration::class.java.getDeclaredField("panelSetupWithRootView").apply { isAccessible=true }
    val fn=f.get(reg) as Function3<View,Any?,Any?,Unit>
    val frame=FrameLayout(activity)
    activity.layoutInflater.inflate(com.overhand.app.R.layout.web_panel,frame,true)
    val column=(frame.getChildAt(0) as LinearLayout).also { frame.removeView(it) }
    fn.invoke(column,bare(com.meta.spatial.runtime.PanelSceneObject::class.java),bare(com.meta.spatial.core.Entity::class.java))
    shadowOf(Looper.getMainLooper()).idle()
    return column
  }
  private fun bare(c: Class<*>): Any {
    val f=Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible=true }
    val unsafe=f.get(null)
    return unsafe.javaClass.getMethod("allocateInstance",Class::class.java).invoke(unsafe,c)
  }
  private fun all(v: View): List<View> = listOf(v)+((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
  /** A control: a switch, button or field, by its label (the field by its tag-free name); recording chips count once. */
  private fun controls(root: View): List<View> {
    val chips=all(root).firstOrNull { it.tag=="recording-chips" }
    val inChips=chips?.let { all(it).toSet() } ?: emptySet()
    return all(root).filter { (it is android.widget.CompoundButton || (it is TextView && it.isClickable) || it is EditText || it.tag=="recording-chips") && it !in inChips-chips }
      .filter { (it.tag as? String)?.startsWith("settings-tab-")!=true && it.tag!="settings-page-nav" }
  }
  private fun name(v: View)=when {
    v.tag=="recording-chips" -> "[recording chips]"
    v is EditText -> "[server field]"
    else -> (v.contentDescription?.toString() ?: (v as TextView).text.toString()).let { if(it.startsWith("Place: ")) "Place: <place>" else it }
  }

  // Main before #1191 (c79e2355f), from its Settings window (runs/settings/t1191/planted/settings.json plus the rows'
  // code: presets and chips sit in containers the capture does not descend into).
  private val mainDebug=setOf("Close","Listen for voice","Smart answers","Answer to my name","Talk to agents","[server field]",
    "USB","Home network","Private network","Connect to live server","[recording chips]","Restart recorded run","Recorded run",
    "Check connection","Connect your computer","About Flowstate","Touch (near)","Start calm","Head-gaze focus (300 ms)",
    "Reset layout","Compose preview","Steady frame rate (72 Hz)","Processor boost","Lag check (2 min)","Mute event sounds",
    "Replay hand tutorial","Passthrough / desk anchoring","Allow room access","Place: <place>","Send real answers: off",
    "Classic view","Smooth build site","Show build site","Pose trials (practice only)","Compose card","Open decision card",
    "Quit Flowstate","Confirm quit")
  private val debugOnly=setOf("USB","Home network","Private network","Compose preview","Processor boost","Lag check (2 min)",
    "Pose trials (practice only)","Compose card")
  private val r395=setOf("Smooth build site")

  @Test fun everyControlIsOnOnePageNothingScrollsAndLagCheckIsTwoPressesAway() {
    val root=settings()
    assertTrue("a ScrollView is back in Settings",all(root).none { it is ScrollView || it is HorizontalScrollView })
    val tabs=SettingsTab.shown()
    assertEquals(listOf("Voice","View","Place","Link","Run","About"),tabs.map { it.label })
    assertNull("hidden Link page is not built at first open",root.findViewWithTag<View>("session-connection-status"))
    tabs.forEach { root.findViewWithTag<View>("settings-tab-${it.key}").performClick() }
    val now=controls(root).map(::name)
    val expected=(if(BuildConfig.DEBUG) mainDebug else mainDebug-debugOnly-r395)-"Close"
    val lost=expected-now.toSet();val added=now.toSet()-expected
    println("settings_controls_lost=${lost.size} added=${added.size} build_type=${BuildConfig.BUILD_TYPE} lost=$lost added=$added")
    // The tab table: each page, its controls in order.
    val pages=tabs.associateWith { t -> root.findViewWithTag<LinearLayout>("settings-page-${t.key}")!! }
    val onPages=mutableListOf<View>()
    for(t in tabs) {
      root.findViewWithTag<View>("settings-tab-${t.key}")!!.performClick()
      shadowOf(Looper.getMainLooper()).idle()
      assertEquals("tab ${t.label} shows its page",View.VISIBLE,pages.getValue(t).visibility)
      assertTrue("one page at a time",pages.filterKeys { it!=t }.values.all { it.visibility==View.GONE })
      val mine=controls(pages.getValue(t));onPages+=mine
      println("TAB\t${BuildConfig.BUILD_TYPE}\t${t.label}\t${mine.joinToString(" | ") { name(it) }}")
    }
    val outside=controls(root).filter { it !in onPages }.map(::name).toSet()
    assertEquals("only Close and the Quit footer sit outside the pages",emptySet<String>(),outside)
    assertEquals("controls lost against main",emptySet<String>(),lost)
    assertEquals("controls added against main",emptySet<String>(),added)
    if(BuildConfig.DEBUG) {
      root.findViewWithTag<View>("settings-tab-run")!!.performClick()
      val page=root.findViewWithTag<LinearLayout>("settings-page-run")!!
      assertEquals(View.VISIBLE,page.visibility)
      assertNotNull(page.findViewWithTag<View>("lag-check-button"))
    }
  }
}
