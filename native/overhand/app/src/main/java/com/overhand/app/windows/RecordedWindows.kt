package com.overhand.app.windows

import android.graphics.Color
import android.widget.*
import android.view.View
import android.text.StaticLayout
import android.text.Layout

/** Whole-line native pages. No WebView or transport exists in this path. */
class RecordedWindows(private val content: RecordedContent,private val activity: android.app.Activity) {
  private val probes=mutableMapOf<String,(String)->Unit>()
  fun preview(view: String,value: String) { probes[view]?.invoke(value) }
  private val openers=mutableMapOf<String,(String,String)->Unit>()
  /** #999: one file's code from the graph drill-down, monospace, opened at its changed lines; Previous/Next scroll it. */
  fun openAt(view: String,title: String,code: String) { openers[view]?.invoke(title,code) }
  private val refreshers=mutableListOf<(String)->Unit>()
  /** #1382 look K3 hooks (set by Shell): WindowsFeature.open, WindowsFeature.dockToggle, SiteScene.askPart. */
  var showWindow: (String,String)->Unit={ _,_ -> };var closeWindow: (String,String)->Unit={ _,_ -> }
  var askPart: (String,String,String,String)->Unit={ _,_,_,_ -> }
  private var selected="";private var pendingCode: Pair<String,String>?=null
  fun attach(root: LinearLayout,view: String,initialProject: String) {
    var project=selected.ifBlank { initialProject }.ifBlank { content.defaultProject }
    val assets=root.context.assets
    fun shellText()=assets.open("windows.js").bufferedReader().use { it.readText() }
    fun deny(shell: String)=Regex("'([^']+)'").findAll(shell.substringAfter("const deny = [").substringBefore("];"))
      .map { it.groupValues[1] }.toList()+com.overhand.app.graph.ArchitectureGraph.EXTRA_DENY
    // #1385: the Code window builds its gate and loads its changes off the main thread (q4: 54.7 s frozen here).
    if(view=="code") { attachCode(root,project) { val words=deny(shellText())
      RecordedText.displayer(content.decisionText(),com.overhand.app.graph.GraphText.pattern(words),RecordedText.decisionGate(words)) };return }
    val shell=shellText()
    val gate=com.overhand.app.graph.GraphText.pattern(deny(shell))
    val decisionGate=RecordedText.decisionGate(deny(shell))
    fun show(value: String)=RecordedText.display(value,content.decisionText(),gate,decisionGate)
    val replay=LinearLayout(root.context).apply { orientation=LinearLayout.VERTICAL }
    if(view=="fleet") com.overhand.app.audio.SpatialEarcons.of(activity).replay(replay,content.source.events,content.projects.map { it.getString("id") }.toSet(),content.source.dayLabel)
    val text=com.overhand.app.features.Shell.inset(TextView(root.context).apply { textSize=28f;setTextColor(Color.WHITE) })
    text.tag="recorded-page"
    val status=com.overhand.app.features.Shell.label(root,"",22f).apply { tag="recorded-status" }
    val summary=com.overhand.app.features.Shell.label(root,"",22f).apply { tag="current-work-summary" }
    // #904: current work is one line under the status, beside the title. The status and that line are one tap target
    // (the existing current-work action, still 3 degrees tall) that opens the full task details in the page.
    val work=LinearLayout(root.context).apply { tag="current-work";orientation=LinearLayout.VERTICAL;gravity=android.view.Gravity.CENTER_VERTICAL }
    status.gravity=android.view.Gravity.END;summary.gravity=android.view.Gravity.END
    work.addView(status,LinearLayout.LayoutParams(-1,-2));work.addView(summary,LinearLayout.LayoutParams(-1,-2))
    // #880: the status shares the title's row (end-aligned), so the page keeps that line.
    val title=root.findViewWithTag<TextView>(TITLE_TAG)
    if(title!=null && title.parent===root) {
      val at=root.indexOfChild(title);root.removeViewAt(at)
      val header=LinearLayout(root.context).apply { tag="window-header" }
      header.addView(title,LinearLayout.LayoutParams(-2,-2))
      header.addView(work,LinearLayout.LayoutParams(0,-2,1f))
      root.addView(header,at,LinearLayout.LayoutParams(-1,-2))
    } else root.addView(work)
    root.addView(text,LinearLayout.LayoutParams(-1,0,1f))
    var full=""
    var preview: String?=null
    var page=0
    var ranges=listOf(0 until 0)
    val nav=LinearLayout(root.context).apply { tag="recorded-nav" }
    val previous=Button(root.context).apply { this.text="Previous" }
    val next=Button(root.context).apply { this.text="Next" }
    fun paginate(reset: Boolean=false) {
      val width=text.width-text.paddingLeft-text.paddingRight
      val height=text.height-text.paddingTop-text.paddingBottom
      if(width<=0 || height<=0) return
      val layout=StaticLayout.Builder.obtain(full,0,full.length,text.paint,width)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(true)
        .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE).build()
      ranges=PolishRules.rowPages(full,(0 until layout.lineCount).map { layout.getLineEnd(it) },
        (0 until layout.lineCount).map { layout.getLineBottom(it) },height-text.lineHeight)
      page=if(reset) 0 else page.coerceIn(ranges.indices)
      // A page cut inside a row can end on a wrap's trailing space, which would overhang the box: trim it (display only).
      text.text=full.substring(ranges[page]).trimEnd()
      text.animate().cancel();text.alpha=0f;text.animate().alpha(1f).setDuration(180).start()
      val open=RecordedContent.rows(content.source.decisions()).count { it.optString("status")=="open" && it.optString("project")==project }
      val name=content.projectName(project)
      val scope=if(view in listOf("blueprint","code")) "Selected: $name - $open needs you" else
        "All projects - ${RecordedContent.rows(content.source.decisions()).count { it.optString("status")=="open" }} needs you"
      summary.text=if(view=="chat") "Current work - tap for details" else show("Cutoff ${content.source.planLabel} \u00b7 "+content.currentWork(project).substringBefore("\n"))
      status.text=show("$scope - page ${page+1}/${ranges.size}")
      previous.isEnabled=page>0;next.isEnabled=page<ranges.lastIndex
    }
    work.contentDescription="Current work. Tap for full task details."
    // A tap target: at least 3 degrees tall at the window's distance (#816).
    work.minimumHeight=(PolishRules.WINDOW_TARGET_DP*root.resources.displayMetrics.density).toInt()
    work.isClickable=true
    work.setOnClickListener {
      preview=if(preview==null) (if(view=="chat") "Cutoff ${content.source.planLabel} \u00b7 " else "")+content.currentWork(project,true) else null
      text.typeface=android.graphics.Typeface.DEFAULT
      full=show(preview ?: content.window(view,project));page=0;paginate(true)
    }
    previous.setOnClickListener { if(page>0) { page--;paginate() } }
    next.setOnClickListener { if(page<ranges.lastIndex) { page++;paginate() } }
    nav.addView(previous,LinearLayout.LayoutParams(0,-2,1f))
    nav.addView(next,LinearLayout.LayoutParams(0,-2,1f))
    // #904: blueprint's nav holds only Previous and Next, so its project chips sit between them (one row, not two).
    // Code keeps its own chip row: its nav also carries the primary action.
    val chipsInNav=view=="blueprint"
    if(chipsInNav) {
      previous.layoutParams=LinearLayout.LayoutParams(-2,-2);next.layoutParams=LinearLayout.LayoutParams(-2,-2)
    }
    // #880: fleet's replay step button joins the nav row (like code's and chat's primary action); its label stays above.
    (0 until replay.childCount).map { replay.getChildAt(it) }.filterIsInstance<Button>().forEach { b ->
      replay.removeView(b);nav.addView(b,LinearLayout.LayoutParams(0,-2,2f))
    }
    text.addOnLayoutChangeListener { _,l,t,r,b,ol,ot,or,ob -> if(r-l!=or-ol || b-t!=ob-ot) paginate() }
    fun render() {
      val lead=if(view=="chat") RecordedContent.rows(content.source.decisions())
        .filter { it.optString("status")=="open" }.minByOrNull { it.optInt("judge_order",Int.MAX_VALUE) }
        ?.let(content::chatLead).orEmpty() else ""
      val nextFull=show(preview ?: (lead+content.window(view,project)))
      val changed=nextFull!=full
      full=nextFull
      if(changed) page=0
      text.post { paginate(changed) }
    }
    probes[view]={ value -> preview=value;full=show(value);page=0;text.post { paginate(true) } }
    openers[view]={ title,code -> preview=title+"\n\n"+code;full=show(preview!!);page=0
      text.typeface=android.graphics.Typeface.MONOSPACE;text.post { paginate(true) } }
    // In-panel chips, never a Spinner: its dropdown is a Dialog, which a panel's window context cannot host.
    var picker: ChipRow?=null
    if(view in listOf("blueprint","code")) {
      val projects=content.projects
      picker=ChipRow(root.context,projects.map { content.projectName(it.getString("id")) },
        projects.indexOfFirst { it.getString("id")==project }.coerceAtLeast(0),perRow=0) { i -> project=projects[i].getString("id");render() }
      if(chipsInNav) nav.addView(picker.view,1,LinearLayout.LayoutParams(0,-2,1f)) else root.addView(picker.view)
    }
    if(view=="fleet") root.addView(replay)
    root.addView(nav)
    refreshers.add { id ->
      if(id.isNotBlank()) {
        project=id
        picker?.select(content.projects.indexOfFirst { it.getString("id")==id }.coerceAtLeast(0))
      }
      render()
    }
    render()
    // look K3 (#1374): Fleet and Blueprint as windows.js draws them (look_nov17 s5); the text page above stays, hidden.
    if(view in LookK3.VIEWS) LookK3.mount(root,view,shell,content,project,LookK3.hooks(this))
      ?.let { show -> refreshers.add { id -> show(id) } }
    // look K8 (#1379): the main window opens on the briefing; this paged list stays behind its History button.
    if(view==LookK8.VIEW) LookK8.mount(root,shell,content)?.let { update -> refreshers.add { update() } }
  }
  fun refresh(project: String="") { if(project.isNotBlank()) selected=project;refreshers.forEach { it(project) } }

  private var code: CodeView?=null
  internal val codeView get()=code
  private var codeProject=""
  private var codeModule: String?=null
  private var codeShown=0
  private var codeAll=false
  private var codeBegan=0L
  /** #1385: every project's gated rows, parsed and gated once off the main thread; null until loaded. */
  @Volatile private var codeCache: Map<String,List<CodeDiffs.Row>>?=null
  /** Calls that arrived before the cache: run in order once it is posted to the main thread. */
  private val codePending=mutableListOf<()->Unit>()
  private fun codeRows(p: String)=codeCache?.get(p).orEmpty()
  private val codeTicker=Runnable { codeTick() }
  /** #1371: the Code window: real diffs of the recording, played oldest to newest over the site's day ([PLAY_MS]). */
  private fun attachCode(root: LinearLayout,initial: String,gates: ()->(String)->String) {
    var gate: ((String)->String)?=null
    val v=CodeView(root.context) { s -> gate?.invoke(s) ?: "" };code=v
    root.addView(v.view,LinearLayout.LayoutParams(-1,0,1f))
    v.loading()
    codeBegan=android.os.SystemClock.uptimeMillis()
    openers["code"]={ title,_ -> openCodeFor("",title.substringAfterLast(" > ").trim(),"blueprint") }
    refreshers.add { id -> if(codeCache==null) codePending.add { codeRefresh(id) } else codeRefresh(id) }
    val main=android.os.Handler(android.os.Looper.getMainLooper())
    codeLoader(Runnable {
      val t0=System.nanoTime()
      // Each distinct string goes through the gate once; the caption reuses the same memo on the main thread.
      val seen=java.util.concurrent.ConcurrentHashMap<String,String>()
      val (memo,all,ids)=runCatching {
        val raw=gates()
        val memo: (String)->String={ s -> seen.getOrPut(s) { gateProbe?.invoke(s);raw(s) } }
        val all=CodeDiffs.recordedAll(content.source.diffs,memo)
        Triple(memo,all,content.projects.map { it.getString("id") }+all.keys)
      }.getOrElse { e -> android.util.Log.w("Overhand","code event=ready error=${e.javaClass.simpleName}")
        Triple({ _: String -> "" },emptyMap<String,List<CodeDiffs.Row>>(),emptyList<String>()) }
      android.util.Log.i("Overhand","code event=ready ms=${(System.nanoTime()-t0)/1_000_000} rows=${all.values.sumOf { it.size }} "+
        "lines=${all.values.sumOf { r -> r.sumOf { it.lines.size } }} thread=${Thread.currentThread().name}")
      val ready: ()->Unit={
        gate=memo;codeCache=all
        // A project without recorded changes would open on "No changes yet"; open the first one that has some.
        codeProject=initial.takeIf { codeRows(it).isNotEmpty() } ?: ids.firstOrNull { codeRows(it).isNotEmpty() } ?: initial
        codeTick()
        codePending.toList().also { codePending.clear() }.forEach { it() }
        pendingCode?.let { pendingCode=null;openCodeFor(it.first,it.second,"blueprint") }
      }
      if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper()) ready() else main.post(ready)
    })
  }
  private fun codeTick() {
    val v=code ?: return
    val all=codeRows(codeProject)
    val t0=all.firstOrNull()?.timeMs ?: 0L;val span=((all.lastOrNull()?.timeMs ?: 0L)-t0).coerceAtLeast(1L)
    val f=(android.os.SystemClock.uptimeMillis()-codeBegan).toDouble()/PLAY_MS
    val upTo=if(codeAll) all.size else all.count { (it.timeMs-t0).toDouble()/span<=f }.coerceAtLeast(minOf(1,all.size))
    if(upTo!=codeShown || v.project!=content.projectName(codeProject)) { codeShown=upTo;v.show(content.projectName(codeProject),all.take(upTo),codeModule) }
    v.view.removeCallbacks(codeTicker)
    if(upTo<all.size) v.view.postDelayed(codeTicker,1000L)
  }
  private fun codeRefresh(id: String) {
    if(id.isNotBlank() && codeRows(id).isNotEmpty() && id!=codeProject) { codeProject=id;codeModule=null;codeShown=0;codeTick() }
  }
  /**
   * The one entry point to the Code window (dock, Blueprint, Yard card): [target] is a file (pinned when it changed)
   * or a part/folder (the list is filtered to it). Logs `code event=open why=<dock|blueprint|yard> target=<...>`.
   * Before the changes are loaded the call waits (in order) and runs when they are.
   */
  fun openCodeFor(project: String,target: String?,why: String) {
    val v=code ?: run { if(why=="blueprint") pendingCode=project to target.orEmpty();return }
    if(codeCache==null) { codePending.add { openCodeFor(project,target,why) };return }
    android.util.Log.i("Overhand","code event=open why=$why target=${target ?: v.selected.orEmpty()}")
    if(target.isNullOrBlank() && project.isBlank()) return  // the dock opens the window as it is (playing or pinned)
    if(project.isNotBlank() && project!=codeProject && codeRows(project).isNotEmpty()) { codeProject=project;codeShown=0 }
    val all=codeRows(codeProject)
    val file=target?.takeIf { it.isNotBlank() }?.let { t -> all.firstOrNull { it.path==t || it.path.endsWith("/$t") } }
    codeModule=if(file==null) target?.takeIf { t -> t.isNotBlank() && CodeDiffs.files(all,t).isNotEmpty() } else null
    codeShown=all.size;codeAll=true
    v.show(content.projectName(codeProject),all,codeModule)
    if(file!=null) v.pin(file.path) else if(why!="dock") v.follow()
  }
  companion object {
    /** #1385 test hook: sees every string the Code window sends through the display gate. */
    @Volatile internal var gateProbe: ((String)->Unit)?=null
    /** Where the Code window loads its changes: a background thread; tests may run it inline ([INLINE]). */
    internal val BACKGROUND: (Runnable)->Unit={ Thread(it,"code-load").apply { isDaemon=true }.start() }
    internal val INLINE: (Runnable)->Unit={ it.run() }
    @Volatile internal var codeLoader: (Runnable)->Unit=BACKGROUND
    /** Tag of the window's title label; [attach] puts the status line on the title's row (#880). */
    const val TITLE_TAG="window-title"
    /** The recorded Code window plays its changes over the same span as the site's day (SiteRecorded.PLAY_MS). */
    const val PLAY_MS=75_000L
  }
}
