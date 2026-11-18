package com.overhand.app.graph

import java.io.File

/** One display gate shared by native UI and the host evidence audit. */
object GraphText {
  fun pattern(words: List<String>)=Regex("(?<![A-Za-z0-9])(?:"+
    words.map(Regex::escape).sortedByDescending { it.length }.joinToString("|")+
    ")(?=$|[^A-Za-z0-9]|(?-i:[A-Z]))",RegexOption.IGNORE_CASE)

  @JvmStatic fun main(args: Array<String>) {
    val gate=pattern(File(args[0]).readLines().filter { it.isNotBlank() })
    print(gate.replace(File(args[1]).readText(),"agent"))
  }
}
