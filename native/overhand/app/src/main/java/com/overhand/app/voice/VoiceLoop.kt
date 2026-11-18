package com.overhand.app.voice

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * #1001 always-on listening, with no Android in it (unit tested, and driven on the JVM by the clip-set eval).
 * PCM16 at 16 kHz goes in through [feed] in any chunk size; turns come out through [Listener].
 *
 * - Voice activity: 20 ms frames, energy against a noise floor that follows the quiet. A turn starts after
 *   [START_FRAMES] voiced frames in the last [START_WINDOW] and keeps [PREROLL_FRAMES] of audio before that.
 * - End of turn: [END_SILENCE_FRAMES] frames (0.7 s) with no voice, or [MAX_FRAMES] (15 s) in all.
 * - Partial captions: while a turn is open the audio so far is re-decoded every [PARTIAL_EVERY] frames (one at a time).
 * - It never hears itself: while [deafen] is on, and for [DEAF_TAIL_FRAMES] (300 ms) after, frames are thrown away
 *   and any open turn is cancelled.
 * Times are counted in samples fed, so the clip eval and the headset measure the same thing.
 */
class VoiceLoop(private val ears: Transcriber, private val run: (Runnable) -> Unit, private val listener: Listener,
                private val clock: () -> Long = System::nanoTime) {
  interface Listener {
    /** A turn opened at sample [atSample]. */
    fun speechStart(turn: Int, atSample: Long) {}
    fun partial(turn: Int, text: String) {}
    /** The 0.7 s of silence ended the turn; its final decode starts now. */
    fun ended(turn: Int) {}
    /** The turn ended at [endSample] (after the 0.7 s of silence); [decodeMs] is the wall time of the final decode. */
    fun final(turn: Int, text: String, endSample: Long, voicedEndSample: Long, decodeMs: Long) {}
    /** The turn was too short to be speech, or was cancelled (the app started speaking). */
    fun dropped(turn: Int) {}
  }

  @Volatile var turn = 0; private set
  /** Samples fed so far (including deaf ones). */
  @Volatile var samples = 0L; private set
  var open = false; private set
  /** Total wall time spent in decodes, for the cost measurement. */
  @Volatile var decodeNanos = 0L; private set
  @Volatile var decodes = 0; private set

  private val frame = ShortArray(FRAME); private var fill = 0
  private var floorDb = -60.0
  private val recent = ArrayDeque<Boolean>()
  private val preroll = ArrayDeque<ShortArray>()
  private val seg = PcmBuffer(RATE, MAX_FRAMES * FRAME / RATE + 2)
  private var voicedFrames = 0; private var silentRun = 0; private var segFrames = 0; private var lastVoicedSample = 0L
  private var sincePartial = 0
  private val partialBusy = java.util.concurrent.atomic.AtomicBoolean(false)
  @Volatile private var deaf = false
  private var deafUntil = 0L

  /** The app's own speech: on when a reply starts, off when it ends (deaf for 300 ms more). */
  @Synchronized fun deafen(on: Boolean) {
    if (on) { deaf = true; cancel() } else if (deaf) { deaf = false; deafUntil = samples + DEAF_TAIL_FRAMES * FRAME }
  }
  val isDeaf get() = deaf || samples < deafUntil

  @Synchronized fun feed(src: ShortArray, n: Int) {
    var i = 0
    while (i < n) {
      val k = minOf(FRAME - fill, n - i)
      System.arraycopy(src, i, frame, fill, k); fill += k; i += k
      if (fill == FRAME) { fill = 0; samples += FRAME; step(frame) }
    }
  }

  /** Close any open turn without a result (mute, pause, the headset taken off). */
  @Synchronized fun cancel() {
    if (open) { open = false; listener.dropped(turn); turn++ }
    recent.clear(); preroll.clear(); seg.clear()
  }

  private fun step(f: ShortArray) {
    if (isDeaf) { if (open) cancel(); preroll.clear(); recent.clear(); return }
    var e = 0.0; for (s in f) e += s.toDouble() * s
    val db = 20 * log10(sqrt(e / FRAME) / 32768.0 + 1e-9)
    val voiced = db > maxOf(floorDb + ABOVE_FLOOR_DB, MIN_DB)
    if (!open) floorDb = if (db < floorDb) db else floorDb + 0.01 * (db - floorDb)
    recent.addLast(voiced); while (recent.size > START_WINDOW) recent.removeFirst()
    val copy = f.copyOf()
    if (!open) {
      preroll.addLast(copy); while (preroll.size > PREROLL_FRAMES) preroll.removeFirst()
      if (recent.count { it } >= START_FRAMES) {
        open = true; turn++; voicedFrames = recent.count { it }; silentRun = 0; segFrames = 0; sincePartial = 0
        seg.clear(); preroll.forEach { seg.append(it, it.size); segFrames++ }; preroll.clear()
        lastVoicedSample = samples
        listener.speechStart(turn, samples - segFrames.toLong() * FRAME)
      }
      return
    }
    seg.append(copy, copy.size); segFrames++
    if (voiced) { voicedFrames++; silentRun = 0; lastVoicedSample = samples } else silentRun++
    if (++sincePartial >= PARTIAL_EVERY && silentRun < END_SILENCE_FRAMES / 2) { sincePartial = 0; partial() }
    if (silentRun >= END_SILENCE_FRAMES || segFrames >= MAX_FRAMES) end()
  }

  private fun partial() {
    if (!partialBusy.compareAndSet(false, true)) return
    val t = turn; val audio = seg.toFloats()
    run(Runnable {
      try { val text = decode(audio); if (t == turn && open && text.isNotBlank()) listener.partial(t, text) }
      finally { partialBusy.set(false) }
    })
  }

  private fun end() {
    open = false
    val t = turn; val endSample = samples; val voicedEnd = lastVoicedSample
    if (voicedFrames < MIN_VOICED_FRAMES) { listener.dropped(t); turn++; seg.clear(); return }
    // Keep 200 ms of the trailing silence: the recogniser needs a little, not 0.7 s of it.
    val keep = seg.size - maxOf(0, silentRun - 10) * FRAME
    val audio = seg.toFloats().copyOf(maxOf(0, keep))
    seg.clear(); recent.clear()
    listener.ended(t)
    run(Runnable {
      val t0 = clock(); val text = decode(audio); val ms = (clock() - t0) / 1_000_000
      listener.final(t, text, endSample, voicedEnd, ms)
    })
  }

  private fun decode(audio: FloatArray): String {
    val t0 = System.nanoTime()
    return try { ears.transcribe(audio, RATE).trim() } finally { decodeNanos += System.nanoTime() - t0; decodes++ }
  }

  companion object {
    const val RATE = 16000
    const val FRAME = 320                 // 20 ms
    const val START_WINDOW = 10           // 200 ms
    const val START_FRAMES = 6            // 120 ms voiced inside it
    const val PREROLL_FRAMES = 15         // 300 ms kept from before the start
    const val END_SILENCE_FRAMES = 35     // 0.7 s of silence ends a turn
    const val MAX_FRAMES = 750            // 15 s
    const val MIN_VOICED_FRAMES = 12      // 240 ms of voice, or it was a knock
    const val PARTIAL_EVERY = 25          // a partial caption every 0.5 s
    const val DEAF_TAIL_FRAMES = 15       // 300 ms after the app stops speaking
    const val ABOVE_FLOOR_DB = 12.0
    const val MIN_DB = -48.0
  }
}

