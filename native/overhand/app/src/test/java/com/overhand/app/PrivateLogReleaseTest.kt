package com.overhand.app

import android.util.Log
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * #1188 (R356), in #1127's pattern: marker words are planted in the recorded day the judge watches (every project name
 * and id, every card question, every drilled file path), then the day is walked row by row through the seven log
 * sites, each written with [Log] exactly as its call site writes it ([PrivateLog]). Every captured log entry is searched
 * for the markers: under release none may carry one. Debug keeps the values and must write byte for byte what the
 * call sites wrote before #1188 (the old templates, copied below).
 * Writes PRIVATE_LOG_OUT (default build/private_log_<debug|release>.txt).
 */
@RunWith(RobolectricTestRunner::class)
class PrivateLogReleaseTest {
  private val project = "qorvane"; private val question = "plixtor"; private val path = "vexmund"
  private val markers = listOf(project, question, path)
  private val judge = File("src/main/assets/judge")

  /** One planted row of the recorded day, with what each site would be handed. */
  private data class Row(val agent: String, val projectId: String, val projectName: String, val task: String, val part: String,
    val question: String?, val decision: String?)

  private fun day(): List<Row> = File(judge, "real_day.jsonl").readLines().filter { it.isNotBlank() }.map { JSONObject(it) }.map { r ->
    Row(r.optString("agent_name"), r.optString("project") + project, r.optString("project_name") + " " + project,
      "t" + r.optLong("t_ms") % 97, r.optString("title").take(20) + project,
      r.optString("decision_id").takeIf { it.isNotEmpty() }?.let { r.optString("title") + " " + question }, r.optString("decision_id").ifEmpty { null })
  }
  private fun drillPaths(): List<Pair<String, JSONObject>> = File(judge, "drill").listFiles()!!.sorted().flatMap { f ->
    val parts = JSONObject(f.readText()).getJSONObject("parts")
    parts.keys().asSequence().toList().flatMap { k -> val a = parts.getJSONArray(k); (0 until a.length()).map { k to a.getJSONObject(it) } }
  }

