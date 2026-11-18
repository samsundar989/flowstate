package com.overhand.app.voice

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ln

/** One scripted answer about the recorded day (res/raw/voice_answers.json); its audio is res/raw/voice_<id>.wav. */
class VoiceAnswer(val id: String, val text: String, val rows: List<Int>, val keys: List<Pair<List<String>, Int>>)

/** How the matcher landed: a confident answer, the nearest answer (played with [VoiceIntents.suggest] after it), off-topic, or nothing heard. */
enum class MatchKind { ANSWER, NEAREST, OUT_OF_SCOPE, NOT_CAUGHT }

/** #946 matcher settings ("matcher" in voice_answers.json). Without them the matcher is the plain key-phrase one. */
class MatcherConfig(
  val margin: Double = 0.0, val lexMin: Double = Double.MAX_VALUE, val lexWeight: Double = 0.0, val fuzzyMinLen: Int = Int.MAX_VALUE,
  val fillers: Set<String> = emptySet(), val stopwords: Set<String> = emptySet(), val noStem: Set<String> = emptySet(),
  /** word -> concept, and multi-word phrase -> concept ("this morning" -> day). */
  val synonyms: Map<String, String> = emptyMap(), val phraseSynonyms: List<Pair<List<String>, String>> = emptyList(),
  val outOfScope: List<List<String>> = emptyList(),
)

/**
 * Picks the scripted answer for a transcript, with no language model. Transcript and keys go through the same
 * [norm]: lower case, fillers dropped, -ing/-ed/-s stemmed, synonyms mapped to one concept ("implemented" -> built).
 * Score = key phrases found in order (the longest match wins, so "now" inside "right now" counts once)
 * + [MatcherConfig.lexWeight] x a BM25-style overlap with each answer's key words and text.
 * - key score >= [threshold] and a lead of [MatcherConfig.margin]: [MatchKind.ANSWER];
 * - otherwise some real overlap (key score >= threshold, or overlap >= lexMin): [MatchKind.NEAREST] (the caller adds [suggest]);
 * - an out-of-scope phrase, or nothing in common: [MatchKind.OUT_OF_SCOPE] with the honest [fallback].
 */
