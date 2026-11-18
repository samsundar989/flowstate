package com.overhand.app.hostlink

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Finds hosts on the local network. Hosts are matched by certificate fingerprint, never by name or address alone. */
interface HostDiscovery {
  /** Every host seen within [timeoutMs] (the pairing list for the 6-digit path). */
  fun browse(timeoutMs: Long): List<Endpoint>
  /** The current address of the host with this fingerprint, or null. */
  fun resolve(fp: String,timeoutMs: Long): Endpoint?=browse(timeoutMs).firstOrNull { it.fp==fp }
}

/** No discovery: the stored address is the only one tried. */
object NoDiscovery: HostDiscovery { override fun browse(timeoutMs: Long)=emptyList<Endpoint>() }

/**
 * mDNS / DNS-SD over the platform [NsdManager] (`_flowstate._tcp`, TXT `v`, `fp`, `n`). Prefers IPv4: the host
 * binds only its LAN IPv4. Device-only; the JVM tests use a fake. Call from a background thread (it blocks).
 */
class NsdHostDiscovery(context: Context): HostDiscovery {
  private val nsd=context.getSystemService(Context.NSD_SERVICE) as NsdManager
  override fun browse(timeoutMs: Long): List<Endpoint> {
    val found=ConcurrentHashMap<String,Endpoint>()
    val listener=object: NsdManager.DiscoveryListener {
      override fun onServiceFound(info: NsdServiceInfo) {
        @Suppress("DEPRECATION")
        nsd.resolveService(info,object: NsdManager.ResolveListener {
          override fun onResolveFailed(i: NsdServiceInfo,code: Int) {}
          override fun onServiceResolved(i: NsdServiceInfo) {
            val txt=i.attributes.mapValues { (_,v) -> v?.toString(Charsets.UTF_8).orEmpty() }
            @Suppress("DEPRECATION") val addr=i.host ?: return
            val fp=txt["fp"].orEmpty()
            if(txt["v"]!=Wire.VERSION.toString() || !Wire.isFingerprint(fp)) return
            // Keep an IPv4 if one is seen for this host.
            if(found[fp]==null || addr is Inet4Address) found[fp]=Endpoint(addr.hostAddress ?: return,i.port,fp,txt["n"] ?: i.serviceName)
          }
        })
      }
      override fun onServiceLost(info: NsdServiceInfo) {}
      override fun onDiscoveryStarted(type: String) {}
      override fun onDiscoveryStopped(type: String) {}
      override fun onStartDiscoveryFailed(type: String,code: Int) {}
      override fun onStopDiscoveryFailed(type: String,code: Int) {}
    }
    nsd.discoverServices(Wire.SERVICE_TYPE,NsdManager.PROTOCOL_DNS_SD,listener)
    try { CountDownLatch(1).await(timeoutMs,TimeUnit.MILLISECONDS) } finally { runCatching { nsd.stopServiceDiscovery(listener) } }
    return found.values.sortedBy { it.name }
  }
}
