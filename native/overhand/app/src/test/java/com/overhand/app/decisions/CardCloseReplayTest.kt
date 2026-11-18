package com.overhand.app.decisions

import org.junit.Assert.*
import org.junit.Test

/**
 * #1373: the q2 wear log (data/worn/live/2026-11-17_nopid/logcat_stream.txt L727-732, scorecard_q2.md line 19).
 * Close's up at 15:35:47.903; InteractionFeature hides the card at .914; WindowsFeature.lifetime re-showed it at .915
 * because the hide fade was still running (fadedOut=false); onDraw hid it at 48.135 when the fade ended.
 * Replayed through the rule both owners now share: after Close, no show of the card within 500 ms.
 */
class CardCloseReplayTest {
  /** (ms after Close's up, owner, show requested, fade finished) for each visibility decision in the log rows. */
  private val rows=listOf(
    Triple(11L,"InteractionFeature_sampleHands",false to false),
    Triple(12L,"WindowsFeature_lifetime",false to false),
    Triple(232L,"WindowsFeature_onDraw",false to true),
    Triple(250L,"WindowsFeature_lifetime",false to true),
    Triple(499L,"WindowsFeature_onDraw",false to true))

  private fun replay(card: Boolean)=rows.filter { (_,owner,_) -> owner!="InteractionFeature_sampleHands" }
    .map { (ms,owner,p) -> Triple(ms,owner,QuestionCard.entityVisible(p.first,p.second,card)) }

  @Test fun noShowWithin500msAfterClose() {
    val shows=replay(card=true).filter { it.third }
    assertTrue("card shown again after Close: $shows",shows.isEmpty())
  }
  /** Control: the old rule (every panel fades out visibly) reproduces the q2 re-show at +12 ms. */
  @Test fun oldRuleReproducesTheReShow() {
    val shows=replay(card=false).filter { it.third }
    assertEquals(12L,shows.first().first);assertEquals("WindowsFeature_lifetime",shows.first().second)
  }
  /** Other panels keep their fade; the card shows at once when asked again. */
  @Test fun showStillShows() {
    assertTrue(QuestionCard.entityVisible(true,true,true));assertTrue(QuestionCard.entityVisible(false,false,false))
  }
}
