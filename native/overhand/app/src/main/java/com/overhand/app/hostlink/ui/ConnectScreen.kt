package com.overhand.app.hostlink.ui

import com.overhand.app.hostlink.Endpoint
import com.overhand.app.hostlink.LinkState
import com.overhand.app.hostlink.LinkStatus

/**
 * What the Connect panel shows, as plain data (#814). [ConnectPanelView] draws it; tests check the words.
 * One title, at most two lines of text, then buttons. Every failure has plain words and one next step.
 */
data class ConnectScreen(val key: String,val title: String,val lines: List<String>,val digits: String?=null,
  val hosts: List<Endpoint> =emptyList(),val buttons: List<Pair<String,String>> =emptyList(),val keypad: String?=null,
  val keyboard: String?=null)

/**
 * "Other ways to connect" (#954): [page] is `other` (the menu) or `address` (typing a computer's name), [typed] the
 * address so far, [note] the last problem with it. Off the first-minute path: reached only from that one button.
 */
data class AddressEntry(val page: String,val typed: String="",val note: String?=null)

/** Why the link is down, from what the headset can see. Pure, so the JVM tests cover every branch. */
enum class Diagnosis(val words: String) {
  NO_WIFI("This headset is not on Wi-Fi. Connect both to the same Wi-Fi."),
  OTHER_NETWORK("The computer is on a different network. Connect both to the same Wi-Fi."),
  ISOLATED("The headset cannot reach the computer. Connect both to the same Wi-Fi, not a guest network."),
  FIREWALL("The computer does not answer. Allow the host through the computer's firewall."),
  HOST_STOPPED("The host is not running on the computer. Start it there.");

  companion object {
    /** The outcome of a plain TCP connect to the host's port. */
    enum class Probe { OPEN, REFUSED, TIMEOUT, UNREACHABLE }

    /** [headset] and [host] are dotted IPv4; [prefix] is the headset's Wi-Fi prefix length. Null when nothing is wrong. */
    fun classify(headset: String?,prefix: Int,host: String,probe: Probe?): Diagnosis? {
      if(headset.isNullOrBlank()) return NO_WIFI
      val a=v4(headset);val b=v4(host)
      // A private overlay address (192.0.2.10/10) is not on the Wi-Fi by design: no subnet verdict for it.
      val overlay=b!=null && (b shr 22)==(100L shl 2 or 1L)
      if(a!=null && b!=null && !overlay && prefix in 1..32) {
        val mask=if(prefix==32) -1L and 0xffffffffL else (0xffffffffL shl (32-prefix)) and 0xffffffffL
        if((a and mask)!=(b and mask)) return OTHER_NETWORK
      }
      return when(probe) { Probe.REFUSED -> HOST_STOPPED; Probe.TIMEOUT -> FIREWALL; Probe.UNREACHABLE -> ISOLATED; else -> null }
    }
    private fun v4(s: String): Long?=s.split('.').takeIf { it.size==4 }?.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return null }
      ?.fold(0L) { acc,n -> (acc shl 8) or n.toLong() }
  }
}

object ConnectScreens {
  const val TITLE="Connect your computer"
  /** Where the host comes from: the public repository, as text (nothing on the headset opens it). */
  const val HOST_ADDRESS="github.com/samsundar989/flowstate"
  /** #1474: the three steps a new user needs. No product names: "your coding agent", "the host". */
  val SETUP_STEPS=listOf("1. On your computer, get the host from $HOST_ADDRESS",
    "2. Sign in to your coding agent there, then start the host as its INSTALL page says.",
    "3. Keep this headset on the same Wi-Fi, then choose Search again.")
  /** The address keyboard (#954): a computer name, an IPv4, or either with `:port`. Five rows of eight. */
  val KEYBOARD=(('a'..'z').map { it.toString() }+('0'..'9').map { it.toString() }+listOf(".","-",":","del")).chunked(8)
  fun spaced(d: String)=if(d.length==6) "${d.take(3)} ${d.drop(3)}" else d

  /**
   * [hosts] is the last mDNS browse (null while browsing), [keypadFor] the host the wearer chose to type a code for,
   * [typed] the digits so far, [why] the last diagnosis, [paired] whether a computer is stored.
   */
  fun of(status: LinkStatus,hosts: List<Endpoint>?,keypadFor: Endpoint?,typed: String,why: Diagnosis?,paired: Boolean,name: String,
         entry: AddressEntry?=null): ConnectScreen {
    if(entry!=null && !paired && (status.state==LinkState.UNPAIRED || status.state==LinkState.REFUSED)) {
      val problem=entry.note ?: status.detail.takeIf { status.state==LinkState.REFUSED }
      return if(entry.page=="address") ConnectScreen("address","Enter computer address",
        listOf(problem ?: "Type the name of the computer running the host."),keyboard=entry.typed,
        buttons=listOf("dial" to "Connect","back" to "Back"))
      else ConnectScreen("other","Other ways to connect",listOf("Use these when the computer is not on this Wi-Fi."),
        buttons=listOfNotNull("address" to "Enter computer address",if(!hosts.isNullOrEmpty()) "code" to "Type a code instead" else null,"back" to "Back"))
    }
    if(keypadFor!=null && status.state!=LinkState.PAIRING && status.state!=LinkState.CONNECTED)
      return ConnectScreen("keypad","Type the code on ${keypadFor.name}",
        listOf("Type the six digits the host shows on the computer."),keypad=typed,
        buttons=listOf("pair" to "Pair","back" to "Back"))
    return when(status.state) {
      LinkState.CONFIRM_ON_COMPUTER -> ConnectScreen("confirm","Confirm ${spaced(status.digits.orEmpty())} on your computer",
        listOf("Check that the computer shows the same six digits, then press y on its keyboard."),digits=spaced(status.digits.orEmpty()),
        buttons=listOf("differ" to "The numbers are different","cancel" to "Cancel"))
      LinkState.PAIRING -> ConnectScreen("pairing",TITLE,listOf(status.detail.ifBlank { "Pairing with $name" }),buttons=listOf("cancel" to "Cancel"))
      LinkState.CONNECTED -> ConnectScreen("connected","Connected to $name",
        listOf("Live questions come from this computer. Answers are sent only when Send real answers is on."),
        buttons=listOf("close" to "Done","forget" to "Forget this computer"))
      LinkState.RECONNECTING, LinkState.SEARCHING -> ConnectScreen("reconnecting","Reconnecting to $name",
        listOfNotNull(why?.words ?: "The connection dropped. Trying again."),buttons=listOf("retry" to "Try now","forget" to "Forget this computer"))
      LinkState.REFUSED -> ConnectScreen("refused","Not connected",listOf(status.detail),
        buttons=listOfNotNull("retry" to "Try again",if(paired) "forget" to "Forget this computer" else "search" to "Search again"))
      LinkState.UNPAIRED -> when {
        hosts==null -> ConnectScreen("searching",TITLE,listOf("Looking for computers on this Wi-Fi."),buttons=listOf("close" to "Close"))
        // #1474: a new user sees the three steps and where to get the host; Close returns to the recorded day unchanged.
        hosts.isEmpty() -> ConnectScreen("none",TITLE,listOfNotNull("No computer found on this Wi-Fi.",why?.words)+SETUP_STEPS,
          buttons=listOf("search" to "Search again","other" to "Other ways to connect","close" to "Close"))
        else -> ConnectScreen("hosts",TITLE,listOf("Choose your computer. Then confirm six digits on it."),hosts=hosts,
          buttons=listOf("code" to "Type a code instead","search" to "Search again","other" to "Other ways to connect"))
      }
    }
  }
}
