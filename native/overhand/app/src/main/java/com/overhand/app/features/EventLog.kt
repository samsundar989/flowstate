package com.overhand.app.features

import java.io.File

/**
 * #1233: the app's own event file, `files/events.jsonl` (debug builds; capped at [CAP_BYTES], oldest lines dropped), so a
 * worn pull scores even when logcat brings back nothing of ours (nov15d: no main or system buffer in `-b all`).
 *
 * No logging choke point exists (call sites use `android.util.Log` directly, 30+ files, and other cards add `event=`
 * lines to theirs), so a daemon thread reads the app's OWN log (`logcat -v epoch --pid=<our pid>`, no permission needed)
 * and keeps: every `event=` line, our VrApi per-second frame lines (the frame-time summaries the scorer reads), every
 * other `Overhand*` line (the scorer's state marks), and crash lines. The lag check writes its lines here directly
 * ([direct]), so they are kept even if the reader gets nothing. One JSON object per line:
 * `{"t":<epoch s>,"pid":..,"tid":..,"lvl":"I","tag":"..","kind":"event|frames|lagcheck|log|error|events","src":"logcat|app","msg":".."}`.
 */
object EventLog {
  const val FILE = "events.jsonl"
  const val CAP_BYTES = 5L * 1024 * 1024
  /** What a cap trim keeps: the newest ~4 MB, cut at a line start. */
  const val KEEP_BYTES = 4L * 1024 * 1024
  const val TAG = "OverhandEvents"
  const val POSE_CAP_BYTES = 16L * 1024 * 1024
  const val POSE_KEEP_BYTES = 8L * 1024 * 1024

  data class Row(val t: Double, val pid: Int, val tid: Int, val lvl: String, val tag: String, val msg: String, val kind: String, val src: String) {
    fun json() = "{\"t\":${"%.3f".format(java.util.Locale.ROOT, t)},\"pid\":$pid,\"tid\":$tid,\"lvl\":\"${esc(lvl)}\",\"tag\":\"${esc(tag)}\"," +
      "\"kind\":\"$kind\",\"src\":\"$src\",\"msg\":\"${esc(msg)}\"}"
  }

  /** `logcat -v epoch`: `  1794775593.123  25616  25630 I VrApi   : FPS=72/72,...`. */
  private val LINE = Regex("""^\s*(\d+\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEFA])\s+(.*?)\s*: (.*)$""")

  /** Which lines are kept, and as what (null: dropped). */
  fun kind(tag: String, lvl: String, msg: String): String? = when {
    tag == "OverhandPose" && msg.startsWith("pose {") -> "pose"
    tag == TAG -> "events"
    tag == "VrApi" && "FPS=" in msg -> "frames"
    tag.startsWith("Overhand") && (msg.startsWith("lagcheck") || msg.startsWith("mark lagcheck")) -> "lagcheck"
    "event=" in msg -> "event"
    tag.startsWith("Overhand") -> "log"
    tag == "AndroidRuntime" || lvl == "F" -> "error"
    else -> null
  }

  /** One logcat line as a kept row, or null. Lag-check and our own lines are written [direct], so the reader skips them. */
  fun parse(line: String): Row? {
    val m = LINE.matchEntire(line) ?: return null
    val (t, pid, tid, lvl, tag, msg) = m.destructured
    val k = kind(tag, lvl, msg) ?: return null
    if (k == "lagcheck" || k == "events") return null
    return Row(t.toDouble(), pid.toInt(), tid.toInt(), lvl, tag, msg, k, "logcat")
  }

  fun esc(s: String): String {
    val b = StringBuilder(s.length + 8)
    for (c in s) when {
      c == '"' -> b.append("\\\""); c == '\\' -> b.append("\\\\"); c == '\n' -> b.append("\\n"); c == '\r' -> b.append("\\r")
      c == '\t' -> b.append("\\t"); c < ' ' -> b.append("\\u%04x".format(c.code)); else -> b.append(c)
    }
    return b.toString()
  }

