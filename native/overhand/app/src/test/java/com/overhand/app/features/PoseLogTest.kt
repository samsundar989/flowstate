package com.overhand.app.features

import com.overhand.app.hands.*
import com.overhand.app.opening.Opening
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PoseLogTest {
  @Volatile private var allocationControl: ByteArray?=null
  private fun checkCounter(allocation: AllocationCounter) {
    val before=allocation.bytes()
    allocationControl=ByteArray(1024) // Escapes: the positive control cannot be removed by the JIT.
    assertTrue("allocation counter must detect an allocating control",allocation.bytes()-before>=1024)
  }
  private val head=PoseLog.Head(0.0,1.69,0.0,0.0,0.0)
  private fun bodies()=listOf("site","dock","chat","blueprint","settings","fleet","code","build","voice","decision").mapIndexed { i,id ->
    PoseLog.Body(id,i*.1,1.0,2.0,0.0,true,true,false) }
  private fun message()=PoseLog.line(PoseLog.Moment.TICK,head,bodies(),PoseLog.Wrist(-.4,.8),PoseLog.Wrist(-.3,.9),false,null)
  private fun row(t: Int,msg: String,kind: String)=EventLog.Row(t.toDouble(),1,1,"I",if(kind=="pose") "OverhandPose" else "OverhandPerf",msg,kind,"app")

  @Test fun twoPerSecondAndNoAllocationBetweenTicks() {
    val c=PoseLog.Clock();var ticks=0
    for(t in 0L until 60_000L) if(c.due(t)) ticks++
    assertEquals(120,ticks)
    // A blocked clock is the real between-tick hot path, not a mocked allocation counter.
    val blocked=PoseLog.Clock();blocked.due(Long.MAX_VALUE-501)
    val allocation=AllocationCounter()
    fun sample(): Int {
      var unexpected=0
      for(i in 0 until 100_000) if(blocked.due(1)) unexpected++
      return unexpected
    }
    repeat(20) { assertEquals(0,sample()) }
    checkCounter(allocation)
    val samples=LongArray(10)
    repeat(samples.size) { i ->
      val before=allocation.bytes()
      val unexpected=sample()
      samples[i]=allocation.bytes()-before
      assertEquals(0,unexpected)
    }
    // No subtraction: the counter read can optimize from 16 bytes to zero after warm-up.
    // A minimum of repeated warmed samples excludes one-off JVM bookkeeping, not steady allocation.
    val bytes=samples.minOrNull()!!
    assertTrue(samples.all { it>=0 });assertEquals("between-tick allocated bytes",0L,bytes)
    println("pose_idle_allocated_bytes=$bytes samples=${samples.joinToString()}")
  }

  @Test fun coneUsesPitchAndYaw() {
    assertTrue(PoseLog.cone(head,0.0,1.69,2.0))
    assertFalse(PoseLog.cone(head,2.1,1.69,2.0))
    assertFalse(PoseLog.cone(head,0.0,1.69,-2.0))
    assertTrue(PoseLog.cone(head.copy(yaw=90.0),2.0,1.69,0.0))
    assertTrue(PoseLog.cone(head.copy(pitch=90.0),0.0,3.69,0.0))
  }

  @Test fun scalarPalmReadingMatchesMenuAndDoesNotAllocate() {
    val w=Point(0.0,1.4,1.0)
    fun finger(x: Double)=Finger(Point(x,1.4,1.06),Point(x,1.4,1.08),Point(x,1.4,1.1),Point(x,1.4,1.12))
    val j=HandJoints(w,finger(-.06),listOf(finger(-.04),finger(-.01),finger(.01),finger(.04)))
    for(left in listOf(true,false)) {
      val normal=Opening.palmNormal(j,left)
      val toHead=(Point(head.x,head.y,head.z)-Opening.palmCentre(j)).unit()
      assertEquals(normal.dot(toHead),PoseLog.facing(j,left,head.x,head.y,head.z),1e-10)
    }
    // Warm the identical loop/call site: a distinct warm-up loop leaves the
    // measured loop's first OSR compilation inside the allocation interval.
    fun sample(): Double {
      var sum=0.0
      for(i in 0 until 100_000) sum+=PoseLog.facing(j,true,head.x,head.y,head.z)
      return sum
    }
    repeat(20) { assertTrue(sample().isFinite()) }
    val allocation=AllocationCounter()
    checkCounter(allocation)
    val samples=LongArray(10)
    repeat(samples.size) { i ->
      val before=allocation.bytes()
      val sum=sample()
      samples[i]=allocation.bytes()-before
      assertTrue(sum.isFinite())
    }
    val bytes=samples.minOrNull()!!
    assertTrue(samples.all { it>=0 });assertEquals("render-rate palm edge allocation",0L,bytes)
    println("pose_palm_allocated_bytes=$bytes samples=${samples.joinToString()}")
  }

  @Test fun poseRetentionPreservesEveryFrameAndOtherEventByteForByte() {
    val off=File.createTempFile("pose-off", ".jsonl");val on=File.createTempFile("pose-on", ".jsonl")
    try {
      val a=EventLog.Sink(off);val b=EventLog.Sink(on,poseCap=12_000,poseKeep=8_000)
      for(t in 0 until 120) {
        val frame=row(t,"FPS=72/72,Stale=0,App=5ms,CPU&GPU=7ms","frames")
        val lag=row(t,"lagcheck step=0 name=as_launched state=start","lagcheck")
        a.write(frame);a.write(lag);b.write(frame);b.write(lag)
        b.write(row(t,message(),"pose"))
      }
      val poses=on.readLines().filter { "\"kind\":\"pose\"" in it }
      assertEquals(off.readLines(),on.readLines().filterNot { "\"kind\":\"pose\"" in it })
      assertTrue(poses.sumOf { it.toByteArray().size+1L }<=12_000)
      assertTrue(poses.last().startsWith("{\"t\":119.000"))
      assertFalse(poses.any { it.startsWith("{\"t\":0.000") })
      println("pose_frame_lines_changed=0\npose_event_lines_dropped=0")
    } finally { off.delete();on.delete() }
  }

  @Test fun thirtyMinutesWithTwelveObjectsAndBothWrists() {
    val files=List(3) { File.createTempFile("pose-session", ".jsonl") }
    try {
      val sinks=listOf(EventLog.Sink(files[0]),EventLog.Sink(files[1]),
        EventLog.Sink(files[2],poseCap=512L*1024,poseKeep=256L*1024))
      val objects=bodies()+listOf("graph","panel_99").map { PoseLog.Body(it,1.0,1.0,2.0,0.0,true,true,false) }
      val msg=PoseLog.line(PoseLog.Moment.TICK,head,objects,PoseLog.Wrist(-.4,.8),PoseLog.Wrist(-.3,.9),false,null)
      var poseBytes=0L
      for(i in 0 until 3600) {
        val t=1794790000.0+i*.5
        if(i%2==0) for(kind in listOf("frames","event")) {
          val r=row(i,if(kind=="frames") "FPS=72/72,Stale=0,App=5ms,CPU&GPU=7ms" else "site event=state why=session",kind).copy(t=t)
          sinks.forEach { it.write(r) }
        }
        val r=row(i,msg,"pose").copy(t=t)
        sinks[1].write(r);sinks[2].write(r);poseBytes+=r.json().toByteArray().size+1
        if(i%60==0) {
          val moment=r.copy(msg=msg.replace("tick","palm_away"))
          sinks[1].write(moment);sinks[2].write(moment);poseBytes+=moment.json().toByteArray().size+1
        }
      }
      val lines=files[1].readLines()
      val first="\"t\":1794790000.000"
      assertTrue(lines.any { first in it && "\"kind\":\"pose\"" in it })
      assertEquals(3660,lines.count { "\"kind\":\"pose\"" in it })
      assertEquals(files[0].readLines(),lines.filterNot { "\"kind\":\"pose\"" in it })
      assertFalse("old-budget planted control loses first tick",files[2].readLines().any { first in it && "\"kind\":\"pose\"" in it })
      assertTrue("startup reserve fits full session",poseBytes<=EventLog.POSE_CAP_BYTES-EventLog.POSE_KEEP_BYTES)
      println("pose_retention_minutes=30 pose_bytes_per_minute_12_objects=${poseBytes/30} pose_session_bytes=$poseBytes old_budget_control=failed_as_designed")
    } finally { files.forEach { it.delete() } }
  }

  @Test fun backgroundWritesOrderedAndPauseDrainsWithSynchronousControl() {
    val capture=Thread.currentThread().id
    val writes=java.util.Collections.synchronizedList(mutableListOf<Pair<Long,EventLog.Row>>())
    val file=File.createTempFile("pose-thread", ".jsonl")
    val sink=EventLog.Sink(file)
    val record: (EventLog.Row)->Unit = { writes.add(Thread.currentThread().id to it); sink.write(it) }
    val writer=EventLog.Writer(record)
    try {
      repeat(100) { writer.submit(row(it,message(),"pose")) }
      writer.flush() // The same barrier used by the pause hook.
      assertEquals((0 until 100).map { it.toDouble() },writes.map { it.second.t })
      assertEquals(0,writes.count { it.first==capture })
      assertEquals(writes.map { it.second.json() },file.readLines())
      writes.clear()
      record(row(101,message(),"pose"))
      assertTrue("same sink detects synchronous planted control",writes.any { it.first==capture })
      println("pose_frame_thread_writes=0 synchronous_control=failed_as_designed pause_flushed=100")
    } finally { writer.close();file.delete() }
  }

  @Test fun overflowCountsExactlyOnceAndCloseDrains() {
    val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
    val writes=mutableListOf<EventLog.Row>()
    val writer=EventLog.Writer({ if(it.t==0.0 && it.kind=="pose") { entered.countDown();release.await() };writes.add(it) },capacity=2)
    try {
      writer.submit(row(0,message(),"pose"));assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
      repeat(10) { writer.submit(row(it+1,message(),"pose")) }
      release.countDown();writer.close()
      assertEquals(listOf(0.0,1.0,2.0),writes.filter { it.kind=="pose" }.map { it.t })
      assertEquals(listOf("pose_dropped n=8"),writes.filter { it.kind!="pose" }.map { it.msg })
    } finally { release.countDown();writer.close() }
  }

  @Test fun nonPoseTrimKeepsEveryNewerPoseIncludingOutOfAppendOrder() {
    val f=File.createTempFile("pose-trim", ".jsonl")
    try {
      val sink=EventLog.Sink(f,cap=2500,keep=1200)
      val expected=mutableListOf<EventLog.Row>()
      repeat(100) { i ->
        val p=row(i,message(),"pose");sink.write(p);expected.add(p)
        sink.write(row(if(i%5==0) i-2 else i,"event=session index=$i","event"))
      }
      val lines=f.readLines()
      val oldest=lines.filterNot { "\"kind\":\"pose\"" in it }.minOf { it.substringAfter("\"t\":").substringBefore(',').toDouble() }
      assertTrue(expected.filter { it.t>=oldest }.all { it.json() in lines })
      val oldTrim=lines.filterNot { "\"kind\":\"pose\"" in it }
      assertFalse("old trimPoses(0) planted control loses newer poses",expected.filter { it.t>=oldest }.all { it.json() in oldTrim })
      println("non_pose_trim_newer_poses_preserved=1 old_trim_control=failed_as_designed")
    } finally { f.delete() }
  }

  @Test fun startupReservesSpaceWithoutChangingNonPoseLines() {
    val f=File.createTempFile("pose-startup", ".jsonl")
    try {
      val old=EventLog.Sink(f,poseCap=100_000,poseKeep=2_000)
      repeat(30) { old.write(row(it,message(),"pose"));old.write(row(it,"event=session","event")) }
      val before=f.readLines().filterNot { "\"kind\":\"pose\"" in it }
      EventLog.Sink(f,poseCap=100_000,poseKeep=2_000)
      val lines=f.readLines()
      assertTrue(lines.filter { "\"kind\":\"pose\"" in it }.sumOf { it.toByteArray().size+1L }<=2_000)
      assertEquals(before,lines.filterNot { "\"kind\":\"pose\"" in it })
    } finally { f.delete() }
  }

  @Test fun releaseDoesNotStartWriterOrEnableSampler() {
    if (!com.overhand.app.BuildConfig.DEBUG) {
      val dir=java.nio.file.Files.createTempDirectory("pose-release").toFile()
      try {
        EventLog.start(dir)
        assertFalse(EventLog.enabled)
        assertFalse(Thread.getAllStackTraces().keys.any { it.name=="overhand-pose-writer" || it.name=="overhand-events" })
        assertTrue(dir.listFiles()!!.isEmpty())
      } finally { dir.deleteRecursively() }
    }
  }

  @Test fun plantedPrivateValuesNeverEnterPoseEventFile() {
    val markers=listOf("qorvane","plixtor","vexmund") // #1188's exact controls
    val f=File.createTempFile("pose-private", ".jsonl")
    try {
      val sink=EventLog.Sink(f)
      for(marker in markers) sink.write(row(1,PoseLog.line(PoseLog.Moment.TICK,head,listOf(
        PoseLog.Body(marker,0.0,0.0,2.0,0.0,true,true,false)),null,null,false,marker),"pose"))
      val text=f.readText()
      val hits=markers.sumOf { marker -> text.windowed(marker.length).count { it==marker } }
      assertEquals(0,hits)
      val control=text+markers.joinToString()
      assertTrue(markers.all { it in control })
      println("pose_private_log_hits=$hits\npose_private_log_control_hits=${markers.count { it in control }}")
      val out=File(System.getenv("POSE_LOG_OUT") ?: "build/pose_log_events.jsonl")
      out.parentFile.mkdirs();out.writeText(text)
      val minute=120*(row(1,message(),"pose").json().toByteArray().size+1)
      println("pose_bytes_per_minute=$minute\npose_example=${row(1,message(),"pose").json()}")
    } finally { f.delete() }
  }
}