/**
 * #1001: what a final transcript is allowed to do. The risk is a false action, not a missed one, so only a known
 * command or a confidently matched question acts; anything else is shown dimmed and dropped.
 *
 * [confidence] is how likely the words were said TO the app, from the words alone (the recogniser reports no
 * probabilities): short, and opening like a question or a request. It does not look at the answer table, so the
 * interface card's command table can use the same number.
 */
object VoiceGate {
  /**
   * Picked on the dev half of runs/voice_loop/clips by VoiceLoopEvalTest (eval.json "dev_curve"), rule: the lowest
   * value with no false or wrong action, at the best recall. Dev recall is flat from 0 to 1; with the app's own lines
   * dropped first ([isOwnLine]) dev false actions are 1 below 0.4 and 0 from 0.4 up, so 0.4 (job10:
   * runs/voice_loop/job10/eval.json). Before the own-line drop, the greeting heard back forced 1.0.
   * #1051: with coverage + confirm in [decide], dev recall and false actions are the same at every value from 0 to 1
   * (0.706, 0), so the same rule picks 0.0: the coverage rule now does what this floor did.
   */
  const val THRESHOLD = 0.0

  private val LEAD = setOf("um", "uh", "so", "okay", "ok", "hey", "and", "well", "oh", "please", "now", "right")
  private val OPENERS = setOf("what", "which", "who", "why", "how", "when", "where", "is", "are", "was", "were", "can", "could",
    "do", "does", "did", "will", "would", "should", "tell", "show", "give", "help", "summarize", "summarise", "explain",
    "mute", "stop", "any", "anything", "has", "have", "take", "open", "go", "describe")
  private val MUTE = setOf("mute", "mute mic", "mute the mic", "mute microphone", "mute the microphone", "mute yourself",
    "stop listening", "go quiet", "be quiet", "quiet")

