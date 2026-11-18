package com.overhand.app.voice

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

/**
 * #1032 "Smart answers": a question that is no command and has no confident scripted answer goes to our relay
 * (relay/CONTRACT.md). Everything here is plain JVM so each route and every failure is unit tested.
 * What leaves the headset: the question's words, the [ScreenSummary] text and a random install id. Never audio,
 * images or file contents. The answer is only ever spoken and captioned: it never reaches the command table.
 */
object Smart {
  /** No first sentence within this long: the scripted answer plays instead. */
  const val FIRST_CHUNK_TIMEOUT_MS = 5_000L
  const val MAX_WORDS = 45
  const val MAX_SCREEN = 1_500
  const val MAX_QUESTION = 300
}

/**
 * The second vendor filter, on the device, before caption and speech. The word list is kept only as salted hashes so
 * the app itself never carries a vendor name (the brand gate scans the APK). Source: agents/bridge/src/brands.ts plus
 * model-family and headset-maker words; SmartAnswersTest checks the hashes still match that file.
 */
object AnswerFilter {
  private val HASHES = setOf(
    "00f7fb6342771610","03ba7f69667518c1","06708d42887b86e1","095af9a3ce7832a3","0eb504487ab4884a","18e1c7d16c989276",
    "1b066aef75ccc05b","1d365b4520103e85","24cda72d078dd39d","287b8d46cdcbef6c","2cb382fbbc8d1f5d","2d90ddc0840d4aa3",
    "2daa37f8fcb3e5c2","318812580cd5cd8e","3587d5b6a340ef2a","38c8741044bce338","3d64003bff58c2b5","41e9014aed2c8589",
    "42c1349e8538cdbb","43685a67cd00008a","47ae4be1decb4ff8","48d03283871e08ab","49942f05e01a1127","4b8e09e55afa2fa0",
    "4bd2abee6c563bc9","4ed2606797ac6e08","5028f29808a55d8a","5511d0848870ab48","56e931e7d312130f","5841d88efcc1459c",
    "5babb0fe4c931ee2","5d278139f1a9ffbb","62d1faaadff5ca0f","675193e92ddc3e12","68575407bb438719","68bca0a9083b207c",
    "6ce1e897955a6b30","70dfc121f1eb657e","727cb7ddbbd258dc","74b670a0eddc1cd1","74f07e219c1f1261","7b599fbac9c4d24e",
    "7c970c79cf47be58","810f08e091f340c2","8b84b937a34f7cd6","8bcfd7db55f76aea","8c719f3c78a1f983","900bf067c45f1255",
    "92b4e31a0246cdf0","97608b17f12d3f34","9ac4ebba61652014","9e983d7b69f67d22","a1fd36720fa3f34d","a5abacf407831f70",
    "a8c8a18ac27b3763","ab1df51d6f770555","abdf8e4e805ae06e","b433da5c472eb0cc","b55c27fc303eb742","c2130ee1d4176638",
    "c3a4a4a27589cd34","c3b68ab020262043","c8ef1c37f64cbc55","d0e38ad6b8f145e7","d141bc200bc6096c","d4b569a2c294b429",
    "dc341e3f6ee35777","e5cb0dbd1d3fa85f","ee58f4888ae5c5ed","f77989d02f4728ff","f79a3e013d7019d6")
  val size get() = HASHES.size

  fun tokens(text: String) = text.lowercase().replace('’', '\'').split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
  fun hash(phrase: String): String = MessageDigest.getInstance("SHA-256").digest("ow1:$phrase".toByteArray())
    .take(8).joinToString("") { "%02x".format(it) }

  /** True when any one, two or three word run of [text] is on the list. */
  fun names(text: String): Boolean {
    val t = tokens(text)
    for (n in 1..3) for (i in 0..t.size - n) if (hash(t.subList(i, i + n).joinToString(" ")) in HASHES) return true
    return false
  }

  /** A sentence claiming the voice did something ("I've sent B"): the voice is read-only, so it is never spoken. */
  private val ACTION_CLAIM = Regex(
    "\\b(?:i|i've|i have|i'll)\\s+(?:just\\s+|now\\s+)?(?:deleted|removed|sent|submitted|confirmed|approved|answered|picked|chose|chosen|" +
      "cancel+ed|created|started|stopped|pushed|merged|deleting|sending)\\b|\\b(?:has|have) been (?:deleted|removed|sent|submitted|confirmed|approved)\\b",
    RegexOption.IGNORE_CASE)
  fun claimsAction(text: String) = ACTION_CLAIM.containsMatchIn(text.replace('’', '\''))
}

/** Splits streamed text into whole sentences: . ! or ? then whitespace (the relay's own rule). */
class SentenceSplitter {
  private val buf = StringBuilder()
  private val end = Regex("[.!?]+[\"')\\]]*\\s+")
  fun push(chunk: String): List<String> {
    buf.append(chunk)
    val out = ArrayList<String>()
    while (true) {
      val m = end.find(buf) ?: break
      out += buf.substring(0, m.range.last + 1).trim()
      buf.delete(0, m.range.last + 1)
    }
    return out.filter { it.isNotEmpty() }
  }
  /** The last, unterminated sentence (only kept when the stream ended cleanly). */
  fun rest(): String = buf.toString().trim().also { buf.setLength(0) }
}

