package com.overhand.app.features

import com.overhand.app.OverhandActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecenterMovedTest {
  @Test fun automaticCenterAndQueuedResumeCannotResetAHandMovedWindow() {
    val shell=Shell(Robolectric.buildActivity(OverhandActivity::class.java).get())
    // Queue before the edit: the queued resettle must observe the later edit.
    shell.recenter.resettle("mounted")
    shell.recenter.markMovedByHand()
    while(shell.commands.isNotEmpty()) shell.commands.poll()!!.invoke()
    // No scene is initialized: reaching head() or any placement here would also fail.
    for(reason in listOf("mounted","resume_after_12s","first60_steady")) {
      shell.recenter.centerAll(reason)
      assertTrue(shell.recenter.movedByHand)
      assertEquals(false,shell.recenter.placedOnce)
    }
  }
}