  fun words(text: String) = VoiceIntents.words(text)

  fun confidence(text: String): Double {
    val w = words(text)
    if (w.isEmpty()) return 0.0
    val lead = w.dropWhile { it in LEAD }.ifEmpty { w }
    val form = if (lead.first() in OPENERS) 1.0 else 0.5
    val length = when { w.size <= 9 -> 1.0; w.size >= 21 -> 0.0; else -> (21 - w.size) / 12.0 }
    return form * length
  }

  /** "mute", "please mute", "stop listening": the whole utterance, nothing else in it. */
  fun isMute(text: String): Boolean {
    val w = words(text).filter { it !in LEAD }
    return w.isNotEmpty() && w.joinToString(" ") in MUTE
  }

  /**
   * The app's own spoken lines (the opening cues of res/raw/first60_cues.json and the scripted answers) heard back,
   * e.g. a recording of its greeting played in the room: never an action, even when the command table would take one
   * of their words ("...pinch to see the options" is not "open settings"). Word-set overlap of at least [OWN_OVERLAP]
   * with a line of four words or more.
   */
  const val OWN_OVERLAP = 0.8
  fun isOwnLine(text: String, own: Collection<String>): Boolean {
    val w = words(text).toSet()
    if (w.size < 4) return false
    return own.any { line -> val o = words(line).toSet(); o.size >= 4 && (w intersect o).size.toDouble() / (w union o).size >= OWN_OVERLAP }
  }

  /** Every cue text and "then_yes" line in first60_cues.json. */
  fun ownLines(cuesJson: String): List<String> {
    val sets = org.json.JSONObject(cuesJson).getJSONObject("sets")
    return sets.keys().asSequence().flatMap { k ->
      val set = sets.getJSONObject(k); val cues = set.optJSONArray("cues")
      (0 until (cues?.length() ?: 0)).map { cues!!.getJSONObject(it).getString("text") } + listOfNotNull(set.optString("then_yes").ifEmpty { null })
    }.toList()
  }

  enum class Route { MUTE, ANSWER, COMMAND, CONFIRM, DROP }
  /**
   * [command] is the #1002 table's reading (side-effect free [UiCommands.parse]), [match] the answer table's.
   * [coverage]: the share of the line's content words the chosen command or answer explains. [why]: for the log.
   */
  data class Decision(val route: Route, val confidence: Double, val match: VoiceIntents.Match?, val command: UiResult? = null,
                      val coverage: Double = 0.0, val why: String = "", val margin: Double = Double.NaN, val explained: Int = 0) {
    /** What a confirm offer or an act names in the log: the command's action or the answer's id. */
    val target get() = command?.action ?: command?.caption?.takeIf { it.isNotEmpty() }?.let { "ask" } ?: match?.answer?.id ?: ""
  }

