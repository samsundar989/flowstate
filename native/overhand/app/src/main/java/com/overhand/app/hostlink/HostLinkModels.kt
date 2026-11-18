package com.overhand.app.hostlink

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Wire contract v1: docs/host/PROTOCOL.md and host/test/fixtures/protocol. */
object Wire {
  const val VERSION=1
  const val PATH="/v1"
  const val SERVICE_TYPE="_flowstate._tcp"
  private val FP=Regex("^[0-9a-f]{64}$")
  fun isFingerprint(fp: String)=FP.matches(fp)
}

/** The four frames the headset sends. Field names and types match host/src/protocol.ts exactly. */
object Frames {
  fun pairToken(token: String,deviceName: String)=JSONObject().put("type","pair_token").put("token",token).put("device_name",deviceName)
  fun pairCode(code: String,deviceName: String)=JSONObject().put("type","pair_code").put("code",code).put("device_name",deviceName)
  fun pairRequest(devicePub: String,deviceName: String)=JSONObject().put("type","pair_request").put("device_pub",devicePub).put("device_name",deviceName)
  fun pairNonce(nonce: String)=JSONObject().put("type","pair_nonce").put("nonce",nonce)
  fun resume(deviceId: String,deviceSecret: String,lastSeq: Long)=JSONObject().put("type","resume").put("device_id",deviceId)
    .put("device_secret",deviceSecret).put("last_seq",lastSeq)
  fun answer(reqId: String,decisionId: String,optionId: String)=JSONObject().put("type","answer").put("req_id",reqId)
    .put("decision_id",decisionId).put("option_id",optionId)
  /** #815: "new project by voice", after the wearer confirmed the transcript. */
  fun createProject(reqId: String,prompt: String)=JSONObject().put("type","create_project").put("req_id",reqId).put("prompt",prompt)
  /** #973: a follow-up change to an open project, after the wearer confirmed its chip. */
  fun prompt(reqId: String,projectId: String,text: String)=JSONObject().put("type","prompt").put("req_id",reqId)
    .put("project_id",projectId).put("text",text)
  /** #1035: the wearer's sentence for one running task's agent. */
  fun say(reqId: String,projectId: String,taskId: String,text: String)=JSONObject().put("type","say").put("req_id",reqId)
    .put("project_id",projectId).put("task_id",taskId).put("text",text)
  /** #973: one chunk of a file committed in a project (the preview), answered by `file_chunk`. */
  fun getFile(reqId: String,projectId: String,commit: String,path: String,offset: Long)=JSONObject().put("type","get_file")
    .put("req_id",reqId).put("project_id",projectId).put("commit",commit).put("path",path).put("offset",offset)
}

/** Where a host listens, and the certificate it must present. */
data class Endpoint(val address: String,val port: Int,val fp: String,val name: String) {
  init { require(Wire.isFingerprint(fp)) { "bad fingerprint" } }
  val url get()="wss://"+(if(address.contains(':')) "[$address]" else address)+":$port"+Wire.PATH
}

/** The paired computer, as stored after pairing. [deviceSecret] is the long-lived credential the host issued. */
data class PairedHost(val endpoint: Endpoint,val deviceId: String,val deviceSecret: String) {
  val fp get()=endpoint.fp
  val name get()=endpoint.name
}

/** The text of the pairing QR: `flowstate://pair?v=1&h&p&fp&t&n`. */
data class PairingInvite(val endpoint: Endpoint,val token: String) {
  companion object {
    fun parse(text: String): PairingInvite {
      val u=try { URI(text.trim()) } catch(e: Exception) { throw PairRefused(Refusal.NOT_A_CODE) }
      if(u.scheme!="flowstate" || u.host!="pair") throw PairRefused(Refusal.NOT_A_CODE)
      val q=(u.rawQuery ?: "").split('&').filter { it.contains('=') }.associate {
        val (k,v)=it.split('=',limit=2);k to URLDecoder.decode(v,"UTF-8")
      }
      fun g(k: String)=q[k]?.takeIf { it.isNotEmpty() } ?: throw PairRefused(Refusal.NOT_A_CODE)
      if(g("v")!=Wire.VERSION.toString()) throw PairRefused(Refusal.VERSION)
      val fp=g("fp");if(!Wire.isFingerprint(fp)) throw PairRefused(Refusal.NOT_A_CODE)
      val port=g("p").toIntOrNull()?.takeIf { it in 1..65535 } ?: throw PairRefused(Refusal.NOT_A_CODE)
      return PairingInvite(Endpoint(g("h"),port,fp,g("n")),g("t"))
    }
  }
}