  /** The file, appended one line at a time; past [cap] bytes it keeps only the newest [keep] bytes (whole lines). */
  class Sink(val file: File, private val cap: Long = CAP_BYTES, private val keep: Long = KEEP_BYTES,
    private val poseCap: Long = POSE_CAP_BYTES, private val poseKeep: Long = POSE_KEEP_BYTES) {
    private var poseBytes=if(file.exists()) file.useLines { rows -> rows.filter { isPose(it) }.sumOf { it.toByteArray().size+1L } } else 0L
    // Reserve eight MiB at process start: over 30 minutes at the measured 12-object rate.
    init { if (poseBytes > poseKeep) trimPoses(poseKeep) }
    private fun isPose(line: String)=line.contains("\"kind\":\"pose\"")
    @Synchronized fun write(row: Row) {
      runCatching {
        val line=row.json()+"\n"
        file.appendText(line)
        if(row.kind=="pose") poseBytes+=line.toByteArray().size
        if(poseBytes>poseCap) trimPoses(poseKeep)
        // Pose traffic never evicts a frame or event. Existing non-pose retention is unchanged.
        if(row.kind!="pose" && file.length()-poseBytes>cap) trim()
      }
    }
    private fun trimPoses(keep: Long) {
      var drop=poseBytes-keep
      val tmp=File(file.parentFile,file.name+".tmp")
      tmp.bufferedWriter().use { out -> file.forEachLine { line ->
        val bytes=line.toByteArray().size+1L
        if(isPose(line) && drop>0) { drop-=bytes;poseBytes-=bytes }
        else { out.write(line);out.write("\n") }
      } }
      if(!tmp.renameTo(file)) { file.delete();tmp.renameTo(file) }
    }
    private fun trim() {
      val lines = file.readLines()
      var bytes = 0L
      var oldest = lines.size
      for (i in lines.indices.reversed()) {
        if (!isPose(lines[i])) {
          val n = lines[i].toByteArray().size + 1L
          if (bytes + n > keep) break
          bytes += n; oldest = i
        }
      }
      // Match the ordinary whole-line suffix, but keep poses by capture time even
      // when the reader and capture queue interleave their physical append order.
      val time = lines.drop(oldest).filterNot { isPose(it) }.mapNotNull { it.substringAfter("\"t\":").substringBefore(',').toDoubleOrNull() }.minOrNull() ?: Double.POSITIVE_INFINITY
      val kept = lines.filterIndexed { i, line ->
        if (!isPose(line)) i >= oldest
        else (line.substringAfter("\"t\":").substringBefore(',').toDoubleOrNull() ?: 0.0) >= time
      }
      val tmp = File(file.parentFile, file.name + ".tmp")
      tmp.bufferedWriter().use { out -> kept.forEach { out.write(it); out.newLine() } }
      if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
      poseBytes = kept.filter { isPose(it) }.sumOf { it.toByteArray().size + 1L }
    }
  }

  /** One bounded FIFO. Pose producers only offer; file work and drop notices run here.
   * Barriers drain all accepted captures, including the final overflow notice. */
  class Writer(private val write: (Row) -> Unit, capacity: Int = 256) : AutoCloseable {
    private val queue = java.util.concurrent.ArrayBlockingQueue<() -> Unit>(capacity)
    private val dropped = java.util.concurrent.atomic.AtomicLong()
    private var closed = false
    private val admission = java.util.concurrent.locks.ReentrantLock()
    private var running = true
    private var last: Row? = null
    private fun drops() {
      val n = dropped.getAndSet(0)
      if (n > 0) last?.let { write(it.copy(tag=TAG, kind="events", msg="pose_dropped n=$n")) }
    }
    private val thread = Thread({
      while (running) { queue.take().invoke(); drops() }
    }, "overhand-pose-writer").apply { isDaemon = true; start() }
    fun submit(row: Row) {
      // A backed-up event producer must never hold a monitor the capture needs.
      if (row.kind == "pose") {
        if (!admission.tryLock()) { dropped.incrementAndGet(); return }
      } else admission.lock()
      try {
        if (closed) return
        val task = { last = row; write(row) }
        if (row.kind == "pose") { if (!queue.offer(task)) dropped.incrementAndGet() }
        else queue.put(task)
      } finally { admission.unlock() }
    }
    fun flush() {
      admission.lock()
      try {
        if (closed) return
        val done = java.util.concurrent.CountDownLatch(1)
        queue.put { drops(); done.countDown() }
        done.await()
      } finally { admission.unlock() }
    }
    override fun close() {
      admission.lock()
      try {
        if (closed) return
        flush(); closed = true
        queue.put { running = false }
        thread.join()
      } finally { admission.unlock() }
    }
  }

  @Volatile private var sink: Writer? = null
  @Volatile private var reader: Process? = null
  val enabled get()=sink!=null
  fun flush() { sink?.flush() }
  @Synchronized fun stop() { reader?.destroy(); reader=null; sink?.close(); sink=null }

  /** A line the app writes itself (now, this process); also logged so logcat keeps it when it can. */
  fun direct(tag: String, msg: String, log: Boolean = false) {
    if (log) android.util.Log.i(tag, msg)
    val s = sink ?: return
    val k = kind(tag, "I", msg) ?: "log"
    s.submit(Row(System.currentTimeMillis() / 1000.0, android.os.Process.myPid(), android.os.Process.myTid(), "I", tag, msg, k, "app"))
  }

  /** Once per process (debug builds): open the file and start the reader. */
  @Synchronized fun start(dir: File) {
    if (sink != null) return
    if (!com.overhand.app.BuildConfig.DEBUG) return
    // Lazy initialization occurs only inside the writer, including start-up trimming.
    val storage by lazy { Sink(File(dir, FILE)) }
    val s = Writer({ storage.write(it) }); sink = s
    val pid = android.os.Process.myPid()
    direct(TAG, "events start pid=$pid reader=logcat_pid cap_bytes=$CAP_BYTES", log = true)
    Thread({
      var n = 0L
      val r = runCatching {
        val p = ProcessBuilder("logcat", "-v", "epoch", "--pid=$pid").redirectErrorStream(true).start()
        reader = p
        if (sink !== s) p.destroy()
        p.inputStream.bufferedReader().forEachLine { l -> parse(l)?.let { s.submit(it); n++ } }
        p.waitFor()
      }
      direct(TAG, "events reader_exit lines=$n result=${r.getOrElse { it.javaClass.simpleName }}", log = true)
    }, "overhand-events").apply { isDaemon = true }.start()
  }
}