  // ---- #1051 coverage + confirm-when-unsure ----

  /** Coverage at which a matched line acts; from [CONFIRM_AT] up to it, the "Do it?" chip is offered instead. */
  const val ACT_AT = 0.6
  const val CONFIRM_AT = 0.34
  /** More words, or more content words, than these is a story or talk, not a request: it never acts directly (at most a confirm offer). */
  const val MAX_DIRECT_WORDS = 14
  const val MAX_DIRECT_CONTENT = 5
  /** More content words than this is never offered either. */
  const val MAX_OFFER_CONTENT = 6

  /** Words that carry no content of their own: they neither count against nor for a command. */
  private val FUNCTION = setOf("a", "an", "the", "i", "me", "my", "mine", "you", "your", "we", "us", "our", "it", "its", "this", "that",
    "these", "those", "there", "here", "is", "are", "am", "was", "were", "be", "been", "do", "does", "did", "can", "could", "would", "will",
    "should", "shall", "may", "might", "please", "what", "which", "who", "whom", "how", "why", "when", "where", "to", "of", "in", "on", "for",
    "at", "with", "from", "by", "up", "and", "or", "so", "um", "umm", "uh", "uhh", "er", "erm", "hmm", "hey", "okay", "ok", "well", "oh",
    "just", "now", "then", "also", "thanks", "thank", "let", "lets", "let's", "give", "tell", "want", "wanna", "need", "like", "id", "i'd",
    "go", "ahead", "quick", "quickly", "kindly", "alright", "right", "yes", "yeah", "o", "hi", "any", "anything", "some", "get", "got",
    "have", "has", "had", "not", "one", "all", "again", "them", "they", "their", "went", "goes", "gone", "wait", "fine", "thats", "too")
  /** Questions put to a person in the room ("did you remember...", "have you seen..."): never acted on directly. */
  private val PERSON = listOf("did you", "do you remember", "do you know", "have you", "did he", "did she", "has he", "has she",
    "are you going", "were you", "you remember", "you know what", "did we", "do we have", "can you pass", "could you pass",
    "will you", "would you mind", "remember to")

  /** Everyday questions the recorded day cannot answer, whose words the answer keys happen to share ("what time"). */
  private val OFF_TOPIC = listOf("time is it", "what time is", "what is the time", "lunch", "dinner", "breakfast")

  private fun content(ws: List<String>) = ws.filter { it !in FUNCTION }

  /** Share of the content words in [ws] that [explains]; a line of function words only ("help") is fully covered. */
  fun coverage(ws: List<String>, explains: (String) -> Boolean): Double {
    val c = content(ws)
    return if (c.isEmpty()) 1.0 else c.count(explains).toDouble() / c.size
  }

  fun addressedToPerson(text: String): Boolean {
    val s = " " + words(text).dropWhile { it in LEAD }.joinToString(" ") + " "
    return PERSON.any { s.contains(" $it ") }
  }

  /** The verdict on a line the command or answer table took: act, offer "Do it?", or drop. */
  private fun verdict(text: String, cov: Double, nContent: Int): Pair<Route?, String> {
    val n = words(text).size
    val person = addressedToPerson(text)
    return when {
      cov < CONFIRM_AT -> null to "coverage"
      nContent > MAX_OFFER_CONTENT -> null to "long"
      person -> (if (cov >= ACT_AT) Route.CONFIRM else null) to "person"
      n > MAX_DIRECT_WORDS || nContent > MAX_DIRECT_CONTENT -> (if (cov >= ACT_AT) Route.CONFIRM else null) to "long"
      cov >= ACT_AT -> Route.ANSWER to "ok"
      else -> Route.CONFIRM to "coverage"
    }
  }

