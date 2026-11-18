package com.overhand.app.layoutlint

import android.app.Activity
import android.graphics.*
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.overhand.app.FirstMinutes
import com.overhand.app.R
import com.overhand.app.data.*
import com.overhand.app.decisions.DecisionCards
import com.overhand.app.decisions.QuestionCard
import com.overhand.app.features.Shell
import com.overhand.app.features.WindowLayout
import com.overhand.app.features.StageWindows
import com.overhand.app.stations.StationRules
import com.meta.spatial.toolkit.LayoutXMLPanelRegistration
import com.meta.spatial.toolkit.PanelRegistration
import com.overhand.app.graph.ArchitectureGraph
import com.overhand.app.graph.GraphLayout
import com.overhand.app.opening.Opening
import com.overhand.app.windows.*
import java.io.File
import java.nio.file.Files
import java.time.Duration
import kotlin.math.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Layout lint capture (#773). Builds every window the app opens in Recorded mode with the real view code,
 * lays it out at its registered panel size (DpDisplayOptions dp at the SDK default 288 dpi, so 1 dp = 1.8 px),
 * renders it with Robolectric native graphics and writes `<window>.png` + `<window>.json` (every element's
 * bounds, text lines, colours, padding). tools/layout_lint/lint.py turns those into rule violations.
 *
 * Where a window's root is assembled inside a Shell-bound closure (the six windows, palm menu, hint), the
 * closure is mirrored here line for line; the content inside it is the production class.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w1600dp-h800dp-288dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LayoutLintCapture {
  private val out=File(System.getProperty("layoutLint.out") ?: "build/layout_lint").apply { mkdirs() }
  private val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
  private val renderer by lazy { LayoutLintRenderer(activity,out) }
  private val density get()=renderer.density
  private val content by lazy { RecordedContent(RecordedSource({ File("src/main/assets/$it").readText() },
    LocalReceiptStore(Files.createTempDirectory("layoutlint").resolve("receipts.json").toFile()),
    HttpClient { _,_,_ -> error("Offline: HTTP forbidden") })) }
  /** SpatialEarcons.of() casts to OverhandActivity: an attached, never-created one (no scene, no sound). */
  private val overhand by lazy { Robolectric.buildActivity(com.overhand.app.OverhandActivity::class.java).get() }
  private val windows by lazy { RecordedWindows(content,overhand) }

  private fun label(column: LinearLayout,text: String,size: Float=22f)=Shell.label(column,text,size)

  private fun stageDistance(p: com.meta.spatial.core.Pose)=p.t.let { sqrt(it.x*it.x+it.y*it.y+it.z*it.z) }
  private fun decisionDistance()=stageDistance(com.overhand.app.features.StageWindows.decision(com.meta.spatial.core.Pose()))

  // InteractionFeature.graphAt + detailPose: default graph panel relative to Code (not the graph mesh).
  private fun graphPanelDistance(): Float {
    val sp=WindowLayout.defaultSpecs.first { it.view=="code" }
    val at=com.overhand.app.features.WindowPose.place(sp,com.meta.spatial.core.Pose(),null,
      com.overhand.app.mr.DeskPlacement.Point(0f,0f,0f))
    return stageDistance(at*com.meta.spatial.core.Pose(com.meta.spatial.core.Vector3(0f,-0.45f,-0.40f)))
  }

  internal fun specs(): List<Panel> {
    val six=WindowLayout.defaultSpecs.filter { it.view!="settings" }.map { sp ->
      Panel(sp.view,WindowLayout.PANEL_W_DP,WindowLayout.heightDp(sp.view),sp.w,sp.h,if(sp.view==WindowLayout.FOCUSED) com.overhand.app.graph.site.SitePutOn.MAIN_DISTANCE.toFloat() else sqrt(sp.x*sp.x+sp.y*sp.y+sp.z*sp.z),
        "WindowsFeature.panels + RecordedWindows.attach") { column ->
        // Mirror of WindowsFeature.panels() panelSetupWithRootView, Recorded branch.
        if(Theme.ENABLED) column.background=Theme.panelBackground(density)
        column.addView(label(column,RecordedSource.BADGE,22f))
        column.addView(label(column,sp.title,36f).apply { tag=RecordedWindows.TITLE_TAG })
        if(sp.view=="fleet") column.addView(label(column,FirstMinutes.PURPOSE,32f).apply { tag="launch-purpose" })
        val primary=when(sp.view) {
          "code" -> Button(column.context).apply { text="Architecture snapshot · Nov 5";textSize=28f }
          "chat" -> Button(column.context).apply { text="Decisions";textSize=28f }
          else -> null
        }
        windows.attach(column,sp.view,if(sp.view=="chat") File("src/main/assets/windows.js").readText() else "")
        primary?.let { action -> column.findViewWithTag<LinearLayout>("recorded-nav")?.addView(action,LinearLayout.LayoutParams(0,-2,2f)) ?: column.addView(action) }
        PanelPolish.install(column,WindowLayout.target(sp.view),PolishRules.WINDOW_GAP_DP,WindowLayout.type(sp.view))
      }
    }
    val card=Panel("decision_card",StageWindows.CARD_W_DP,StageWindows.CARD_H_DP,StageWindows.CARD_W,StageWindows.CARD_H,decisionDistance(),"DecisionCards.attach + open (InteractionFeature.panels)") { column ->
      val cards=DecisionCards(overhand,{ "" },{ content.source },ModeGeneration()) { }
      cards.attach(column);cards.open()
      PanelPolish.install(column,StageWindows.CARD_TARGET,PolishRules.NEAR_GAP_DP,StageWindows.CARD_TYPE)
    }.apply { wholeText=true }
    val graph=Panel("architecture_panel",1000f,800f,1f,0.8f,graphPanelDistance(),"ArchitectureGraph.attach + open (InteractionFeature.panels)") { column ->
      val g=ArchitectureGraph(activity,{ "" },{ },{ content }) { }
      g.attach(column);g.open(content.defaultProject)
      PanelPolish.install(column,128,PolishRules.NEAR_GAP_DP)
    }
    // #1236: the wrist menu (WristMenu.PANEL_W x PANEL_H, read at about 0.40 m).
    // OpeningFeature's private MENU_DP_W/H and MENU_GAP_DP are mirrored here.
    val menu=Panel("palm_menu",416f,224f,
      com.overhand.app.opening.WristMenu.PANEL_W,com.overhand.app.opening.WristMenu.PANEL_H,0.40f,"OpeningFeature.buildMenu (mirrored)") { column ->
      if(Theme.ENABLED) column.background=Theme.panelBackground(density)
      Opening.MENU.chunked(2).forEach { pair ->
        val row=LinearLayout(column.context)
        pair.forEach { item ->
          row.addView(Button(column.context).apply { text=item.label;textSize=20f;tag="menu-${item.view}" },LinearLayout.LayoutParams(0,-1,1f).apply { val m=Math.round(12*density);setMargins(m,m,m,m) })
        }
        column.addView(row,LinearLayout.LayoutParams(-1,0,1f))
      }
      PanelPolish.install(column,64,16,PolishRules.TypeScale(20f,20f,20f,20f))
    }
    val hint=Panel("opening_hint",1600f,200f,0.80f,0.10f,1.0f,"OpeningFeature hint panel (mirrored)") { column ->
      if(Theme.ENABLED) column.background=Theme.panelBackground(density)
      column.gravity=Gravity.CENTER
      column.addView(TextView(column.context).apply { text=Opening.HINT;textSize=46f;setTextColor(Color.WHITE);gravity=Gravity.CENTER })
    }
    val detail=Panel("architecture_detail",1000f,800f,1f,0.8f,graphPanelDistance(),"ArchitectureGraph.select (a node pinched)") { column ->
      val g=ArchitectureGraph(activity,{ "" },{ },{ content }) { }
      g.attach(column);g.open(content.defaultProject)
      g.select(content.graph(content.defaultProject).nodes.first().id)
      PanelPolish.install(column,128,PolishRules.NEAR_GAP_DP)
    }
    // #999: the drill-down's files list and code view (the part with the most files, its first file).
    val drillPart=com.overhand.app.graph.GraphDrill.parse(File("src/main/assets/judge/drill/${content.source.dataset}.json").readText())
      .maxByOrNull { it.value.size }?.key ?: content.graph(content.defaultProject).nodes.first().id
    val files=Panel("architecture_files",1000f,800f,1f,0.8f,graphPanelDistance(),"ArchitectureGraph drill: part level (files, 7 a page)") { column ->
      val g=ArchitectureGraph(activity,{ "" },{ },{ content }) { }
      g.attach(column);g.open(content.defaultProject);g.debugDrill(drillPart)
      PanelPolish.install(column,128,PolishRules.NEAR_GAP_DP)
    }
    val codeView=Panel("architecture_code",1000f,800f,1f,0.8f,graphPanelDistance(),"ArchitectureGraph drill: code level (one file, monospace)") { column ->
      val g=ArchitectureGraph(activity,{ "" },{ },{ content }) { }
      g.attach(column);g.open(content.defaultProject);g.debugDrill(drillPart,0)
      PanelPolish.install(column,128,PolishRules.NEAR_GAP_DP)
    }
    return six+listOf(card,graph,detail,files,codeView,menu,hint)
  }

  /**
   * Shell test double (#807): the production Shell over an attached, never-created OverhandActivity (no scene,
   * Recorded mode). Settings and the station labels are built by their own registration's
   * panelSetupWithRootView, called directly, so nothing is mirrored.
   */
  private val shell by lazy { Shell(overhand) }
  @Suppress("UNCHECKED_CAST")
  private fun setup(reg: PanelRegistration): (LinearLayout) -> Unit {
    val f=LayoutXMLPanelRegistration::class.java.getDeclaredField("panelSetupWithRootView").apply { isAccessible=true }
    val fn=f.get(reg) as Function3<View,Any?,Any?,Unit>
    // The setup lambdas never use their scene object and entity, but Kotlin null-checks them: pass bare instances.
    return { column -> fn.invoke(column,bare(com.meta.spatial.runtime.PanelSceneObject::class.java),bare(com.meta.spatial.core.Entity::class.java)) }
  }
  private fun bare(c: Class<*>): Any {
    val f=Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible=true }
    val unsafe=f.get(null)
    return unsafe.javaClass.getMethod("allocateInstance",Class::class.java).invoke(unsafe,c)
  }
  internal fun shellPanels(): List<Panel> {
    val sp=WindowLayout.defaultSpecs.first { it.view=="settings" }
    val settingsReg=shell.windows.panels().first { it.registrationId==sp.id }
    // #1191: Settings has pages and no scrolling; every page is its own lint window (settings_<tab>), opened by its tab.
    val settings=com.overhand.app.features.SettingsTab.shown().map { t ->
      Panel("settings_${t.key}",PolishRules.SETTINGS_W_DP,WindowLayout.PANEL_H_DP,
        (sp.w*com.overhand.app.features.SettingsPanel.SCALE).toFloat(),
        (sp.h*com.overhand.app.features.SettingsPanel.SCALE).toFloat(),
        com.overhand.app.features.SettingsPanel.DISTANCE_M.toFloat(),
        "WindowsFeature.panels (settings) + SettingsFeature.build, tab ${t.label}") { column ->
        setup(settingsReg)(column)
        column.findViewWithTag<View>("settings-tab-${t.key}")!!.performClick()
      }
    }
    val stations=shell.windows.stations
    val count=content.stations().size
    val regs=stations.registrations().take(count)
    val labels=regs.mapIndexed { slot,reg ->
      val distance=stageDistance(com.overhand.app.features.StageWindows.project(com.meta.spatial.core.Pose(),slot,count))
      Panel("station_$slot",384f,256f,0.60f,0.40f,distance,"Stations.registrations + paint (recorded)") { column ->
        setup(reg)(column)
        if(slot==0) stations.start()     // the recorded poll paints every station label on the next frames
      }
    }
    val dock=Panel("dock",com.overhand.app.features.ControlDock.DP,com.overhand.app.features.ControlDock.HEIGHT_DP,
      com.overhand.app.features.ControlDock.WIDTH_M,com.overhand.app.features.ControlDock.HEIGHT_M,1.2f,
      "ControlDock.build") { com.overhand.app.features.ControlDock.build(it) {} }
    return settings+listOf(dock)+labels
  }

  /**
   * The 3D architecture graph is Spatial SDK meshes and Text3D (GraphScene), not views. t775: GraphScene draws
   * exactly GraphLod.items (one line per item, whole words, at most GraphLod.maxChars per line) on GraphMesh tiles,
   * and GraphRender mirrors that same geometry and alpha rule here. The system level is captured for every project
   * at the default distance; the default project also gets its module, file and code levels at the framing distance.
   */
  private fun architectureBoxes(project: String,distance: Float,level: com.overhand.app.graph.GraphLod.Level?=null): JSONObject {
    val source=File("src/main/java/com/overhand/app/graph/ArchitectureGraph.kt").readText()
    assertTrue("GraphScene label rule changed: update LayoutLintCapture.architectureBoxes",
      source.contains("GraphLod.items(model,infos,page,focus)") && source.contains("Text3D.create(text=item.text,fontSize=item.em.toFloat()"))
    val gate=ArchitectureGraph.displayPattern(File("src/main/assets/windows.js").readText().let { shell ->
      Regex("'([^']+)'").findAll(shell.substringAfter("const deny = [").substringBefore("];")).map { it.groupValues[1] }.toList() }+ArchitectureGraph.EXTRA_DENY)
    val code=content.source.code(project)
    val infos=com.overhand.app.graph.GraphLod.infos(content.components(project),code,{ s -> gate.replace(s,"agent") })
    val model=content.graph(project)
    val focus=infos.values.maxByOrNull { it.detail.modules.size+it.detail.code.size+it.detail.files.size }?.id
    val suffix=level?.let { "_"+it.name.lowercase() } ?: ""
    val name="architecture_graph_${project.replace(Regex("[^A-Za-z0-9_-]"),"_")}$suffix"
    val r=if(level==null) com.overhand.app.graph.GraphRender.render(name,model,infos,null,1.0,distance.toDouble())
      else com.overhand.app.graph.GraphRender.render(name,model,infos,focus,
        com.overhand.app.graph.GraphLod.clampZoom(com.overhand.app.graph.GraphLod.threshold(level,distance.toDouble())*1.25),distance.toDouble(),widthM=1.6,heightM=1.0)
    File(out,"$name.png").outputStream().use { r.bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    val meta=r.meta.put("default_project",project==content.defaultProject)
      .put("project_label",com.overhand.app.graph.ProjectContext.label(com.overhand.app.graph.ProjectContext.labels(content.projects),project))
    File(out,"$name.json").writeText(meta.toString(1))
    return meta
  }

  /**
   * #928: the decision card after the tutorial, in the states a person meets after their first answer (the ones
   * CardCopyFramesTest shows cold readers): the saved receipt, the next question, and the next question with no
   * hands seen (the tracking line takes room). Each runs on its own activity and receipt store, so nothing leaks
   * into the other windows; they are captured last because the tutorial-done flag is app-wide.
   */
  private fun cardStates(): List<Panel> {
    val looper=shadowOf(Looper.getMainLooper())
    fun idle(ms: Long) { repeat((ms/20).toInt()) { Thread.sleep(1);looper.idleFor(Duration.ofMillis(20)) } }
    fun buttons(v: View): List<Button> = (if(v is Button && v.isShown) listOf(v) else emptyList())+
      (if(v is ViewGroup) (0 until v.childCount).flatMap { buttons(v.getChildAt(it)) } else emptyList())
    fun waitFor(column: LinearLayout,what: (List<Button>) -> Boolean) {
      repeat(300) { if(what(buttons(column))) return;Thread.sleep(10);idle(20) }
      error("decision card state not reached: "+buttons(column).map { it.text })
    }
    val hands=mapOf("right" to com.overhand.app.hands.ThumbPose.NONE,"left" to com.overhand.app.hands.ThumbPose.NONE)
    fun state(name: String,step: (DecisionCards,LinearLayout) -> Unit)=Panel("decision_card_$name",StageWindows.CARD_W_DP,StageWindows.CARD_H_DP,StageWindows.CARD_W,StageWindows.CARD_H,decisionDistance(),
      "DecisionCards after a practice answer (CardCopyFramesTest states)") { column ->
      val host=Robolectric.buildActivity(com.overhand.app.OverhandActivity::class.java).get()
      host.getSharedPreferences("hands-onboarding-v1",0).edit().putBoolean("recorded",true).commit()
      val source=RecordedSource({ File("src/main/assets/$it").readText() },
        LocalReceiptStore(Files.createTempDirectory("layoutlint-card").resolve("receipts.json").toFile()),null)
      val cards=DecisionCards(host,{ "" },{ source },ModeGeneration()) { }
      cards.attach(column);cards.open();PanelPolish.install(column,StageWindows.CARD_TARGET,PolishRules.NEAR_GAP_DP,StageWindows.CARD_TYPE)
      idle(500);cards.frame(hands,true,1)
      buttons(column).first { it.tag==com.overhand.app.onboarding.HandsOnboarding.OPTION_TAG }.performClick();idle(300)
      buttons(column).first { it.text.startsWith("Confirm") }.performClick()
      waitFor(column) { b -> b.any { it.tag==QuestionCard.TAG_NEXT } }
      step(cards,column)
    }.apply { wholeText=true }
    return listOf(
      state("answered") { _,_ -> },
      state("next_question") { cards,column ->
        idle(5000);buttons(column).first { it.tag==QuestionCard.TAG_NEXT }.performClick();idle(300);cards.frame(hands,true,1) },
      state("next_no_hands") { cards,column ->
        idle(5000);buttons(column).first { it.tag==QuestionCard.TAG_NEXT }.performClick();idle(300);cards.frame(hands,true,0) })
  }

  /** Independent rendered-page oracle: contiguous visible slices must reconstruct the recorded source. */
  private fun contentJourney() {
    val host=Robolectric.buildActivity(com.overhand.app.OverhandActivity::class.java).get()
    host.getSharedPreferences("hands-onboarding-v1",0).edit().putBoolean("recorded",true).commit()
    val source=RecordedSource({ File("src/main/assets/$it").readText() },
      LocalReceiptStore(Files.createTempDirectory("reading-journey").resolve("receipts.json").toFile()),null)
    val cards=DecisionCards(host,{ "" },{ source },ModeGeneration()) { }
    lateinit var column: LinearLayout
    fun all(v: View): List<View> = listOf(v)+(if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList())
    fun controls()=all(column).filterIsInstance<Button>()
    fun click(label: String) { controls().first { it.text==label }.performClick() }
    val base=Panel("reading_setup",StageWindows.CARD_W_DP,StageWindows.CARD_H_DP,StageWindows.CARD_W,StageWindows.CARD_H,decisionDistance(),"real recorded source reading traversal") {
      column=it;cards.attach(it);cards.open();PanelPolish.install(it,StageWindows.CARD_TARGET,PolishRules.NEAR_GAP_DP,StageWindows.CARD_TYPE)
    }.apply { wholeText=true }
    renderer.capture(base)
    val raw=RecordedContent.rows(source.decisions())
    val open=raw.filter { it.optString("status","open")=="open" }.sortedBy { it.optInt("judge_order",Int.MAX_VALUE) }
    val deny=File("src/main/assets/windows.js").readText().substringAfter("const deny = [").substringBefore("];")
    val pattern=Regex("\\b(?:"+Regex("'([^']+)'").findAll(deny).map { Regex.escape(it.groupValues[1]) }.sortedByDescending { it.length }.joinToString("|")+")\\b",RegexOption.IGNORE_CASE)
    fun clean(value: String)=pattern.replace(value,"agent")
    data class Slice(val label: String,val start: Int,val end: Int,val text: String)
    fun reconstruct(slices: List<Slice>): Map<String,String> {
      val result=linkedMapOf<String,String>()
      slices.forEach { slice ->
        val before=result[slice.label].orEmpty()
        assertEquals("noncontiguous ${slice.label}",before.length,slice.start)
        assertEquals(slice.end-slice.start,slice.text.length)
        result[slice.label]=before+slice.text
      }
      return result
    }
    var renderedPages=0;var fields=0;var planted=false
    fun read(name: String,expected: Map<String,String>): String {
      // The full reading oracle now lives behind More. Exercise the real entry and Back, never bypass the card.
      if(controls().any { it.tag==QuestionCard.TAG_MORE }) {
        val question=column.findViewWithTag<TextView>(QuestionCard.TAG_QUESTION).text.toString()
        renderer.captureExisting(base.copy(name="reading_${name}_card",build={}),column)
        click(QuestionCard.MORE)
        assertNotNull(column.findViewWithTag<TextView>("reading-page"))
        click("Back")
        assertEquals(question,column.findViewWithTag<TextView>(QuestionCard.TAG_QUESTION).text.toString())
        click(QuestionCard.MORE)
      }
      renderer.settle(column,(base.wDp*density).roundToInt(),(base.hDp*density).roundToInt())
      while(controls().first { it.text=="Read back" }.isEnabled) click("Read back")
      val actionsBefore=org.robolectric.shadows.ShadowLog.getLogsForTag("OverhandTouch").size
      val slices=mutableListOf<Slice>()
      repeat(500) { n ->
        val panel=base.copy(name="reading_${name}_$n",build={}).apply { wholeText=true }
        renderer.captureExisting(panel,column)
        val body=column.findViewWithTag<TextView>("reading-page")
        val title=column.findViewWithTag<TextView>("reading-label").text.toString().substringBefore(" \u00b7 ")
        val bounds=body.contentDescription.toString().split(":").map(String::toInt)
        val rect=Rect();assertTrue("page invisible",body.getGlobalVisibleRect(rect))
        assertEquals(body.height,rect.height());assertEquals(body.width,rect.width())
        assertTrue("page text clipped $name/$n",body.layout.height+body.compoundPaddingTop+body.compoundPaddingBottom<=body.height)
        assertTrue("page text wider than box $name/$n",(0 until body.layout.lineCount).all { body.layout.getLineRight(it)<=body.width-body.totalPaddingLeft-body.totalPaddingRight })
        slices.add(Slice(title,bounds[1],bounds[2],body.text.toString()));renderedPages++
        if(!controls().first { it.text=="Read more" }.isEnabled) {
          val actual=reconstruct(slices)
          expected.forEach { (label,value) -> assertEquals("$name missing $label",clean(value),actual[label]) }
          fields+=expected.size
          if(!planted) {
            val omitted=reconstruct(slices.dropLast(1))
            assertTrue("omitted final page escaped oracle",expected.any { (k,v) -> clean(v)!=omitted[k] })
            planted=true
          }
          // Reverse traversal must return to the same first page without answering.
          while(controls().first { it.text=="Read back" }.isEnabled) click("Read back")
          assertEquals(slices.first().text,column.findViewWithTag<TextView>("reading-page").text.toString())
          val actions=org.robolectric.shadows.ShadowLog.getLogsForTag("OverhandTouch").drop(actionsBefore)
          assertTrue("reading issued another action: $actions",actions.all { it.msg.contains("outcome=read_") })
          return actual.values.joinToString("\n")
        }
        click("Read more")
      }
      error("unbounded reading pages")
    }
    val hands=mapOf("right" to com.overhand.app.hands.ThumbPose.NONE)
    fun questionFields(d: JSONObject,index: Int,available: List<JSONObject>,answered: Int,noHands: Boolean): LinkedHashMap<String,String> {
      val opts=d.getJSONArray("options");val expected=linkedMapOf<String,String>()
      expected["Question"]=com.overhand.app.RecordedFirstCard.position(index+1,available.size,answered,true,com.overhand.app.RecordedFirstCard.blocking(available))+" | "+d.optString("question",d.optString("title"))
      expected["Recommendation"]=if(d.isNull("recommendation")) "None" else d.optString("recommendation","None")
      expected["Asked by"]=com.overhand.app.RecordedFirstCard.askedBy(d.optString("project_name",d.optString("project")),d.optString("agent_name","Agent"),true)
      (0 until opts.length()).forEach { i -> expected["Option ${i+1}"]=opts.optJSONObject(i)?.optString("label") ?: opts.getString(i) }
      expected["Source"]=source.badge
      expected["How to answer"]=DecisionCards.IDLE;if(noHands) expected["Hands"]=DecisionCards.HANDS_HINT
      return expected
    }
    open.forEachIndexed { index,d ->
      cards.frame(hands,true,0)
      // Every option, Confirm and More on the compact card; all source fields still checked below.
      if(index>0) click("Back")
      assertFalse("Ask must require a pick",column.findViewWithTag<Button>(QuestionCard.TAG_CONFIRM).isEnabled)
      val opts=d.getJSONArray("options")
      (0 until opts.length()).forEach { i ->
        controls().filter { it.tag==com.overhand.app.onboarding.HandsOnboarding.OPTION_TAG }[i].performClick()
        assertTrue(controls().filter { it.tag==com.overhand.app.onboarding.HandsOnboarding.OPTION_TAG }[i].isSelected)
      }
      assertTrue("Picked must enable Confirm",column.findViewWithTag<Button>(QuestionCard.TAG_CONFIRM).isEnabled)
      val expected=questionFields(d,index,open,0,true)
      read("q${index}_nohands",expected)
      assertEquals("reading issued an answer",0,RecordedContent.rows(source.decisions()).count { it.has("practice_receipt") })
      if(index<open.lastIndex) click("Next")
    }
    // Longest real source is included above, along with every option, recommendation and no-hands page.
    click("Next");cards.frame(hands,true,1)
    click("Select 1")
    val selected=controls().filter { it.tag==com.overhand.app.onboarding.HandsOnboarding.OPTION_TAG }.map { it.isSelected }
    read("selected",questionFields(open.first(),0,open,0,false))
    val staleConfirm=controls().first { it.text=="Confirm selected option" }
    assertEquals(selected,controls().filter { it.tag==com.overhand.app.onboarding.HandsOnboarding.OPTION_TAG }.map { it.isSelected })
    cards.frame(hands,true,1)
    cards.frame(mapOf("right" to com.overhand.app.hands.ThumbPose.UP),true,1)
    val holding=cards.gateState
    click("Read more");click("Read back")
    assertEquals("reading changed hold state",holding,cards.gateState)
    assertEquals(0,RecordedContent.rows(source.decisions()).count { it.has("practice_receipt") })
    cards.frame(hands,true,1)
    click("Confirm selected option")
    repeat(300) { if(controls().none { it.text=="Next decision" }) { Thread.sleep(10);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)) } }
    assertTrue("no receipt",controls().any { it.text=="Next decision" })
    val first=open.first();val receipt=RecordedContent.rows(source.decisions()).first { it.has("practice_receipt") }
    val saved=receipt.getJSONObject("practice_receipt").getString("option_label")
    val receiptFields=linkedMapOf("Source" to source.badge,"Receipt" to com.overhand.app.RecordedFirstCard.SAVED_TITLE,
      com.overhand.app.RecordedFirstCard.SAVED_QUESTION to first.getString("question"),
      com.overhand.app.RecordedFirstCard.SAVED_ANSWER to saved,
      "Destination" to "${first.optString("project_name",first.optString("project"))} \u00b7 ${first.optString("agent_name","Agent")}",
      com.overhand.app.RecordedFirstCard.SAVED_WHERE to com.overhand.app.RecordedFirstCard.SAVED_DETAIL)
    read("receipt",receiptFields)
    staleConfirm.performClick()
    read("receipt_guard",receiptFields)
    assertEquals(1,RecordedContent.rows(source.decisions()).count { it.has("practice_receipt") })
    click("Next decision");click("History")
    val history=questionFields(open[1],0,open.drop(1),1,false)
    val lines=listOf(com.overhand.app.RecordedFirstCard.SAVED_TITLE+" \u00b7 "+first.getString("question")+"\n"+
      com.overhand.app.RecordedFirstCard.savedChoice(saved)+". No live agent was contacted.")+
      raw.filter { it.has("historical_answer") }.map { "Original run choice \u00b7 "+it.getString("question")+"\n"+it.getString("historical_answer") }
    lines.forEachIndexed { i,line -> history["Answer history ${i+1}"]=line }
    read("history",history)
    cards.frame(hands,true,0)
    history["Hands"]=DecisionCards.HANDS_HINT
    read("history_nohands",history)
    assertEquals(1,RecordedContent.rows(source.decisions()).count { it.has("practice_receipt") })
    com.overhand.app.onboarding.HandsOnboarding.get(host).replay { cards.open() }
    history["Practice"]=com.overhand.app.RecordedFirstCard.PURPOSE
    history["Practice instructions"]=com.overhand.app.onboarding.OnboardingFlow.REHEARSAL
    read("rehearsal",history)
    assertEquals(1,RecordedContent.rows(source.decisions()).count { it.has("practice_receipt") })
    cards.close()
    host.getSharedPreferences("hands-onboarding-v1",0).edit().remove("recorded").commit()
    File(out,"content_coverage.txt").writeText("content_field_coverage=1.0\nmissing_content_fields=0\npage_navigation_wrong_actions=0\npage_content_fault_caught=${if(planted) 1 else 0}\nrendered_pages=$renderedPages\nfields_checked=$fields\n")
  }

  /** Old type/targets at the original 2 m and final 2.8 m; controls live outside the passing capture set. */
  private fun oldTypographyControls() {
    for(distance in listOf(2f,decisionDistance())) {
      val old=LayoutLintRenderer(activity,File(out.parentFile,"old_type_$distance"))
      val host=Robolectric.buildActivity(com.overhand.app.OverhandActivity::class.java).get()
      host.getSharedPreferences("hands-onboarding-v1",0).edit().putBoolean("recorded",true).commit()
      val source=RecordedSource({ File("src/main/assets/$it").readText() },
        LocalReceiptStore(Files.createTempDirectory("old-type").resolve("receipts.json").toFile()),null)
      val cards=DecisionCards(host,{ "" },{ source },ModeGeneration()) { }
      val panel=Panel("decision_old_type",StageWindows.CARD_W_DP,StageWindows.CARD_H_DP,StageWindows.CARD_W,StageWindows.CARD_H,distance,"planted original NEAR type and 80dp targets; repaired reflow") { column ->
        cards.attach(column);cards.open();PanelPolish.install(column,80,PolishRules.NEAR_GAP_DP,PolishRules.NEAR)
      }.apply { wholeText=true }
      old.capture(panel);cards.close()
    }
  }

  /** Seeded fixture: exactly one overflow, one 2 px misalignment and one small target. Nothing else. */
  private fun seeded(): Panel=Panel("seeded_fixture",1000f,720f,1f,0.72f,1.0f,"LayoutLintCapture.seeded") { column ->
    column.setBackgroundColor(Color.BLACK)
    fun t(value: String,tag: String)=TextView(column.context).apply { text=value;textSize=32f;setTextColor(Color.WHITE);this.tag=tag }
    column.addView(t("Aligned heading","seed-left-a"),LinearLayout.LayoutParams(-2,-2))
    column.addView(t("Shifted line","seed-left-b").apply { translationX=2f },LinearLayout.LayoutParams(-2,-2))
    // One line tall, three lines of words: the bottom two are cut off.
    column.addView(t("Tasks waiting for you today\nSecond line is cut\nThird line is cut","seed-overflow"),
      LinearLayout.LayoutParams(-1,(58*density).roundToInt()))
    column.addView(View(column.context).apply { tag="seed-small";isClickable=true;setBackgroundColor(Color.DKGRAY) },
      LinearLayout.LayoutParams((32*density).roundToInt(),(32*density).roundToInt()))
    // #807 gross misalignment: 10 px off the stack (developer saw 4 to 16 px); a column gap is 24 dp = 43 px.
    column.addView(t("Gross shifted line","seed-gross").apply { translationX=10f },LinearLayout.LayoutParams(-2,-2))
    // Clean: two aligned lines and one deliberate indent wider than a column gap (48 dp). Must stay silent.
    val clean=LinearLayout(column.context).apply { orientation=LinearLayout.VERTICAL;tag="seed-clean" }
    clean.addView(t("Clean first","seed-clean-a"),LinearLayout.LayoutParams(-2,-2))
    clean.addView(t("Clean second","seed-clean-b"),LinearLayout.LayoutParams(-2,-2))
    clean.addView(t("Clean indent","seed-clean-indent").apply { translationX=48*density },LinearLayout.LayoutParams(-2,-2))
    column.addView(clean,LinearLayout.LayoutParams(-1,-2))
  }

  /**
   * Live mode's WebView windows. web_capture.mjs fetched the server's dashboard into web/classic.html; this
   * builds each window's document exactly as Dashboards.shouldInterceptRequest does, for headless Chromium.
   */
  private fun webDocuments() {
    val dir=File(out,"web");val classic=File(dir,"classic.html")
    if(!classic.isFile) return
    val html=FirstMinutes.dashboardDocument(classic.readText())
    val script=File("src/main/assets/windows.js").readText()
    val rows=JSONArray()
    WindowLayout.defaultSpecs.filter { it.view!="settings" }.forEach { sp ->
      val distance=sqrt(sp.x*sp.x+sp.y*sp.y+sp.z*sp.z)
      val injected="<script>"+Theme.webScript()+"window.AF_WINDOW="+JSONObject.quote(sp.view)+
        ";window.AF_PROJECT="+JSONObject.quote("")+";window.AF_ZOOM="+(3.5f*distance/1.1f)+";"+script+"</script>"
      val head=Regex("<head[^>]*>",RegexOption.IGNORE_CASE).find(html)!!
      File(dir,"${sp.view}.html").writeText(html.substring(0,head.range.last+1)+injected+html.substring(head.range.last+1))
      // The WebView fills the column minus the shell header; Chromium gets the whole panel (conservative).
      rows.put(JSONObject().put("view",sp.view).put("width_dp",WindowLayout.PANEL_W_DP.toDouble()).put("height_dp",WindowLayout.PANEL_H_DP.toDouble())
        .put("density",density.toDouble()).put("width_m",sp.w.toDouble()).put("distance_m",distance.toDouble()))
    }
    File(dir,"windows.json").writeText(rows.toString(1))
  }

  @Test fun captureEveryWindow() {
    try { contentJourney() } finally { File(out,"measurement_debug.txt").writeText(org.robolectric.shadows.ShadowLog.getLogsForTag("ReadingPages").joinToString("\n") { it.msg }) }
    webDocuments()
    val index=JSONArray()
    for(p in specs()+shellPanels()+LayoutLintPanels.panels+seeded()+cardStates()) {
      val meta=runCatching { renderer.capture(p) }.getOrElse { e ->
        System.err.println("layout_lint capture failed ${p.name}: $e");e.printStackTrace()
        JSONObject().put("window",p.name).put("error",e.toString())
      }
      index.put(JSONObject().put("window",p.name).put("ok",!meta.has("error")).put("error",meta.optString("error")))
    }
    for(project in content.projects.map { it.getString("id") }) {
      if(content.graph(project).nodes.isEmpty()) continue
      val meta=architectureBoxes(project,1.6f)
      index.put(JSONObject().put("window",meta.getString("window")).put("ok",true).put("error",""))
      if(project==content.defaultProject) listOf(com.overhand.app.graph.GraphLod.Level.MODULE,com.overhand.app.graph.GraphLod.Level.FILE,
        com.overhand.app.graph.GraphLod.Level.CODE).forEach { level ->
        val deep=architectureBoxes(project,2.4f,level)
        index.put(JSONObject().put("window",deep.getString("window")).put("ok",true).put("error",""))
      }
    }
    oldTypographyControls()
    overhand.getSharedPreferences("hands-onboarding-v1",0).edit().remove("recorded").commit()
    runCatching { shell.windows.stations.close() }
    File(out,"capture_index.json").writeText(index.toString(1))
    println("layout_lint_captured=${index.length()} out=${out.absolutePath}")
    val failed=(0 until index.length()).map { index.getJSONObject(it) }.filter { !it.getBoolean("ok") }
    assertTrue("windows failed to render: $failed",failed.isEmpty())
  }
}
