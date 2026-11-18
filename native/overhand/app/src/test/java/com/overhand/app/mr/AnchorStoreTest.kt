package com.overhand.app.mr

import com.meta.spatial.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AnchorStoreTest {
  @get:Rule val tmp=TemporaryFolder()
  private val desk="7f1c2b8e-0000-4000-8000-000000000001"
  private fun log()=AnchorLog(tmp.root.resolve("anchor_log.jsonl"))
  private fun store(l: AnchorLog=log())=AnchorStore(tmp.root.resolve("anchors.json"),l)
  private fun events(l: AnchorLog)=(0 until l.tail().length()).map { l.tail().getJSONObject(it) }
  private fun near(a: Pose,b: Pose) { assertEquals(0f,(a.t-b.t).length(),1e-4f);assertEquals(1f,kotlin.math.abs(a.q.dot(b.q)),1e-4f) }

  @Test fun restartAndRecentreRelocalizeAgainstTheSavedAnchor() {
    val anchorAtSave=Pose(Vector3(0.2f,0.74f,-0.9f),Quaternion.fromEuler(0f,20f,0f))
    val station=Pose(Vector3(0.5f,0.9f,-1.6f),Quaternion.fromEuler(0f,-10f,0f))
    val first=log()
    store(first).persist("station/example-project",station,AnchorRef(desk,anchorAtSave))
    assertEquals(listOf("loaded","created","persisted"),events(first).map { it.getString("event") })

    // New process; the recentre moved the anchor in tracking space by 1.3 m and 35 degrees.
    val second=log()
    val reopened=store(second)
    val anchorNow=Pose(Vector3(1.5f,0.74f,0.1f),Quaternion.fromEuler(0f,55f,0f))
    val r=reopened.resolve("station/example-project",AnchorRef(desk,anchorNow))!!
    assertTrue(r.anchored)
    near(anchorNow*anchorAtSave.inverse()*station,r.pose)
    assertEquals(listOf("loaded","loaded","relocalized"),events(second).map { it.getString("event") })
    assertEquals(desk,events(second)[2].getString("uuid"))
    assertTrue(tmp.root.resolve("anchor_log.jsonl").readLines().size==6) // both launches, one file
  }

  @Test fun missingAnchorFallsBackToSavedTransformAndSaysSo() {
    val world=Pose(Vector3(0.1f,1f,-2f))
    store().persist("station/a",world,AnchorRef(desk,Pose(Vector3(0f,0.7f,-1f))))
    val l=log();val s=store(l)
    val none=s.resolve("station/a",null)!!
    assertFalse(none.anchored);near(world,none.pose)
    val other=s.resolve("station/a",AnchorRef("another-room",Pose()))!!
    assertFalse(other.anchored);near(world,other.pose)
    assertTrue(other.reason.contains("not in room"))
    assertEquals(2,events(l).count { it.getString("event")=="failed" })
    assertNull(s.resolve("station/unknown",null))
  }

  @Test fun neverAnchoredRecordIsAFallbackNotARelocalization() {
    store().persist("station/a",Pose(Vector3(1f,1f,1f)),null)
    val r=store().resolve("station/a",AnchorRef(desk,Pose()))!!
    assertFalse(r.anchored);assertEquals("never anchored",r.reason)
  }

  @Test fun unanchoredSaveKeepsThePriorBindingAndJitterDoesNotRewrite() {
    val l=log();val s=store(l);val a=AnchorRef(desk,Pose(Vector3(0f,0.7f,-1f)))
    s.persist("desk",a.pose,a)
    s.persist("desk",Pose(Vector3(0.0002f,0.7f,-1f)),a.copy(pose=Pose(Vector3(0.0002f,0.7f,-1f))))
    assertEquals(1,events(l).count { it.getString("event")=="persisted" })
    s.persist("desk",Pose(Vector3(3f,0.7f,-1f)),null)
    assertEquals(desk,store().record("desk")!!.anchor)
  }

  @Test fun corruptStoreIsLoggedAndRecovered() {
    tmp.root.resolve("anchors.json").writeText("{not json")
    val l=log();val s=store(l)
    assertEquals("failed",events(l)[0].getString("event"))
    assertTrue(s.keys().isEmpty())
    s.persist("desk",Pose(),AnchorRef(desk,Pose()))
    assertEquals(desk,JSONObject(tmp.root.resolve("anchors.json").readText()).getJSONObject("desk").getString("anchor"))
  }

  @Test fun settleWithoutAnchorMarksStationsNotAnchored() {
    val l=log();MrStations.store=store(l)
    try {
      MrStations.store!!.persist("station/a",Pose(Vector3(0f,1f,-2f)),AnchorRef(desk,Pose()))
      MrStations.settle(null,"room load result=ERROR_NO_ROOMS_FOUND")
      assertTrue(MrStations.notAnchored)
      assertTrue(events(l).any { it.getString("event")=="failed" && it.getString("key")=="desk" })
      MrStations.settle(AnchorRef(desk,Pose()),"desk")
      assertFalse(MrStations.notAnchored)
      assertTrue(events(l).any { it.getString("event")=="relocalized" && it.getString("key")=="station/a" })
    } finally { MrStations.store=null;MrStations.anchor=null }
  }
}
