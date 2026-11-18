package com.overhand.app.voice

/** One name the wearer can say for a slot: [key] is what the action receives, [label] what the caption shows. */
data class SlotName(val key: String, val label: String, val aliases: List<String> = emptyList())

/**
 * #1002: what spoken commands can do. Every member is one existing function of the app (see VoiceFeature's binding);
 * nothing here is a new feature. The lists are read live at each turn.
 */
interface UiActions {
  fun windows(): List<SlotName>
  fun projects(): List<SlotName>
  /** The project of the station in front of the wearer (the "this project" of a command). */
  fun currentProject(): String
  /** Parts of the graph on show (or of the current project's graph). */
  fun parts(): List<SlotName>
  /** Hides every open window; returns the keys it hid (for undo). */
  fun closeAll(): List<String>
  fun openWindow(key: String)
  fun closeWindow(key: String)
  fun showArchitecture(project: String)
  fun hideArchitecture()
  fun openProject(project: String)
  fun zoomInto(part: String)
  fun zoomOut()
  fun centerWindows()
  fun openSettings()
  fun closeSettings()
  fun mute(on: Boolean)
}

/** What a transcript did. [action] null: not a command (the scripted answers get it). [choices]: "Which one?" asked. */
data class UiResult(val action: String?, val slot: String = "", val caption: String = "", val choices: List<SlotName> = emptyList()) {
  val acted get() = action != null && choices.isEmpty()
}

/**
 * Spoken commands with no language model: normalised keyword rules, then slot matching against the live lists by
 * spelling and sound. Below [SURE], or with a rival inside [MARGIN], it asks "Which one?" with up to three choices
 * and does nothing until one is picked. Anything that hides something can be undone ("back", "undo").
 */
class UiCommands(private val ui: UiActions) {
  private val undo = ArrayDeque<Pair<String, () -> Unit>>()
  private var pending: Pair<String, List<SlotName>>? = null

  /** Runs [text] if it is a command. Returns [UiResult.action] null when it is not one (nothing was done). */
  fun handle(text: String): UiResult {
    val ws = tokens(text)
    pending?.let { (action, options) -> pending = null; pick(ws, options)?.let { return run(action, it) } }
    val r = parse(ws) ?: return UiResult(null)
    if (r.action == null) return r
    if (r.choices.isNotEmpty()) { pending = r.action!! to r.choices; return r }
    return run(r.action!!, r.slot)
  }

  /** True while a "Which one?" waits for its answer. */
  val asking get() = pending != null

  /** A pinched choice from the last "Which one?". */
  fun choose(i: Int): UiResult {
    val (action, options) = pending ?: return UiResult(null)
    pending = null
    return options.getOrNull(i)?.let { run(action, it.key) } ?: UiResult(null)
  }

  private fun pick(ws: List<String>, options: List<SlotName>): String? {
    val ord = listOf("first", "second", "third").indexOfFirst { it in ws }
    if (ord in options.indices && ws.size <= 4) return options[ord].key
    val m = resolve(ws, options)
    if (m.size == 1) return m[0].key
    // Among the offered names only, one word that only one of them has is enough ("the web one").
    val own = options.filter { o -> tokens(o.label).any { t -> t in ws && options.count { t in tokens(it.label) } == 1 } }
    return own.singleOrNull()?.key
  }

  // ---- parsing (no side effects) ----

  /** The action and slot [text] means, or null. For tests and logs. */
  fun parse(text: String): UiResult? = parse(tokens(text))

  /** Verb-led actions run only when the verb leads ("I'm about to open the fleet" and "she wants me to mute" do nothing). */
  private fun parse(w: List<String>): UiResult? {
    val r = parseRules(w) ?: return null
    return if (r.action in VERB_LED && !commanded(w, VERBS) && w.any { it in INTENT }) null else r
  }

