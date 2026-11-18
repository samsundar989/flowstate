package com.overhand.app.graph

import com.overhand.app.features.V3
import com.overhand.app.hands.Finger
import com.overhand.app.hands.HandJoints
import com.overhand.app.hands.Point
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.abs

class GraphSafetyTest {
  private fun hand(gap: Double): HandJoints {
    val p=Point(0.0,0.0,0.0);val f=Finger(p,p,p,Point(.1,0.0,0.0))
    return HandJoints(p,Finger(p,p,p,Point(.1+gap,0.0,0.0)),listOf(f,f,f,f))
  }
  private fun frame(x: Double,z: Double)=GraphDrill.Framing(Vec(x,0.0,2.4),doubleArrayOf(1.0,0.0,0.0,0.0),z)
  private class Visual(var hittable: Boolean=false,var destroyed: Boolean=false)

  @Test fun fiftyBoxPinchesWithEightyMsDoublesAndHeldPinchesProduceTwentyFiveDrills() {
    val pointer=GraphPointer();val input=GraphDrillInput();val selection=GraphSelection<GraphDrill.Framing>("Project",{ emptyList() })
    var creates=0;var destroys=0;var onsets=0;var accepted=0;var pose=frame(0.0,1.0)
    val pool=GraphEntities<Visual>({ it.hittable=false },{ assertFalse(it.hittable);it.destroyed=true;destroys++ })
    fun sample(t: Long,gap: Double,key: String): Boolean {
      var moved=false
      pointer.update(t,V3(0.0,1.6,0.0),mapOf("right" to hand(gap))) { _,_ -> key }
        .filterIsInstance<GraphPointer.Event.Select>().forEach { ev ->
          onsets++
          if(input.accept(t)) {
            accepted++
            assertTrue(selection.select(ev.target!!.removePrefix("node:"),pose))
            pool.get(ev.target!!) { creates++;Visual() }
            moved=true
          }
        }
      pool.tick();return moved
    }
    repeat(25) { i ->
      val t=i*1000L+10;val key="node:${i%4}"
      sample(t-10,.05,key);assertTrue(sample(t,.01,key))
      val from=pose;val to=frame((i%4).toDouble(),2.0+(i%3))
      sample(t+40,.05,key);assertFalse(sample(t+80,.01,"node:other"))
      var previous=from
      for(dt in 16..400 step 16) {
        assertFalse(sample(t+dt+80,.01,key)) // closed fingers cannot retrigger, even after the move
        val current=GraphDrillMove.sample(from,to,dt/400.0)
        val expectedStep=abs(GraphLod.ease(dt/400.0)-GraphLod.ease((dt-16)/400.0))
        assertTrue(current.distanceTo(previous)<=from.distanceTo(to)*expectedStep+1e-9)
        assertTrue(abs(current.zoom-previous.zoom)<=abs(to.zoom-from.zoom)*expectedStep+1e-9)
        previous=current
      }
      pose=previous
      assertEquals(to.t,pose.t);assertEquals(to.zoom,pose.zoom,1e-9)
      sample(t+600,.05,key)
    }
    assertEquals(50,onsets);assertEquals(25,accepted);assertEquals(4,creates);assertEquals(0,destroys)
    pool.retire();assertEquals(0,destroys);pool.tick();assertEquals(0,destroys);pool.tick();assertEquals(4,destroys)
  }

  @Test fun trackingLossDoesNotReleaseThePinchLatch() {
    val p=GraphPointer();val head=V3(0.0,1.6,0.0)
    fun at(t: Long,j: HandJoints?)=p.update(t,head,mapOf("right" to j)) { _,_ -> "node:a" }.filterIsInstance<GraphPointer.Event.Select>()
    assertEquals(1,at(0,hand(.01)).size)
    assertTrue(at(50,null).isEmpty());assertTrue(at(600,hand(.01)).isEmpty())
    at(620,hand(.05));assertEquals(1,at(650,hand(.01)).size)
  }

  @Test fun retirementUnregistersBeforeDestructionAndWaitsTwoTicks() {
    val pool=GraphEntities<Visual>({ it.hittable=false },{ assertFalse(it.hittable);it.destroyed=true })
    val old=pool.get("root") { Visual(true) };pool.retire()
    assertFalse(old.hittable);assertFalse(old.destroyed)
    val fresh=pool.get("root") { Visual() };assertNotSame(old,fresh)
    pool.tick();assertFalse(old.destroyed);pool.tick();assertTrue(old.destroyed);assertFalse(fresh.destroyed)
  }

  @Test fun rotationUsesTheShortArcWithoutAFinalFrameJump() {
    val a=frame(0.0,1.0);val b=GraphDrill.Framing(a.t,doubleArrayOf(-1.0,0.0,0.0,0.0),1.0)
    for(i in 0..25) assertArrayEquals(a.q,GraphDrillMove.sample(a,b,i/25.0).q,1e-9)
  }
}

/** Covers the native adapter, which cannot be loaded by a JVM. Baseline source makes these checks red. */
class GraphNativeContractTest {
  private val source get()=File(System.getenv("GRAPH_CONTRACT_SOURCE") ?: "src/main/java/com/overhand/app/graph/ArchitectureGraph.kt").readText()
  @Test fun everyVisualMeshExplicitlyOptsOutOfNativeHitTests() {
    val s=source.substringAfter("class GraphScene(")
    val starts=Regex("\\bMesh\\(").findAll(s).map { it.range.last+1 }.toList()
    assertTrue(starts.isNotEmpty())
    starts.forEach { start -> var end=start;var depth=1
      while(depth>0) { if(s[end]=='(') depth++;if(s[end]==')') depth--;end++ }
      assertTrue("Native hit mesh: "+s.substring(start,end),s.substring(start,end).contains("MeshCollision.NoCollision"))
    }
  }
  @Test fun noDirectDestructionOutsideTheRetirementCallback() {
    assertEquals(1,Regex("\\.destroy\\(\\)").findAll(source.substringAfter("class GraphScene(")).count())
    assertTrue(source.contains("entities.retire()"));assertTrue(source.contains("entities.tick()"))
  }
  @Test fun pinchDimmingCannotSwapTheSolidMesh() {
    val s=source.substringAfter("private fun recolourDim()").substringBefore("/** The panel")
    assertFalse(s.contains("GraphMesh.write"));assertFalse(s.contains("sceneGraph.recolour"))
  }
}
