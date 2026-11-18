package com.overhand.app.hostlink

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * #814, seen on the Quest 2: pairing restarts the session, the old activity's link is shut down while its first connection
 * is still in flight, and OkHttp's failure callback then hit the terminated executor (RejectedExecutionException on the
 * dispatcher thread), which killed the app. A late callback must be dropped quietly.
 */
class HostLinkShutdownTest {
  @Test fun aCallbackAfterShutdownDoesNotThrowOnAnyThread() {
    val uncaught=CopyOnWriteArrayList<Throwable>()
    val before=Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { _,e -> uncaught.add(e) }
    val server=ServerSocket(0,1,InetAddress.getLoopbackAddress())
    val accepted=LinkedBlockingQueue<Socket>()
    Thread { runCatching { while(true) accepted.put(server.accept()) } }.apply { isDaemon=true;start() }
    try {
      val store=InMemoryPairedHostStore().apply { save(PairedHost(Endpoint("127.0.0.1",server.localPort,"a".repeat(64),"PC"),"d_1","secret")) }
      val link=HostLink(store,NoDiscovery,"Headset",{ true })
      link.start()
      val conn=accepted.poll(5,TimeUnit.SECONDS)
      assertNotNull("the link opened its connection",conn)
      link.shutdown()            // the activity is destroyed while the TLS handshake is in flight
      conn!!.close()             // ... then the socket fails: OkHttp calls onFailure after shutdown
      Thread.sleep(1500)
      assertEquals("no exception escaped to any thread: $uncaught",0,uncaught.size)
      link.stop()                // stop() after shutdown returns instead of blocking on a discarded task
    } finally { Thread.setDefaultUncaughtExceptionHandler(before);server.close() }
  }
}