  private fun parseRules(w: List<String>): UiResult? {
    if (w.isEmpty() || rejected(w)) return null
    val s = " " + w.joinToString(" ") + " "
    fun has(vararg p: String) = p.any { s.contains(" $it ") }
    val close = has(*CLOSE) || has("do not need", "do not want", "done with", "finished with", "no longer need")
    val open = has(*OPEN)
    val sound = has("sound", "sounds", "audio", "volume", "noise", "noises", "beeps", "earcons")
    val window = resolveOne(w, ui.windows())

    // undo / back
    if (has("undo", "undo that", "never mind", "nevermind", "restore", "revert", "oops", "did not mean", "didnt mean", "take that back", "reverse that", "bring that back", "put that back")) return UiResult("undo")
    if (sound) {
      if (has("unmute", "on", "back", "turn up", "again", "enable", "hear")) return UiResult("unmute")
      if (has("mute", "off", "silence", "quiet", "stop", "turn down", "kill", "no more", "enough")) return UiResult("mute")
    }
    if (has("unmute")) return UiResult("unmute")
    if (has("mute", "silence", "be quiet")) return UiResult("mute")
    if (has("zoom out", "zoom back out", "zoom all the way out", "pull back", "full graph", "whole graph", "entire graph", "zoom back", "step back", "back out", "full view", "overview of the graph", "zoom to fit", "whole thing", "everything in the graph", "all of the graph", "the big picture", "wider"))
      return UiResult("zoom_out")
    if (has("center", "centre", "recenter", "recentre", "in front of me", "in front", "where i am looking", "where i look", "in my view", "to the middle", "back to the middle", "closer to me", "bring them here", "bring them over"))
      return UiResult("center_windows")
    if (has("back to", "return to")) slotted("open_project", w, ui.projects())?.let { if (it.action != null) return it }
    if (has("bring back", "put back", "bring them back", "bring it back", "put it back", "put them back", "go back", "undo")
      && window == null && !has("graph")) return UiResult("undo")
    if (w == listOf("back")) return UiResult("undo")

    // zoom into a part
    // A strong cue asks which part when none is heard; a weak one ("up close") only counts with a part.
    val zoom = has("zoom", "zoom in", "zoom into", "focus on", "drill into", "drill down", "closer look", "dive into", "magnify", "enlarge")
    val weak = has("go into", "expand", "up close", "closer", "close up")
    if (zoom || weak) slotted("zoom_in", w.drop(maxOf(0, w.indexOfFirst { it in ZOOM_WORDS })), ui.parts())?.let { return it }
    if (zoom) return UiResult(null, caption = "Zoom into which part?")

    // settings
    if (has("settings", "setting", "preferences", "options")) {
      if (close || has("hide")) return UiResult("close_settings")
      return UiResult("open_settings")
    }

    // architecture of a project
    val arch = has("architecture", "graph", "diagram", "structure", "how", "look like", "looks like", "map", "layout") &&
      !has("window", "panel") && (has("architecture", "graph", "diagram", "structure") || has("built", "build", "look like", "looks like"))
    if (arch && close) return UiResult("close_window", "architecture", "Closed the architecture")
    if (arch && (open || has("what", "how") || w.first() in setOf("architecture", "graph", "diagram", "structure"))) {
      val p = slotted("show_architecture", w, ui.projects())
      return if (p != null && (p.choices.isNotEmpty() || p.slot.isNotEmpty())) p else UiResult("show_architecture", "this")
    }

    // close everything
    // #1002: the most destructive command, so the close word must lead the sentence ("the close all button" is not a request).
    if (close && commanded(w, CLOSE.toList()) && window?.key != "architecture" && (has("all", "everything", "every", "these windows", "the windows", "my windows", "windows", "my view", "the view", "screen", "the window", "panels", "every panel") && (window == null || has("all", "everything", "every"))))
      return UiResult("close_all")

    // one window
    if (window != null && close) return UiResult("close_window", window.key)
    if (window != null && (open || has("again", "back"))) return if (window.key == "architecture") UiResult("show_architecture", "this") else UiResult("open_window", window.key)

    // a project
    if (open || has("go to", "switch to", "switch over", "over to", "head to", "head over", "take me to", "jump to", "move to", "select", "change to")) {
      val p = slotted("open_project", w, ui.projects())
      if (p != null) return p
      if (has("project")) return UiResult(null, caption = "Which project?")
    }
    return null
  }

  /**
   * #1051 coverage: true when [w] (a [tokens] word) is one the command rules or the live slot names use, so a
   * matched line made mostly of other words ("did you remember to buy milk on the way home") is not explained by it.
   */
  fun explains(w: String): Boolean = w in COVER ||
    (ui.windows() + ui.projects() + ui.parts()).any { n -> (listOf(n.label, n.key) + n.aliases).any { f -> tokens(f).any { it == w || (w.length >= 5 && similar(w, it) >= SURE) } } }

