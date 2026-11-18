package com.overhand.app.graph

/**
 * t775: what a graph node says at rest. One line of at most [MAX] characters, whole words only, never an ellipsis.
 * Rule, in order (the first step that fits wins):
 *  1. drop a file path's folders ("src/app/Graph.kt" -> "Graph.kt"), bracketed markers and parentheticals;
 *  2. snake, kebab and camel case become words ("featureStack" -> "feature Stack");
 *  3. " and " becomes " & "; the text before the first separator (: | · + / , and prepositions);
 *  4. words from [ABBREVIATIONS] (a checked-in table);
 *  5. the longer of the longest whole-word prefix and suffix that fits (ties go to the suffix, the head noun);
 *  6. a single word still over the limit is cut to MAX-1 letters plus "." (an abbreviation, never mid-line wrap).
 */
object GraphLabels {
  const val MAX=14

  /** Checked-in abbreviation table, applied word by word (case-insensitive) only when a label is too long. */
  val ABBREVIATIONS=linkedMapOf(
    "architecture" to "arch","application" to "app","applications" to "apps","configuration" to "config",
    "evaluation" to "eval","evaluations" to "evals","environment" to "env","experiment" to "exp","experiments" to "exps",
    "infrastructure" to "infra","implementation" to "impl","repository" to "repo","documentation" to "docs",
    "generation" to "gen","submission" to "submit","submissions" to "submits","features" to "feat","feature" to "feat",
    "detection" to "detect","detector" to "detect","selection" to "select","management" to "mgmt","manager" to "mgr",
    "dependencies" to "deps","dependency" to "dep","development" to "dev","information" to "info","interface" to "UI",
    "database" to "DB","authentication" to "auth","navigation" to "nav","performance" to "perf","statistics" to "stats",
    "processing" to "proc","preprocessing" to "preproc","postprocessing" to "postproc","benchmarks" to "bench",
    "benchmark" to "bench","validation" to "valid","pipeline" to "pipe","library" to "lib","utilities" to "utils",
    "transformer" to "xformer","strategy" to "strat","recordings" to "recs","structure" to "struct")
  private val ARTICLES=setOf("the","a","an")
  private val PREPOSITIONS=setOf("from","in","by","for","with","of","on","across","into","via","to","using","per")
  private val SEPARATORS=Regex("""\s*(?::|\||·|\+|,|;|\s/\s|\s-\s|\s–\s|\s—\s)\s*""")

  fun shorten(raw: String,max: Int=MAX,preferSuffix: Boolean=false): String {
    val MAX=max
    var s=raw.trim()
    // 1. A path (no spaces around its slashes) keeps its last segment.
    if(!s.contains(' ') && (s.contains('/') || s.contains('\\'))) s=s.split('/','\\').last { it.isNotBlank() }
    s=s.replace(Regex("""\[[^\]]*]"""),"").replace(Regex("""\([^)]*\)?"""),"").trim()
    // 2. Case and separators to words. Identifiers (no spaces) also split on - and _.
    s=s.replace('_',' ')
    if(!s.contains(' ')) s=s.replace('-',' ')
    s=s.replace(Regex("""(?<=[a-z0-9])(?=[A-Z])""")," ")
    s=s.replace(Regex("""\s+""")," ").trim()
    if(s.isEmpty()) s=raw.trim().ifEmpty { "Component" }
    fun fits(t: String)=t.length<=MAX
    if(fits(s)) return cap(s)
    // 3. "&" for "and"; then the text before the first separator or preposition.
    s.replace(" and "," & ").let { if(fits(it)) return cap(it) }
    val head=SEPARATORS.split(s).firstOrNull { it.isNotBlank() }?.trim() ?: s
    var words=head.split(' ').filter { it.isNotBlank() }
    val cut=words.indexOfFirst { it.lowercase() in PREPOSITIONS || it.lowercase()=="and" || it=="&" }
    if(cut>0) words=words.take(cut)
    words.joinToString(" ").let { if(fits(it)) return cap(it) }
    // 4. Drop articles, then abbreviate from the table.
    words=words.filter { it.lowercase() !in ARTICLES }.ifEmpty { words }
    words.joinToString(" ").let { if(fits(it)) return cap(it) }
    words=words.map { w -> ABBREVIATIONS[w.lowercase()]?.let { a -> if(w.first().isUpperCase() && a.first().isLowerCase()) a.replaceFirstChar { it.uppercase() } else a } ?: w }
    words.joinToString(" ").let { if(fits(it)) return cap(it) }
    // 5. Longest whole-word prefix or suffix (head noun on a tie).
    fun prefix(): String { var out="";for(w in words) { val n=if(out.isEmpty()) w else "$out $w";if(n.length>MAX) break;out=n };return out }
    fun suffix(): String { var out="";for(w in words.asReversed()) { val n=if(out.isEmpty()) w else "$w $out";if(n.length>MAX) break;out=n };return out }
    val p=prefix();val q=suffix()
    val best=if(preferSuffix) (q.ifEmpty { p }) else if(q.length>=p.length) q else p
    if(best.isNotEmpty()) return cap(best)
    // 6. One word longer than the limit: a marked abbreviation.
    return cap(words.first().take(MAX-1)+".")
  }
  private fun cap(s: String)=s.replaceFirstChar { it.uppercase() }

