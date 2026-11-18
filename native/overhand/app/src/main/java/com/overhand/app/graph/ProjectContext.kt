package com.overhand.app.graph

import org.json.JSONObject

/** Recorded-mode project wording shared by the station panels and the graph header.
 * Project IDs stay routing keys and are never returned as display text. Pure: no Android types. */
object ProjectContext {
  data class Line(val text: String,val scale: Float=1f,val bold: Boolean=false)
  const val FALLBACK="Unnamed recorded project"
  private const val SMALL=.72f
  private const val TITLE=1.2f

  /** Key to label, from the recording's own project rows. A missing name gets an honest
   * placeholder and a duplicate gets its entry number in the recording. */
  fun labels(rows: List<JSONObject>): Map<String,String> {
    val names=rows.map { it.optString("name").trim() }
    val uses=names.filter { it.isNotEmpty() }.groupingBy { it }.eachCount()
    return rows.mapIndexed { i,row ->
      row.getString("id") to when {
        names[i].isEmpty() -> "$FALLBACK ${i+1}"
        uses.getValue(names[i])>1 -> "${names[i]} (recorded entry ${i+1})"
        else -> names[i]
      }
    }.toMap()
  }
  fun label(labels: Map<String,String>,key: String)=labels[key] ?: FALLBACK

  private const val NONE="No plain-words summary is recorded for this project."
  private fun head(v: ProjectView.State?)=v?.let { "What it is: "+it.goal } ?: NONE

  /** Station: the short form. What it is, the newest thing being built, and what needs the wearer. */
  fun stationLines(label: String,selected: Boolean,v: ProjectView.State?,clock: String,notAnchored: Boolean)=
    listOfNotNull(if(selected) Line("Selected project",SMALL,true) else null,Line(label,TITLE,true),Line(head(v),SMALL),
      v?.let { Line(ProjectView.nowLine(it,1),SMALL) },v?.let { Line(ProjectView.needsLine(it,1),SMALL,true) },
      Line("Replay · $clock · partial day",SMALL),
      if(notAnchored) Line("○ Not anchored · saved position",SMALL) else null)
  /** Graph header: the full project view, five plain blocks. */
  fun graphLines(v: ProjectView.State?,clock: String,step: Int,count: Int)=listOfNotNull(
    Line(head(v),1.1f,true),
    v?.let { Line(ProjectView.nowLine(it,2)) },v?.let { Line(ProjectView.doneLine(it,3)) },
    v?.let { Line(ProjectView.stuckLine(it,1)) },v?.let { Line(ProjectView.needsLine(it,1),1f,true) },
    Line("Replay · $clock · event $step of $count across projects · a partial recorded day"))   // #861: no 0.72 span (it read 0.91 deg)
  fun spoken(label: String,selected: Boolean,v: ProjectView.State?,clock: String)=
    "$label${if(selected) ", selected project" else ""}. "+(v?.let { listOf(head(it),ProjectView.nowLine(it,2),
      ProjectView.doneLine(it,3),ProjectView.stuckLine(it,1),ProjectView.needsLine(it,1)).joinToString(" ") } ?: NONE)+
      " Replay time $clock."
}
