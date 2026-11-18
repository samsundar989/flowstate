package com.overhand.app.graph

import org.junit.Assert.*
import org.junit.Test

class GraphLayoutTest {
  private val input=listOf(Component("data",emptyList()),Component("app",listOf("data")),Component("test",listOf("data","app")),Component("other",emptyList()))
  @Test fun deterministicAcrossInputAndEdgeOrder() {
    val expected=GraphLayout.build(input)
    repeat(50) { assertEquals(expected,GraphLayout.build(input.shuffled().map { it.copy(after=it.after.shuffled()) })) }
  }
  @Test fun edgesFlowLeftToRight() {
    val m=GraphLayout.build(input);val nodes=m.nodes.associateBy { it.id }
    m.edges.forEach { assertTrue(nodes.getValue(it.from).x<nodes.getValue(it.to).x) }
    assertEquals(3,m.edges.size)
  }
  @Test fun eachNodeRayHitsCorrectComponent() {
    val m=GraphLayout.build(input)
    m.nodes.forEach { assertEquals(it.id,GraphLayout.hit(m,Vec(it.x,it.y,-2.0),Vec(0.0,0.0,1.0))) }
  }
  @Test fun missBehindParallelAndInvalidRays() {
    val m=GraphLayout.build(listOf(Component("one",emptyList())))
    assertNull(GraphLayout.hit(m,Vec(2.0,0.0,-1.0),Vec(0.0,0.0,1.0)))
    assertNull(GraphLayout.hit(m,Vec(0.0,0.0,-1.0),Vec(0.0,0.0,-1.0)))
    assertNull(GraphLayout.hit(m,Vec(0.0,0.0,-1.0),Vec(1.0,0.0,0.0)))
    assertNull(GraphLayout.hit(m,Vec(0.0,0.0,-1.0),Vec(0.0,0.0,0.0)))
    assertNull(GraphLayout.hit(m,Vec(Double.NaN,0.0,0.0),Vec(0.0,0.0,1.0)))
  }
  @Test fun nearestNodeWinsAndEmptyIsEmpty() {
    val m=GraphModel(listOf(GraphNode("far",0,0.0,0.0,1.0),GraphNode("near",0,0.0,0.0,0.0)),emptyList())
    assertEquals("near",GraphLayout.hit(m,Vec(0.0,0.0,-1.0),Vec(0.0,0.0,1.0)))
    assertEquals(GraphModel(emptyList(),emptyList()),GraphLayout.build(emptyList()))
  }
  @Test fun cyclesRetainTopology() {
    val m=GraphLayout.build(listOf(Component("a",listOf("b")),Component("b",listOf("a"))))
    assertEquals(2,m.nodes.size);assertEquals(2,m.edges.size)
  }
  @Test(expected=IllegalArgumentException::class) fun danglingEdgeFailsLoudly() { GraphLayout.build(listOf(Component("a",listOf("missing")))) }
  @Test fun pageCapDoesNotDropModelNodes() {
    val m=GraphLayout.build((0..65).map { Component("c$it",emptyList()) });assertEquals(66,m.nodes.size)
    val n=m.nodes[40]
    assertNull(GraphLayout.hit(m,Vec(n.x,n.y,-2.0),Vec(0.0,0.0,1.0),0))
    assertEquals(n.id,GraphLayout.hit(m,Vec(n.x,n.y,-2.0),Vec(0.0,0.0,1.0),1))
  }
}
