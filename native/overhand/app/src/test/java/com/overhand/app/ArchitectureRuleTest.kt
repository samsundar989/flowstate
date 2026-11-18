package com.overhand.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** ARCHITECTURE.md rule: new features add a file under features/, not lines in OverhandActivity. */
class ArchitectureRuleTest {
  private val app=File("src/main/java/com/overhand/app")

  @Test fun activityStaysAThinDispatcher() {
    val lines=File(app,"OverhandActivity.kt").readLines().size
    assertTrue("OverhandActivity.kt has $lines lines; move feature wiring into features/",lines<=200)
  }

  @Test fun everyFeatureFileIsListedInShell() {
    val shell=File(app,"features/Shell.kt").readText()
    val features=File(app,"features").listFiles()!!.map { it.nameWithoutExtension }.filter { it.endsWith("Feature") }
    assertTrue(features.isNotEmpty())
    features.forEach { assertTrue("$it is not in Shell.features",Regex("""= ?$it\(this\)""").containsMatchIn(shell)) }
  }
}