  /** Words of the recorded day any question may carry ("project", "agents", "today"), besides the answer's own keys. */
  private val DOMAIN = "project projects agent agents task tasks day today worker workers team right now so far"

  /** The words an answer explains: every word of its key phrases and the day's own words, normalised the matcher's way. */
  private fun answerWords(intents: VoiceIntents, m: VoiceIntents.Match): Set<String> =
    (m.answer.keys.flatMap { intents.norm(it.first.joinToString(" ")) } + intents.norm(DOMAIN)).toSet()

  /**
   * #1051: the whole decision for one final transcript, with no side effects. Mute bypasses (whole utterance);
   * then #1002's command table, then the answer table. A table's match acts only when it explains at least [ACT_AT]
   * of the content words, the line is short, and it is not a question put to a person; between [CONFIRM_AT] and
   * [ACT_AT] (or short but person-shaped / long-ish yet well covered) it is offered with one "Do it?" chip.
   * [threshold] is #1001's lexical confidence floor, still applied first.
   */
  fun decide(intents: VoiceIntents, text: String, threshold: Double = THRESHOLD, commands: UiCommands? = null): Decision {
    val c = confidence(text)
    if (isMute(text)) return Decision(Route.MUTE, c, null, why = "exact")
    if (words(text).isEmpty()) return Decision(Route.DROP, c, null, why = "empty")
    val s = " " + words(text).joinToString(" ") + " "
    if (OFF_TOPIC.any { s.contains(" $it ") }) return Decision(Route.DROP, c, null, why = "off_topic")
    val you = youWithoutAppNoun(text)
    val cmd = commands?.parse(text)?.takeIf { it.action != null || it.caption.isNotEmpty() }
    if (cmd != null) {
      val ws = UiCommands.tokens(text)
      val (cov, _, nContent) = commandCoverage(commands, text)
      var (r, why) = verdict(text, cov, nContent)
      // #1056: a command that closes, moves or switches what is around the wearer only acts on a named window.
      if (r == Route.ANSWER && cmd.action in LAYOUT && !namesTarget(ws, cmd)) { r = Route.CONFIRM; why = "layout" }
      if (r == Route.ANSWER && you && cmd.slot.isEmpty()) { r = Route.CONFIRM; why = "you" }
      if (r == Route.ANSWER && namesRoomThing(text)) { r = Route.CONFIRM; why = "room" }
      return Decision(if (r == Route.ANSWER) Route.COMMAND else r ?: Route.DROP, c, null, cmd, cov, why)
    }
    if (c < threshold) return Decision(Route.DROP, c, null, why = "confidence")
    // #1056: paraphrase slots ("sign off on" = approve, "rundown" = summary) for the answer table only.
    val m = intents.match(paraphrase(text))
    if (m.kind == MatchKind.NEAREST && askedOfApp(text)) return Decision(Route.ANSWER, c, m, null, 1.0, "smart")
    if (m.kind != MatchKind.ANSWER) return Decision(Route.DROP, c, m, why = m.kind.name.lowercase())
    val (cov, _, nContent) = answerCoverage(intents, m)
    var (r, why) = verdict(text, cov, nContent)
    if (r == Route.ANSWER && you) { r = Route.CONFIRM; why = "you" }
    if (r == Route.ANSWER && namesRoomThing(text)) { r = Route.CONFIRM; why = "room" }
    return Decision(r ?: Route.DROP, c, m, null, cov, why)
  }

  /** Coverage of [text] by the command table, the words it explains, and the content words: (coverage, explained, content). */
  fun commandCoverage(commands: UiCommands, text: String): Triple<Double, Int, Int> {
    val ws = UiCommands.tokens(text)
    // A name the recogniser split ("run time" for runtime) is explained by its joined form.
    fun ex(i: Int) = commands.explains(ws[i]) || (i + 1 < ws.size && commands.explains(ws[i] + ws[i + 1])) || (i > 0 && commands.explains(ws[i - 1] + ws[i]))
    val idx = ws.indices.filter { ws[it] !in FUNCTION }
    val n = idx.count(::ex)
    return Triple(if (idx.isEmpty()) 1.0 else n.toDouble() / idx.size, n, idx.size)
  }