/** Everything the UI shows. One value at a time; [detail] is plain words, safe to display. */
enum class LinkState { UNPAIRED, SEARCHING, PAIRING, CONFIRM_ON_COMPUTER, CONNECTED, RECONNECTING, REFUSED }
/** [digits] is set only in [LinkState.CONFIRM_ON_COMPUTER]: the six digits the wearer checks against the computer's screen. */
data class LinkStatus(val state: LinkState,val detail: String,val digits: String?=null)

/** Why a pairing or a connection was refused, in words a wearer can act on. No vendor names. */
enum class Refusal(val words: String) {
  NOT_A_CODE("That is not a pairing code from the computer."),
  VERSION("The computer runs a different version. Update both."),
  WRONG_CERTIFICATE("This is not the computer you paired with. Nothing was sent."),
  BAD_CODE("Wrong code. Check the six digits on the computer."),
  CODE_EXPIRED("That code has expired. Use the new one on the computer."),
  BAD_TOKEN("That pairing code is not valid. Show a new one on the computer."),
  TOKEN_EXPIRED("That pairing code has expired. Show a new one on the computer."),
  TOKEN_USED("That pairing code was already used. Show a new one on the computer."),
  RATE_LIMITED("Too many tries. Wait a minute."),
  FORGOTTEN("The computer no longer knows this headset. Pair again."),
  UNREACHABLE("Cannot reach the computer. Check that both are on the same Wi-Fi and the host is running."),
  PROTOCOL("The computer sent something unexpected. Update both."),
  DECLINED("The computer said no. Nothing was paired."),
  CONFIRM_EXPIRED("Nobody confirmed on the computer in time. Try again."),
  BUSY("The computer is pairing another headset. Try again in a minute."),
  NOT_THIS_COMPUTER("This is not the computer it claims to be. Nothing was paired."),
  USE_CODE("This computer needs the six-digit code instead. Type the code it shows."),
  CANCELLED("Pairing cancelled. Nothing was paired."),
  NOT_AN_ADDRESS("That is not a computer name or address. Use letters, digits, dots and dashes."),
  ADDRESS_UNREACHABLE("Cannot reach that computer. Check the name, that the host is running, and that both are on the same private network.");
  companion object {
    fun ofCode(code: String)=when(code) {
      "bad_code" -> BAD_CODE; "code_expired" -> CODE_EXPIRED; "bad_token" -> BAD_TOKEN; "token_expired" -> TOKEN_EXPIRED
      "token_used" -> TOKEN_USED; "rate_limited" -> RATE_LIMITED; "unauthorized" -> FORGOTTEN; else -> PROTOCOL
    }
    /** `pair_refused.reason` from the confirm-on-computer path. */
    fun ofReason(reason: String)=when(reason) {
      "declined" -> DECLINED; "expired" -> CONFIRM_EXPIRED; "busy" -> BUSY; "rate_limited" -> RATE_LIMITED; else -> PROTOCOL
    }
  }
}
class PairRefused(val refusal: Refusal,cause: Throwable?=null): Exception(refusal.words,cause)

/** Result of a pairing: show [check] next to the computer's; if they differ the wearer presses "not my computer". */
data class Paired(val host: PairedHost,val check: String)