/**
 * What the relay is told about the screen, in the question set's shape:
 * `recording=…; project=…; windows=…; selected=…; card=…; moment=…; events=a | b | …` (the last five events).
 * Text only, [Smart.MAX_SCREEN] characters at most.
 */
data class ScreenSummary(val recording: String, val project: String, val windows: List<String>, val selected: String?,
                         val card: String?, val moment: String?, val events: List<String>) {
  fun text(): String {
    fun clean(s: String) = s.replace(Regex("[\\r\\n;]+"), " ").trim()
    val head = "recording=${clean(recording)}; project=${clean(project)}; windows=${windows.joinToString(",") { clean(it) }.ifEmpty { "none" }}; " +
      "selected=${selected?.let(::clean) ?: "none"}; card=${card?.let(::clean) ?: "none"}" + (moment?.let { "; moment=${clean(it)}" } ?: "")
    var s = head
    val ev = events.takeLast(5).map { clean(it).take(240) }
    if (ev.isNotEmpty()) s += "; events=" + ev.joinToString(" | ")
    return if (s.length <= Smart.MAX_SCREEN) s else s.take(Smart.MAX_SCREEN - 1) + "…"
  }
}

/** One relay response: an HTTP status and the body as it arrives. */
class RelayResponse(val status: Int, val body: InputStream)

/** How a request reaches the relay. The real one is [HttpRelayTransport]; tests replay recorded streams. */
fun interface RelayTransport { @Throws(IOException::class) fun post(path: String, json: String, readTimeoutMs: Int): RelayResponse }

/** HTTPS to the one relay host from build config. Any other host is refused, so nothing else can be reached from here. */
class HttpRelayTransport(base: String) : RelayTransport {
  val base: URL = URL(base.trimEnd('/'))
  init { require(this.base.protocol == "https" && this.base.host.isNotEmpty()) { "relay must be https" } }
  override fun post(path: String, json: String, readTimeoutMs: Int): RelayResponse {
    val url = URL(base, path)
    check(url.host == base.host) { "relay host only" }
    val c = url.openConnection() as HttpURLConnection
    c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = readTimeoutMs; c.readTimeout = readTimeoutMs
    c.setRequestProperty("content-type", "application/json"); c.setChunkedStreamingMode(0)
    c.outputStream.use { it.write(json.toByteArray()) }
    val code = c.responseCode
    return RelayResponse(code, if (code in 200..299) c.inputStream else (c.errorStream ?: InputStream.nullInputStream()))
  }
}

/** Why a smart answer was not spoken: each one plays the scripted line, with no error shown. */
enum class SmartFail { OFF, NOT_CONFIGURED, OVER_CAP, RATE_LIMITED, BAD_REQUEST, UNAVAILABLE, OFFLINE, TIMEOUT, EMPTY, CUT, FILTERED, CANCELLED }

/** Relay state as last seen. CONFIGURED only after the relay itself said so (a probe or a streamed answer). */
enum class RelayState { UNKNOWN, CONFIGURED, NOT_CONFIGURED, OFF, OVER_CAP, OFFLINE }

class SmartAnswerResult(val spoken: List<String>, val fail: SmartFail?, val firstChunkMs: Long?, val dropped: Int, val totalMs: Long)

/**
 * The relay client. [ask] blocks (run it on a worker thread) and calls [onSentence] for each sentence that passed the
 * filters, as soon as it arrives, so speech starts with the first one. [cancelled] is polled between chunks.
 */
