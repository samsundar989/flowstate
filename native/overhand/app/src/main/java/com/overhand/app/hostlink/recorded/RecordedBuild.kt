package com.overhand.app.hostlink.recorded

import com.overhand.app.hostlink.HostLink
import com.overhand.app.hostlink.InMemoryPairedHostStore
import com.overhand.app.hostlink.project.PreviewFetch
import com.overhand.app.hostlink.project.ProjectScreen
import org.json.JSONObject
import java.util.Base64

/**
 * #1008: one real host run (runs/host/e2e/golden, bundled by tools/judge_bundle/build_from_nothing.py) as Recorded mode
 * plays it: the host-to-headset frames verbatim, and the files committed at each commit that `get_file` serves.
 * No Android in it: [readText]/[readBytes] read `assets/` paths.
 */
class RecordedBuildBundle(readText: (String) -> String,private val readBytes: (String) -> ByteArray) {
  data class Served(val commit: String,val path: String,val bytes: Long,val sha256: String)
  val run=JSONObject(readText(DIR+"run.json"))
  /** `welcome` first, then every `event` in seq order, exactly as the host sent them. */
  val frames: List<JSONObject> =readText(DIR+"frames.jsonl").lineSequence().filter { it.isNotBlank() }.map(::JSONObject).toList()
  val files: List<Served> =run.getJSONArray("files").let { a -> (0 until a.length()).map { a.getJSONObject(it) }
    .map { Served(it.getString("commit"),it.getString("path"),it.getLong("bytes"),it.getString("sha256")) } }
  val projectId: String=run.getString("project_id")
  val realElapsedMs: Long=run.getLong("real_elapsed_ms")
  val followUpTask: String=run.getJSONObject("followup").getString("task_id")
  val followUpText: String=run.getJSONObject("followup").getString("text")
  val recordedAnswer: String=run.getJSONObject("question").getString("recorded_label")
  val events get()=frames.filter { it.optString("type")=="event" }
  val name get()=frames.firstOrNull { it.optString("kind")=="project_created" }?.optString("name").orEmpty()
  /** The committed bytes of [f], refused unless they still hash to the table's sha256. */
  fun bytes(f: Served): ByteArray? =runCatching { readBytes(DIR+"files/${f.commit}/${f.path}") }.getOrNull()
    ?.takeIf { it.size.toLong()==f.bytes && PreviewFetch.sha256Hex(it)==f.sha256 }

  companion object {
    const val DIR="judge/build_from_nothing/"
    /** What it is, in the Recorded project list. */
    const val LIST_NAME="Build from nothing"
    const val KEY="build_from_nothing"
    fun load(readText: (String) -> String,readBytes: (String) -> ByteArray): RecordedBuildBundle? =
      runCatching { RecordedBuildBundle(readText,readBytes) }.getOrNull()
  }
}

/**
 * The host's end of `get_file` (PROTOCOL.md 7e) over the bundle: one request, one `file_chunk` of at most [CHUNK] bytes,
 * `sha256` of the whole file; an unknown commit or path gets the host's `error not_found`. [served] lists what went out.
 */
class RecordedHost(private val bundle: RecordedBuildBundle) {
  val served=mutableListOf<Pair<String,String>>()
  fun reply(f: JSONObject): JSONObject? {
    if(f.optString("type")!="get_file") return null
    val req=f.optString("req_id");val commit=f.optString("commit");val path=f.optString("path");val offset=f.optLong("offset",-1)
    fun error(code: String,msg: String)=JSONObject().put("type","error").put("req_id",req).put("code",code).put("message",msg)
    if(f.optString("project_id")!=bundle.projectId) return error("unknown_project","No such project")
    if(!Regex("^[0-9a-f]{7,40}$").matches(commit) || offset<0) return error("bad_request","Bad file request")
    val file=bundle.files.firstOrNull { it.commit.startsWith(commit) && it.path==path } ?: return error("not_found","That file is not in that commit")
    val all=bundle.bytes(file) ?: return error("not_found","That file is not in that commit")
    if(offset>all.size) return error("bad_request","Offset past the end")
    val end=minOf(all.size.toLong(),offset+CHUNK).toInt()
    served.add(file.commit to file.path)
    return JSONObject().put("type","file_chunk").put("req_id",req).put("path",path).put("sha256",file.sha256)
      .put("offset",offset).put("total",all.size).put("data",Base64.getEncoder().encodeToString(all.copyOfRange(offset.toInt(),end)))
  }
  companion object { const val CHUNK=4500 }
}

/**
 * When each frame plays, in ms from the start: the real gaps scaled down to fit [targetMs], with a floor per kind so
 * file writes come one at a time, the design question's card stays up long enough to read and answer, and the commit
 * and Preview are seen. Pure, so the JVM test checks the whole run fits in 75 s.
 */