object Pin {
  private const val ALPHABET="ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
  fun sha256Hex(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
  fun of(cert: X509Certificate)=sha256Hex(cert.encoded)
  /** The 4-character check both screens show: binds the certificate actually seen to the secret used. */
  fun check(fp: String,secret: String): String {
    val d=MessageDigest.getInstance("SHA-256").digest("flowstate-check|$fp|$secret".toByteArray(Charsets.UTF_8))
    return (0..3).joinToString("") { ALPHABET[d[it].toInt() and 31].toString() }
  }
}

/**
 * Pairing by confirming six digits on the computer (docs/host/PROTOCOL.md 2d), the same derivation as
 * host/src/pairing_confirm.ts. [fp] is always the fingerprint this headset actually saw on the TLS connection.
 */
object Confirm {
  private val rng=SecureRandom()
  private fun b64(b: ByteArray)=Base64.getUrlEncoder().withoutPadding().encodeToString(b)
  private fun sha(s: String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
  fun newNonce()=b64(ByteArray(16).also { rng.nextBytes(it) })
  /** A fresh P-256 public key, base64url SPKI DER (the host checks it parses). */
  fun newDevicePub(): String=b64(KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"),rng) }.generateKeyPair().public.encoded)
  fun commitment(fp: String,devicePub: String,hostNonce: String)=sha("flowstate-commit|1|$fp|$devicePub|$hostNonce").joinToString("") { "%02x".format(it) }
  fun digits(fp: String,devicePub: String,hostNonce: String,deviceNonce: String): String {
    val d=sha("flowstate-digits|1|$fp|$devicePub|$hostNonce|$deviceNonce")
    val n=((d[0].toLong() and 0xff) shl 24) or ((d[1].toLong() and 0xff) shl 16) or ((d[2].toLong() and 0xff) shl 8) or (d[3].toLong() and 0xff)
    return (n%1_000_000).toString().padStart(6,'0')
  }
  fun spaced(d: String)=d.take(3)+" "+d.drop(3)
}

/**
 * Accepts exactly one certificate: SHA-256 of the leaf's DER bytes must equal [fp]. No CA chain, no hostname
 * (the host is reached by IP and is self-signed). There is no trust-all path anywhere in this package.
 */
class PinningTrustManager(val pinned: String): X509TrustManager {
  @Volatile var seen: String?=null
    private set
  override fun checkServerTrusted(chain: Array<out X509Certificate>?,authType: String?) {
    val leaf=chain?.firstOrNull() ?: throw CertificateException("no certificate")
    val got=Pin.of(leaf);seen=got
    if(got!=pinned) throw CertificateException("certificate ${got.take(12)}… is not the pinned ${pinned.take(12)}…")
  }
  override fun checkClientTrusted(chain: Array<out X509Certificate>?,authType: String?)=throw CertificateException("client mode only")
  override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
  fun context(): SSLContext=SSLContext.getInstance("TLS").apply { init(null,arrayOf(this@PinningTrustManager),null) }
}

/**
 * "Enter computer address" (#954): a name or IPv4 the wearer typed, for networks Wi-Fi discovery cannot see across
 * (a private overlay network between two places). `name` or `name:port`; the port defaults to the host's.
 */
object ManualAddress {
  const val DEFAULT_PORT=47821
  private val HOST=Regex("^[a-z0-9]([a-z0-9.-]{0,62}[a-z0-9])?$")
  private val NO_PIN="0".repeat(64)

  fun parse(text: String): Pair<String,Int>? {
    val t=text.trim().lowercase()
    val i=t.lastIndexOf(':')
    val host=if(i>=0) t.substring(0,i) else t
    val port=if(i>=0) t.substring(i+1).toIntOrNull() ?: return null else DEFAULT_PORT
    if(port !in 1..65535 || !HOST.matches(host) || host.contains("..")) return null
    return host to port
  }

  /**
   * The SHA-256 of the certificate the computer at [host]:[port] presents, or null if nothing answered. The TLS
   * handshake is checked against a pin no certificate has, so it always fails right after the certificate is seen:
   * no frame is ever sent on an unpinned connection. The six digits confirmed on the computer then bind this
   * fingerprint ([HostLink.pairByConfirm]), so a relay in between is caught there.
   */
  fun fingerprint(host: String,port: Int,timeoutMs: Int=5000): String? {
    val tm=PinningTrustManager(NO_PIN)
    runCatching {
      java.net.Socket().use { raw ->
        raw.connect(java.net.InetSocketAddress(host,port),timeoutMs);raw.soTimeout=timeoutMs
        (tm.context().socketFactory.createSocket(raw,host,port,true) as javax.net.ssl.SSLSocket).use { it.startHandshake() }
      }
    }
    return tm.seen?.takeIf { Wire.isFingerprint(it) && it!=NO_PIN }
  }

  /** The endpoint to pair with by six digits, named by what was typed until the computer gives its own name. */
  fun endpoint(text: String,timeoutMs: Int=5000): Endpoint {
    val (host,port)=parse(text) ?: throw PairRefused(Refusal.NOT_AN_ADDRESS)
    val fp=fingerprint(host,port,timeoutMs) ?: throw PairRefused(Refusal.ADDRESS_UNREACHABLE)
    return Endpoint(host,port,fp,host.substringBefore('.').take(32))
  }
}
