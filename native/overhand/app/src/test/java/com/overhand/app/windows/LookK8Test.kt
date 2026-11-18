package com.overhand.app.windows

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/** #1379 look K8: each briefing row calls its own open path and logs `briefing event=open row=<row>`. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk=[34])
class LookK8Test {
  @Test fun eachRowCallsItsOpenPath() {
    val calls=mutableListOf<String>()
    val p=LookK8.Paths({ calls+="needs" },{ calls+="fleet" },{ calls+="done_list" })
    ShadowLog.clear()
    assertTrue(LookK8.route("needs",p));assertTrue(LookK8.route("building",p));assertTrue(LookK8.route("done",p))
    assertEquals(listOf("needs","fleet","done_list"),calls)
    assertFalse(LookK8.route("other",p))
    assertFalse(LookK8.route("needs",null))
    val logged=ShadowLog.getLogs().map { it.msg }.filter { it.startsWith("briefing ") }
    assertEquals(listOf("needs" to "decision","building" to "fleet","done" to "yard","needs" to "decision").map { (r,t) -> "briefing event=open row=$r target=$t" },logged)
  }
  @Test fun outcomesKeepOnlyDoneAndError() {
    val ev=listOf("done","message","error","done").map { JSONObject().put("kind",it).put("title","x") }
    val out=LookK8.outcomes(ev)
    assertEquals(3,out.length());assertEquals("{\"kind\":\"done\"}",out.getJSONObject(0).toString())
  }
}
