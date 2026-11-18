package com.overhand.app.features

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #960 regression guard. A Material or Mesh write on a visible mesh://box rebuilds its scene object, and the pointer ray
 * can meet it mid-rebuild (SIGSEGV Nov 13 12:41, #944). nov13d still rebuilt ~225 times/min per "building" halo because
 * a SetOnChange keyed on a clock-driven breath step changed every ~0.27 s (#947). Static rules over src/main:
 *  R1  every setComponent(...) that writes a Material or Mesh goes through a SetOnChange `.set(` on the same line,
 *      or carries `// rebuild-ok: <why>` (event-driven, not per frame);
 *  R2  inside a per-frame body (execute, onFrame, tick, animate, motionTick, drawBars) no Material/Mesh write depends on
 *      the clock (`now`, uptimeMillis, elapsedRealtime, breath, phase or a value derived from them);
 *  R3  inside a per-frame body every Entity.create(...) or call to a same-file mesh factory carries `// create-once`.
 */
class PerFrameRebuildGuardTest {
  private val app=File("src/main/java/com/overhand/app")
  /** A line that calls setComponent and names a Material, Mesh, Box or Quad anywhere (so `x.set(e,material) { e.setComponent(it) }` counts). */
  private val kindRe=Regex("""\b(Material|Mesh|Box|Quad)\(|[mM]aterial|\b[mM]esh\b""")
  private fun isWrite(l: String)=l.contains("setComponent(") && kindRe.containsMatchIn(l)
  private val perFrameRe=Regex("""(override fun execute\(\)|override fun onFrame\(|fun tick\(|fun animate\(|fun motionTick\(|fun drawBars\()""")
  private val clockRe=Regex("""\b(now|uptimeMillis|elapsedRealtime|currentTimeMillis|nanoTime|breath\w*|phase)\b""")

  data class Violation(val file: String,val line: Int,val rule: String,val text: String)

  /** Line ranges (0-based, inclusive) of per-frame function bodies. */
  private fun perFrameBodies(lines: List<String>): List<IntRange> {
    val out=mutableListOf<IntRange>()
    lines.forEachIndexed { i,l ->
      if(!perFrameRe.containsMatchIn(l)) return@forEachIndexed
      var depth=0;var opened=false
      for(j in i until lines.size) {
        for(ch in lines[j]) { if(ch=='{') { depth++;opened=true } else if(ch=='}') depth-- }
        if(opened && depth<=0) { out.add(i..j);return@forEachIndexed }
        if(!opened && j>i+2) return@forEachIndexed   // expression body or abstract: nothing to scan
      }
    }
    return out
  }

  fun scan(name: String,text: String): List<Violation> {
    val lines=text.lines();val v=mutableListOf<Violation>()
    lines.forEachIndexed { i,l ->
      if(isWrite(l) && !l.contains(".set(") && !l.contains("// rebuild-ok:")) v.add(Violation(name,i+1,"R1",l.trim()))
    }
    val factories=Regex("""fun (\w+)\([^)]*\)\s*=\s*Entity\.create\(listOf\(Mesh""").findAll(text).map { it.groupValues[1] }.toSet()
    perFrameBodies(lines).forEach { body ->
      val tainted=mutableSetOf("now")
      repeat(3) { body.forEach { k -> Regex("""\b(?:val|var)\s+(\w+)\s*=(.*)""").find(lines[k])?.let { m ->
        val rhs=m.groupValues[2]
        if(clockRe.containsMatchIn(rhs) || tainted.any { Regex("""\b$it\b""").containsMatchIn(rhs) }) tainted.add(m.groupValues[1]) } } }
      body.forEach { k -> val l=lines[k]
        if(isWrite(l) && (clockRe.containsMatchIn(l) || tainted.any { Regex("""\b$it\b""").containsMatchIn(l) }))
          v.add(Violation(name,k+1,"R2",l.trim()))
        val creates=l.contains("Entity.create(") || factories.any { Regex("""(?<![\w.])$it\(""").containsMatchIn(l) && !l.contains("fun $it(") }
        if(creates && k!=body.first && !l.contains("// create-once")) v.add(Violation(name,k+1,"R3",l.trim()))
      }
    }
    return v.distinct()
  }

  private fun sources()=app.walkTopDown().filter { it.isFile && it.extension=="kt" }.map { it.relativeTo(app).path.replace('\\','/') to it.readText() }.toList()

  @Test fun noPerFrameRebuildsInTheApp() {
    val v=sources().flatMap { (n,t) -> scan(n,t) }
    assertTrue("per-frame rebuild writes:\n"+v.joinToString("\n") { "${it.file}:${it.line} ${it.rule} ${it.text}" },v.isEmpty())
  }

  @Test fun theGuardSeesSourcesAndPerFrameBodies() {
    val s=sources().toMap()
    assertTrue(s.size>50)
    val graph=s.getValue("graph/ArchitectureGraph.kt").lines()
    assertTrue("animate/tick/motionTick bodies found",perFrameBodies(graph).size>=3)
  }

  /** The nov13d halo breath (ArchitectureGraph.kt:682-683 before #960) is caught even though it used SetOnChange. */
  @Test fun theNov13dHaloBreathFails() {
    val old="""
      private fun animate(r: Entity,eye: Vector3) {
        val now=android.os.SystemClock.uptimeMillis()
        val step=GraphLod.breathStep(now,HALO_STEPS)
        halos.values.forEach { h -> haloStep.set(h,step) { h.setComponent(haloMaterials[it]) } }
      }
    """.trimIndent()
    assertEquals(listOf("R2"),scan("old.kt",old).map { it.rule })
  }

  @Test fun unguardedWritesAndPerFrameCreatesFail() {
    val src="""
      fun recolour(e: Entity) { e.setComponent(Material().apply { unlit=true }) }
      private fun dot()=Entity.create(listOf(Mesh(Uri.parse("mesh://box"))))
      override fun onFrame(now: Long) {
        val d=dot()
        val e2=Entity.create(listOf(Mesh(Uri.parse("mesh://box")))) // create-once: guarded
      }
    """.trimIndent()
    assertEquals(listOf("R1" to 1,"R3" to 4),scan("x.kt",src).map { it.rule to it.line })
  }

  @Test fun guardedBucketWriteIsAllowed() {
    val src="""
      private fun animate(r: Entity,eye: Vector3) {
        val bucket=(a*4).roundToInt()
        itemMaterial.set(e,material) { e.setComponent(it) }
      }
    """.trimIndent()
    assertTrue(scan("ok.kt",src).isEmpty())
  }
}
