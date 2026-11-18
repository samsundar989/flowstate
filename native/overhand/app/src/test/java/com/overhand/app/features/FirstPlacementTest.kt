package com.overhand.app.features

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import com.overhand.app.graph.site.SitePlacement
import org.junit.Assert.*
import org.junit.Test

/** #1351: first placement can no longer lose the build site silently (nov17a, pid 7195: never opened). */
class FirstPlacementTest {
  /** The ready() order: card, put-on, settings button. A throwing card used to skip put-on for the whole process. */
  @Test fun throwingCardStillOpensTheSite() {
    val rows=mutableListOf<String>();val site=SitePlacement()
    val ran=mutableListOf<String>()
    val failed=FirstPlacement.run(listOf(
      "placeCard" to { ran+="placeCard";throw IllegalStateException("card entity not ready") },
      "putOn" to { ran+="putOn";site.open(Pose(Vector3(0f,1f,2f)),"put_on") },
      "placeSettingsButton" to { ran+="placeSettingsButton" }),rows::add)
    assertEquals(listOf("placeCard","putOn","placeSettingsButton"),ran)
    assertEquals(listOf("placeCard"),failed)
    assertNotNull("the site is placed although the card threw",site.pose)
    assertEquals(listOf("site event=first_place_failed step=placeCard error=IllegalStateException message=card entity not ready"),rows)
  }
  /** The cause (main RecenterFeature.kt:127): First60's centerAll("first60_steady") before HeadSettle's first settle
   *  set placedOnce, so ready() never ran put-on. Now that centerAll IS the first placement, exactly once. */
  @Test fun first60CenterAllBeforeTheSettleStillRunsPutOn() {
    val gate=FirstPlacementGate();val site=SitePlacement();var putOns=0;var recenters=0
    val place={ putOns++;site.open(Pose(Vector3(0f,1.2f,0f)),"put_on");Unit }
    gate.centerAll(place) { recenters++ }          // First60 steady wins the race
    assertFalse("the later settle does not place twice",gate.settled(place))
    gate.centerAll(place) { recenters++ }          // a later system recenter is a re-place
    assertNotNull("the site is placed although First60 came first",site.pose)
    assertEquals(1,putOns);assertEquals(1,recenters);assertTrue(gate.placedOnce)
  }

  /** The race with the shipped gates at their shipped clocks: a live, still head looking 55 degrees down (at the card,
   *  a desk, the strap). First60 ([SteadyGate], 20 Hz from scene ready, plan loaded at 2.2 s as on pid 1581) fires;
   *  [HeadSettle] (10 Hz from 3 s, pitch within 50 deg) waits for its 10 s fallback. The site must still open. */
  @Test fun first60WinsTheRaceOnALiveHeadAndTheSiteStillOpens() {
    val steady=com.overhand.app.first60.SteadyGate().also { it.mount(0) };val settle=HeadSettle()
    val gate=FirstPlacementGate();val site=SitePlacement();var first60At=-1L;var settleAt=-1L
    val place={ site.open(Pose(Vector3(0f,1.2f,0f)),"put_on");Unit }
    fun fwd(t: Long): V3 { val yaw=Math.toRadians(0.3*Math.sin(t/300.0));val pitch=Math.toRadians(-55.0)
      return V3(Math.sin(yaw)*Math.cos(pitch),Math.sin(pitch),Math.cos(yaw)*Math.cos(pitch)) }
    val head=V3(0.0,1.2,0.0)
    for(t in 0L..15_000L step 50) {
      val f=fwd(t)
      if(t>=2_242 && first60At<0 && steady.sample(t,doubleArrayOf(f.x,f.y,f.z),HeadSettle.tracked(head,f))) {
        first60At=t;gate.centerAll(place) {}
      }
      if(t>=3_000 && t%100==0L && settleAt<0 && settle.sample(t,head,f)) { settleAt=t;gate.settled(place) }
    }
    assertTrue("First60 fired first ($first60At ms against $settleAt ms)",first60At in 0 until settleAt)
    assertNotNull("put-on ran: the site is placed",site.pose)
  }

  @Test fun everyFailingStepIsLoggedAndTheRestStillRun() {
    val rows=mutableListOf<String>();var button=false
    val failed=FirstPlacement.run(listOf(
      "placeCard" to { error("a") },"putOn" to { throw NullPointerException("b") },"placeSettingsButton" to { button=true }),rows::add)
    assertEquals(listOf("placeCard","putOn"),failed);assertTrue(button);assertEquals(2,rows.size)
    assertTrue(rows[1].contains("step=putOn error=NullPointerException message=b"))
  }
  @Test fun nothingLoggedWhenEveryStepSucceeds() {
    val rows=mutableListOf<String>()
    assertTrue(FirstPlacement.run(listOf("a" to {},"b" to {}),rows::add).isEmpty());assertTrue(rows.isEmpty())
  }

  /** A lost put-on (nov17a): recenter, resume and the timer each ask again, at most 3 times, and the site gets a pose. */
  @Test fun lostPutOnIsRetriedUntilTheSiteIsPlaced() {
    val rows=mutableListOf<String>();val site=SitePlacement();val retry=SiteOpenRetry(rows::add)
    // put_on lost: no open. Then a system recenter skips the pose (as on nov17a), and the retry opens it.
    assertNull(site.recenter(Pose(),SitePlacement.SYSTEM_RECENTER))
    if(retry.due("recenter_system_recenter",switchOn=true,classic=false,everPlaced=site.pose!=null)) site.open(Pose(Vector3(0f,1f,2f)),SiteOpenRetry.WHY)
    assertNotNull(site.pose)
    assertFalse("placed: no more retries",retry.due("resume",true,false,site.pose!=null))
    assertEquals(listOf("site event=open_retry n=1 why=recenter_system_recenter"),rows)
  }
  @Test fun atMostThreeRetries() {
    val rows=mutableListOf<String>();val retry=SiteOpenRetry(rows::add)
    val asked=(1..6).count { retry.due("timer",true,false,false) }
    assertEquals(SiteOpenRetry.MAX,asked)
    assertEquals((1..3).map { "site event=open_retry n=$it why=timer" },rows)
  }
  @Test fun neverReopensAClosedSiteNorOverridesTheSwitchOrClassic() {
    val retry=SiteOpenRetry {}
    assertFalse(retry.due("timer",switchOn=false,classic=false,everPlaced=false))
    assertFalse(retry.due("timer",switchOn=true,classic=true,everPlaced=false))
    assertFalse(retry.due("timer",switchOn=true,classic=false,everPlaced=true))
    retry.closed()
    assertFalse("the wearer closed it",retry.due("resume",switchOn=true,classic=false,everPlaced=false))
  }
}
