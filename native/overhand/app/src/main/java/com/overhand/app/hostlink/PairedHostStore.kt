package com.overhand.app.hostlink

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The one place the paired computer's identity lives: fingerprint, name, address, and the credential issued at pairing. */
interface PairedHostStore {
  fun load(): PairedHost?
  fun save(host: PairedHost)
  fun clear()
}

/** JVM tests, and a session that must not persist anything. */
class InMemoryPairedHostStore(@Volatile private var host: PairedHost?=null): PairedHostStore {
  override fun load()=host
  override fun save(host: PairedHost) { this.host=host }
  override fun clear() { host=null }
}

internal object PairedHostJson {
  fun encode(h: PairedHost)=JSONObject().put("address",h.endpoint.address).put("port",h.endpoint.port).put("fp",h.fp)
    .put("name",h.name).put("device_id",h.deviceId).put("device_secret",h.deviceSecret).toString()
  fun decode(s: String)=JSONObject(s).let {
    PairedHost(Endpoint(it.getString("address"),it.getInt("port"),it.getString("fp"),it.getString("name")),
      it.getString("device_id"),it.getString("device_secret"))
  }
}

/**
 * Device storage: the record is AES-GCM encrypted with a key that never leaves the Android Keystore, and the
 * ciphertext sits in private preferences. Not exercised by the JVM tests (no Keystore there); the wiring task
 * checks it on the headset.
 */
class KeystorePairedHostStore(context: Context): PairedHostStore {
  private val prefs=context.getSharedPreferences("hostlink-v1",Context.MODE_PRIVATE)
  private fun key(): SecretKey {
    val ks=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (ks.getKey(ALIAS,null) as? SecretKey)?.let { return it }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
      init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256).build())
    }.generateKey()
  }
  override fun load(): PairedHost? {
    val blob=prefs.getString(FIELD,null) ?: return null
    return runCatching {
      val raw=Base64.decode(blob,Base64.NO_WRAP)
      val c=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,raw,0,12)) }
      PairedHostJson.decode(String(c.doFinal(raw,12,raw.size-12),Charsets.UTF_8))
    }.getOrNull()
  }
  override fun save(host: PairedHost) {
    val c=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
    val body=c.doFinal(PairedHostJson.encode(host).toByteArray(Charsets.UTF_8))
    prefs.edit().putString(FIELD,Base64.encodeToString(c.iv+body,Base64.NO_WRAP)).commit()
  }
  override fun clear() { prefs.edit().remove(FIELD).commit() }
  private companion object { const val ALIAS="hostlink-paired-host";const val FIELD="paired" }
}
