package com.overhand.app.graph

import org.json.JSONObject

/** Recorded-mode project view in plain words: what the project is, what it is building now, what is
 * done, what is stuck and why, and what needs the wearer. Every sentence is hand-written from rows of
 * assets/judge/real_day.jsonl; [Entry.rows] cites them by line number (1-based). An entry shows once
 * the replay clock reaches its [Entry.from] row and hides again at its [Entry.until] row, so the view
 * moves with the same clock as the graph and the task counts. Pure: no Android types. */
object ProjectView {
  enum class Block { NOW, DONE, STUCK, NEEDS }
  data class Entry(val block: Block,val text: String,val from: Int,val until: Int?,val rows: List<Int>)
  data class Project(val goal: String,val goalRows: List<Int>,val entries: List<Entry>)
  data class State(val goal: String,val now: List<String>,val done: List<String>,val stuck: List<String>,val needs: List<String>)

  private class B(val goal: String,vararg val goalRows: Int) {
    val entries=mutableListOf<Entry>()
    fun now(text: String,from: Int,until: Int?,vararg rows: Int)=entries.add(Entry(Block.NOW,text,from,until,rows.toList()))
    fun done(text: String,from: Int,vararg rows: Int)=entries.add(Entry(Block.DONE,text,from,null,rows.toList()))
    fun stuck(text: String,from: Int,until: Int?,vararg rows: Int)=entries.add(Entry(Block.STUCK,text,from,until,rows.toList()))
    fun needs(text: String,from: Int,until: Int?,vararg rows: Int)=entries.add(Entry(Block.NEEDS,text,from,until,rows.toList()))
    fun build()=Project(goal,goalRows.toList(),entries.toList())
  }