  private fun slotted(action: String, w: List<String>, names: List<SlotName>): UiResult? {
    val m = resolve(w, names)
    return when {
      m.isEmpty() -> null
      m.size == 1 -> UiResult(action, m[0].key)
      else -> UiResult(action, caption = "Which one? " + m.joinToString(" · ") { it.label }, choices = m)
    }
  }

  private fun resolveOne(w: List<String>, names: List<SlotName>) = resolve(w, names).singleOrNull()

  /** One sure name, or up to three close ones (ask), or none. */
  fun resolve(w: List<String>, names: List<SlotName>): List<SlotName> {
    val scored = names.map { n -> n to score(w, n) }.filter { it.second >= MAYBE }.sortedByDescending { it.second }
    if (scored.isEmpty()) return emptyList()
    val best = scored[0].second
    val rivals = scored.filter { it.second > best - MARGIN }
    return if (best >= SURE && rivals.size == 1) listOf(scored[0].first) else scored.take(3).map { it.first }.takeIf { best >= SURE || it.size > 1 }
      ?: emptyList()
  }

  private fun score(w: List<String>, n: SlotName): Double {
    var best = 0.0
    val forms = (listOf(n.label, n.key) + n.aliases).map { tokens(it) }.filter { it.isNotEmpty() }
    for (f in forms) {
      val full = f.joinToString("")
      for (i in w.indices) for (len in 1..minOf(5, w.size - i)) {
        val span = w.subList(i, i + len)
        if (span.first() in EDGE || span.last() in EDGE) continue
        best = maxOf(best, similar(span.joinToString(""), full))
      }
      // One distinctive word of a longer name ("geothermal" for Geothermal faults), a little less sure.
      if (f.size > 1) for (part in f) if (part.length >= 5) for (x in w) if (x !in EDGE) best = maxOf(best, 0.92 * similar(x, part))
    }
    return best
  }

  private fun run(action: String, slot: String): UiResult {
    val c = when (action) {
      "close_all" -> { val hid = ui.closeAll(); if (hid.isNotEmpty()) push("windows back") { hid.forEach { ui.openWindow(it) } }
        if (hid.isEmpty()) "No windows open" else "Closed ${hid.size} window${if (hid.size == 1) "" else "s"}" }
      "open_window" -> { ui.openWindow(slot); "Opened ${label(ui.windows(), slot)}" }
      "close_window" -> if (slot == "architecture") { ui.hideArchitecture(); push("architecture back") { ui.showArchitecture(ui.currentProject()) }; "Closed the architecture" }
        else { ui.closeWindow(slot); push("${label(ui.windows(), slot)} back") { ui.openWindow(slot) }; "Closed ${label(ui.windows(), slot)}" }
      "show_architecture" -> { val p = if (slot == "this" || slot.isEmpty()) ui.currentProject() else slot
        ui.showArchitecture(p); push("architecture closed") { ui.hideArchitecture() }; "Architecture: ${label(ui.projects(), p)}" }
      "open_project" -> { val was = ui.currentProject(); ui.openProject(slot); push("back to ${label(ui.projects(), was)}") { ui.openProject(was) }; "Opened ${label(ui.projects(), slot)}" }
      "zoom_in" -> { ui.zoomInto(slot); push("zoomed out") { ui.zoomOut() }; "Zoomed to ${label(ui.parts(), slot)}" }
      "zoom_out" -> { ui.zoomOut(); "Zoomed out" }
      "undo" -> undo.removeLastOrNull()?.let { (what, f) -> f(); "Undone: $what" } ?: "Nothing to undo"
      "center_windows" -> { ui.centerWindows(); "Windows in front of you" }
      "open_settings" -> { ui.openSettings(); "Settings open" }
      "close_settings" -> { ui.closeSettings(); push("settings open") { ui.openSettings() }; "Settings closed" }
      "mute" -> { ui.mute(true); push("sounds on") { ui.mute(false) }; "Sounds muted" }
      "unmute" -> { ui.mute(false); "Sounds on" }
      else -> return UiResult(null)
    }
    return UiResult(action, slot, short(c))
  }

