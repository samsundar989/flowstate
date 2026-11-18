package com.overhand.app.decisions

import android.app.Activity
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.util.Log
import android.view.View
import android.widget.*
import com.overhand.app.BuildConfig
import com.overhand.app.FirstMinutes
import com.overhand.app.hands.*
import org.json.*
import java.net.*
import java.util.concurrent.Executors

/** Native Views inside a Spatial panel. All mutable UI/pose state lives on the Android thread. */
class DecisionCards(private val activity: Activity,private val server: () -> String,
  private val source: () -> com.overhand.app.data.SessionSource,
  private val generation: com.overhand.app.data.ModeGeneration,
  private val showPanel: (Boolean) -> Unit) {
  private val ui=Handler(Looper.getMainLooper())
  private val io=Executors.newSingleThreadExecutor { r -> Thread(r,"decision-request").apply { isDaemon=true } }
  private val gate=PoseHoldGate()
  private val onboarding=com.overhand.app.onboarding.HandsOnboarding.get(activity)
  val trials=PoseTrialLogger(activity)
  private var root: LinearLayout?=null
  private var content: LinearLayout?=null
  private var status: TextView?=null
  private val board=DeliveryBoard()
  private var tracking: TextView?=null
  private var ring: HoldRing?=null
  /** #800: every pinch on the card leaves an OverhandTouch line; handlers say what it did. */
  private val touch=CardTouchTrace("decision")
  /** #874: draw the card with Compose (FlowstateDecisionCard). A debug Settings switch, off by default; the rules stay here. */
  var compose=false
    set(value) { if(field==value) return;field=value;Log.i(TAG,"compose card on=$value");render() }
  private var composeCard: ComposeCard?=null
  /** The last Compose frame render() drew; processFrame adds the hold ring and status to it. */
  private var composeBase: ComposeCard.Frame?=null
  private var pulses=0
  /** #1373: the reading view, history, Skip and tutorial sit behind More on the question card. */
  private var more=false
  private var cardState: CardState?=null
  /** Why the next state change happens: pinch, confirm, thumb, next or close (round rule 7). */
  private var stateWhy="open"
  private fun logState(to: String) {
    if(to==(cardState?.name?.lowercase() ?: "hidden")) return
    Log.i(TAG,QuestionCard.stateLine(cardState,to,stateWhy))
    cardState=CardState.values().firstOrNull { it.name.lowercase()==to }
  }
  private var holdProgress=0f
  private var holdSaves: String?=null
  private var look=false
  /** #1033: a thumbs-up seen on the card with nothing selected; the card says to select first instead of filling the ring. */
  private var pick=false
  private var holdShown=false
  /** Debug frame record: what the card's thumb gate saw on the last frame. */
  var gateState=""
    private set
  private var trackedHands=0
  private var historyExpanded=false
  /** Live answers confirmed this session; Recorded counts its stored practice receipts instead. */
  private var answeredHere=0
  private var receipt: PracticeReceipt?=null
  private val confirmGuard=ConfirmGuard()
  private var list=listOf<JSONObject>()
  private var current=0
  private var selected=-1
  private var visible=false
  private val dismissed=mutableSetOf<String>()
  /** false: the card opens only when asked (Recorded opening scene); no tutorial or blocking pop-up. */
  @Volatile var autoOpen=true
  private var pollBusy=false
  private var simulationRunning=false
  private var fixturePreview: String?=null
  private var trialRunning=false
  private var cue=""
  private var trialGeneration=0
  private var lastPoses=mapOf<String,ThumbPose>()
  private val seenBlocking=mutableSetOf<String>()
  @Volatile private var stopped=false
  @Volatile private var stream: HttpURLConnection?=null
  private var streamThread: Thread?=null
  private fun text(value: String,size: Float=32f)=TextView(activity).apply {
    text=clean(value);textSize=size;setTextColor(Color.WHITE);com.overhand.app.windows.PolishRules.labelInset(this)
  }
  /** A card control. Its trace outcome defaults to its label; a handler that blocks reports none:<reason> itself. */
  private fun button(value: String,action: () -> Unit)=Button(activity).apply {
    text=clean(value);textSize=30f;setOnClickListener { touch.report(CardTouchTrace.slug(text.toString()));action() }
  }
  /** A pinch that cannot do what it aimed at: say why on the card and in the trace, never a silent return. */
  private fun refuse(reason: String,text: String?) { touch.report("none:$reason");text?.let { message(it,true) } }
  // Same displayed-text denylist as the existing shell. Preserve raw option bytes for POST.
  private val deny by lazy {
    val source=activity.assets.open("windows.js").bufferedReader().use { it.readText() }
    val part=source.substringAfter("const deny = [").substringBefore("];")
    val words=Regex("'([^']+)'").findAll(part).map { Regex.escape(it.groupValues[1]) }.toList()
    Regex("\\b(?:"+words.sortedByDescending { it.length }.joinToString("|")+")\\b",RegexOption.IGNORE_CASE)
  }
  private fun clean(value: String)=deny.replace(value,"agent")
  init {
    LiveSafety.sendReal=false
    onboarding.onPracticeThumb={
      decision()?.let { d -> val opts=options(d);if(selected in opts.indices) { gate.reset();post(d,opts[selected],"thumbs_up") } }
    }
    onboarding.onRehearsalDone={ finishRehearsal() }
    onboarding.onProbeHold={ debugHeldThumb() }
  }
  /** t1282: a clickable control at fractions (0..1) of the card panel (the grab path's one-owner check). */
  fun clickableAt(fx: Double,fy: Double)=touch.clickableAt(fx,fy)
  fun attach(column: LinearLayout) {
    com.overhand.app.audio.SpatialEarcons.of(activity).controls(column)
    // The traced layer fills the panel root, so its coordinates are the panel's.
    val traced=touch.Layout(activity)
    column.removeAllViews();column.addView(traced,LinearLayout.LayoutParams(-1,-1))
    root=traced
    touch.namer={ x,y -> composeCard?.takeIf { compose && it.view.parent===traced }?.let { c -> c.nameAt(x-c.view.left,y-c.view.top) } }
    render()
  }
  fun start() {
    stopped=false
    // Restart: answers whose commit was never confirmed come back unresolved and are only re-checked.
    (source() as? com.overhand.app.data.LiveSource)?.let { board.restore(it.pendingAnswers());showNotice() }
    refresh()
    if(source().recorded) return
    val modeToken=generation.token
    streamThread=Thread({
      while(!stopped && generation.accepts(modeToken)) {
        try {
          val base=server()
          val c=URL(base+"/api/stream").openConnection() as HttpURLConnection
          stream=c;c.connectTimeout=2000;c.readTimeout=15000;c.setRequestProperty("Accept","text/event-stream")
          try {
            if(c.responseCode!=200) throw IllegalStateException("stream HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { input ->
              val data=StringBuilder()
              while(!stopped && generation.accepts(modeToken)) {
                val line=input.readLine() ?: break
                if(line.startsWith("data:")) data.append(line.substringAfter(":").trimStart()).append('\n')
                else if(line.isEmpty() && data.isNotEmpty()) {
                  val payload=data.toString();data.clear()
                  val snap=runCatching { JSONObject(payload) }.getOrNull()
                  val rows=snap?.optJSONArray("decisions")
                  if(rows!=null) ui.post { if(!stopped && generation.accepts(modeToken) && server()==base) { accept((source() as? com.overhand.app.data.LiveSource)?.adopt(rows,base) ?: rows);reconcileUnresolved() } } else ui.post { if(generation.accepts(modeToken)) refresh() }
                }
              }
            }
          } finally {
            c.disconnect();stream=null
            if(!stopped && generation.accepts(modeToken)) {
              (source() as? com.overhand.app.data.LiveSource)?.markStale()
              ui.post { if(generation.accepts(modeToken)) { board.stale("Live data is stale; reconnect before answering.");showNotice();refresh() } }
            }
          }
        } catch(_: Exception) {
          if(!stopped && generation.accepts(modeToken)) {
            (source() as? com.overhand.app.data.LiveSource)?.markStale()
            ui.post { if(generation.accepts(modeToken)) { board.stale("Live data is stale; reconnect before answering.");showNotice();refresh() } }
          }
        }
        // Bounded reconnect; interrupted on close.
        try { Thread.sleep(2000) } catch(_: InterruptedException) { break }
      }
    },"decision-stream").apply { isDaemon=true;start() }
  }
  fun refresh() {
    if(pollBusy || stopped) return
    pollBusy=true
    val captured=source();val modeToken=generation.token
    io.execute {
      if(stopped || !generation.accepts(modeToken)) return@execute
      val result=runCatching { captured.decisions() }
      ui.post {
        if(stopped || !generation.accepts(modeToken)) return@post
        pollBusy=false
        result.fold({ accept(it);reconcileUnresolved() }, { board.loadFailed("Unable to load decisions. Live data is stale; reconnect before answering.");showNotice() })
      }
    }
  }
  fun serverChanged() {
    stream?.disconnect();streamThread?.interrupt();board.reset();pollBusy=false
    list=emptyList();loaded=false;selected=-1;gate.reset();seenBlocking.clear();render();start()
  }

  private fun accept(rows: JSONArray) {
    val oldId=fixturePreview ?: decision()?.optString("id")
    val next=(0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.filter {
      it.optString("status","open")=="open" && (!FirstMinutes.isFixture(it.optString("question")) ||
        (BuildConfig.DEBUG && it.optString("id")==fixturePreview))
    }.let { rows -> if(source().recorded) rows.sortedBy { it.optInt("judge_order",Int.MAX_VALUE) } else rows }
    val blocking=next.filter { it.optBoolean("blocking") || it.optInt("blocking")==1 }.map { it.optString("id") }.filter { it !in seenBlocking && it !in dismissed }
    val changed=next.toString()!=list.toString()
    list=next;loaded=true
    current=(list.indexOfFirst { it.optString("id")==oldId }).coerceAtLeast(0)
    if(changed && !board.busy && !trialRunning) { selected=-1;render() }
    if(autoOpen && next.isNotEmpty() && onboarding.needsIntroduction(source().recorded) && !visible && next.any { it.optString("id") !in dismissed }) open()
    if(autoOpen && blocking.isNotEmpty() && !trialRunning && !board.busy && receipt==null) {
      seenBlocking.addAll(blocking)
      current=list.indexOfFirst { it.optString("id")==blocking.first() }.coerceAtLeast(0)
      open()
    }
  }
  private fun decision()=list.getOrNull(current)
  /** #1281: what the open card shows, for the spoken opening: its decision id and option labels. */
  data class Shown(val id: String,val options: List<String>)
  /** Null while hidden, on a receipt or the trials, or until every option of the card's question is a laid-out view. */
  fun shown(): Shown? {
    if(!visible || receipt!=null || trialRunning || trials.enabled) return null
    val d=decision() ?: return null
    val opts=options(d)
    if(opts.isEmpty()) return null
    if(composeBase==null) {
      fun tagged(v: View): List<View> = if(v.tag==com.overhand.app.onboarding.HandsOnboarding.OPTION_TAG) listOf(v)
        else if(v is android.view.ViewGroup) (0 until v.childCount).flatMap { tagged(v.getChildAt(it)) } else emptyList()
      val drawn=root?.let(::tagged).orEmpty()
      if(drawn.size!=opts.size || drawn.any { !it.isLaidOut }) return null
    }
    return Shown(d.optString("id"),opts)
  }
  /** #1281: the open decisions' ids in card order; null until the source has loaded once. */
  fun openIds(): List<String>?=if(loaded) list.map { it.optString("id") } else null
  /** #1281: put the card on an open decision by id. False when it is not open, or a receipt or a save holds the card. */
  fun showDecision(id: String): Boolean {
    val i=list.indexOfFirst { it.optString("id")==id }
    if(i<0 || receipt!=null || board.busy || trialRunning) return false
    if(i!=current) { current=i;selected=-1;gate.reset();render() }
    return true
  }
  private var loaded=false
  private fun options(d: JSONObject)=d.optJSONArray("options")?.let { a ->
    (0 until a.length()).map { i -> a.optJSONObject(i)?.optString("label") ?: a.optString(i) }
  } ?: emptyList()
  private fun recommended(d: JSONObject,opts: List<String>): Int {
    val rec=d.optString("recommendation").ifBlank { d.optString("default_option") }
    if(rec.isBlank()) return -1
    return opts.indexOfFirst { it==rec || it.substringBefore(':').trim()==rec || it.substringBefore(' ').trimEnd('.',')')==rec }
  }
  fun open() {
    board.clear()
    // Only a replayed tutorial may replace an open receipt; reopening the panel must not lose it.
    if(onboarding.guiding(source().recorded)) dropReceipt()
    visible=true;showPanel(true);gate.reset();render();refresh()
  }
  private fun hide() { dismissed.addAll(list.map { it.optString("id") }); if(cardState!=null) { stateWhy="close";logState("hidden") };more=false;disposePager();if(source().recorded) selected=-1;dropReceipt();visible=false;showPanel(false);gate.reset();fixturePreview=null;refresh() }
  private fun dropReceipt() { receipt=null;confirmGuard.acknowledge() }
  private fun navigate(delta: Int) {
    when {
      board.busy -> return refuse("busy",ConfirmBlock.BUSY)
      trialRunning -> return refuse("trials",null)
      list.isEmpty() -> return refuse("no_cards",null)
      receipt!=null -> return refuse("receipt",ConfirmBlock.SAVED)
    }
    touch.report(if(delta<0) "previous" else "next_card")
    board.clear()
    current=(current+delta+list.size)%list.size;selected=-1;gate.reset();render()
  }
  private var activePager: ReadingPages?=null
  private fun disposePager() { activePager?.dispose();activePager=null }
  private fun render() {
    disposePager()
    val column=root ?: return
    val guided=decision()!=null && !trials.enabled && !trialRunning && onboarding.guiding(source().recorded)
    // The tutorial, the pose trials and the empty list keep the View card (HandsOnboarding walks its Views).
    if(compose && !guided && !trialRunning && !trials.enabled && (receipt!=null || decision()!=null)) return renderCompose(column)
    composeBase=null
    if(!trials.enabled && !trialRunning && (receipt!=null || decision()!=null)) {
      // #1373: the question card is the default; the tutorial keeps its own view while it runs (its reducer walks it).
      if(lookCard && !more) return renderLook(column)
      renderPaged(column,guided);return
    }
    column.removeAllViews()
    // Equal-height regions keep every frequent target below the physical midpoint.
    val upper=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;tag="card-context" }
    val lower=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;tag="card-actions" }
    column.addView(upper,LinearLayout.LayoutParams(-1,0,1f))
    column.addView(lower,LinearLayout.LayoutParams(-1,0,1f))
    // #881: the receipt gets the whole upper half, so its "where it went" line is never scrolled away.
    val showingReceipt=receipt!=null && !trials.enabled && !trialRunning
    if(guided) upper.addView(text(com.overhand.app.RecordedFirstCard.PURPOSE,28f))
    else if(!showingReceipt) {
      upper.addView(text("Decisions",36f).apply { setPadding(paddingLeft,0,paddingRight,0) })
      if(source().recorded) upper.addView(text((source() as? com.overhand.app.data.RecordedSource)?.badge ?: com.overhand.app.data.RecordedSource.BADGE,22f).apply { setPadding(paddingLeft,0,paddingRight,0) })
    }
    val scroll=ScrollView(activity);content=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
    scroll.addView(content);upper.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
    if(trialRunning) { content!!.addView(text(cue,42f));lower.addView(button("Close") { if(!trialRunning) hide() }); return }
    ring=null
    receipt?.takeIf { !trials.enabled }?.let { renderReceipt(it,lower);return }
    if(trials.enabled) {
      content!!.addView(text("Practice only: no live answers are sent. Release your hand between cues.",28f))
      content!!.addView(button("Start 20 trials per pose") { startTrials() })
      content!!.addView(button("Begin 60 s rest (hands visible)") { startRest() })
      lower.addView(button("Close") { if(!trialRunning) hide() })
      return
    }
    val d=decision()
    if(d==null) {
      content!!.addView(text("No open decisions"))
      lower.addView(button("Close") { if(!board.busy && !trialRunning) hide() })
    } else {
      val opts=options(d)
      // Recorded always waits for an explicit pick; the recommendation stays advice.
      if(selected !in opts.indices) selected=if(source().recorded) -1 else recommended(d,opts)
      // #925: numbering stays stable across answers ("Question 2 of 11 · 1 practiced so far"), never restarting at 1.
      val answered=if(source().recorded) source().decisions().let { r -> (0 until r.length()).count { r.getJSONObject(it).has("practice_receipt") } } else answeredHere
      // #905: a long question steps down a size so it and its recommendation show whole above the context line.
      val heading=com.overhand.app.RecordedFirstCard.position(current+1,list.size,answered,source().recorded,
        if(source().recorded) com.overhand.app.RecordedFirstCard.blocking(list) else 0)+" | "+d.optString("question",d.optString("title"))
      content!!.addView(text(heading,if(heading.length>LONG_QUESTION) 28f else 36f).apply { setPadding(paddingLeft,0,paddingRight,0) })
      content!!.addView(text("Recommendation: "+(if(d.isNull("recommendation")) "None" else d.optString("recommendation","None")),24f).apply { setPadding(paddingLeft,0,paddingRight,0) })
      if(!historyExpanded) fitContext(scroll,content!!)
      upper.addView(text(com.overhand.app.RecordedFirstCard.askedBy(d.optString("project_name",d.optString("project")),d.optString("agent_name","Agent"),source().recorded),22f))
      val optionScroll=ScrollView(activity)
      val optionRow=LinearLayout(activity)
      opts.forEachIndexed { i,label -> optionRow.addView(button(label) { selectOption(d,i) }.apply { tag=com.overhand.app.onboarding.HandsOnboarding.OPTION_TAG;OptionGlow.apply(this,i==selected) },LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(8,8,8,8) }) }
      optionScroll.addView(optionRow);lower.addView(optionScroll,LinearLayout.LayoutParams(-1,0,1f))
      fitOptions(optionScroll,optionRow)
      val answerRow=LinearLayout(activity)
      answerRow.addView(button("Confirm selected option") { confirmSelected(d,opts) }.apply { tag=QuestionCard.TAG_CONFIRM;
        if(selected !in opts.indices) alpha=0.55f else if(guided) foreground=accent()
      },LinearLayout.LayoutParams(0,-2,1f))
      ring=HoldRing(activity).also { it.label=if(selected in opts.indices) letter(opts[selected]) else ""; answerRow.addView(it,LinearLayout.LayoutParams(96,96).apply { gravity=android.view.Gravity.CENTER_VERTICAL }) }
      if(guided) answerRow.addView(button("Skip") { onboarding.skip();selected=-1;gate.reset();render() })
      answerRow.addView(button("Close") { closeCard() })
      lower.addView(answerRow)

    }
    status=text(board.notice?.text ?: IDLE,24f)
    if(board.notice?.error==true) status?.setTextColor(Color.rgb(255,100,100))
    if(guided && board.notice==null) status?.visibility=View.GONE
    // #1033: both commit gestures, as icons beside the six-word hint (pinch Confirm, or hold thumbs up).
    if(d!=null && !guided) upper.addView(LinearLayout(activity).apply {
      gravity=android.view.Gravity.CENTER_VERTICAL;tag=GESTURE_HINT_TAG
      addView(com.overhand.app.onboarding.GestureGuide(activity,true,compact=true),LinearLayout.LayoutParams(56,48))
      addView(com.overhand.app.onboarding.GestureGuide(activity,false,compact=true),LinearLayout.LayoutParams(56,48))
      addView(status,LinearLayout.LayoutParams(0,-2,1f))
    }) else upper.addView(status)
    // One instruction at a time on the guided card; the tracking caveat and browsing return after it.
    tracking=if(guided) null else text(trackingText(),22f).apply { visibility=if(trackedHands==0) View.VISIBLE else View.GONE }
    tracking?.let { upper.addView(it) }
    val nav=LinearLayout(activity)
    nav.addView(button("Previous") { navigate(-1) });nav.addView(button("Next") { navigate(1) })
    nav.addView(button("Refresh") { refreshCard() })
    if(!guided) lower.addView(nav)
    if(source().recorded) {
      val rows=source().decisions()
      val history=(0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.has("historical_answer") }
      val practice=(0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.has("practice_receipt") }
      if(history.isNotEmpty() || practice.isNotEmpty()) {
        val count="Answer history"
        nav.addView(button(count+" "+if(historyExpanded) "⌄" else "›") { toggleHistory() })
        // Stored receipts stay behind this control and name their own question, never the open card's.
        if(historyExpanded) {
          practice.forEach { row ->
            content!!.addView(text(com.overhand.app.RecordedFirstCard.SAVED_TITLE+" · "+row.optString("question")+"\n"+
              com.overhand.app.RecordedFirstCard.savedChoice(row.getJSONObject("practice_receipt").optString("option_label"))+". No live agent was contacted.",24f))
          }
          history.forEach { row ->
            content!!.addView(text("Original run choice · "+row.optString("question")+"\n"+row.optString("historical_answer"),24f))
          }
        }
      }
    }
    if(d!=null) onboarding.decorate(column,source().recorded)
  }
  // Reading state is independent of decision/answer state. Only changing the source resets it.
  private var readingKey=""
  private var readingPage=0
  private fun renderPaged(column: LinearLayout,guided: Boolean) {
    column.removeAllViews();ring=null;tracking=null;status=null
    val d=decision();val r=receipt
    val key=(r?.let { "receipt:${it.question}" } ?: d?.optString("id").orEmpty())+"/$historyExpanded/${board.notice?.text.orEmpty()}"
    if(readingKey!=key) { readingKey=key;readingPage=0 }
    val upper=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;tag="card-context" }
    val lower=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;tag="card-actions" }
    column.addView(upper,LinearLayout.LayoutParams(-1,0,1f))
    column.addView(lower,LinearLayout.LayoutParams(-1,0,1f))
    upper.addView(text(if(source().recorded) "Recorded · Practice only" else "Live · "+if(com.overhand.app.decisions.LiveSafety.sendReal) "Sends enabled" else "Practice only",22f))
    val fields=mutableListOf<Pair<String,String>>()
    board.notice?.let { fields.add("Notice" to it.text) }
    val opts=d?.let(::options).orEmpty()
    if(r!=null) {
      fields.add("Receipt" to com.overhand.app.RecordedFirstCard.SAVED_TITLE)
      fields.add(com.overhand.app.RecordedFirstCard.SAVED_QUESTION to r.question)
      fields.add(com.overhand.app.RecordedFirstCard.SAVED_ANSWER to r.option)
      fields.add("Destination" to r.context)
      fields.add(com.overhand.app.RecordedFirstCard.SAVED_WHERE to com.overhand.app.RecordedFirstCard.SAVED_DETAIL)
    } else if(d!=null) {
      if(selected !in opts.indices) selected=if(source().recorded) -1 else recommended(d,opts)
      val answered=if(source().recorded) source().decisions().let { rows -> (0 until rows.length()).count { rows.getJSONObject(it).has("practice_receipt") } } else answeredHere
      fields.add("Question" to (com.overhand.app.RecordedFirstCard.position(current+1,list.size,answered,source().recorded,
        if(source().recorded) com.overhand.app.RecordedFirstCard.blocking(list) else 0)+" | "+d.optString("question",d.optString("title"))))
      fields.add("Recommendation" to if(d.isNull("recommendation")) "None" else d.optString("recommendation","None"))
      fields.add("Asked by" to com.overhand.app.RecordedFirstCard.askedBy(d.optString("project_name",d.optString("project")),d.optString("agent_name","Agent"),source().recorded))
      opts.forEachIndexed { i,label -> fields.add("Option ${i+1}" to label) }
      fields.add("How to answer" to IDLE)
      if(trackedHands==0) fields.add("Hands" to trackingText())
      if(guided) { fields.add("Practice" to com.overhand.app.RecordedFirstCard.PURPOSE);fields.add("Practice instructions" to if(onboarding.rehearsing()) com.overhand.app.onboarding.OnboardingFlow.REHEARSAL else com.overhand.app.RecordedFirstCard.PRACTICE) }
      if(historyExpanded) historyRows().second.forEachIndexed { i,line -> fields.add("Answer history ${i+1}" to line) }
    }
    if(source().recorded) fields.add("Source" to ((source() as? com.overhand.app.data.RecordedSource)?.badge ?: com.overhand.app.data.RecordedSource.BADGE))
    if(more) upper.addView(button("Back") { more=false;render() })
    val title=text("",22f).apply { tag="reading-label" };upper.addView(title)
    val body=text("",22f).apply { tag="reading-page" };upper.addView(body,LinearLayout.LayoutParams(-1,0,1f))
    val pages=LinearLayout(activity).apply { isBaselineAligned=false }
    val back=button("Read back") { };val more=button("Read more") { }
    pages.addView(back,LinearLayout.LayoutParams(0,-2,1f));pages.addView(more,LinearLayout.LayoutParams(0,-2,1f));lower.addView(pages)
    val pager=ReadingPages(body,title,back,more,fields.map { it.first to clean(it.second) },readingPage,clicked={ touch.report(it) }) { readingPage=it }
    activePager=pager;pager.bind()
    fun row(vararg controls: View): LinearLayout = LinearLayout(activity).apply { isBaselineAligned=false }.also { line -> controls.forEach { line.addView(it,LinearLayout.LayoutParams(0,-2,1f)) };lower.addView(line) }
    if(r!=null) {
      row(button(if(list.isEmpty()) "Continue" else "Next decision") { advanceFromReceipt() }.apply { foreground=accent();guardReceiptNext(this) })
      lower.addView(View(activity),LinearLayout.LayoutParams(-1,0,1f))
      row(button("Close") { closeCard() })
    } else if(d!=null) {
      if(!guided) {
        pages.addView(button("Previous") { navigate(-1) },LinearLayout.LayoutParams(0,-2,1f))
        pages.addView(button("Next") { navigate(1) },LinearLayout.LayoutParams(0,-2,1f))
      }
      val choices=LinearLayout(activity).apply { isBaselineAligned=false }
      opts.forEachIndexed { i,_ -> choices.addView(button("Select ${i+1}") { selectOption(d,i) }.apply {
        tag=com.overhand.app.onboarding.HandsOnboarding.OPTION_TAG;OptionGlow.apply(this,i==selected)
      },LinearLayout.LayoutParams(0,-2,1f)) }
      choices.addView(button("Close") { closeCard() },LinearLayout.LayoutParams(0,-2,1f))
      lower.addView(choices)
      val confirm=button("Confirm selected option") { confirmSelected(d,opts) }.apply { tag=QuestionCard.TAG_CONFIRM; if(selected !in opts.indices) alpha=0.55f }
      val answerRow=LinearLayout(activity).apply { isBaselineAligned=false }
      answerRow.addView(confirm,LinearLayout.LayoutParams(0,-2,2f))
      ring=HoldRing(activity).also { it.label=if(selected in opts.indices) letter(opts[selected]) else "";answerRow.addView(it,LinearLayout.LayoutParams(64,64)) }
      if(guided) answerRow.addView(button("Skip") { onboarding.skip();selected=-1;gate.reset();render() })
      if(!guided) {
        answerRow.addView(button("Refresh") { refreshCard() },LinearLayout.LayoutParams(0,-2,1f))
        if(historyRows().first!=null) answerRow.addView(button("History") { toggleHistory() },LinearLayout.LayoutParams(0,-2,1f))
      }
      lower.addView(answerRow)
      // The same tutorial reducer and targets remain in charge of the first-card answer.
      if(guided) { onboarding.decorate(column,source().recorded,true)
        val tutorial=column.getChildAt(1)
        if(tutorial!==lower) { column.removeView(tutorial);upper.addView(tutorial,0) }
      }
    }
  }

  /**
   * #928: the question and its recommendation show whole above the context line in every state (the next question
   * shares its band with the answered count and, without hands, the tracking line). When they do not fit, their
   * text steps down to the type scale's caption size; the scroll stays only as a last resort.
   */
  private fun fitContext(band: ScrollView,body: LinearLayout) {
    val fit=object: android.view.ViewTreeObserver.OnPreDrawListener {
      var seen=-1
      override fun onPreDraw(): Boolean {
        if(band.height<=0 || band.width<=0 || band.height==seen) return true
        seen=band.height
        val texts=(0 until body.childCount).map { body.getChildAt(it) }.filterIsInstance<TextView>()
        body.measure(View.MeasureSpec.makeMeasureSpec(band.width-band.paddingLeft-band.paddingRight,View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        val scaled=activity.resources.displayMetrics.scaledDensity
        val caption=com.overhand.app.features.StageWindows.CARD_TYPE.caption
        if(body.measuredHeight<=band.height-band.paddingTop-band.paddingBottom || texts.none { it.textSize/scaled>caption }) return true
        texts.forEach { if(it.textSize/scaled>caption) it.textSize=caption }
        return false  // laid out again at caption size
      }
    }
    band.viewTreeObserver.addOnPreDrawListener(fit)
    band.addOnAttachStateChangeListener(object: View.OnAttachStateChangeListener {
      override fun onViewAttachedToWindow(v: View) {}
      override fun onViewDetachedFromWindow(v: View) { if(v.viewTreeObserver.isAlive) v.viewTreeObserver.removeOnPreDrawListener(fit) }
    })
  }
  /**
   * #905: every option shows whole, never cut at the band's edge. After PanelPolish has sized the buttons, the
   * option text steps down together until the tallest option fits its band; the scroll stays only as a last resort.
   */
  private fun fitOptions(band: ScrollView,row: LinearLayout) {
    val obs=band.viewTreeObserver
    // Stays for the band's life: PanelPolish restyles new buttons (size, padding) after the first layout, so a
    // one-shot fit can pass before that and then overflow. Refit only when the band, widths or text sizes change.
    var seen=""
    val fit=object: android.view.ViewTreeObserver.OnPreDrawListener {
      override fun onPreDraw(): Boolean {
        val buttons=(0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<Button>()
        if(band.height<=0 || buttons.isEmpty() || buttons.any { it.width<=0 }) return true
        val key=band.height.toString()+buttons.joinToString { "${it.width}/${it.textSize}/${it.paddingTop}/${it.minimumHeight}" }
        if(key==seen) return true
        fun tallest()=buttons.maxOf { b ->
          b.measure(View.MeasureSpec.makeMeasureSpec(b.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
          val m=b.layoutParams as? android.view.ViewGroup.MarginLayoutParams
          b.measuredHeight+(m?.topMargin ?: 0)+(m?.bottomMargin ?: 0)
        }
        val scaled=activity.resources.displayMetrics.scaledDensity
        val start=buttons.maxOf { it.textSize }/scaled
        var size=start
        while(tallest()>band.height && size>com.overhand.app.features.StageWindows.CARD_TYPE.caption) { size-=1f;buttons.forEach { it.textSize=size } }
        if(size==start) { seen=key;return true }
        return false  // laid out again at the fitted size, then confirmed on the next pass
      }
    }
    obs.addOnPreDrawListener(fit)
    band.addOnAttachStateChangeListener(object: View.OnAttachStateChangeListener {
      override fun onViewAttachedToWindow(v: View) {}
      override fun onViewDetachedFromWindow(v: View) { if(v.viewTreeObserver.isAlive) v.viewTreeObserver.removeOnPreDrawListener(fit) }
    })
  }
  private fun accent()=GradientDrawable().apply { setColor(Color.TRANSPARENT);setStroke(6,Color.rgb(90,235,215));cornerRadius=28f }
  /** Committed state: the answered question, its choice and where it went, until an explicit Next or Close. */
  private fun renderReceipt(r: PracticeReceipt,lower: LinearLayout) {
    status=null;tracking=null
    content!!.addView(LinearLayout(activity).apply {
      orientation=LinearLayout.VERTICAL;tag="card-saved";com.overhand.app.windows.PolishRules.labelInset(this)
      background=GradientDrawable().apply { setColor(Color.rgb(18,62,48));setStroke(4,Color.rgb(110,235,170));cornerRadius=16f }
      // #881: three labelled answers a cold reader looks for: what was asked, what was answered, where it went.
      fun label(value: String)=text(value,20f).apply { setTextColor(Color.rgb(150,200,180));setPadding(paddingLeft,0,paddingRight,0) }
      addView(text(com.overhand.app.RecordedFirstCard.SAVED_TITLE,30f))
      addView(label(com.overhand.app.RecordedFirstCard.SAVED_QUESTION))
      addView(text(r.question,28f).apply { tag="card-saved-question" })
      addView(label(com.overhand.app.RecordedFirstCard.SAVED_ANSWER))
      addView(text(r.option,28f).apply { tag="card-saved-choice" })
      addView(label(com.overhand.app.RecordedFirstCard.SAVED_WHERE))
      addView(text(com.overhand.app.RecordedFirstCard.SAVED_DETAIL,24f))
    })
    // Next sits apart from where Confirm was, and the bottom row keeps Confirm's cell empty.
    lower.addView(button(if(list.isEmpty()) "Continue" else "Next decision") { advanceFromReceipt() }.apply { foreground=accent();guardReceiptNext(this) })
    lower.addView(View(activity),LinearLayout.LayoutParams(-1,0,1f))
    val row=LinearLayout(activity)
    row.addView(View(activity),LinearLayout.LayoutParams(0,0,1f))
    row.addView(button("Close") { closeCard() })
    lower.addView(row)
  }
  // The handlers both cards call (#874: one copy of the rules).
  private fun selectOption(d: JSONObject,i: Int) {
    when {
      board.busy -> refuse("busy",ConfirmBlock.BUSY)
      receipt!=null -> refuse("receipt",ConfirmBlock.SAVED)
      d.optString("id")!=decision()?.optString("id") -> refuse("stale",ConfirmBlock.STALE)
      else -> { touch.report("select");stateWhy="pinch";board.clear();selected=i;gate.reset();onboarding.selected();render();com.overhand.app.audio.SpatialEarcons.of(activity).card(com.overhand.app.audio.Earcon.SELECT) }
    }
  }
  private fun closeCard() { if(!board.busy && !trialRunning) hide() }
  private fun refreshCard() { refresh();if(source().recorded) message("Recorded day: nothing new to load") }
  private fun toggleHistory() { historyExpanded=!historyExpanded;render() }
  /** Recorded rows: (earlier-answers label, the lines it expands to). */
  private fun historyRows(): Pair<String?,List<String>> {
    if(!source().recorded) return null to emptyList()
    val rows=source().decisions()
    val history=(0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.has("historical_answer") }
    val practice=(0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.has("practice_receipt") }
    if(history.isEmpty() && practice.isEmpty()) return null to emptyList()
    val count="Answer history"
    val lines=practice.map { row -> com.overhand.app.RecordedFirstCard.SAVED_TITLE+" · "+row.optString("question")+"\n"+
      com.overhand.app.RecordedFirstCard.savedChoice(row.getJSONObject("practice_receipt").optString("option_label"))+". No live agent was contacted." }+
      history.map { row -> "Original run choice · "+row.optString("question")+"\n"+row.optString("historical_answer") }
    return count to lines
  }
  /**
   * #874: the same card state drawn by FlowstateDecisionCard. Every control reports its trace outcome the way
   * [button] does (its label first, the handler's refusal or result overrides), then calls the shared handler.
   */
  private fun renderCompose(column: LinearLayout) {
    val card=composeCard ?: ComposeCard(activity).also { composeCard=it }
    card.prepareWindow(column)
    if(card.view.parent!==column) { column.removeAllViews();(card.view.parent as? android.view.ViewGroup)?.removeView(card.view);column.addView(card.view,LinearLayout.LayoutParams(-1,-1)) }
    status=null;tracking=null;ring=null
    val d=decision();val r=receipt
    val opts=d?.let(::options).orEmpty()
    if(d!=null && r==null && selected !in opts.indices) selected=if(source().recorded) -1 else recommended(d,opts)
    fun tap(label: String,action: () -> Unit): () -> Unit = { touch.report(CardTouchTrace.slug(label));action() }
    val on=ComposeCard.Handlers(
      select={ i -> touch.report(CardTouchTrace.slug(opts.getOrNull(i).orEmpty()));if(d!=null) selectOption(d,i) },
      confirm=tap("Confirm selected option") { if(d!=null) confirmSelected(d,opts) },
      close=tap("Close") { closeCard() },previous=tap("Previous") { navigate(-1) },next=tap("Next") { navigate(1) },
      history=tap("history") { toggleHistory() },advance=tap("Next decision") { advanceFromReceipt() },refresh=tap("Refresh") { refreshCard() })
    val (historyLabel,historyLines)=historyRows()
    val captions=mutableListOf<String>()
    // #881: the receipt has the upper half to itself (no badge); "where it went" is one of its labelled answers.
    if(source().recorded && r==null) captions.add((source() as? com.overhand.app.data.RecordedSource)?.badge ?: com.overhand.app.data.RecordedSource.BADGE)
    if(r==null && trackedHands==0) captions.add(trackingText())
    val hasRec=d!=null && !d.isNull("recommendation") && d.optString("recommendation").isNotBlank()
    val ui=com.overhand.app.design.DecisionCardUi(position=current,total=list.size,
      question=clean(d?.optString("question",d.optString("title")).orEmpty()),
      project=clean(d?.optString("project_name",d.optString("project")).orEmpty()),agent=clean(d?.optString("agent_name","Agent") ?: "Agent"),
      options=opts.map(::clean),recommended=if(hasRec) recommended(d!!,opts) else -1,selected=selected,
      notice=board.notice?.text?.let(::clean),noticeIsError=board.notice?.error==true,
      historyLabel=historyLabel?.let { it+" "+if(historyExpanded) "⌄" else "›" },
      receipt=r?.let { com.overhand.app.design.DecisionCardUi.Receipt(clean(it.question),com.overhand.app.RecordedFirstCard.SAVED_TITLE,clean(it.option),clean(com.overhand.app.RecordedFirstCard.SAVED_DETAIL)) },
      confirmRefusesInPlace=true,captions=captions.map(::clean),history=if(historyExpanded) historyLines.map(::clean) else emptyList(),
      showRefresh=true,pulse=pulses,holdLabel=opts.getOrNull(selected)?.let(::letter),
      advanceLabel=if(list.isEmpty()) "Continue" else "Next decision")
    composeBase=ComposeCard.Frame(ui,on)
    composeFrame()
    if(d!=null) onboarding.decorate(column,source().recorded)
  }
  /** The drawn frame plus what processFrame changes per frame (hold ring, "Thumbs up saves", look-at-the-card). */
  private fun composeFrame() {
    val c=composeCard ?: return;val b=composeBase ?: return
    val notice=holdSaves ?: (if(look) ConfirmBlock.LOOK else if(pick) ConfirmBlock.NO_OPTION else null)
    c.frame.value=b.copy(ui=b.ui.copy(holdProgress=holdProgress,notice=notice?.let(::clean) ?: b.ui.notice,
      noticeIsError=if(notice!=null) false else b.ui.noticeIsError))
  }
  private fun guardReceiptNext(next: Button) {
    val expected=receipt
    fun ready() {
      if(receipt!==expected || stopped) return
      val live=confirmGuard.canAdvance(SystemClock.elapsedRealtime())
      next.isEnabled=live;next.alpha=if(live) 1f else 0.4f
      if(!live) ui.postDelayed({ ready() },50)
    };ready()
  }
  private fun advanceFromReceipt() {
    if(receipt==null) return refuse("no_receipt",null)
    if(!confirmGuard.canAdvance(SystemClock.elapsedRealtime())) return refuse("quiet",null)
    touch.report("next");stateWhy="next"
    dropReceipt();board.clear();selected=-1;gate.reset();render()
  }
  private fun confirmSelected(d: JSONObject,opts: List<String>) {
    // A stale reference to this button must not reach a card or receipt it was not drawn for.
    touch.report("confirm")
    ConfirmBlock.reason(ConfirmBlock.State(receipt=receipt!=null,stale=d.optString("id")!=decision()?.optString("id")))?.let { refuse(ConfirmBlock.key(it),it);return }
    val guided=onboarding.guiding(source().recorded)
    // #800: Confirm with nothing picked was answered only by a status line far from the hand, unchanged on repeats.
    if(guided && onboarding.awaitingChoice()) { refuse("no_selection","Pinch an option first");pulseOptions();return }
    if(selected !in opts.indices) { refuse("no_selection",ConfirmBlock.NO_OPTION);pulseOptions();return }
    if(guided) {
      val rehearsal=onboarding.rehearsing()
      if(!onboarding.confirmPractice(true)) { refuse("tutorial",ConfirmBlock.TUTORIAL);return }
      if(rehearsal) { finishRehearsal();return }
    }
    post(d,opts[selected],"pinch")
  }
  /** A replayed tutorial ends on the same open card with nothing written. */
  private fun finishRehearsal() {
    selected=-1;gate.reset();board.say(com.overhand.app.onboarding.OnboardingFlow.REHEARSAL_DONE,false);render()
  }
  private fun message(value: String,error: Boolean=false) { board.say(value,error);showNotice() }
  /** The options dip and return, right above Confirm, so "pick one first" shows where the hand is (#800). */
  private fun pulseOptions() {
    if(composeBase!=null) { pulses++;render();return }
    fun options(v: View): List<View> = if(v.tag==com.overhand.app.onboarding.HandsOnboarding.OPTION_TAG) listOf(v)
      else if(v is android.view.ViewGroup) (0 until v.childCount).flatMap { options(v.getChildAt(it)) } else emptyList()
    root?.let(::options)?.forEach { b ->
      android.animation.ObjectAnimator.ofFloat(b,"alpha",1f,0.35f,1f).setDuration(CardTouchTrace.PULSE_MS).start()
    }
  }
  private fun letter(option: String)=option.substringBefore(':').trim().takeIf { option.contains(':') && it.length in 1..3 } ?: option.take(1)
  private fun showNotice() {
    val n=board.notice ?: return
    if(composeBase!=null || (!trials.enabled && !trialRunning && (receipt!=null || decision()!=null))) return render()
    status?.visibility=View.VISIBLE;status?.text=clean(n.text);status?.setTextColor(if(n.error) Color.rgb(255,100,100) else Color.rgb(110,235,170))
  }
  fun setTrials(value: Boolean) {
    if(trialRunning) return
    trials.enable(value);gate.reset();render()
    if(value) open() else refresh()
  }
  /** #881: the one hands line, in words a person acts on; shown only while no hand is in view. */
  private fun trackingText()=HANDS_HINT
  fun frame(poses: Map<String,ThumbPose>,aimed: Boolean,tracked: Int) {
    if(!simulationRunning) processFrame(poses,aimed,tracked)
  }
  private fun processFrame(poses: Map<String,ThumbPose>,aimed: Boolean,tracked: Int,input: String?=null) {
    if(trackedHands!=tracked) { trackedHands=tracked;tracking?.text=trackingText();tracking?.visibility=if(tracked==0) View.VISIBLE else View.GONE;if(composeBase!=null || (!trials.enabled && !trialRunning)) render() }
    if(stopped) return
    if(visible && !trials.enabled && onboarding.consume(poses,aimed)) { ring?.progress=onboarding.holdProgress();gateState="tutorial";return }
    val card=if(trials.enabled) "pose-fixture" else decision()?.optString("id").orEmpty()
    poses.forEach { (hand,pose) -> if(lastPoses[hand]!=pose) trials.pose(hand,pose.wire,card,"none") }
    lastPoses=poses
    val recognized=poses.entries.firstOrNull { it.value!=ThumbPose.NONE }
    // After a commit only an observed, sustained release re-arms poses; one lost frame is not a release.
    val armed=confirmGuard.poseFrame(recognized!=null,tracked>0 || input!=null,SystemClock.elapsedRealtime())
    if(!trials.enabled && (receipt!=null || !armed)) { gate.reset();ring?.progress=0f;holdProgress=0f;gateState="receipt=${receipt!=null} armed=$armed";composeFrame();return }
    // #1033: a thumbs-up arms only on a card awaiting a decision with an option selected; one hold, one commit.
    val holdOpts=decision()?.let(::options).orEmpty()
    val choosable=trials.enabled || recognized==null || ThumbCommit.armed(recognized.value,receipt==null && decision()!=null,selected,holdOpts.size)
    val enabled=visible && (aimed || trials.enabled) && !board.busy && choosable
    pick=visible && aimed && !choosable && board.notice==null
    val update=if(!enabled && recognized!=null) {
      // Disabled input must cancel a hold, without pretending a still-held hand was released.
      gate.reset();PoseHoldGate.Update(false,false)
    } else gate.update(if(enabled && recognized!=null) "$card/${recognized.key}/${recognized.value}" else null,SystemClock.elapsedRealtime())
    val now=SystemClock.elapsedRealtime()
    ring?.progress=if(update.fire) 1f else if(update.holding) gate.progress(now) else 0f
    holdProgress=if(update.fire) 1f else if(update.holding) gate.progress(now) else 0f
    gateState="visible=$visible aimed=$aimed busy=${board.busy} receipt=${receipt!=null} selected=$selected armed=$armed holding=${update.holding} fire=${update.fire}"
    // A thumbs-up while looking away is the one case the hold silently ignored; say so on the card.
    if(visible && !aimed && recognized?.value==ThumbPose.UP && board.notice==null && !trials.enabled) { status?.visibility=View.VISIBLE;status?.text=ConfirmBlock.LOOK;look=true }
    else if(aimed && board.notice==null && status?.text?.toString()==ConfirmBlock.LOOK) status?.text=IDLE
    if(aimed || board.notice!=null) look=false
    status?.setBackgroundColor(if(update.holding) Color.rgb(110,75,10) else Color.TRANSPARENT)
    // #800: while the ring fills, the option it will save is named on the ring and in the status line.
    if(update.holding && !trials.enabled && selected in holdOpts.indices && recognized?.value==ThumbPose.UP) {
      ring?.label=letter(holdOpts[selected]);status?.visibility=View.VISIBLE;status?.text=clean("Thumbs up saves "+holdOpts[selected]);holdShown=true
      holdSaves="Thumbs up saves "+holdOpts[selected]
    } else if(pick) { status?.visibility=View.VISIBLE;status?.text=ConfirmBlock.NO_OPTION;holdShown=true }
    else if(holdShown) { holdShown=false;status?.text=clean(board.notice?.text ?: IDLE) }
    if(!holdShown) holdSaves=null
    if(!compose && !trials.enabled && !trialRunning) {
      root?.findViewWithTag<TextView>("reading-label")?.let { label ->
        label.text=when {
          update.holding -> "Thumbs up saves option ${selected+1}"
          pick -> "Select an option first"
          look -> "Look at the card to confirm"
          else -> label.contentDescription ?: label.text
        }
      }
    }
    composeFrame()
    if(!update.fire || recognized==null) return
    val (hand,pose)=recognized
    Log.i(TAG,"thumb fired hand=$hand pose=${pose.wire} card=$card selected=$selected")
    // Legacy validator wire vocabulary: down is called snooze, even on binary rejection POSTs.
    trials.pose(hand,pose.wire,card,if(pose==ThumbPose.UP) "answer" else "snooze")
    if(trials.enabled) { message("Recognized | release your hand");return }
    val d=decision() ?: return
    val opts=options(d)
    // Recorded saves only the option the person picked; down never turns into the other option there.
    if(pose==ThumbPose.DOWN && (opts.size!=2 || source().recorded)) { com.overhand.app.audio.SpatialEarcons.of(activity).card(com.overhand.app.audio.Earcon.DEFER);message("Kept pending");hide();return }
    if(selected !in opts.indices) { message("Select an option first",true);return }
    val choice=if(pose==ThumbPose.UP) selected else 1-selected
    post(d,opts[choice],input ?: pose.wire)
  }
  private fun post(d: JSONObject,answer: String,input: String) {
    ConfirmBlock.reason(ConfirmBlock.State(stopped,board.busy,trials.enabled,onboarding.blocksAnswer(),receipt!=null))?.let {
      Log.i(TAG,"confirm blocked input=$input reason=$it");refuse(ConfirmBlock.key(it),it);return
    }
    val id=d.optString("id")
    val captured=source();val sendReal=LiveSafety.sendReal
    if(!LiveSafety.maySend(captured.recorded,sendReal)) {
      // Live with the switch off: the confirm registers, nothing leaves the headset.
      Log.i(TAG,"confirm practice input=$input card=$id sent=false");gate.reset()
      com.overhand.app.audio.SpatialEarcons.of(activity).card(com.overhand.app.audio.Earcon.CONFIRM);message(LiveSafety.PRACTICE);return
    }
    if(!confirmGuard.begin(id,SystemClock.elapsedRealtime())) { Log.i(TAG,"confirm blocked input=$input reason=guard");refuse("guard",ConfirmBlock.GUARD);return }
    Log.i(TAG,"confirm post input=$input card=$id recorded=${captured.recorded}")
    val modeToken=generation.token
    val token=board.begin(d,captured.recorded) ?: run { confirmGuard.settle(id,false,SystemClock.elapsedRealtime());showNotice();return }
    showNotice()
    io.execute {
      if(stopped || !generation.accepts(modeToken)) { ui.post { confirmGuard.settle(id,false,SystemClock.elapsedRealtime()) };return@execute }
      val result=runCatching { LiveSafety.answer(captured,sendReal,d,answer,input) }
      ui.post {
        if(stopped || !generation.accepts(modeToken)) { confirmGuard.settle(id,false,SystemClock.elapsedRealtime());return@post }
        val effect=board.finish(token,d,answer,result,captured.recorded)
        Log.i(TAG,"confirm result input=$input card=$id ok=${result.isSuccess} receipt=${captured.recorded && effect?.confirmed==true}")
        confirmGuard.settle(id,effect?.confirmed==true,SystemClock.elapsedRealtime())
        // Live has no receipt to leave; only the quiet window remains.
        if(!captured.recorded) confirmGuard.acknowledge()
        effect?.let {
          // The receipt carries the saved result, so the same sentence is not repeated as a status line.
          if(captured.recorded && it.confirmed) {
            stateWhy=QuestionCard.whyOf(input)
            receipt=PracticeReceipt(id,d.optString("question",d.optString("title")),
              "${d.optString("project_name",d.optString("project"))} · ${d.optString("agent_name","Agent")}",answer)
            board.clear()
          }
          else if(captured.recorded) render()  // a failed save must leave the guided layout, which was already dismissed
          applyEffect(d,it,input)
        }
      }
    }
  }
  /** Read-only re-check of unresolved Live answers on the source that sent them; never posts. */
  private fun reconcileUnresolved() {
    val live=source() as? com.overhand.app.data.LiveSource ?: return
    val modeToken=generation.token
    board.checks().forEach { u ->
      io.execute {
        if(stopped || !generation.accepts(modeToken)) return@execute
        val result=runCatching { live.reconcile(u.card,u.label) }
        ui.post { if(!stopped && generation.accepts(modeToken)) applyEffect(u.card,board.reconciled(u.card,u.label,result),"reconcile") }
      }
    }
  }
  private fun applyEffect(d: JSONObject,effect: DeliveryBoard.Effect,input: String) {
    if(effect.confirmed) com.overhand.app.audio.SpatialEarcons.of(activity).card(if(input.contains("down")) com.overhand.app.audio.Earcon.DEFER else com.overhand.app.audio.Earcon.CONFIRM)
    if(effect.removeCard) {
      if(effect.confirmed) answeredHere++
      list=list.filter { it.optString("id")!=d.optString("id") }
      current=current.coerceAtMost((list.size-1).coerceAtLeast(0));selected=-1;render()
    }
    showNotice()
  }

  private fun startTrials() {
    if(trialRunning) return
    trialRunning=true;val generation=++trialGeneration
    fun next(i: Int) {
      if(stopped || generation!=trialGeneration) return
      if(i==40) { trialRunning=false;render();message("40 cues complete. Begin the 60 s rest next.");return }
      val pose=if(i<20) "thumbs_up" else "thumbs_down";val hand=if(i%2==0) "right" else "left";val id="trial-${i+1}"
      cue="${i+1} / 40 | $hand | ${pose.replace('_',' ')}";render();trials.cue(id,pose,hand)
      ui.postDelayed({ if(!stopped && generation==trialGeneration) {
        trials.event("trial_end","trial" to id);cue="Release both hands";render()
        ui.postDelayed({ next(i+1) },1000)
      } },2000)
    }
    cue="Get ready | hands visible";render();ui.postDelayed({ next(0) },3000)
  }
  private fun startRest() {
    if(trialRunning) return
    trialRunning=true;cue="Rest both hands visibly for 60 seconds";render();trials.idleStart()
    ui.postDelayed({ if(!stopped) { trials.idleEnd();trialRunning=false;render();message("Rest complete. Trial log ready to pull.") } },60000)
  }
  /** Only a named test fixture can be driven by debug intents. Real decisions are rejected. */
  fun previewTest(id: String) {
    if(!BuildConfig.DEBUG) return
    fixturePreview=id;open()
    val i=list.indexOfFirst { it.optString("id")==id && it.optString("question").startsWith("[Overhand test 261]") }
    if(i>=0) { current=i;selected=-1;open() }
  }
  fun debugPose(id: String,pose: String) {
    if(!BuildConfig.DEBUG) return
    fixturePreview=id
    refresh()
    ui.postDelayed({
      val d=list.firstOrNull { it.optString("id")==id && it.optString("question").startsWith("[Overhand test 261]") }
      if(d==null) { message("Test fixture unavailable",true);return@postDelayed }
      current=list.indexOf(d);selected=recommended(d,options(d));open()
      val p=if(pose=="thumbs_down") ThumbPose.DOWN else ThumbPose.UP
      // Drive the same frame/hold/POST path. Live samples cannot cancel this named fixture input.
      if(trials.enabled || simulationRunning) return@postDelayed
      simulationRunning=true;gate.reset();gate.update(null,SystemClock.elapsedRealtime())
      val started=SystemClock.elapsedRealtime()
      fun tick() {
        if(stopped) { simulationRunning=false;return }
        processFrame(mapOf("right" to p,"left" to ThumbPose.NONE),true,0,"simulated_${p.wire}")
        if(SystemClock.elapsedRealtime()-started<600) ui.postDelayed({ tick() },50)
        else simulationRunning=false
      }
      tick()
    },1200)
  }
  /** Synthetic QA, Recorded only: pinch-click the drawn Confirm button, exactly as a hand ray would. */
  fun debugConfirm() {
    if(!BuildConfig.DEBUG || !source().recorded) return
    if(composeBase!=null) { composeCard?.click(CardTouchTrace.slug(com.overhand.app.design.CardTags.CONFIRM_NAME)).also { Log.i(TAG,"debug confirm clicked=${it==true} compose=true") };return }
    fun find(v: View): Button? = when(v) {
      is Button -> v.takeIf { it.tag==QuestionCard.TAG_CONFIRM }
      is android.view.ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
      else -> null
    }
    root?.let(::find)?.performClick().also { Log.i(TAG,"debug confirm clicked=${it==true}") }
  }
  /** Synthetic QA (#800): a real down/up at a panel fraction, held [ms], through the same touch path a pinch takes. */
  fun debugTap(xFrac: Float,yFrac: Float,ms: Long) {
    if(!BuildConfig.DEBUG) return
    val v=root ?: return
    val x=xFrac*v.width;val y=yFrac*v.height;val t0=SystemClock.uptimeMillis()
    v.dispatchTouchEvent(android.view.MotionEvent.obtain(t0,t0,android.view.MotionEvent.ACTION_DOWN,x,y,0))
    ui.postDelayed({ v.dispatchTouchEvent(android.view.MotionEvent.obtain(t0,SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_UP,x,y,0)) },ms)
  }
  /** Synthetic QA, Recorded only: a visible released hand for 0.5 s, then thumbs up held 1.5 s, through processFrame. */
  fun debugThumbHold() {
    if(!BuildConfig.DEBUG || !source().recorded || simulationRunning) return
    simulationRunning=true
    val started=SystemClock.elapsedRealtime()
    fun tick() {
      if(stopped) { simulationRunning=false;return }
      val t=SystemClock.elapsedRealtime()-started
      processFrame(mapOf("right" to (if(t<500) ThumbPose.NONE else ThumbPose.UP),"left" to ThumbPose.NONE),true,1)
      if(t<2000) ui.postDelayed({ tick() },50) else { simulationRunning=false;processFrame(mapOf("right" to ThumbPose.NONE,"left" to ThumbPose.NONE),true,1) }
    }
    Log.i(TAG,"debug thumb hold start");tick()
  }
  /** Synthetic QA: a thumb held through the save, the receipt and Next, on the real frame path. */
  private fun debugHeldThumb() {
    if(!BuildConfig.DEBUG || simulationRunning) return
    simulationRunning=true
    val started=SystemClock.elapsedRealtime()
    fun tick() {
      if(stopped) { simulationRunning=false;return }
      processFrame(mapOf("right" to ThumbPose.UP,"left" to ThumbPose.NONE),true,1)
      if(SystemClock.elapsedRealtime()-started<3000) ui.postDelayed({ tick() },50) else simulationRunning=false
    }
    tick()
  }
  fun snapshot() {
    val v=root ?: return
    if(v.width<=0 || v.height<=0) return
    val image=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(image))
    activity.openFileOutput("card_panel.png",0).use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()
    activity.openFileOutput("card_status.json",0).use { it.write(JSONObject().put("text",status?.text.toString()).put("color",status?.currentTextColor).toString().toByteArray()) }
    fun collect(view: View): String = if(view.visibility!=View.VISIBLE) "" else when(view) {
      is TextView -> view.text.toString()+"\n"
      is android.view.ViewGroup -> (0 until view.childCount).joinToString("") { collect(view.getChildAt(it)) }
      else -> ""
    }
    val compose=composeCard?.takeIf { composeBase!=null }
    activity.openFileOutput("card_text.txt",0).use { it.write((compose?.texts()?.joinToString("\n") ?: collect(v)).toByteArray()) }
    if(compose!=null) {
      val bounds=JSONArray(compose.bounds().map { (text,r) -> JSONObject().put("text",text).put("left",r.left).put("top",r.top).put("width",r.width()).put("height",r.height()).put("visible_width",r.width()).put("visible_height",r.height()).put("clipped",false) })
      activity.openFileOutput("card_bounds.json",0).use { it.write(JSONObject().put("panel_width",v.width).put("panel_height",v.height).put("compose",true).put("buttons",bounds).toString().toByteArray()) }
      return
    }
    fun buttons(view: View): List<Button> = when(view) {
      is Button -> listOf(view)
      is android.view.ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
      else -> emptyList()
    }
    val bounds=JSONArray(buttons(v).map { b ->
      val r=Rect();val shown=b.getGlobalVisibleRect(r)
      JSONObject().put("text",b.text.toString()).put("width",b.width).put("height",b.height).put("visible_width",r.width()).put("visible_height",r.height()).put("clipped",!shown || r.width()<b.width || r.height()<b.height)
    })
    activity.openFileOutput("card_bounds.json",0).use { it.write(JSONObject().put("panel_width",v.width).put("panel_height",v.height).put("buttons",bounds).toString().toByteArray()) }
  }
  fun pause() { gate.reset() }
  /** #1373: Ask, Picked, Receipt on one card (look_nov17 s5). Confirm is dim and not pressable until a pick. */
  private fun renderLook(column: LinearLayout) {
    column.removeAllViews();status=null;tracking=null;content=null
    val d=decision();val r=receipt
    val opts=d?.let(::options).orEmpty()
    if(d!=null && r==null && selected !in opts.indices) selected=if(source().recorded) -1 else recommended(d,opts)
    val st=QuestionCard.state(selected,opts.size,r!=null)
    logState(st.name.lowercase())
    val why=listOfNotNull(board.notice?.text,if(d!=null && !d.isNull("recommendation") && d.optString("recommendation").isNotBlank()) "Suggested: "+recommended(d,opts).takeIf { it in opts.indices }?.let { ('A'+it).toString() }.orEmpty() else null).firstOrNull()
    val m=QuestionCard.Model(st,clean(d?.optString("project_name",d.optString("project")).orEmpty()),
      clean(d?.optString("question",d.optString("title")).orEmpty()),why?.let(::clean),opts.map(::clean),selected,
      r?.let { clean(it.option) },if(r!=null) QuestionCard.PRACTICE else null)
    val card=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
    column.addView(card,LinearLayout.LayoutParams(-1,-1))
    ring=QuestionCard.draw(activity,card,m,QuestionCard.Handlers(
      select={ i -> if(d!=null) selectOption(d,i) },
      confirm={ if(d!=null) confirmSelected(d,opts) },
      more={ more=true;render() },
      next={ advanceFromReceipt() },
      button=::button))
    ring?.label=""
    column.findViewWithTag<Button>(QuestionCard.TAG_NEXT)?.let(::guardReceiptNext)
  }
  companion object {
    /** #1373: draw the question card (default). Tests of the reading view set it false. */
    @JvmField var lookCard=true
    const val TAG="OverhandCard";const val MIN_OPTION_SP=14f;const val LONG_QUESTION=100;const val IDLE="Pinch Confirm or hold thumbs up";const val GESTURE_HINT_TAG="gesture-hint"
    const val HANDS_HINT="Hold your hand up in view to use thumbs up. Pinching works without it." }
  fun close() {
    disposePager();stopped=true;trialGeneration++;ui.removeCallbacksAndMessages(null)
    stream?.disconnect();streamThread?.interrupt();io.shutdownNow()
  }
}