  val PROJECTS: Map<String,Project> = mapOf(
    "s74617e7e4f3c9e64" to B("A hands-first room for supervising AI coding agents, for the Nov 18 headset contest.",80,696).apply {
      now("Rules and judging pages snapshot",55,163,55,163)
      now("A shared format for agent events, with sample recordings",54,247,54,247)
      now("Setting up the game engine for a first hands demo",67,280,67,235,280)
      now("A browser backup: hands and panels on a web page",68,220,68,220)
      now("The experience design: layout, gestures, onboarding",69,269,69,238,269)
      now("Game engine version, part 1: agent panels",182,323,182,237,323)
      now("A scoring checklist for the build",183,357,183,239,302,357)
      now("The film script and entry text",184,256,184,256)
      now("Three sample recordings for the film",299,345,299,345)
      now("Browser version, part 1: agent panels with replay",300,399,300,399)
      now("Browser version, part 2: the 3D task graph",329,522,329,522)
      now("Eleven sound cues",330,560,330,560)
      now("A virtual room scene",331,432,331,432)
      now("Installing the game engine to get a first hands demo onto the headset",406,null,406,599,698)
      now("Scoring the browser version against the checklist",454,550,454,550)
      now("Browser version, part 3: room, sounds and thumbs gestures",455,594,455,594)
      now("Removing brand names from the recorded data so the rules check passes",565,null,565,698)
      done("Rules and judging pages saved",163,55,163,181)
      done("A shared format for agent events",247,54,247,259)
      done("The film script and entry text, first draft",256,184,256,263)
      done("The experience design",269,238,269,271)
      done("Three sample recordings for the film",345,299,345,350)
      done("A scoring checklist of 100 checks",357,302,357,404)
      done("Browser version, part 1: agent panels with replay",399,300,399,452)
      done("A virtual room scene",432,331,432,453)
      done("Browser version, part 2: the 3D task graph",522,329,522,527)
      done("A first score of the browser version",550,454,550,564)
      done("Eleven sound cues",560,330,560,570)
      stuck("Putting the demo on the headset failed: no headset was plugged in and there was no app yet.",284,null,284,294)
      stuck("The game engine is not installed: the owner must approve its install prompts and sign in.",293,486,293,295,303,486)
      stuck("The owner paused the project overnight.",347,378,347,378)
      stuck("Building is on hold until the owner picks a direction; only the headset demo and the brand clean-up continue.",594,null,594,698)
      needs("Say whether you are in the headset developer program, and which headset you own.",40,167,40,167)
      needs("Finish four sign-up steps so the Nov 4 check can pass.",51,378,51,378)
      needs("Decide whether to buy the newer headset the judges use.",194,405,194,405)
      needs("Apply to the headset developer program today.",205,378,205,378)
      needs("Pick how the browser version gets a permanent web address.",215,227,215,227)
      needs("Approve the game engine's install prompts and sign in to it.",303,411,303,411)
      needs("Click two install prompts, sign in to the game engine and plug in the headset.",411,486,411,486)
      needs("Choose the direction for the Nov 18 entry: the agent room (recommended), a focus desk, or a video studio.",696,null,696,698)
    }.build(),
    "sc7e2275fab6a13cd" to B("A coding agent that fixes real bugs in open-source projects, for a coding-agent contest.",91,704).apply {
      now("Serving the large model and measuring its speed",77,null,77,127,423)
      now("Installing the test harness and passing the reference-fix check",78,499,78,423,499)
      now("The agent setup: large model, thinking on, 5.5-minute cap",79,121,79,121)
      now("A held-out test set from projects it never trained on",88,null,88)
      now("A first score on all 129 public tasks",156,null,156,695)
      done("The agent setup, built and checked: one model, six tools",121,79,121,143)
      stuck("The large model server is blocked: the machine does not have enough free memory.",419,null,419,423)
      stuck("Work waits for the owner to choose a direction.",692,null,692,695)
      needs("Finish four sign-up steps: join, grant model access, turn on Linux support, raise the memory cap.",89,null,89,160)
      needs("Free memory and raise the memory cap for the large model server.",419,null,419,423)
      needs("Choose the direction: agent only, add paid training, or add a small-model bonus entry (recommended).",692,null,692,695)
    }.build(),
    "s4a3f3b09ec1c0d92" to B("A map of geothermal faults from geophysics and elevation data, for a fault-mapping challenge.",92,108,648).apply {
      now("A scorer and test splits for measuring fault maps",94,126,94,126)
      now("A data card for the challenge files",95,138,95,111,138)
      now("A reference baseline and a first valid submission",101,149,101,114,149)
      now("Training scaffolding, tested on made-up data",152,393,152,393)
      now("Elevation features from 1 m lidar, tried on one tile",153,444,153,444)
      now("Edge features from gravity and magnetic data",394,710,394,710)
      now("The reference baseline on the real data, then a first valid submission",421,540,421,540)
      now("The reference baseline on two kinds of test split, then a first valid submission",545,null,540,545,650)
      now("A data card on the real files",422,472,422,472)
      now("Downloading elevation tiles and building the lidar feature map",478,711,478,711)
      now("Features from known faults: tips, step-overs, density",513,537,513,537)
      now("One feature stack built from several data sources",552,592,552,592)
      now("Per-fold structure features, so test labels cannot leak",609,null,607,609)
      done("A scorer and test splits",126,94,126,148)
      done("Training scaffolding",393,152,393,416)
      done("Lidar elevation features, prototype",444,153,444,447)
      done("A data card on the real files",472,422,472,476)
      done("Features from known faults",537,513,537,539)
      done("One feature stack from several sources",592,552,592,607)
      stuck("No data on disk: the owner had to join the challenge and download it.",102,425,102,110,159,425)
      stuck("The first real baseline waits for the owner to choose the approach.",650,null,650)
      needs("Join the challenge, finish the eligibility check and download the data.",92,425,92,159,425)
      needs("Choose the approach: geophysics model, plus lidar scarp features (recommended), or plus self-training.",648,null,648,650)
    }.build(),
    "s04f0600b6c43c5ad" to B("Identify unknown molecules by ranking candidates from a database, for a molecule identification challenge.",86,103,104).apply {
      now("Nothing running: five starter tasks are queued",85,null,85,86,87,99,100,103,597)
      done("Joined the challenge with the owner's account",197,197,198,597)
      stuck("Parked by the Chief until about Nov 20.",116,null,116,597)
      needs("Join the challenge and accept its rules; the data stays locked until then.",104,197,104,197)
    }.build(),
    "s523af42b36935d2d" to B("Beat the public best score (about 24) in a circuit compilation contest, starting from the baseline of 1.0.",70,73).apply {
      now("Nothing running: three starter tasks are queued",65,null,65,66,70,598)
      stuck("Waiting for the owner to join the contest and fix the access key.",71,null,71,598)
      needs("Join the contest, fix the access key, and say when workers should start.",71,null,71,598)
    }.build(),
    "s1f2fd986ca050085" to B("Measure how athletes move and link it to how they play, for a sports tracking hackathon due Jan 6, 2027.",58,59,64).apply {
      now("Four starter tasks are queued",56,201,56,57,58,59,201)
      now("The data is downloading; four starter tasks are queued",201,null,201)
      done("Rules saved and the Jan 6, 2027 deadline checked",62,62,64)
      done("Registered for the hackathon",177,177,201)
      stuck("Idle on purpose until the week of Dec 14.",64,null,64,201)
      needs("Accept the hackathon rules so the data can download.",60,177,60,177)
    }.build(),
  )