  /** Node type, from the plan's `kind` or, when blank, a keyword rule on id and name. */
  enum class Kind { DATA, MODEL, FEATURE, EVAL, SUBMIT, UI, INFRA, AGENT, MODULE }
  fun kind(kind: String,id: String,name: String): Kind {
    when(kind.lowercase()) {
      "data" -> return Kind.DATA;"model" -> return Kind.MODEL;"feature" -> return Kind.FEATURE;"eval" -> return Kind.EVAL
      "submit" -> return Kind.SUBMIT;"ui" -> return Kind.UI;"infra" -> return Kind.INFRA;"agent" -> return Kind.AGENT
    }
    val t="$id $name".lowercase()
    return when {
      Regex("data|schema|label|fixture|record").containsMatchIn(t) -> Kind.DATA
      Regex("eval|test|check|judge|verif|scor|rubric|instrument").containsMatchIn(t) -> Kind.EVAL
      Regex("model|learn|train|detect|imitation|pilot|engine").containsMatchIn(t) -> Kind.MODEL
      Regex("feature|link|division|postprocess|stack").containsMatchIn(t) -> Kind.FEATURE
      Regex("submi|ship|package|release|leaderboard|final|writeup|report").containsMatchIn(t) -> Kind.SUBMIT
      Regex("panel|window|card|scene|graph|station|view|ui|dive|voice|replay|assets|gesture").containsMatchIn(t) -> Kind.UI
      Regex("agent|bridge|adapter|runtime").containsMatchIn(t) -> Kind.AGENT
      Regex("infra|harness|serv|shell|native|sandbox|scaffold|notes|deck").containsMatchIn(t) -> Kind.INFRA
      else -> Kind.MODULE
    }
  }

  /** Icon strokes in a unit square (-1..1), drawn as rods in the mesh and as lines in the off-device render. */
  fun icon(kind: Kind): List<FloatArray> = when(kind) {
    // Database: three stacked bands and two sides.
    Kind.DATA -> listOf(f(-.7f,.7f,.7f,.7f),f(-.7f,0f,.7f,0f),f(-.7f,-.7f,.7f,-.7f),f(-.7f,.7f,-.7f,-.7f),f(.7f,.7f,.7f,-.7f))
    // Model: a triangle of three connected nodes.
    Kind.MODEL -> listOf(f(0f,.8f,-.75f,-.6f),f(-.75f,-.6f,.75f,-.6f),f(.75f,-.6f,0f,.8f),f(0f,.8f,0f,-.6f))
    // Feature: a funnel.
    Kind.FEATURE -> listOf(f(-.8f,.7f,.8f,.7f),f(-.8f,.7f,-.15f,0f),f(.8f,.7f,.15f,0f),f(-.15f,0f,-.15f,-.8f),f(.15f,0f,.15f,-.8f))
    // Eval: a check mark.
    Kind.EVAL -> listOf(f(-.75f,0f,-.2f,-.6f),f(-.2f,-.6f,.8f,.7f))
    // Submit: an arrow leaving a tray.
    Kind.SUBMIT -> listOf(f(0f,-.3f,0f,.85f),f(-.45f,.4f,0f,.85f),f(.45f,.4f,0f,.85f),f(-.75f,-.2f,-.75f,-.75f),f(-.75f,-.75f,.75f,-.75f),f(.75f,-.75f,.75f,-.2f))
    // UI: a window with a title bar.
    Kind.UI -> listOf(f(-.8f,.7f,.8f,.7f),f(.8f,.7f,.8f,-.7f),f(.8f,-.7f,-.8f,-.7f),f(-.8f,-.7f,-.8f,.7f),f(-.8f,.35f,.8f,.35f))
    // Infra: two stacked server units.
    Kind.INFRA -> listOf(f(-.8f,.75f,.8f,.75f),f(-.8f,.05f,.8f,.05f),f(-.8f,-.05f,.8f,-.05f),f(-.8f,-.75f,.8f,-.75f),
      f(-.8f,.75f,-.8f,-.75f),f(.8f,.75f,.8f,-.75f),f(.35f,.4f,.55f,.4f),f(.35f,-.4f,.55f,-.4f))
    // Agent: head and shoulders.
    Kind.AGENT -> listOf(f(-.25f,.8f,.25f,.8f),f(.25f,.8f,.25f,.3f),f(.25f,.3f,-.25f,.3f),f(-.25f,.3f,-.25f,.8f),
      f(-.75f,-.8f,-.45f,-.05f),f(-.45f,-.05f,.45f,-.05f),f(.45f,-.05f,.75f,-.8f))
    // Module: a cube.
    Kind.MODULE -> listOf(f(-.8f,.4f,.4f,.4f),f(.4f,.4f,.4f,-.8f),f(.4f,-.8f,-.8f,-.8f),f(-.8f,-.8f,-.8f,.4f),
      f(-.8f,.4f,-.4f,.8f),f(.4f,.4f,.8f,.8f),f(.4f,-.8f,.8f,-.4f),f(-.4f,.8f,.8f,.8f),f(.8f,.8f,.8f,-.4f))
  }
  private fun f(vararg v: Float)=v
}