object ReplayClock {
  const val TARGET_MS=60_000L
  const val LEAD_MS=1_500L
  fun floor(kind: String,prev: String)=when {
    kind=="answered" -> 9_000L          // the question card, open for the judge
    kind=="file_written" -> 3_500L      // one write at a time
    kind=="plan" -> 2_500L
    kind=="preview" -> 2_500L
    kind=="finished" -> 2_000L
    kind=="task_state" && prev=="preview" -> 6_000L   // the playable Preview before the follow-up is spoken
    else -> 350L
  }
  fun schedule(frames: List<JSONObject>,targetMs: Long=TARGET_MS): LongArray {
    val at=frames.map { f -> f.optString("at").takeIf { it.isNotBlank() }?.let { java.time.Instant.parse(it).toEpochMilli() } }
    val gaps=LongArray(frames.size);val floors=LongArray(frames.size)
    var prevAt: Long?=null;var prevKind=""
    frames.forEachIndexed { i,f ->
      val a=at[i]
      if(a!=null && prevAt!=null) { gaps[i]=a-prevAt!!;floors[i]=floor(f.optString("kind"),prevKind) }
      if(a!=null) { prevAt=a;prevKind=f.optString("kind") }
    }
    fun total(scale: Double)=LEAD_MS+gaps.indices.sumOf { maxOf(floors[it],(gaps[it]*scale).toLong()) }
    var lo=0.0;var hi=1.0
    repeat(40) { val mid=(lo+hi)/2;if(total(mid)<=targetMs) lo=mid else hi=mid }
    // The welcome (no `at`) plays at 0, the first event after LEAD_MS, each later one after its scaled gap or its floor.
    val out=LongArray(frames.size);var t=0L;var first=true
    frames.indices.forEach { i ->
      if(at[i]!=null) { t=if(first) LEAD_MS else t+maxOf(floors[i],(gaps[i]*lo).toLong());first=false }
      out[i]=t
    }
    return out
  }
  /** "3 min 24 s". */
  fun words(ms: Long)=(ms/1000).let { s -> if(s>=60) "${s/60} min ${s%60} s" else "$s s" }
}

/**
 * One replay: a fresh [HostLink] with no socket and no stored computer, fed the bundle's frames through the handler the
 * socket feeds ([HostLink.feed]); its reads go to a [RecordedHost], whose replies come back the same way. Answers are
 * never sent ([HostLink] is built with mayTransmit=false); the recorded `answered` event closes them.
 * [post] schedules a frame (delay ms from now); the caller owns the clock, so the JVM test can run it instantly.
 */
class RecordedBuildRun(val bundle: RecordedBuildBundle,private val post: (Long,Runnable) -> Unit) {
  val link=HostLink(InMemoryPairedHostStore(),mayTransmit={ false })
  val host=RecordedHost(bundle)
  val schedule=ReplayClock.schedule(bundle.frames)
  val playMs get()=schedule.last()
  @Volatile var fed=0; private set
  @Volatile var stopped=false; private set

  init { link.attachRecorded { f -> host.reply(f)?.let { link.feed(it.toString()) } } }

  fun start() {
    bundle.frames.forEachIndexed { i,f ->
      post(schedule[i],Runnable { if(!stopped) { link.feed(f.toString());fed++ } })
    }
  }
  fun stop() { stopped=true;link.shutdown() }
  /** The banner every screen of the replay carries (the honesty rule: replayed is labelled recorded). */
  val banner get()="Recorded run, ${ReplayClock.words(bundle.realElapsedMs)}, shown in ${ReplayClock.words(playMs)}"
}

/** What the replay's project window shows (drawn by [com.overhand.app.hostlink.project.ProjectPanelView]). Pure, for layout lint. */
object RecordedBuildScreens {
  /** Human title for the recorded request; routing and captured project name stay intact. */
  fun title(name: String)=if(name=="squash bug bugs pop up") "Squash the bug" else
    name.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() } ?: RecordedBuildBundle.LIST_NAME
  const val PICK_HINT="Pick one if you like: it stays on this headset. The run goes on with its recorded answer."
  /** #1100: the host's rows speak to the person who answered live ("You said: Sunny garden"); in the replay that person
   *  is the recorded run, not the wearer, whose own pick may differ. */
  fun row(text: String)=when {
    text.startsWith("You said: ") -> "Recorded answer: "+text.removePrefix("You said: ")
    text=="Your coding agent picked up your answer" -> "The coding agent picked up the recorded answer"
    else -> text
  }
  /** [clock] is the banner line ("Recorded run, 3 min 24 s, shown in 1 min 0 s · 0:12 of 1:00"); it always comes first. */
  fun of(clock: String,name: String,question: JSONObject?,pickNote: String,caption: String,rows: List<String>,planned: Boolean,done: Boolean): ProjectScreen {
    val title=title(name)
    val lines=mutableListOf(clock)
    if(question!=null) {
      lines+="Asks: "+question.optString("title")
      lines+=pickNote.ifBlank { PICK_HINT }
      val a=question.optJSONArray("options")
      val buttons=(0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it) }.map { "pick_"+it.optString("id") to it.optString("label") }
      return ProjectScreen("bfn_question",title,lines,buttons)
    }
    // #1100: the note naming the recorded answer stays up with the follow-up caption, so a wearer who picked another
    // style never reads the run's own "sunny garden" lines without it.
    if(pickNote.isNotBlank()) lines+=pickNote
    if(caption.isNotBlank()) lines+=caption
    lines+=(if(rows.isEmpty()) listOf("Starting the recorded run…") else rows.map(::row)).takeLast(6-lines.size)
    val buttons=(if(planned) listOf("graph" to "Show plan on graph") else emptyList())+
      (if(done) listOf("replay" to "Replay") else emptyList())+listOf("close" to "Close")
    return ProjectScreen(if(done) "bfn_done" else "bfn_working",title,lines,buttons)
  }
}
