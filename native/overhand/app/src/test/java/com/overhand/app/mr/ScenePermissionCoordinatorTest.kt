package com.overhand.app.mr

import com.overhand.app.mr.ScenePermissionCoordinator.Reason
import org.junit.Assert.*
import org.junit.Test

/** A device model: a request opens one system dialog, which the user later answers. */
class ScenePermissionCoordinatorTest {
  private class Device(var granted: Boolean=false, var enabled: Boolean=true) {
    val trace=mutableListOf<String>()
    val coordinator=ScenePermissionCoordinator({ granted },{ enabled },
      { trace+="request" },{ trace+="load" },{ trace+="fallback:$it" })
    val requests get()=trace.count { it=="request" }
    val loads get()=trace.count { it=="load" }
    val last get()=trace.last()
    fun answer(allow: Boolean) { granted=allow;coordinator.permissionResult() }
    fun dump(name: String) = println("permission_trace $name requests=$requests loads=$loads trace=$trace")
  }

  @Test fun coldRecordedEntryWithUnknownPermissionNeverAsks() {
    val d=Device()
    d.coordinator.refresh()
    d.coordinator.resumed()
    d.dump("startup_unknown")
    assertEquals(0,d.requests);assertEquals(0,d.loads)
    assertEquals("fallback:${Reason.NOT_REQUESTED}",d.last)
  }
  @Test fun coldEntryAfterEarlierDenialNeverAsks() {
    val first=Device();first.coordinator.explicitAction();first.answer(false)
    val relaunch=Device(granted=false)
    relaunch.coordinator.refresh();relaunch.coordinator.resumed()
    relaunch.dump("startup_denied")
    assertEquals(0,relaunch.requests)
  }
  @Test fun explicitActionAsksOnceAndGrantLoadsScene() {
    val d=Device();d.coordinator.refresh()
    d.coordinator.explicitAction()
    assertEquals(1,d.requests);assertEquals(0,d.loads)
    d.answer(true)
    d.dump("explicit_grant")
    assertEquals(1,d.requests);assertEquals(1,d.loads);assertEquals("load",d.last)
  }
  @Test fun repeatedActionWhileDialogIsOpenDoesNotAskAgain() {
    val d=Device()
    repeat(3) { d.coordinator.explicitAction() }
    assertEquals(1,d.requests)
    assertEquals(1,d.trace.count { it=="fallback:${Reason.WAITING}" })
  }
  @Test fun denialKeepsFallbackAndDoesNotReask() {
    val d=Device();d.coordinator.explicitAction();d.answer(false)
    assertEquals("fallback:${Reason.DENIED}",d.last)
    d.coordinator.resumed();d.coordinator.refresh()
    d.dump("explicit_denied")
    assertEquals(1,d.requests);assertEquals(0,d.loads)
    assertEquals("fallback:${Reason.DENIED}",d.last)
  }
  @Test fun deliberateRetryAfterDenialAsksExactlyOnceMore() {
    val d=Device();d.coordinator.explicitAction();d.answer(false)
    d.coordinator.explicitAction()
    assertEquals(2,d.requests)
    d.answer(true)
    assertEquals(1,d.loads)
  }
  @Test fun alreadyGrantedStartupLoadsWithoutAsking() {
    val d=Device(granted=true)
    d.coordinator.refresh()
    d.dump("startup_granted")
    assertEquals(0,d.requests);assertEquals(1,d.loads)
  }
  @Test fun resumeBeforeFirstEvaluationDoesNotLoadTwice() {
    val d=Device(granted=true)
    d.coordinator.resumed();d.coordinator.refresh()
    assertEquals(1,d.loads)
  }
  @Test fun grantedReentryDoesNotReloadOrAsk() {
    val d=Device(granted=true);d.coordinator.refresh()
    repeat(3) { d.coordinator.resumed() }
    assertEquals(1,d.loads);assertEquals(0,d.requests)
  }
  @Test fun grantInSystemSettingsWhileAwayLoadsOnReturn() {
    val d=Device();d.coordinator.refresh()
    d.granted=true;d.coordinator.resumed()
    assertEquals(1,d.loads);assertEquals(0,d.requests)
  }
  @Test fun revocationWhileAwayFallsBackWithoutAsking() {
    val d=Device(granted=true);d.coordinator.refresh()
    d.granted=false;d.coordinator.resumed()
    assertEquals("fallback:${Reason.NOT_REQUESTED}",d.last);assertEquals(0,d.requests)
  }
  @Test fun explicitActionWhenAlreadyGrantedLooksAgainWithoutAsking() {
    val d=Device(granted=true);d.coordinator.refresh();d.coordinator.explicitAction()
    assertEquals(2,d.loads);assertEquals(0,d.requests)
  }
  @Test fun passthroughOffNeverAsksOrLoads() {
    val d=Device(enabled=false)
    d.coordinator.refresh();d.coordinator.explicitAction()
    assertEquals(0,d.requests);assertEquals(0,d.loads)
    assertEquals("fallback:${Reason.PASSTHROUGH_OFF}",d.last)
  }
  @Test fun turningPassthroughOffDuringDialogDoesNotLoadOnGrant() {
    val d=Device();d.coordinator.explicitAction()
    d.enabled=false;d.answer(true)
    assertEquals(0,d.loads);assertEquals("fallback:${Reason.PASSTHROUGH_OFF}",d.last)
  }
  @Test fun resumeDuringDialogDoesNotDisturbTheRequest() {
    val d=Device();d.coordinator.explicitAction()
    val before=d.trace.size
    d.coordinator.resumed()
    assertEquals(before,d.trace.size)
  }
}