  /** Before the first close word come only lead-ins ("could you", "now we tidy up and"), never a subject like "the". */
  private fun commanded(w: List<String>, verbs: List<String>): Boolean {
    val i = w.indices.firstOrNull { i -> verbs.any { c -> val p = c.split(' '); w.size - i >= p.size && w.subList(i, i + p.size) == p } || w[i] == "do" } ?: return false
    return w.take(i).all { it in LEAD }
  }

  private fun push(what: String, f: () -> Unit) { undo.addLast(what to f); while (undo.size > 10) undo.removeFirst() }
  private fun label(names: List<SlotName>, key: String) = names.firstOrNull { it.key == key }?.label ?: key

  companion object {
    const val SURE = 0.84
    const val MAYBE = 0.70
    const val MARGIN = 0.06
    /** Every caption is 8 words or fewer. */
    fun short(c: String) = c.split(' ').let { if (it.size <= 8) c else it.take(8).joinToString(" ") }

    private val CLOSE = arrayOf("close", "hide", "dismiss", "remove", "clear", "minimize", "minimise", "get rid of", "put away", "shut", "lose", "exit", "quit", "get out of", "out of here", "out of the way", "out of my way", "out of my face", "away")
    private val OPEN = arrayOf("open", "show", "pull up", "bring up", "display", "see", "view", "launch", "go to", "switch to", "take me to", "jump to", "load", "put up", "give me", "let me see", "look at", "need the")
    private val ZOOM_WORDS = setOf("zoom", "focus", "drill", "closer", "dive", "go", "expand", "into", "on", "in", "magnify", "enlarge", "show", "get")
    private val VERB_LED = setOf("open_window", "close_window", "open_project", "mute", "unmute")
    private val VERBS = (CLOSE + OPEN).toList() + listOf("mute", "unmute", "turn", "make", "take", "stop", "silence", "enable", "kill", "get",
      "switch", "head", "move", "change", "select", "bring", "put", "need", "want", "pull", "give", "let", "quiet", "be", "no", "sound", "sounds", "audio")
    /** Every word the rules in parseRules name, plus the verb lists ([explains]). */
    private val COVER: Set<String> by lazy {
      (
        "again all am architecture audio back be beeps big bring build built center centre change close closer diagram " +
        "did didnt dive do done down drill earcons enable enlarge enough entire every everything expand finished fit " +
        "focus front full go graph head hear here hide how i in into it jump kill layout like longer look looking looks " +
        "magnify map me mean middle mind more move mute my need never nevermind no noise noises not of off on oops " +
        "options out over overview panel panels picture preferences project pull put quiet recenter recentre restore " +
        "return reverse revert screen select setting settings silence sound sounds step stop structure switch take that " +
        "the them these thing to turn undo unmute up view volume want way what where whole wider window windows with " +
        "zoom projects part parts").split(' ').toSet() +
        (CLOSE + OPEN).flatMap { it.split(' ') } + VERBS.flatMap { it.split(' ') } + ZOOM_WORDS
    }
    /** Words that mark a sentence about someone's plan or someone else's device, not a request to the app. */
    private val INTENT = setOf("about", "wants", "wanted", "wanting", "planning", "plan", "going", "gonna", "mom", "mum", "dad", "friend", "boss",
      "wife", "husband", "kids", "tv", "television", "radio", "told", "asked", "tell", "ask", "help", "show you", "you can see")
    private val LEAD = setOf("can", "could", "would", "will", "you", "i", "we", "want", "wanna", "to", "need", "id", "like", "lets", "let", "us",
      "go", "ahead", "time", "it", "is", "tidy", "up", "quick", "quickly", "first", "right", "yes", "yeah", "o", "hi", "please", "me")
    /** Split words the recogniser writes that the rules need whole ("set tings"). */
    private val JOIN = setOf("settings", "unmute", "recenter", "undo", "blueprint", "everything", "overview", "fullscreen")
    private val FILLERS = setOf("um", "umm", "uh", "uhh", "er", "erm", "hmm", "okay", "ok", "so", "please", "hey", "alright", "well", "just", "actually", "now", "then", "and", "like", "kindly", "quickly")
    /** Words that cannot start or end a slot name span. */
    private val EDGE = setOf("the", "a", "an", "of", "for", "to", "into", "in", "on", "me", "my", "this", "that", "project", "projects", "window",
      "windows", "panel", "part", "layer", "node", "section", "component", "show", "open", "close", "zoom", "is",
      "what", "how", "it", "go", "can", "you", "please", "station", "one", "page", "screen", "built", "look", "like", "does", "at", "and", "with")
    /** Not a command: talk about the future, other people, the physical world, or a refusal. */
    private val REJECT = listOf("later", "tomorrow", "tonight", "before i", "after i", "should", "shouldnt", "should not", "yet", "probably", "wish", "i will", "ill", "i'll", "gonna", "going to", "remind",
      "he", "she", "him", "her", "they", "them to", "told", "said", "asked", "says", "if", "when i", "might", "maybe", "watch", "in a second",
      "in a minute", "house", "car", "kitchen", "bedroom", "office", "phone", "door", "gate", "ship", "ships", "film", "movie", "window cleaner",
      "never", "yesterday", "earlier", "last time", "button", "would you like", "did you", "have you", "do you", "are you", "weather", "cold", "draft", "rain")
    private fun rejected(w: List<String>): Boolean {
      val s = " " + w.joinToString(" ") + " "
      if (REJECT.any { s.contains(" $it ") }) return true
      if ((w.first() == "no" && w.getOrNull(1) != "more") || w.first() == "not") return true
      // "do not close" refuses; "do not need" is a way of closing.
      val i = w.windowed(2).indexOfFirst { it == listOf("do", "not") }
      // ...but "do not want to open" refuses too.
      if (i >= 0 && (w.getOrNull(i + 2) !in setOf("need", "want", "use") || w.getOrNull(i + 3) == "to")) return true
      return false
    }

    /** Lower case words, fillers dropped, spelled letters joined ("a p i" -> api), "re center" / "un mute" joined. */
    fun tokens(text: String): List<String> {
      val raw = VoiceIntents.words(text.replace("+", " plus ").replace("/", " "))
        .map { it.removeSuffix("'").replace("'", "") }.map { if (it == "dont") "do not" else it }.flatMap { it.split(' ') }
      val out = ArrayList<String>()
      var i = 0
      while (i < raw.size) {
        var j = i
        while (j < raw.size && raw[j].length == 1 && raw[j][0].isLetter()) j++
        if (j - i >= 2) { out += raw.subList(i, j).joinToString(""); i = j; continue }
        val x = raw[i]
        if ((x == "re" || x == "un") && i + 1 < raw.size) { out += x + raw[i + 1]; i += 2; continue }
        out += x; i++
      }
      for (k in out.size - 2 downTo 0) if (out[k] + out[k + 1] in JOIN) { out[k] = out[k] + out[k + 1]; out.removeAt(k + 1) }
      return out.filter { it !in FILLERS }.map { if (it == "centre") "center" else if (it == "recentre") "recenter" else it }
    }

    /** 1 = same; by letters, and by a rough sound key for words of 5+ letters ("molecular" ~ "molecule"). */
    fun similar(a: String, b: String): Double {
      if (a == b) return 1.0
      val l = 1.0 - lev(a, b).toDouble() / maxOf(a.length, b.length)
      if (minOf(a.length, b.length) < 5) return l
      val ka = sound(a); val kb = sound(b)
      val p = if (ka.isEmpty() || kb.isEmpty()) 0.0 else 1.0 - lev(ka, kb).toDouble() / maxOf(ka.length, kb.length)
      return maxOf(l, 0.95 * p)
    }

    fun sound(w: String): String {
      val s = w.lowercase().replace("ph", "f").replace("ck", "k").replace("c", "k").replace("q", "k").replace("z", "s").replace("x", "ks")
        .replace("v", "f").replace("dg", "j")
      val sb = StringBuilder()
      for ((i, ch) in s.withIndex()) {
        if (i > 0 && ch in "aeiouyhw") continue
        if (sb.isNotEmpty() && sb.last() == ch) continue
        sb.append(ch)
      }
      return sb.toString()
    }

    fun lev(a: String, b: String): Int {
      var prev = IntArray(b.length + 1) { it }
      for (i in 1..a.length) {
        val cur = IntArray(b.length + 1); cur[0] = i
        for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        prev = cur
      }
      return prev[b.length]
    }
  }
}
