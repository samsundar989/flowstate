package com.overhand.app.voice

import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The screen the test set was written against (#1002), and a record of every app function the dispatcher calls. */
class FakeUi : UiActions {
  val calls = ArrayList<String>()
  var current = "headset competition"
  val open = linkedSetOf("fleet", "blueprint", "code", "yard", "chat")
  override fun windows() = listOf(SlotName("fleet", "Fleet"), SlotName("blueprint", "Blueprint"), SlotName("code", "Code"),
    SlotName("yard", "Yard"), SlotName("chat", "Chat", listOf("decisions", "decision", "chat decisions")),
    SlotName("architecture", "Architecture", listOf("graph", "architecture graph")))
  override fun projects() = listOf("Headset competition", "Agent challenge", "Geothermal faults", "Molecule discovery",
    "Circuit compilation", "Sports tracking").map { SlotName(it.lowercase(), it) }
  override fun currentProject() = current
  override fun parts() = listOf("schema", "bridge", "neutral runtime", "web runtime", "panels tray", "Passthrough+scene", "Decision cards",
    "Stations", "Replay the night", "deep dive", "voice", "native app", "assets", "eval", "package", "adapter").map { SlotName(it.lowercase(), it) }
  override fun closeAll(): List<String> { calls += "closeAll()"; return open.toList().also { open.clear() } }
  override fun openWindow(key: String) { calls += "openWindow($key)"; open += key }
  override fun closeWindow(key: String) { calls += "closeWindow($key)"; open -= key }
  override fun showArchitecture(project: String) { calls += "showArchitecture($project)" }
  override fun hideArchitecture() { calls += "hideArchitecture()" }
  override fun openProject(project: String) { calls += "openProject($project)"; current = project }
  override fun zoomInto(part: String) { calls += "zoomInto($part)" }
  override fun zoomOut() { calls += "zoomOut()" }
  override fun centerWindows() { calls += "centerWindows()" }
  override fun openSettings() { calls += "openSettings()" }
  override fun closeSettings() { calls += "closeSettings()" }
  override fun mute(on: Boolean) { calls += "mute($on)" }
}

class UiCommandsTest {
  private val answers = VoiceIntents.parse(File("src/main/res/raw/voice_answers.json").readText())
  private fun load(v: String) = File("src/test/resources/voice_ui/phrasings_$v.jsonl").readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
  private val rows = load("v1")
  /** v2: a second fresh-context set, frozen after v1's held-out half had been looked at, scored whole and never tuned on. */
  private val rowsV2 = load("v2")
  /** v3: the acceptance set. A third fresh-context set, frozen after the grammar was, scored once and never tuned on. */
  private val rowsV3 = load("v3")
  /** v4: the final set. A fourth fresh-context set, frozen after the last grammar change (74fc2c2b), scored once. */
  private val rowsV4 = load("v4")
  private val askIds = mapOf("ask_done" to setOf("built", "overnight", "agents", "now"), "ask_blocked" to setOf("needs_me", "failed"))

  /** Held out = odd sha256 of the row id (fixed before any tuning). */
  private fun heldOut(id: String) = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())[31].toInt() and 1 == 1

  private class Scored(val row: JSONObject, val got: UiResult, val answer: String, val ok: Boolean)

  private fun run(r: JSONObject): Scored {
    val ui = FakeUi(); val got = UiCommands(ui).handle(r.getString("text"))
    val want = r.getString("action"); val slot = r.getString("slot")
    val answer = answers.match(r.getString("text")).answer.id
    // The architecture "window" and "show the architecture of this project" run the same function.
    fun norm(a: String?, s: String) = if (a == "open_window" && s == "architecture") "show_architecture" to "this" else a to s
    val ok = when (want) {
      "none" -> !got.acted
      in askIds -> !got.acted && answer in askIds.getValue(want)
      else -> got.acted && norm(got.action, got.slot) == norm(want, slot)
    }
    return Scored(r, got, answer, ok)
  }

  private fun report(name: String, set: List<JSONObject>): Pair<Double, Double> {
    val s = set.map { run(it) }
    val acts = s.filter { it.row.getString("action") != "none" }; val nones = s.filter { it.row.getString("action") == "none" }
    val acc = acts.count { it.ok }.toDouble() / acts.size
    val fa = nones.count { it.got.acted }.toDouble() / nones.size
    val out = File("build/voice_ui").apply { mkdirs() }
    File(out, "$name.json").writeText(JSONObject().put("set", name).put("voice_ui_intent_acc", acc).put("voice_ui_false_action_rate", fa)
      .put("n_actions", acts.size).put("n_none", nones.size).put("rows", JSONArray(s.map { x ->
        JSONObject().put("id", x.row.getString("id")).put("text", x.row.getString("text")).put("want", x.row.getString("action") + ":" + x.row.getString("slot"))
          .put("got", "${x.got.action}:${x.got.slot}").put("caption", x.got.caption).put("answer", x.answer).put("ok", x.ok)
      })).toString(1))
    println("VOICE_UI set=$name acc=${"%.3f".format(acc)} (${acts.count { it.ok }}/${acts.size}) false_action=${"%.3f".format(fa)} (${nones.count { it.got.acted }}/${nones.size})")
    s.filter { !it.ok }.forEach { println("  MISS ${it.row.getString("id")}: \"${it.row.getString("text")}\" want=${it.row.getString("action")}:${it.row.getString("slot")} got=${it.got.action}:${it.got.slot} '${it.got.caption}' answer=${it.answer}") }
    return acc to fa
  }

  @Test fun frozenSetsAreUnchanged() = listOf("v1", "v2", "v3", "v4").forEach { v ->
    val sha = MessageDigest.getInstance("SHA-256").digest(File("src/test/resources/voice_ui/phrasings_$v.jsonl").readText().replace("\r\n", "\n").toByteArray()).joinToString("") { "%02x".format(it) }
    assertEquals(File("src/test/resources/voice_ui/phrasings_$v.sha256").readText().trim().split(' ')[0], sha)
  }

  @Test fun finalSetMeetsTheBar() {
    val (acc, fa) = report("final_v4", rowsV4)
    assertTrue("voice_ui_intent_acc $acc", acc >= 0.9)
    assertTrue("voice_ui_false_action_rate $fa", fa <= 0.02)
  }

  @Test fun acceptanceSetMeetsTheBar() {
    val (acc, fa) = report("acceptance_v3", rowsV3)
    assertTrue("voice_ui_intent_acc $acc", acc >= 0.9)
    assertTrue("voice_ui_false_action_rate $fa", fa <= 0.02)
  }

  @Test fun freshSetMeetsTheBar() {
    val (acc, fa) = report("heldout_v2", rowsV2)
    assertTrue("voice_ui_intent_acc $acc", acc >= 0.9)
    assertTrue("voice_ui_false_action_rate $fa", fa <= 0.02)
  }

  @Test fun devSetIsScored() { report("dev", rows.filter { !heldOut(it.getString("id")) }) }

  @Test fun heldOutMeetsTheBar() {
    val (acc, fa) = report("heldout", rows.filter { heldOut(it.getString("id")) })
    assertTrue("voice_ui_intent_acc $acc", acc >= 0.9)
    assertTrue("voice_ui_false_action_rate $fa", fa <= 0.02)
  }

  @Test fun noSentenceInTheFileWronglyClosesAll() {
    val wrong = (rows + rowsV2 + rowsV3 + rowsV4).filter { it.getString("action") != "close_all" && run(it).got.let { g -> g.acted && g.action == "close_all" } }
    assertEquals(wrong.joinToString { it.getString("text") }, 0, wrong.size)
  }

  /** The four lines developer named, spoken by three synthetic voices and transcribed by the on-device recogniser on the PC. */
  @Test fun spokenLinesRunTheRightAction() {
    val f = File("src/test/resources/voice_ui/e2e_transcripts.jsonl")
    val t = f.readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
    val ok = t.count { r -> val ui = FakeUi(); val g = UiCommands(ui).handle(r.getString("transcript"))
      val good = g.acted && g.action == r.getString("action") && g.slot == r.getString("slot")
      println("VOICE_UI_E2E voice=${r.getString("voice")} said=\"${r.getString("line")}\" heard=\"${r.getString("transcript")}\" got=${g.action}:${g.slot} '${g.caption}' ok=$good"); good }
    println("VOICE_UI_E2E ok=$ok/${t.size}")
    assertEquals(12, t.size)
    assertTrue("$ok of 12", ok >= 10)
  }

  // ---- one test per action: the right function, with the right argument, and a caption of 8 words or fewer ----

  private fun said(text: String, ui: FakeUi = FakeUi(), c: UiCommands = UiCommands(ui)): Pair<UiResult, FakeUi> {
    val r = c.handle(text); assertTrue(r.caption, r.caption.split(' ').size <= 8); return r to ui
  }

  @Test fun closeAll() { val (r, ui) = said("close all windows"); assertEquals(listOf("closeAll()"), ui.calls); assertEquals("Closed 5 windows", r.caption) }
  @Test fun openWindow() { val (r, ui) = said("open the code window"); assertEquals(listOf("openWindow(code)"), ui.calls); assertEquals("Opened Code", r.caption) }
  @Test fun closeWindow() { val (_, ui) = said("hide the yard"); assertEquals(listOf("closeWindow(yard)"), ui.calls) }
  @Test fun showArchitectureOfThisProject() {
    val (r, ui) = said("show me the architecture for this project"); assertEquals(listOf("showArchitecture(headset competition)"), ui.calls)
    assertEquals("Architecture: Headset competition", r.caption)
  }
  @Test fun showArchitectureOfANamedProject() { val (_, ui) = said("show the architecture of geo thermal faults"); assertEquals(listOf("showArchitecture(geothermal faults)"), ui.calls) }
  @Test fun openProject() { val (r, ui) = said("open project sports tracking"); assertEquals(listOf("openProject(sports tracking)"), ui.calls); assertEquals("Opened Sports tracking", r.caption) }
  @Test fun zoomInto() { val (r, ui) = said("zoom into the web runtime"); assertEquals(listOf("zoomInto(web runtime)"), ui.calls); assertEquals("Zoomed to web runtime", r.caption) }
  @Test fun zoomIntoSpelledLetters() {
    val ui = object : UiActions by FakeUi() { val calls = ArrayList<String>()
      override fun parts() = listOf(SlotName("api", "API layer"), SlotName("ui", "UI shell"), SlotName("db", "Database"))
      override fun zoomInto(part: String) { calls += "zoomInto($part)" } }
    UiCommands(ui).handle("zoom into the a p i layer"); assertEquals(listOf("zoomInto(api)"), ui.calls)
  }
  @Test fun zoomOut() { val (_, ui) = said("zoom out"); assertEquals(listOf("zoomOut()"), ui.calls) }
  @Test fun undoBringsBackWhatWasHidden() {
    val ui = FakeUi(); val c = UiCommands(ui)
    c.handle("close all windows"); val r = c.handle("undo")
    assertEquals(listOf("closeAll()", "openWindow(fleet)", "openWindow(blueprint)", "openWindow(code)", "openWindow(yard)", "openWindow(chat)"), ui.calls)
    assertEquals("Undone: windows back", r.caption)
    ui.calls.clear(); c.handle("show the architecture"); c.handle("go back"); assertEquals(listOf("showArchitecture(headset competition)", "hideArchitecture()"), ui.calls)
  }
  @Test fun centerWindows() { val (_, ui) = said("bring the windows in front of me"); assertEquals(listOf("centerWindows()"), ui.calls) }
  @Test fun openSettings() { val (_, ui) = said("open settings"); assertEquals(listOf("openSettings()"), ui.calls) }
  @Test fun closeSettings() { val (_, ui) = said("close the settings"); assertEquals(listOf("closeSettings()"), ui.calls) }
  @Test fun mute() { val (r, ui) = said("mute"); assertEquals(listOf("mute(true)"), ui.calls); assertEquals("Sounds muted", r.caption) }
  @Test fun unmute() { val (_, ui) = said("unmute"); assertEquals(listOf("mute(false)"), ui.calls) }
  @Test fun questionsStillGoToTheAnswers() {
    listOf("what did the agents do", "what is blocked").forEach { q -> val (r, ui) = said(q); assertEquals(null, r.action); assertTrue(ui.calls.isEmpty()) }
  }

  @Test fun twoCloseNamesAskWhichOneAndDoNothing() {
    val ui = FakeUi(); val c = UiCommands(ui)
    val r = c.handle("zoom into the runtime")
    assertTrue(r.caption, r.caption.startsWith("Which one?")); assertEquals(listOf("neutral runtime", "web runtime"), r.choices.map { it.key }.sorted())
    assertTrue(ui.calls.isEmpty())
    val picked = c.choose(r.choices.indexOfFirst { it.key == "web runtime" })
    assertEquals(listOf("zoomInto(web runtime)"), ui.calls); assertEquals("zoom_in", picked.action)
  }
  @Test fun answeringWhichOneByVoice() {
    val ui = FakeUi(); val c = UiCommands(ui)
    c.handle("zoom into the runtime"); c.handle("the web one")
    assertEquals(listOf("zoomInto(web runtime)"), ui.calls)
  }
}
