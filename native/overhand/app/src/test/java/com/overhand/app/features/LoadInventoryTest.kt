package com.overhand.app.features

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.UIPanelSettings
import com.overhand.app.mr.PanelFacing
import com.overhand.app.opening.Opening
import com.overhand.app.places.Place
import com.overhand.app.places.Places
import com.overhand.app.voice.VoiceGate
import com.overhand.app.voice.VoiceLoop
import java.io.File
import kotlin.math.atan2
import kotlin.math.sqrt
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1181: what the first view costs, measured where a PC can (desktop JVM, not Quest ART).
 *
 * 1. [LoadInventorySdkPanelTest.sdkPanelConfig] (Robolectric: the SDK reads Build.BOARD in a static init): the Spatial SDK's own [UIPanelSettings.toPanelConfigOptions] for the dp sizes our panels use: the
 *    layout dpi it applies, whether the panel is a compositor layer, and its mip count. tools/perf/load_inventory.py
 *    turns dp into pixels with the dpi this test reads (runs/perf/t1181/sdk_panel.tsv).
 * 2. [firstViewTick]: the pure-Kotlin share of every Feature.onFrame at the Recorded first view (opening active, no
 *    card, no graph, passthrough on, Settings closed), one row per feature and one for the whole tick. ECS calls
 *    (getComponent/setComponent/Query) go into native code that cannot run here; the inventory counts them instead.
 * 3. [voiceLoopIdle]: the always-on listening loop (#1001, absent in nov13c) fed one idle minute: turns opened,
 *    decodes started, CPU per second of audio, and the wake-name match per final result.
 * With PERF_OUT_DIR set, each writes a TSV there.
 */
class LoadInventoryTest {
  private val mx: Any? = runCatching { Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null) }.getOrNull()
  private val allocatedBytes = runCatching { Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType) }.getOrNull()
  private fun allocated(): Long = if (mx != null && allocatedBytes != null) allocatedBytes.invoke(mx, Thread.currentThread().id) as Long else -1L
  private val out = System.getenv("PERF_OUT_DIR")?.let { File(it).apply { mkdirs() } }
  private fun write(name: String, tsv: String) { println(tsv); out?.let { File(it, name).writeText(tsv) } }

  class Cost(val op: String, val inNov13c: Boolean, val calls: Int, val p50Us: Double, val p99Us: Double, val maxUs: Double, val bytesPerCall: Long)

  private fun measure(op: String, inNov13c: Boolean, reps: Int, call: () -> Unit): Cost {
    repeat(reps) { call() } // JIT warm-up
    val ns = LongArray(reps); val a0 = allocated()
    for (i in 0 until reps) { val t = System.nanoTime(); call(); ns[i] = System.nanoTime() - t }
    val a1 = allocated()
    ns.sort()
    return Cost(op, inNov13c, reps, ns[reps / 2] / 1e3, ns[(reps * 99) / 100] / 1e3, ns.last() / 1e3, if (a0 < 0 || a1 < 0) -1L else (a1 - a0) / reps)
  }

  /** Per tick at the nov15c first view. [stations]: Recorded rows (judge/snapshot.json projects). */
  @Test fun firstViewTick() {
    val stations = System.getenv("LOAD_STATIONS")?.toIntOrNull() ?: 6
    val head = Pose(Vector3(0f, 1.22f, 0f), Quaternion.fromEuler(0f, 10f, 0f))
    val mainPose = Pose(Vector3(0.1f, 1.3f, 1.2f), Quaternion.fromEuler(0f, 4f, 0f))
    val stationPoses = (0 until stations).map { i ->
      val a = com.overhand.app.stations.StationRules.arc(i, stations)
      Pose(Vector3(a[0], a[1], a[2]), Quaternion.fromEuler(0f, a[3], 0f))
    }
    val saved = stationPoses.map { stEncode(it) }.toMutableList()
    var elapsed = 0L
    var sink = 0.0
    // OpeningFeature.onFrame (both): hint timing and station mode. palm() needs live hands and the ECS: not timed.
    val opening = { elapsed += 100; sink += if (Opening.hintDue(elapsed, false, false)) 1.0 else 0.0; sink += Opening.stationMode(elapsed, false) }
    // WindowsFeature.onFrame -> Stations.frame (both): per station facing test, encode() for the save check, aim dot.
    val stationsFrame = {
      stationPoses.forEachIndexed { i, p ->
        val n = p.forward(); val e = head.t - p.t
        if (PanelFacing.front(n.x, n.y, n.z, e.x, e.y, e.z)) sink += 1
        val enc = stEncode(p); if (enc != saved[i]) saved[i] = enc
        sink += (p.t - head.t).normalize().dot(head.forward())
      }
    }
    // WindowsFeature.onFrame (nov15c): the main window's upright fix and the orb/dock lifetimes.
    val windows = { sink += WindowPose.upright(mainPose).t.x }
    // No attached menu placement remains in SettingsFeature.
    val settings = { sink += if(SettingsPanel.State().open) 1.0 else 0.0 }
    // RecenterFeature.onFrame (nov15c: one main window entity; nov13c: none at put-on).
    val recenter = { sink += if (RecenterFeature.easesBack("chat")) 1.0 else 0.0 }
    // PlacesFeature.onFrame (both): sky visibility with passthrough on.
    val places = { sink += if (Places.skyVisible(Place.MOON, true)) 1.0 else 0.0 }
    val parts = listOf(
      Triple("opening", true, opening), Triple("stations_frame_x$stations", true, stationsFrame), Triple("windows_main", false, windows),
      Triple("settings_orb_dock", false, settings), Triple("recenter", false, recenter), Triple("places", true, places))
    val per = parts.map { (n, in13, f) -> measure(n, in13, 20_000, f) }
    val whole = measure("tick_nov15c_first_view", false, 20_000) { parts.forEach { it.third() } }
    val nov13 = measure("tick_nov13c_set", true, 20_000) { parts.filter { it.second }.forEach { it.third() } }
    val rows = per + whole + nov13
    write("first_view_tick.tsv", "op\tin_nov13c\tcalls\tp50_us\tp99_us\tmax_us\tbytes_per_call\n" + rows.joinToString("") {
      "${it.op}\t${it.inNov13c}\t${it.calls}\t${"%.2f".format(it.p50Us)}\t${"%.2f".format(it.p99Us)}\t${"%.1f".format(it.maxUs)}\t${it.bytesPerCall}\n" })
    assertTrue(sink.isFinite())
    assertTrue("tick p50 ${whole.p50Us} us", whole.p50Us < 11_100)
  }

  private fun stEncode(p: Pose) = JSONArray(listOf(p.t.x, p.t.y, p.t.z, p.q.w, p.q.x, p.q.y, p.q.z)).toString() // Stations.encode

  @Test fun voiceLoopIdle() {
    var decodes = 0; var turns = 0
    val loop = VoiceLoop({ _, _ -> decodes++; "" }, { it.run() }, object : VoiceLoop.Listener {
      override fun speechStart(turn: Int, atSample: Long) { turns++ }
    })
    // One idle minute: room noise at about -60 dBFS (seeded), fed in 20 ms chunks as AudioRecord delivers them.
    val rnd = java.util.Random(1181)
    val chunk = ShortArray(VoiceLoop.FRAME)
    val minute = 60 * VoiceLoop.RATE / VoiceLoop.FRAME
    val a0 = allocated(); val t0 = System.nanoTime()
    repeat(minute) { for (i in chunk.indices) chunk[i] = (rnd.nextGaussian() * 33).toInt().toShort(); loop.feed(chunk, chunk.size) }
    val ns = System.nanoTime() - t0; val bytes = allocated() - a0
    val lines = listOf("Flowstate, what is blocked right now?", "so which of the projects is blocked right now and what needs me first today",
      "I was in a flow state all afternoon")
    val named = lines.map { l -> measure("wake_name_match \"${l.take(24)}\"", false, 20_000) { VoiceGate.named(l) } }
    val tsv = "row\tvalue\tunit\n" +
      "idle_minute_turns_opened\t$turns\tturns/min\n" +
      "idle_minute_decodes_started\t$decodes\tdecodes/min\n" +
      "idle_vad_cpu\t${"%.1f".format(ns / 1e3 / 60)}\tus per second of audio (JVM)\n" +
      "idle_vad_alloc\t${if (a0 < 0) -1 else bytes / 60}\tbytes per second of audio (JVM)\n" +
      named.joinToString("") { "${it.op}\t${"%.2f".format(it.p50Us)}\tus p50 (p99 ${"%.2f".format(it.p99Us)}, ${it.bytesPerCall} B)\n" }
    write("voice_idle.tsv", tsv)
    assertEquals("idle noise opens no turn", 0, turns)
    assertTrue(sqrt(ns.toDouble()) > 0)
  }
}
