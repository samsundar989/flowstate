package com.overhand.app.windows

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.overhand.app.data.HttpClient
import com.overhand.app.data.LocalReceiptStore
import com.overhand.app.data.RecordedSource
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration

/** #1371: the Code window shows real diffs (recorded day, demos, host fixture) in the look of Code.dc.html. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w1600dp-h800dp-288dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CodeViewTest {
  init { RecordedWindows.codeLoader=RecordedWindows.INLINE }  // #1385: Code loads inline so these checks see it at once
  private val out=File(System.getProperty("e2.out") ?: "build/e2").apply { mkdirs() }
  private val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
  private val density get()=activity.resources.displayMetrics.density
  private fun source(key: String=RecordedSource.DAY)=RecordedSource({ File("src/main/assets/$it").readText() },
    LocalReceiptStore(Files.createTempDirectory("e2").resolve("r.json").toFile()),HttpClient { _,_,_ -> error("offline") },requested=key)
  private fun all(v: View): List<View> = listOf(v)+(if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList())
  private fun idle() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200)) }
  private fun settle(v: View,w: Int,h: Int) { repeat(3) {
    v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY))
    v.layout(0,0,w,h);idle() } }
  /** The panel at 1280 x 800 dp over #C8C8C8 (look rule 4), alpha kept in the PNG. */
  private fun picture(name: String,column: LinearLayout): File {
    val w=(1280*density).toInt();val h=(800*density).toInt()
    column.background=Theme.panelBackground(density)
    settle(column,w,h)
    val b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);val c=Canvas(b);c.drawColor(0xFFC8C8C8.toInt());column.draw(c)
    return File(out,"$name.png").also { f -> f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) } }
  }
  private fun panel(title: String)=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL
    val p=(40*density).toInt();setPadding(p,(36*density).toInt(),p,(36*density).toInt())
    addView(TextView(context).apply { text=title;textSize=40f;setTextColor(CodeView.TEXT);tag=RecordedWindows.TITLE_TAG }) }

  // Red (s2 #FF6B6B, FlowstateTokens r50 #F05F69) or anything red-dominant.
  private fun reddish(c: Int)=Color.alpha(c)>0 && Color.red(c)>=200 && Color.red(c)-Color.green(c)>=60 && Color.red(c)-Color.blue(c)>=60
  private fun colours(v: View): List<Int> = listOfNotNull((v as? TextView)?.currentTextColor,(v.background as? ColorDrawable)?.color,
    (v.background as? GradientDrawable)?.color?.defaultColor)

  @Test fun addedRemovedAndContextLinesGetTheirThreeTreatmentsAndNoRed() {
    val v=CodeView(activity) { it }
    fun parts(k: CodeDiffs.Kind,t: String)=v.diffLine(CodeDiffs.Line(k,t)).let { l -> Triple(l,l.getChildAt(0) as TextView,l.getChildAt(1) as TextView) }
    val (addRow,addGlyph,addText)=parts(CodeDiffs.Kind.ADD,"x = 1")
    val (delRow,delGlyph,delText)=parts(CodeDiffs.Kind.DEL,"x = 0")
    val (ctxRow,_,ctxText)=parts(CodeDiffs.Kind.CTX,"y = 2")
    assertEquals("+",addGlyph.text.toString());assertEquals(CodeView.GREEN,addGlyph.currentTextColor)
    assertEquals(CodeView.GREEN_16,(addRow.background as ColorDrawable).color);assertEquals(CodeView.TEXT,addText.currentTextColor)
    assertEquals("-",delGlyph.text.toString());assertEquals(CodeView.CAPTION,delText.currentTextColor)
    assertTrue("removed is struck through",(delText.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG)!=0)
    assertTrue("removed has no fill",delRow.background==null)
    assertTrue("context has no fill and no strike",ctxRow.background==null && (ctxText.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG)==0)
    assertEquals(CodeView.TEXT,ctxText.currentTextColor)
    assertTrue("added is not struck",(addText.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG)==0)
    // The whole window on a real diff: no red anywhere.
    val rows=CodeDiffs.recorded(source().diffs,"s74617e7e4f3c9e64")
    v.show("Headset competition",rows)
    val used=all(v.view).flatMap(::colours)
    assertTrue("no red: ${used.filter(::reddish).map { Integer.toHexString(it) }}",used.none(::reddish))
    assertTrue(all(v.view).any { it.tag==CodeView.TAG_LINE })
  }

  @Test fun everyRecordingWithChangesShowsADiffAndThePictures() {
    val report=JSONArray();var withChanges=0;var shown=0
    for((key,_) in RecordedSource.datasets { File("src/main/assets/$it").readText() }) {
      val src=source(key);val content=RecordedContent(src)
      for(p in content.projects.map { it.getString("id") }) {
        // "Has changes": the recording names changed files for it (drill parts / demo excerpts) or counts changed files.
        val code=src.code(p);val counted=RecordedContent.rows(code.optJSONArray("modules")).sumOf { it.optInt("changed_files") }
        val named=CodeDiffs.recorded(src.diffs,p).size
        if(counted==0 && named==0 && !code.optBoolean("excerpts")) continue
        withChanges++
        val w=RecordedWindows(content,activity);val col=panel("Code");w.attach(col,"code",p)
        w.openCodeFor(p,null,"yard")
        settle(col,(1280*density).toInt(),(800*density).toInt())
        val lines=all(col).count { it.tag==CodeView.TAG_LINE };val files=all(col).count { it.tag==CodeView.TAG_ROW }
        val diff=lines>0 && named>0
        if(diff) shown++
        report.put(JSONObject().put("recording",key).put("project",p).put("name",content.projectName(p)).put("changed_files_counted",counted)
          .put("diff_rows",named).put("file_rows_shown",files).put("diff_lines_shown",lines).put("diff",diff)
          .put("why_none",if(diff) "" else if(named==0) "the recording names no file and no commit for it (file names [redacted], no sha)" else "not rendered"))
      }
    }
    File(out,"code_diffs_shown.json").writeText(JSONObject().put("with_changes",withChanges).put("shown",shown).put("projects",report).toString(1))
    println("code_diffs_shown $shown/$withChanges")
    assertTrue("demos and the recorded day's headset project show diffs",shown>=5)

    // Pictures: before (the text the recorded Code window showed: RecordedContent.window("code")) and after.
    val content=RecordedContent(source())
    val before=panel("Code").apply { addView(TextView(context).apply { text=content.window("code",content.defaultProject).lines().take(18).joinToString("\n")
      textSize=28f;setTextColor(Color.WHITE) }) }
    picture("code_before",before)
    val w=RecordedWindows(content,activity);val col=panel("Code");w.attach(col,"code","")
    w.openCodeFor("s74617e7e4f3c9e64","web/agentdeck/src/room.ts","blueprint")
    val after=picture("code_after",col)
    val v=all(col).first { it.tag=="code-view" }
    assertTrue(all(v).filterIsInstance<TextView>().any { it.text.contains("room.ts") })
    w.openCodeFor("s74617e7e4f3c9e64","eval/run_eval.py","blueprint");picture("code_after_run_eval",col)
    w.openCodeFor("s74617e7e4f3c9e64","adapter","yard");picture("code_after_part_adapter",col)
    File(out,"manifest.tsv").writeText("path\tbytes\tsha256\n"+out.listFiles()!!.filter { it.extension=="png" }.sorted().joinToString("") { f ->
      "${f.name}\t${f.length()}\t${MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }}\n" })
    assertTrue(after.length()>0)
  }

  @Test fun pinFollowAndEmptyState() {
    val rows=CodeDiffs.recorded(source().diffs,"s74617e7e4f3c9e64")
    val v=CodeView(activity) { it }
    v.show("P",emptyList())
    fun visibleTexts(x: View): List<String> = if(x.visibility!=View.VISIBLE) emptyList() else
      (if(x is TextView && x.text.isNotEmpty()) listOf(x.text.toString()) else emptyList())+
      (if(x is ViewGroup) (0 until x.childCount).flatMap { visibleTexts(x.getChildAt(it)) } else emptyList())
    assertEquals(listOf(CodeView.EMPTY),visibleTexts(v.view))
    v.show("P",rows)
    val newest=CodeDiffs.files(rows).first().path
    assertEquals(newest,v.selected);assertTrue(v.following)
    val old=CodeDiffs.files(rows).last().path
    v.pin(old);assertEquals(old,v.selected);assertTrue(!v.following)
    v.show("P",rows);assertEquals("a pinned file stays open as changes arrive",old,v.selected)
    all(v.view).first { it.tag==CodeView.TAG_LIVE }.performClick();assertEquals(newest,v.selected);assertTrue(v.following)
    // A module filter keeps only that part's files.
    v.show("P",rows,"adapter");assertTrue(v.rows.isNotEmpty() && v.rows.all { it.part=="adapter" })
    // "Writing now" only while a worker writes that file.
    assertTrue(all(v.view).filterIsInstance<TextView>().none { it.text=="Writing now" })
  }

  @Test fun hostFileWrittenEventMakesARowAndADiffRowWhenItCarriesOne() {
    val withDiff=JSONObject("""{"type":"file_written","project_id":"p1","data":{"path":"index.html","part_id":"ui","slot":2,
      "diff":"diff --git a/index.html b/index.html\n--- a/index.html\n+++ b/index.html\n@@ -1,2 +1,3 @@\n <html>\n-<p>old</p>\n+<p>new</p>\n+<p>more</p>\n"}}""")
    val r=CodeDiffs.host(withDiff,1000L)!!
    assertEquals("index.html",r.path);assertEquals(2,r.added);assertEquals(1,r.removed);assertEquals("ui",r.part)
    assertEquals(listOf(CodeDiffs.Kind.HUNK,CodeDiffs.Kind.CTX,CodeDiffs.Kind.DEL,CodeDiffs.Kind.ADD,CodeDiffs.Kind.ADD),r.lines.map { it.kind })
    val v=CodeView(activity) { it };v.writing="index.html";v.show("p1",listOf(r))
    assertEquals(4+1,all(v.view).count { it.tag==CodeView.TAG_LINE })
    assertTrue("a worker writing the open file shows Writing now",all(v.view).filterIsInstance<TextView>().any { it.text=="Writing now" })
    // Today's host: file_written without a diff is still a changed-file row, with no lines.
    val bare=CodeDiffs.host(JSONObject("""{"type":"file_written","project_id":"p1","data":{"path":"app.js"}}"""),2000L)!!
    assertEquals("app.js",bare.path);assertTrue(bare.lines.isEmpty())
    assertEquals(null,CodeDiffs.host(JSONObject("""{"type":"message","data":{}}"""),0L))
  }
}