class RelayClient(private val transport: RelayTransport, val install: String, private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
  @Volatile var state = RelayState.UNKNOWN; private set

  /** Free: an empty body is refused before any model call; the error code alone says whether the relay is configured. */
  fun probe(): RelayState {
    state = try {
      val r = transport.post("/ask", "{}", Smart.FIRST_CHUNK_TIMEOUT_MS.toInt())
      val code = r.body.use { runCatching { JSONObject(it.readBytes().decodeToString()).optString("error") }.getOrDefault("") }
      when {
        r.status == 400 || r.status == 413 || code == "bad_request" -> RelayState.CONFIGURED
        code == "off" -> RelayState.OFF
        code == "not_configured" -> RelayState.NOT_CONFIGURED
        code == "over_cap" -> RelayState.OVER_CAP
        else -> RelayState.UNKNOWN
      }
    } catch (e: IOException) { RelayState.OFFLINE }
    return state
  }

  fun body(question: String, screen: String): String = JSONObject().put("question", question.trim().take(Smart.MAX_QUESTION))
    .put("screen", screen.take(Smart.MAX_SCREEN)).put("install", install).toString()

  fun ask(question: String, screen: String, cancelled: () -> Boolean = { false }, onSentence: (String) -> Unit): SmartAnswerResult {
    val t0 = clock()
    val spoken = ArrayList<String>(); var dropped = 0; var words = 0; var first: Long? = null
    fun done(f: SmartFail?) = SmartAnswerResult(spoken, if (f == null && spoken.isEmpty()) if (dropped > 0) SmartFail.FILTERED else SmartFail.EMPTY else f, first, dropped, clock() - t0)
    fun take(s: String): Boolean {
      if (AnswerFilter.names(s) || AnswerFilter.claimsAction(s)) { dropped++; return true }
      val n = s.split(Regex("\\s+")).count { it.isNotEmpty() }
      if (words + n > Smart.MAX_WORDS) return false
      words += n; spoken += s; onSentence(s); return true
    }
    val r = try { transport.post("/ask", body(question, screen), Smart.FIRST_CHUNK_TIMEOUT_MS.toInt()) }
            catch (e: java.net.SocketTimeoutException) { return done(SmartFail.TIMEOUT) }
            catch (e: IOException) { state = RelayState.OFFLINE; return done(SmartFail.OFFLINE) }
    if (r.status != 200) {
      val code = r.body.use { runCatching { JSONObject(it.readBytes().decodeToString()).optString("error") }.getOrDefault("") }
      return done(when (code) {
        "off" -> SmartFail.OFF.also { state = RelayState.OFF }
        "not_configured" -> SmartFail.NOT_CONFIGURED.also { state = RelayState.NOT_CONFIGURED }
        "over_cap" -> SmartFail.OVER_CAP.also { state = RelayState.OVER_CAP }
        "rate_limited" -> SmartFail.RATE_LIMITED
        "bad_request" -> SmartFail.BAD_REQUEST
        else -> SmartFail.UNAVAILABLE
      })
    }
    state = RelayState.CONFIGURED
    val split = SentenceSplitter()
    val buf = ByteArray(1024)
    val dec = java.io.ByteArrayOutputStream()
    try {
      r.body.use { inp ->
        while (true) {
          if (cancelled()) return done(SmartFail.CANCELLED)
          val n = inp.read(buf)
          if (n < 0) break
          dec.write(buf, 0, n)
          val text = runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(dec.toByteArray())).toString() }.getOrNull() ?: continue
          dec.reset()
          for (s in split.push(text)) {
            if (first == null) { first = clock() - t0; if (first!! > Smart.FIRST_CHUNK_TIMEOUT_MS) return done(SmartFail.TIMEOUT) }
            if (cancelled()) return done(SmartFail.CANCELLED)
            if (!take(s)) return done(null)
          }
        }
      }
    } catch (e: java.net.SocketTimeoutException) { return done(if (first == null) SmartFail.TIMEOUT else SmartFail.CUT) }
    catch (e: IOException) { return done(SmartFail.CUT) }
    split.rest().takeIf { it.isNotEmpty() }?.let { s ->
      if (first == null) { first = clock() - t0; if (first!! > Smart.FIRST_CHUNK_TIMEOUT_MS) return done(SmartFail.TIMEOUT) }
      take(s)
    }
    return done(null)
  }
}

/** What the controller needs for the relay route. [userOn] is the Settings switch "Smart answers". */
class SmartVoice(val relay: RelayClient, val speaker: Speaker, val screen: () -> String, val userOn: () -> Boolean) {
  /** On only when the wearer left it on, the relay itself reported it is set up, and the headset can speak. */
  fun on() = userOn() && speaker.ready && relay.state == RelayState.CONFIGURED
  fun offReason(): SmartFail = when {
    !userOn() -> SmartFail.OFF
    relay.state == RelayState.OVER_CAP -> SmartFail.OVER_CAP
    relay.state == RelayState.OFFLINE -> SmartFail.OFFLINE
    relay.state == RelayState.OFF -> SmartFail.OFF
    else -> SmartFail.NOT_CONFIGURED
  }
}

/** Router order (#1032): fixed command; scripted answer above its threshold; relay; scripted fallback line. */
enum class Route { COMMAND, CANNED, RELAY, FALLBACK }

object SmartRouter {
  /** The route for a transcript that is not a command. [smartOn]: switch on, relay configured, speech ready. */
  fun route(m: VoiceIntents.Match, smartOn: Boolean): Route = when {
    m.kind == MatchKind.ANSWER || m.kind == MatchKind.NOT_CAUGHT -> Route.CANNED
    smartOn -> Route.RELAY
    else -> Route.FALLBACK
  }

  /** One log line per question: the route and stage times, never the question or answer text. */
  fun logLine(turn: Int, route: Route, asrMs: Long?, thinkingMs: Long?, firstChunkMs: Long?, firstAudioMs: Long?, fail: SmartFail?, sentences: Int, dropped: Int) =
    "route turn=$turn route=${route.name.lowercase()} asr_ms=${asrMs ?: "-"} thinking_ms=${thinkingMs ?: "-"} first_chunk_ms=${firstChunkMs ?: "-"} " +
      "first_audio_ms=${firstAudioMs ?: "-"} fail=${fail?.name?.lowercase() ?: "-"} sentences=$sentences dropped=$dropped"
}
