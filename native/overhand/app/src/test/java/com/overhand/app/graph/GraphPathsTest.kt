package com.overhand.app.graph

import org.junit.Assert.*
import org.junit.Test

class GraphPathsTest {
  @Test fun pathsMatchFilesOrDirectoryBoundaries() {
    assertTrue(ArchitectureGraph.matchesPath("native/overhand/app/Main.kt","native/overhand"))
    assertTrue(ArchitectureGraph.matchesPath("eval/run.py","eval/"))
    assertTrue(ArchitectureGraph.matchesPath("src/train.py","src/train.py"))
    assertFalse(ArchitectureGraph.matchesPath("native/overhand-old/Main.kt","native/overhand"))
    assertFalse(ArchitectureGraph.matchesPath("src/train.py.bak","src/train.py"))
    assertTrue(ArchitectureGraph.matchesPath("native/overhand/app/Main.kt","native\\overhand"))
  }
  @Test fun wildcardPathsRemainBounded() {
    assertTrue(ArchitectureGraph.matchesPath("src/core/train.py","src/*.py"))
    assertFalse(ArchitectureGraph.matchesPath("tools/train.py","src/*.py"))
    assertTrue(ArchitectureGraph.matchesPath("src/a.kt","src/?.kt"))
    assertFalse(ArchitectureGraph.matchesPath("src/ab.kt","src/?.kt"))
  }
  @Test fun scrubCoversPortfolioIdentifiersAndEscapesAliases() {
    val gate=ArchitectureGraph.displayPattern(listOf("vllm","meta","unity","fly.io"))
    assertEquals("agentConfig agent-vr agent_runtime metadata agent flyXio",
      gate.replace("VllmConfig example-project Unity_runtime metadata fly.io flyXio","agent"))
  }
}