  /** A demo history's view.json (#759): the same blocks, rows cited 1-based in its day.jsonl. */
  fun fromJson(v: JSONObject): Project {
    fun ints(a: org.json.JSONArray?)=(0 until (a?.length() ?: 0)).map { a!!.getInt(it) }
    val e=v.getJSONArray("entries")
    return Project(v.getString("goal"),ints(v.optJSONArray("goal_rows")),(0 until e.length()).map { e.getJSONObject(it) }.map {
      val block=Block.valueOf(it.getString("block"))
      // NOW and DONE are joined as a "; " list (nowLine/doneLine), so a sentence's final period would read ".;".
      Entry(block,if(block==Block.NOW || block==Block.DONE) it.getString("text").trimEnd('.') else it.getString("text"),it.getInt("from"),if(it.isNull("until")) null else it.getInt("until"),ints(it.optJSONArray("rows"))) })
  }

  /** The view at replay time [tMs]; rowTime maps a 1-based row to its recorded t_ms. */
  fun state(project: String,tMs: Long,rowTime: (Int)->Long,projects: Map<String,Project> =PROJECTS): State? {
    val p=projects[project] ?: return null
    fun shown(e: Entry)=rowTime(e.from)<=tMs && (e.until==null || rowTime(e.until)>tMs)
    // Most recently started first: that is what "now" means to a reader.
    fun of(b: Block)=p.entries.filter { it.block==b && shown(it) }.sortedByDescending { it.from }.map { it.text }
    return State(p.goal,of(Block.NOW),of(Block.DONE),of(Block.STUCK),of(Block.NEEDS))
  }
  fun rowTimes(rows: List<JSONObject>): (Int)->Long { val t=rows.map { it.getLong("t_ms") };return { t[it-1] } }

  fun nowLine(s: State,max: Int)=if(s.now.isEmpty()) "Now: nothing running at this point of the day."
    else "Now: "+list(s.now.take(max))+more(s.now.size-max)+"."
  fun doneLine(s: State,max: Int)=if(s.done.isEmpty()) "Done: nothing finished yet."
    else "Done (${s.done.size}): "+list(s.done.take(max))+more(s.done.size-max)+"."
  fun stuckLine(s: State,max: Int)=if(s.stuck.isEmpty()) "Stuck: nothing at this point."
    else "Stuck: "+s.stuck.take(max).joinToString(" ")
  fun needsLine(s: State,max: Int)=if(s.needs.isEmpty()) "Needs you: nothing right now."
    else "Needs you: "+s.needs.take(max).joinToString(" ")+(if(s.needs.size>max) " (${s.needs.size-max} more)" else "")
  /** "A; b; c": later items lose their capital unless their first word is an acronym. */
  private fun list(items: List<String>)=items.mapIndexed { i,t -> val w=t.substringBefore(' ')
    if(i>0 && (w.length==1 || w.drop(1).any { it.isLowerCase() })) t[0].lowercase()+t.substring(1) else t }.joinToString("; ")
  private fun more(n: Int)=if(n>0) "; and $n more" else ""
}