class VoiceIntents(val answers: List<VoiceAnswer>, val threshold: Int, private val fallbackId: String, private val notCaughtId: String,
                   val config: MatcherConfig = MatcherConfig(), private val suggestId: String? = null) {
  private val byId = answers.associateBy { it.id }
  val fallback get() = byId.getValue(fallbackId)
  val notCaught get() = byId.getValue(notCaughtId)
  /** The one line naming three things to ask, played after a [MatchKind.NEAREST] answer. */
  val suggest get() = suggestId?.let { byId[it] }
  operator fun get(id: String) = byId[id]

  /** [margin] (#1080): the best answer's total score minus the second best's, for the named path's ask-when-unsure rule. */
  data class Match(val answer: VoiceAnswer, val score: Int, val words: List<String>, val kind: MatchKind = MatchKind.ANSWER, val lex: Double = 0.0,
                   val margin: Double = 0.0)

  private class Scored(val answer: VoiceAnswer, val keys: Map<List<String>, Int>, val bag: Map<String, Int>)
  private val scored: List<Scored> = answers.filter { it.keys.isNotEmpty() }.map { a ->
    val keys = HashMap<List<String>, Int>()
    a.keys.forEach { (p, w) -> val n = norm(p.joinToString(" ")); if (n.isNotEmpty()) keys[n] = maxOf(keys[n] ?: 0, w) }
    val bag = HashMap<String, Int>()
    keys.forEach { (p, w) -> p.filter { it !in config.stopwords }.forEach { bag[it] = (bag[it] ?: 0) + w } }
    norm(a.text).filter { it !in config.stopwords }.forEach { bag[it] = (bag[it] ?: 0) + 1 }
    Scored(a, keys, bag)
  }
  private val idf: Map<String, Double> = HashMap<String, Int>().also { df -> scored.forEach { s -> s.bag.keys.forEach { df[it] = (df[it] ?: 0) + 1 } } }
    .mapValues { (_, d) -> ln(1 + (scored.size - d + 0.5) / (d + 0.5)) }
  private val vocab = idf.keys.sorted()
  private val outOfScope = config.outOfScope.map { norm(it.joinToString(" ")) }.filter { it.isNotEmpty() }

  private fun stem(w: String): String {
    if (w in config.noStem || w.length <= 3) return w
    for (suf in listOf("ing", "ed", "s")) if (w.endsWith(suf) && w.length - suf.length >= 3 && !w.endsWith("ss")) return w.dropLast(suf.length)
    return w
  }

  /** [words], then multi-word synonyms, fillers dropped, stem, single-word synonyms. Keys and transcripts alike. */
  fun norm(text: String): List<String> {
    var ws = words(text)
    for ((p, c) in config.phraseSynonyms) {
      var i = 0
      while (i + p.size <= ws.size) { if (ws.subList(i, i + p.size) == p) ws = ws.take(i) + c + ws.drop(i + p.size); i++ }
    }
    return ws.filter { it !in config.fillers }.map { w -> val v = config.synonyms[w] ?: w; val s = stem(v); config.synonyms[s] ?: s }
  }

  /** ASR slips ("summery"): an unknown word of fuzzyMinLen+ letters becomes the first vocabulary word one edit away. */
  private fun fuzzy(w: String): String {
    if (w.length < config.fuzzyMinLen || w in idf || w in config.stopwords) return w
    return vocab.firstOrNull { abs(it.length - w.length) <= 1 && oneEdit(it, w) } ?: w
  }

  fun match(transcript: String): Match {
    val raw = norm(transcript)
    if (raw.isEmpty()) return Match(notCaught, 0, raw, MatchKind.NOT_CAUGHT)
    if (outOfScope.any { contains(raw, it) }) return Match(fallback, 0, raw, MatchKind.OUT_OF_SCOPE)
    val ws = raw.map { fuzzy(it) }
    val content = ws.filter { it !in config.stopwords }.toSet()
    val hits = scored.map { s -> s.keys.keys.filter { contains(ws, it) } }
    val all = hits.flatten()
    data class R(val s: Scored, val total: Double, val key: Int, val lex: Double)
    val res = scored.indices.map { i ->
      val s = scored[i]
      val key = hits[i].filter { p -> all.none { q -> q.size > p.size && contains(q, p) } }.sumOf { s.keys.getValue(it) }
      val lex = content.sumOf { x -> s.bag[x]?.let { tf -> (idf[x] ?: 0.0) * tf / (tf + 1.2) } ?: 0.0 }
      R(s, key + config.lexWeight * lex, key, lex)
    }.sortedByDescending { it.total } // stable: ties keep the table's order
    val top = res.firstOrNull() ?: return Match(fallback, 0, ws, MatchKind.OUT_OF_SCOPE)
    val second = res.getOrNull(1)?.total ?: 0.0
    return when {
      top.key >= threshold && top.total - second >= config.margin -> Match(top.s.answer, top.key, ws, MatchKind.ANSWER, top.lex, top.total - second)
      top.key >= threshold || top.lex >= config.lexMin -> Match(top.s.answer, top.key, ws, MatchKind.NEAREST, top.lex, top.total - second)
      else -> Match(fallback, top.key, ws, MatchKind.OUT_OF_SCOPE, top.lex, top.total - second)
    }
  }

  companion object {
    private val CONTRACTIONS = mapOf("what's" to "what is", "whats" to "what is", "who's" to "who is", "where's" to "where is",
      "when's" to "when is", "why's" to "why is", "how's" to "how is", "it's" to "it is", "that's" to "that is",
      "there's" to "there is", "i'm" to "i am", "didn't" to "did not", "doesn't" to "does not", "don't" to "do not",
      "isn't" to "is not", "aren't" to "are not", "wasn't" to "was not", "can't" to "can not", "cannot" to "can not",
      "won't" to "will not", "you're" to "you are", "they're" to "they are", "i've" to "i have", "what're" to "what are",
      "colour" to "color", "colours" to "colors")

    /** Lower case, contractions expanded, punctuation dropped. "What's broken?" -> [what, is, broken]. */
    fun words(text: String): List<String> = text.lowercase().replace('’', '\'')
      .split(Regex("[^a-z0-9']+")).filter { it.isNotBlank() }
      .flatMap { (CONTRACTIONS[it] ?: it.trim('\'').removeSuffix("'s")).split(' ') }.filter { it.isNotEmpty() }

    fun contains(words: List<String>, phrase: List<String>): Boolean {
      if (phrase.isEmpty() || phrase.size > words.size) return false
      for (i in 0..words.size - phrase.size) if (phrase.indices.all { words[i + it] == phrase[it] }) return true
      return false
    }

    /** Exactly one insert, delete or substitution apart. */
    fun oneEdit(a0: String, b0: String): Boolean {
      if (a0 == b0) return false
      val (a, b) = if (a0.length <= b0.length) a0 to b0 else b0 to a0
      var i = 0
      while (i < a.length && a[i] == b[i]) i++
      return if (a.length == b.length) a.substring(minOf(i + 1, a.length)) == b.substring(minOf(i + 1, b.length)) else a.substring(i) == b.substring(i + 1)
    }

    private fun strings(o: JSONObject, k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()

    fun parse(json: String): VoiceIntents {
      val o = JSONObject(json); val arr = o.getJSONArray("answers")
      val answers = (0 until arr.length()).map { i ->
        val a = arr.getJSONObject(i)
        val rows = a.getJSONArray("rows").let { r -> (0 until r.length()).map { r.getInt(it) } }
        val keys = a.getJSONArray("keys").let { k -> (0 until k.length()).map { j ->
          val raw = k.getString(j); val c = raw.lastIndexOf(':')
          val (p, w) = if (c > 0) raw.substring(0, c) to raw.substring(c + 1).toInt() else raw to 1
          words(p) to w
        } }
        VoiceAnswer(a.getString("id"), a.getString("text"), rows, keys)
      }
      val config = o.optJSONObject("matcher")?.let { m ->
        val syn = HashMap<String, String>(); val phr = ArrayList<Pair<List<String>, String>>()
        m.optJSONObject("synonyms")?.let { s -> s.keys().forEach { c -> strings(s, c).forEach { w -> if (' ' in w) phr += words(w) to c else syn[w] = c } } }
        MatcherConfig(m.getDouble("margin"), m.getDouble("lex_min"), m.getDouble("lex_weight"), m.optInt("fuzzy_min_len", Int.MAX_VALUE),
          strings(m, "fillers").toSet(), strings(m, "stopwords").toSet(), strings(m, "no_stem").toSet(), syn, phr,
          strings(m, "out_of_scope").map { words(it) })
      } ?: MatcherConfig()
      return VoiceIntents(answers, o.getInt("threshold"), o.getString("fallback"), o.getString("not_caught"), config, o.optString("suggest").ifEmpty { null })
    }
  }
}