  /** Coverage of the matched line by answer [m]'s words: (coverage, explained, content). */
  fun answerCoverage(intents: VoiceIntents, m: VoiceIntents.Match): Triple<Double, Int, Int> {
    val explained = answerWords(intents, m)
    // The matcher's form of the words (stemmed, synonyms), so "anything" (anyth) and "this morning" (day) line up.
    val plain = FUNCTION.flatMap { intents.norm(it) }.toSet() + intents.config.stopwords
    val ws = m.words.filter { it !in plain }
    val n = ws.count { it in explained }
    return Triple(if (ws.isEmpty()) 1.0 else n.toDouble() / ws.size, n, ws.size)
  }

  // ---- #1056 ----

  /** Commands that close, move or switch what surrounds the wearer: always-on speech only offers them... */
  val LAYOUT = setOf("close_all", "close_window", "close_settings", "center_windows", "open_project")
  /** ...unless the line names one of these windows ("window" alone is not a target), and the command is about it. */
  private val TARGETS = mapOf("fleet" to "fleet", "chat" to "chat", "decisions" to "chat", "settings" to "settings",
    "graph" to "architecture", "architecture" to "architecture", "site" to "site")
  private fun namesTarget(ws: List<String>, cmd: UiResult): Boolean {
    val named = ws.mapNotNull { TARGETS[it] }.toSet()
    return when (cmd.action) {
      "close_window" -> cmd.slot in named
      "close_settings" -> "settings" in named
      else -> false
    }
  }

  /** Nouns that make a "you" line about the app ("what are the agents doing", "can you open the fleet"). */
  private val APP_NOUNS = setOf("project", "projects", "agent", "agents", "fleet", "chat", "settings", "graph", "architecture", "site",
    "app", "flowstate", "task", "tasks", "voice", "geothermal", "headset", "challenge", "circuit", "compilation", "data", "demo", "schema")
  /** "What are you doing right now?": a line put to "you" with no app noun in it is offered at most, never acted on. */
  fun youWithoutAppNoun(text: String): Boolean {
    val w = words(text)
    return w.any { it == "you" || it == "your" || it == "yourself" || it == "ya" } && w.none { it in APP_NOUNS }
  }

  /** Things in the room, not in the app: a line naming one ("turn the sound up on the telly") is offered at most. */
  private val ROOM_THINGS = setOf("tv", "telly", "television", "radio", "stereo", "speaker", "speakers", "lights", "light", "lamp",
    "oven", "kettle", "fridge", "car", "bike", "phone", "door", "heating", "fan", "music", "song", "kitchen")
  fun namesRoomThing(text: String): Boolean = words(text).any { it in ROOM_THINGS }

  /** Nouns only the app has: a short question naming one is for the app even when no scripted answer fits it. */
  private val STRONG_NOUNS = setOf("agent", "agents", "fleet", "graph", "architecture", "flowstate", "geothermal", "circuit", "compilation")
  private val QUESTION = setOf("what", "which", "who", "why", "how", "when", "where", "is", "are", "was", "were", "did", "does", "has", "have")
  /**
   * #1056: the one way a line with no confident scripted answer is still for the app (and so may go to Smart answers):
   * a short question (opener first), naming an app-only noun, not put to a person or to "you", not long.
   */
  fun askedOfApp(text: String): Boolean {
    val w = words(text).dropWhile { it in LEAD }
    return w.isNotEmpty() && w.first() in QUESTION && w.size <= MAX_DIRECT_WORDS && w.any { it in STRONG_NOUNS } &&
      !addressedToPerson(text) && !w.any { it == "you" || it == "your" }
  }

