package com.overhand.app

/**
 * #1188 (R356): log lines that carry a project name, a card question or a file path. [priv] is #1127's guard
 * (`BuildConfig.DEBUG`, as on the `heard` line): debug writes the value, release and rc write nothing in its place. The key
 * stays, and so does every scorer mark (`site event=...`, counts, ids). Each builder is the exact line its call site wrote
 * before; in debug the output is byte-identical (PrivateLogReleaseTest checks that).
 */
object PrivateLog {
  fun priv(v: Any?): String = if (BuildConfig.DEBUG) v.toString() else ""

  fun composeOpen(shim: Any?, id: Any?, question: String, options: Int, dist: Double) =
    "open shim=$shim id=$id question=\"${priv(question.take(60))}\" options=$options head_to_panel_m=${"%.3f".format(dist)}"
  fun tapClick(only: Any?, index: Int, text: String?, found: Boolean) = "click view=$only index=$index text=${priv(text.orEmpty())} found=$found"
  fun first60Ready(dataset: Any?, project: String?, decisionId: Any?, yes: Any?, askEndsMs: Any?, ears: Boolean, mic: Boolean) =
    "event=ready dataset=$dataset card=${priv(project)}#$decisionId " + "yes=$yes ask_ends_ms=$askEndsMs ears=$ears mic_permission=$mic"
  fun siteTalkTarget(agent: String, project: String, task: Any?) = "site event=talk_target agent=$agent project=${priv(project)} task=$task"
  fun siteShowOnSite(project: String, part: String, onSite: Boolean) =
    "site event=show_on_site project=${priv(project)} part=${priv(part)} on_site=$onSite"
  fun sitePick(project: String, part: String, simulated: Boolean) =
    "site event=pick project=${priv(project)} part=${priv(part)} then=graph_open" + (if (simulated) " simulated=true" else "")
  fun siteTalkLock(agent: String, project: String, task: Any?, part: String?) =
    "site event=talk_lock agent=$agent project=${priv(project)} task=$task part=${priv(part)}"
  /** `site event=talk mode=<mode> ...`; [rest] is the line's tail after the task (counts, request id, route). */
  fun siteTalk(mode: String, agent: String, project: String, task: Any?, rest: String) =
    "site event=talk mode=$mode agent=$agent project=${priv(project)} task=$task $rest"
  fun drillCode(part: Any?, path: String, line: Int, changed: Int, codeLines: Int) =
    "drill level=CODE part=$part file=${priv(path)} line=$line changed=$changed code_lines=$codeLines"
  fun syntheticRoom(name: String, rooms: Int) = "SYNTHETIC room=${priv(name)} rooms=$rooms"
}