  /** Each emitted line: (site, new line, the line the call site wrote before #1188). */
  private fun walk(): List<Triple<String, String, String>> {
    val out = ArrayList<Triple<String, String, String>>()
    fun add(site: String, now: String, old: String) { out += Triple(site, now, old) }
    for (r in day()) {
      add("SiteScene.talk_target", PrivateLog.siteTalkTarget(r.agent, r.projectId, r.task),
        "site event=talk_target agent=${r.agent} project=${r.projectId} task=${r.task}")
      add("SiteScene.show_on_site", PrivateLog.siteShowOnSite(r.projectId, r.part, true),
        "site event=show_on_site project=${r.projectId} part=${r.part} on_site=${true}")
      add("SiteScene.pick", PrivateLog.sitePick(r.projectId, r.part, false), "site event=pick project=${r.projectId} part=${r.part} then=graph_open")
      add("SiteScene.pick_simulated", PrivateLog.sitePick(r.projectId, r.part, true),
        "site event=pick project=${r.projectId} part=${r.part} then=graph_open simulated=true")
      add("SiteTalkVoice.talk_lock", PrivateLog.siteTalkLock(r.agent, r.projectId, r.task, r.part),
        "site event=talk_lock agent=${r.agent} project=${r.projectId} task=${r.task} part=${r.part}")
      val text = "how is it going"; val req = "s_0123456789ab"; val answer = "All good here today."
      add("SiteTalkVoice.talk_live", PrivateLog.siteTalk("live", r.agent, r.projectId, r.task, "chars=${text.length} req=$req"),
        "site event=talk mode=live agent=${r.agent} project=${r.projectId} task=${r.task} chars=${text.length} req=$req")
      add("SiteTalkVoice.talk_recorded", PrivateLog.siteTalk("recorded", r.agent, r.projectId, r.task, "route=recorded words=${answer.split(' ').size}"),
        "site event=talk mode=recorded agent=${r.agent} project=${r.projectId} task=${r.task} route=recorded words=${answer.split(' ').size}")
      for (ok in listOf(true, false)) add("SiteTalkVoice.talk_relay", PrivateLog.siteTalk("recorded", r.agent, r.projectId, r.task, "route=${if (ok) "relay" else "recorded"}"),
        "site event=talk mode=recorded agent=${r.agent} project=${r.projectId} task=${r.task} route=${if (ok) "relay" else "recorded"}")
      val q = r.question ?: continue
      val dist = 1.2345
      add("ComposePreview.open", PrivateLog.composeOpen(true, "d${r.decision}", q, 3, dist),
        "open shim=${true} id=d${r.decision} question=\"${q.take(60)}\" options=${3} head_to_panel_m=${"%.3f".format(dist)}")
      add("TapAllFeature.click", PrivateLog.tapClick("decisions", 2, q, true), "click view=decisions index=2 text=${q.orEmpty()} found=${true}")
      add("First60Feature.ready", PrivateLog.first60Ready("day", r.projectName, r.decision, "A", 4200L, true, false),
        "event=ready dataset=day card=${r.projectName}#${r.decision} " + "yes=A ask_ends_ms=4200 ears=${true} mic_permission=${false}")
    }
    for ((part, f) in drillPaths()) {
      val p = f.getString("path") + "/" + path; val text = f.getString("text"); val changed = f.getJSONArray("changed").length()
      add("ArchitectureGraph.drill_code", PrivateLog.drillCode(part, p, f.getInt("start_line"), changed, text.lines().size),
        "drill level=CODE part=$part file=$p line=${f.getInt("start_line")} changed=$changed code_lines=${text.lines().size}")
    }
    for (name in listOf("Bedroom00", "Livingroom01")) add("MixedRealityMode.synthetic", PrivateLog.syntheticRoom(name + project, 1),
      "SYNTHETIC room=${name + project} rooms=${1}")
    return out
  }

  @Test fun plantedNamesQuestionsAndPathsNeverReachTheReleaseLog() {
    ShadowLog.clear()
    val lines = walk()
    lines.forEach { (site, now, _) -> Log.i("Overhand" + site.substringBefore('.'), now) }
    val logged = ShadowLog.getLogs()
    val hits = logged.filter { e -> markers.any { (e.msg ?: "").contains(it, ignoreCase = true) } }
    val changed = lines.count { it.second != it.third }
    val sites = lines.groupBy { it.first }
    val rows = sites.map { (s, l) ->
      val h = l.count { x -> markers.any { m -> x.second.contains(m, true) } }
      "$s\tlines=${l.size}\tmarker_hits=$h\tchanged_vs_before=${l.count { it.second != it.third }}"
    }
    val variant = if (BuildConfig.DEBUG) "debug" else "release"
    val out = File(System.getenv("PRIVATE_LOG_OUT") ?: "build/private_log_$variant.txt")
    out.parentFile?.mkdirs()
    out.writeText("variant=$variant sites=${sites.size} lines=${lines.size} private_log_hits=${hits.size} lines_changed_vs_before=$changed\n" +
      rows.joinToString("\n") + "\n" + hits.take(3).joinToString("") { "example_hit\t${it.tag}\t${it.msg.take(120)}\n" })
    println("PRIVATE_LOG variant=$variant\nprivate_log_hits=${hits.size}\nprivate_log_lines=${lines.size}\nprivate_log_changed=$changed\n" + rows.joinToString("\n"))
    assertEquals("all seven files' sites walked", 13, sites.size)
    if (BuildConfig.DEBUG) {
      assertEquals("debug writes exactly what it wrote before #1188", 0, changed)
      assertTrue("debug control: the planted markers are logged in debug (else the search proves nothing)", hits.isNotEmpty())
    } else assertEquals("release log carries a planted name, question or path:\n${hits.take(5).joinToString("\n") { it.msg }}", 0, hits.size)
  }
}