  /** Spoken paraphrases mapped onto words the answer keys already have. */
  private val PARAPHRASES = listOf(listOf("sign", "off", "on") to "approve", listOf("sign", "off") to "approve",
    listOf("signoff") to "approve", listOf("rundown") to "summary", listOf("run", "through") to "summary", listOf("lowdown") to "summary")
  fun paraphrase(text: String): String {
    var ws = words(text)
    for ((p, r) in PARAPHRASES) {
      var i = 0
      while (i + p.size <= ws.size) { if (ws.subList(i, i + p.size) == p) ws = ws.take(i) + r + ws.drop(i + p.size); i++ }
    }
    return ws.joinToString(" ")
  }

  // ---- #1070: the app's name ----

  /**
   * How the recogniser writes the name ("Flowstate"), as word sequences after [words]: runs/voice_fa/t_name/name_asr.jsonl
   * heard it as "flow state" (24 of 36) and "flow stayed" (1); a hyphen splits into "flow state" too.
   */
  val NAMES = listOf(listOf("flowstate"), listOf("flowstates"), listOf("flow", "state"), listOf("flow", "states"),
    listOf("flow", "stayed"), listOf("flo", "state"))
  /** Said before the name, they make it a noun ("in a flow state", "the flow state"), not the app being spoken to. */
  private val NOUN_BEFORE = setOf("a", "an", "the", "that", "this", "my", "your", "his", "her", "our", "their", "in", "into", "of", "deep", "total", "real")
  /** Said with the name when addressing it ("hey Flowstate", "okay Flowstate"): stripped with it. */
  private val CALL = setOf("hey", "hi", "ok", "okay", "yo", "um", "uh", "so", "oh")
  /** #1080: what may follow a name said last ("..., Flowstate, please"). */
  private val AFTER_NAME = setOf("please", "thanks", "thank", "you", "ok", "okay")
  /** #1080: verbs that make a leading name the subject of a sentence about the app ("Flowstate is ...", "Flowstate has ..."). */
  private val SUBJECT_VERBS = setOf("is", "was", "has", "had", "does", "did", "will", "would", "can", "could", "should", "makes",
    "made", "lets", "looks", "works", "worked", "runs", "shows", "keeps", "just", "really", "basically", "actually")
  /** A pause (punctuation) right after the name: the recogniser's comma in "Flowstate, is anything broken?". */
  private val PAUSED = Regex("(flowstates?|flow[ -]?states?|flow stayed|flo state)\\s*[,.!?:;]", RegexOption.IGNORE_CASE)

  /**
   * #1070: a line is named when the name is in its first or last three words and is not a noun ("a flow state"). Returns
   * the line without the name (and a "hey"/"okay" said with it), or null when the line is not named.
   */
  fun named(text: String): String? {
    val w = words(text)
    for (n in NAMES) for (i in 0..w.size - n.size) {
      if (w.subList(i, i + n.size) != n) continue
      val end = i + n.size
      if (!(end <= 3 || i >= w.size - 3)) continue
      if (i > 0 && w[i - 1] in NOUN_BEFORE) continue
      // #1080: the app talked about, not to. Near the end, only "please"/"thanks" may follow the name ("show you Flowstate
      // after dinner" is not address); at the start, a verb straight after it with no pause ("Flowstate is the thing...").
      if (end > 3 && w.drop(end).any { it !in AFTER_NAME }) continue
      if (end <= 3 && end < w.size && w[end] in SUBJECT_VERBS && !PAUSED.containsMatchIn(text)) continue
      var a = i; while (a > 0 && w[a - 1] in CALL) a--
      return (w.take(a) + w.drop(end)).joinToString(" ")
    }
    return null
  }

