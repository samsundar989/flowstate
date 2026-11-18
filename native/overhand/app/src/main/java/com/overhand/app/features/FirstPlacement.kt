package com.overhand.app.features

/**
 * #1351 (nov17a: the build site never opened at put-on, 0 of 196 samples, and no crash was recorded).
 * Pure, so the first-placement sequence is replayed on the JVM ([FirstPlacementTest]).
 */
object FirstPlacement {
  /** Runs every step even when one throws; each failure is one `site event=first_place_failed` row. Returns the failed names. */
  fun run(steps: List<Pair<String,() -> Unit>>,log: (String) -> Unit): List<String> = steps.mapNotNull { (name,step) ->
    try { step();null } catch(e: Exception) {
      log("site event=first_place_failed step=$name error=${e.javaClass.simpleName} message=${e.message}");name
    }
  }
}

/**
 * #1351 cause: two head gates race for the first placement. RecenterFeature.ready ([HeadSettle]: 4 deg and 3 cm over
 * 800 ms, pitch within 50 deg, sampled at 10 Hz from 3 s after scene ready) runs put-on; First60 (Recorded,
 * [com.overhand.app.first60.SteadyGate]: under 15 deg/s for 1.5 s, sampled at 20 Hz from scene ready) calls
 * centerAll("first60_steady"). centerAll used to set placedOnce, so when First60 won on a real (live) head the
 * first-placement block, and with it put-on and the build site, never ran in that process (nov17a pid 7195).
 * A scripted fake head is frozen, so First60 waits for its 15 s fallback and put-on always won (t1299 pid 10788).
 * Now whichever gate comes first runs the first placement exactly once. ECS thread only.
 */
class FirstPlacementGate {
  var placedOnce=false; private set
  /** HeadSettle's first settle. True when the caller placed for the first time (via [place]). */
  fun settled(place: () -> Unit): Boolean { if(placedOnce) return false;placedOnce=true;place();return true }
  /** Any centerAll. Before the first placement it IS the first placement (put-on included); after it, a re-place. */
  fun centerAll(place: () -> Unit,recenter: () -> Unit) { if(!placedOnce) { placedOnce=true;place() } else recenter() }
}

/**
 * #1351: while "Show build site" is on, Classic view is off and the site has never been placed in this process,
 * a recenter, a resume or the put-on timer asks again ([SiteOpenRetry.MAX] times at most). A site the wearer closed is
 * never reopened by this. Thread-safe: called from the ECS thread (recenter) and the UI thread (resume, timer).
 */
class SiteOpenRetry(private val log: (String) -> Unit) {
  private var tries=0
  private var wearerClosed=false
  /** The wearer (or the switch) closed the site: never reopen it from here. */
  @Synchronized fun closed() { wearerClosed=true }
  /** True when the caller should call `showSite("retry_never_opened")` now. */
  @Synchronized fun due(why: String,switchOn: Boolean,classic: Boolean,everPlaced: Boolean): Boolean {
    if(everPlaced || wearerClosed || !switchOn || classic || tries>=MAX) return false
    tries++
    log("site event=open_retry n=$tries why=$why")
    return true
  }
  companion object {
    const val MAX=3
    /** The put-on timer: a few seconds, so a normal open (one frame later) has long placed the site. */
    const val TIMER_MS=4_000L
    const val WHY="retry_never_opened"
  }
}
