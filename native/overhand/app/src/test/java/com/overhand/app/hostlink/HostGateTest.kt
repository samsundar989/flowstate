package com.overhand.app.hostlink

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Opt-in integration gate; real CLI by default, never silently substitutes replay. */
class HostGateTest {
  @Test fun completeGate() {
    assumeTrue("run with tools/host_gate/run_gate.sh",System.getenv("HOST_GATE_RUN")=="1")
    val rows=JSONArray()
    val out=Repo.out
    val result=JSONObject().put("client","jvm").put("agent",System.getenv("HOST_GATE_AGENT") ?: "codex")
      .put("steps",rows).put("host_gate_jvm_steps_ok",0)
    fun save() { File(out,"gate.json").writeText(result.toString(2)) }
    fun <T> step(name: String, body: () -> T): T {
      val row=JSONObject().put("step",name).put("started_utc",java.time.Instant.now().toString());rows.put(row)
      val start=System.nanoTime()
      try { return body().also { row.put("ok",true) } }
      catch(e: Throwable) { row.put("ok",false).put("error",e.toString());throw e }
      finally { row.put("seconds",(System.nanoTime()-start)/1e9);save() }
    }
    fun waitFor(what: String, ms: Long=30_000, cond: () -> Boolean) {
      val end=System.nanoTime()+ms*1_000_000
      while(!cond()) {check(System.nanoTime()<end) {"timeout: $what"};Thread.sleep(20)}
    }
    val home=File(System.getenv("FLOWSTATE_HOST_HOME") ?: error("isolated FLOWSTATE_HOST_HOME required"))
    check(!File(home,"cfg/events.jsonl").exists()) {"refusing to reuse an existing gate home"}
    var host: RealHost?=null
    val link=HostLink(InMemoryPairedHostStore(),NoDiscovery,"Gate JVM",{true},LinkTiming())
    try {
      val h=step("host_start") {RealHost(File(Repo.root,"tools/host_gate/gate_host.ts"),home).also {host=it}}
      val source=HostSource(link,null,{true},waitMs=15_000)
      // #954: HOST_GATE_ADDRESS is what the wearer types on "Enter computer address" (a name, no discovery).
      val typed=System.getenv("HOST_GATE_ADDRESS")?.takeIf { it.isNotBlank() }
      result.put("connect",if(typed==null) "harness endpoint" else "typed address").put("host_bind_overlay",h.info.optBoolean("overlay"))
      if(typed!=null) result.put("typed_address",typed)
      step("pair_six_digit_confirm") {
        val ep=if(typed==null) h.endpoint else ManualAddress.endpoint("$typed:${h.port}").also { assertEquals("typed address reached the gate host",h.fp,it.fp) }
        val future=CompletableFuture.supplyAsync {link.pairByConfirm(ep)}
        waitFor("six digits") {link.status.state==LinkState.CONFIRM_ON_COMPUTER}
        val digits=h.cmd("confirm").getString("digits")
        assertTrue(digits.matches(Regex("[0-9]{6}")));assertEquals(digits,link.status.digits)
        assertTrue(h.cmd("decide","yes" to true).getBoolean("decided"))
        future.get(30,TimeUnit.SECONDS)
        waitFor("connected") {link.status.state==LinkState.CONNECTED}
      }
      val commits=JSONArray()
      result.put("commits",commits)
      val previews=JSONArray()
      result.put("previews",previews)
      fun ev(kind: String,tid: String)=link.events.firstOrNull {it.optString("kind")==kind&&it.optString("task_id")==tid}
      /** #972: the preview event for [tid] names main's commit; the harness fetches the file with get_file over the
       *  production link and hashes it beside `git show <commit>:index.html`. All three must agree. */
      fun previewMatches(made: JSONObject,tid: String,fin: JSONObject): JSONObject {
        waitFor("preview event") {ev("preview",tid)!=null}
        val pv=ev("preview",tid)!!
        assertEquals("index.html",pv.getString("path"))
        assertTrue(pv.toString(),pv.getString("commit").startsWith(fin.getString("commit")))
        val got=h.cmd("fetch","project_id" to made.getString("project_id"),"commit" to pv.getString("commit"),"path" to "index.html")
        assertEquals(got.toString(),pv.getString("sha256"),got.getString("announced_sha256"))
        assertEquals(got.toString(),pv.getString("sha256"),got.getString("fetched_sha256"))
        assertEquals(got.toString(),pv.getString("sha256"),got.getString("git_show_sha256"))
        assertEquals(pv.getInt("bytes"),got.getInt("bytes"))
        previews.put(got.put("task_id",tid))
        return pv
      }
      fun request(n: Int,followUp: Boolean,talk: Boolean=false) {
        // #972 build from nothing: the host pins a static web app (index.html, at most 3 files, offline) and asks for a plan first.
        val text="new project: a tap counter for the headset, with one big button that counts taps and a reset button."
        result.put("transcript",text)
        val made=step("request_${n}_project_created") {
          val m=link.createProject(text,"gate_request_$n").get(30,TimeUnit.SECONDS)
          waitFor("project event") {link.events.any {it.optString("kind")=="project_created"&&it.optString("project_id")==m.getString("project_id")}}
          m
        }
        val tid=made.getString("task_id")
        val plan=step("request_${n}_plan_received") {
          waitFor("plan event",240_000) {
            check(ev("finished",tid)==null) {"agent ended before planning; inspect cfg/raw"}
            ev("plan",tid)!=null
          }
          val p=ev("plan",tid)!!
          val parts=p.getJSONArray("parts")
          assertTrue(p.toString(),parts.length() in 3..6)
          for(i in 0 until parts.length()) parts.getJSONObject(i).getString("name").let { assertTrue(it,it.isNotBlank()&&it.length<=24) }
          result.put("plan_$n",p)
          p
        }
        val card=step("request_${n}_card_received") {
          waitFor("agent decision card",240_000) {
            check(ev("finished",tid)==null) {"agent ended before asking; inspect cfg/raw"}
            source.decisions().length()>0
          }
          val q=ev("question",tid)!!
          assertTrue("plan arrives before the question",plan.getLong("seq")<q.getLong("seq"))
          assertTrue(q.toString(),q.getJSONArray("options").length() in 2..3)
          assertTrue("no file before the answer",link.events.none {it.optString("kind")=="file_written"&&it.optString("task_id")==tid})
          source.decisions().getJSONObject(0)
        }
        step("request_${n}_card_answered") {
          val label=card.getJSONArray("options").getJSONObject(0).getString("label")
          assertEquals("Answer received",source.answer(card,label,"pinch"))
          assertNotNull(link.answeredOption(card.getString("id")))
        }
        if(talk) step("request_${n}_talk_reaches_agent") {
          // #1035: the wearer talks to this agent while it works. The words must land in its own session and in no other.
          val answeredSeq=link.events.last { it.optString("kind")=="answered"&&it.optString("task_id")==tid }.getLong("seq")
          waitFor("agent at work after the answer",240_000) {
            ev("finished",tid)!=null || link.events.any { it.optString("task_id")==tid&&it.getLong("seq")>answeredSeq&&it.optString("kind") in setOf("command_run","file_written","message") }
          }
          check(ev("finished",tid)==null) {"agent finished before it could be talked to"}
          val marker="kestrel"+(1000+java.util.Random().nextInt(9000))
          val sentence="Quick check from the headset, code word $marker: what are you working on right now?"
          val r=link.say(made.getString("project_id"),tid,sentence).get(30,TimeUnit.SECONDS)
          assertEquals(r.toString(),"ok",r.optString("type"));assertEquals(tid,r.optString("task_id"))
          waitFor("the host logged the words") { link.events.any { it.optString("kind")=="answered"&&it.optString("req_id")==r.optString("req_id") } }
          val said=link.events.first { it.optString("kind")=="answered"&&it.optString("req_id")==r.optString("req_id") }
          var where=JSONObject()
          waitFor("the words in the agent's session",180_000) {
            where=h.cmd(JSONObject().put("cmd","sessions").put("marker",marker).put("task_id",tid),60_000)
            check(where.optInt("other_hits")==0) {"the words reached another agent: $where"}
            Thread.sleep(2_000);where.optInt("target_hits")>0
          }
          // The reply the headset reads back: the agent's next message on this task (not required: it may only answer in its final summary).
          val reply=runCatching { waitFor("a reply",120_000) { link.events.any { it.optString("kind")=="message"&&it.optString("task_id")==tid&&it.getLong("seq")>said.getLong("seq") } }
            link.events.first { it.optString("kind")=="message"&&it.optString("task_id")==tid&&it.getLong("seq")>said.getLong("seq") } }.getOrNull()
          result.put("talk",JSONObject().put("sentence",sentence).put("sessions",where)
            .put("reply",reply?.let { it.optString("detail").ifBlank { it.optString("title") } } ?: JSONObject.NULL))
          result.put("talk_reaches_right_agent",where.getInt("talk_reaches_right_agent"))
          assertEquals(where.toString(),1,where.getInt("talk_reaches_right_agent"))
        }
        fun commitVerified(t: String): JSONObject {
          waitFor("agent commit",240_000) {ev("finished",t)!=null}
          val fin=ev("finished",t)!!
          assertTrue(fin.toString(),fin.getBoolean("ok"));assertTrue(fin.getBoolean("merged"))
          // Independent of the agent and the host: index.html tracked, at most 3 files, nothing from the network, brand-clean text.
          val evidence=h.cmd("verify","project_id" to made.getString("project_id"))
          assertTrue(evidence.toString(),evidence.getString("commit").startsWith(fin.getString("commit")))
          assertTrue(evidence.toString(),evidence.getBoolean("ok"))
          commits.put(evidence.put("task_id",t))
          return fin
        }
        val fin=step("request_${n}_commit_verified") {commitVerified(tid)}
        val pv=step("request_${n}_preview_sha_matches") {previewMatches(made,tid,fin)}
        if(followUp) step("request_${n}_followup_preview") {
          // A follow-up on the same project: a new task in the same repo, no plan, no question, a new preview on main.
          val f=h.cmd("followup","project_id" to made.getString("project_id"),"text" to "add a minus one button below the tap button")
          val t2=f.getString("task_id")
          assertNotEquals(tid,t2)
          val fin2=commitVerified(t2)
          val pv2=previewMatches(made,t2,fin2)
          assertNotEquals(pv.getString("commit"),pv2.getString("commit"))
          assertTrue("follow-up skips the plan phase",ev("plan",t2)==null&&ev("question",t2)==null)
          result.put("followup_task",t2)
        }
      }
      request(1,followUp=true)
      step("drop_and_resume") {
        val before=h.stats().getInt("connections")
        val drop=h.cmd("kill")
        assertEquals(1,drop.getInt("killed"))
        waitFor("resumed with offline events") {link.status.state==LinkState.CONNECTED && link.lastSeq>=drop.getLong("head") && h.stats().getInt("connections")>before}
      }
      request(2,followUp=false,talk=true)
      step("zero_lost_events") {
        val head=h.stats().getLong("head")
        waitFor("caught up") {link.lastSeq==head}
        val seqs=link.events.map {it.getLong("seq")}
        val lost=(1L..head).count {it !in seqs.toSet()}
        result.put("lost_events",lost).put("event_count",seqs.size).put("head",head)
        assertEquals(0,lost);assertEquals(seqs.size,seqs.toSet().size)
        assertEquals((1L..head).toList(),seqs)
      }
      result.put("real_commits",if(result.getString("agent")=="replay") 0 else commits.length())
      // Two new projects plus one follow-up: three agent commits, each verified and previewed.
      result.put("host_gate_jvm_steps_ok",if(result.optInt("real_commits")==3) 1 else 0)
      result.put("transport_steps_ok",1)
    } finally {
      // 2 + request 1 (7, with the follow-up) + drop + request 2 (7, with #1035's talk) + zero_lost_events = 18 (#972: 17).
      val ok=(0 until rows.length()).count { rows.getJSONObject(it).optBoolean("ok") }
      result.put("steps_total",18).put("steps_ok",ok).put("e2e_host_steps_ok","$ok/18")
      link.shutdown();host?.close();save()
    }
  }
}