  /**
   * #1070: the pinch path (#1002, [VoiceMachine.heard]) for a named line, [rest] being the line without the name: mute;
   * else the command table; else the answer table, whose ANSWER or NEAREST plays. No coverage, person or room rule: the
   * name says the line is for the app. Nothing matched (or only the name was said) drops; there is no fallback reply.
   */
  fun decideNamed(intents: VoiceIntents, rest: String, commands: UiCommands? = null): Decision {
    val c = confidence(rest)
    if (words(rest).isEmpty()) return Decision(Route.DROP, c, null, why = "named_empty")
    if (isMute(rest)) return Decision(Route.MUTE, c, null, why = "named")
    val cmd = commands?.parse(rest)?.takeIf { it.action != null || it.caption.isNotEmpty() }
    if (cmd != null) { val (cov, n, _) = commandCoverage(commands, rest); return Decision(Route.COMMAND, c, null, cmd, cov, "named", explained = n) }
    val m = intents.match(paraphrase(rest))
    if (m.kind == MatchKind.ANSWER || m.kind == MatchKind.NEAREST) {
      val (cov, n, _) = answerCoverage(intents, m); return Decision(Route.ANSWER, c, m, null, cov, "named", m.margin, n)
    }
    return Decision(Route.DROP, c, m, why = "named_" + m.kind.name.lowercase())
  }

  // ---- #1080: act only when named, ask when unsure ----

  /**
   * Chosen on dev lines only (v2/v3/v4 intents on the pinch path + v5 named; runs/voice_fa/t1080/rule_grid.tsv): a named
   * command acts at this coverage or more, a named answer at this lead over the second-best answer or more. Dev: 91 of
   * 120 acted rightly, 5 wrongly (0.042), 8 right ones offered instead.
   */
  const val NAMED_ACT_COVERAGE = 0.8
  const val NAMED_ACT_MARGIN = 0.6
  /** Commands that undo or close everything: a named line asks first unless every content word is explained. */
  val ALWAYS_ASK = setOf("undo", "close_all")
  /** A reply that accepts a "Did you mean ...?" the app just asked. */
  val YES = setOf("yes", "yeah", "yep", "yes please", "do it", "yes do it")
  fun isYes(text: String) = words(text).joinToString(" ") in YES
  /** A question, by its first word (after "hey", "okay"...): only these go to the answer path when nothing matched. */
  fun isQuestion(text: String) = words(text).dropWhile { it in LEAD }.firstOrNull()?.let { it in QUESTION || it in OPENERS } == true

  /**
   * #1080: [decideNamed], then ask when unsure: a command under [NAMED_ACT_COVERAGE] (or an [ALWAYS_ASK] command under
   * full coverage) or an answer under [NAMED_ACT_MARGIN] is offered (route CONFIRM, "Did you mean ...?") instead of
   * run. A named line no command or answer fits goes to the answer path (the fallback, or Smart answers when on).
   */
  fun decideCalled(intents: VoiceIntents, rest: String, commands: UiCommands? = null): Decision {
    val d = decideNamed(intents, rest, commands)
    return when {
      d.route == Route.COMMAND && d.command?.action in ALWAYS_ASK && d.coverage < 1.0 -> d.copy(route = Route.CONFIRM, why = "named_ask")
      d.route == Route.COMMAND && d.coverage < NAMED_ACT_COVERAGE -> d.copy(route = Route.CONFIRM, why = "named_unsure")
      d.route == Route.ANSWER && !(d.margin >= NAMED_ACT_MARGIN) -> d.copy(route = Route.CONFIRM, why = "named_unsure")
      d.route == Route.DROP && d.match?.kind == MatchKind.OUT_OF_SCOPE && isQuestion(rest) -> d.copy(route = Route.ANSWER, why = "named_open")
      else -> d
    }
  }

  /** What a "Did you mean ...?" names: the command in plain words, or the answer's strongest key phrase. */
  fun meaning(d: Decision): String {
    d.command?.let { c -> return (c.action ?: "").replace('_', ' ') + (if (c.slot.isNotEmpty() && c.slot != "this") " " + c.slot else "") }
    return d.match?.answer?.keys?.maxByOrNull { it.second }?.first?.joinToString(" ") ?: ""
  }
}
