package com.overhand.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One atomic file, synchronized within the app's single store instance. */
class LocalReceiptStore(private val file: File) {
  private var receipts=if(file.exists()) JSONArray(file.readText()) else JSONArray()
  @Synchronized fun find(hash: String, id: String): JSONObject? = (0 until receipts.length())
    .map { receipts.getJSONObject(it) }.firstOrNull { it.getString("fixture_hash")==hash && it.getString("decision_id")==id }
  @Synchronized fun save(hash: String,id: String,option: String,label: String,input: String,recordedMs: Long,utcMs: Long=System.currentTimeMillis()): JSONObject {
    find(hash,id)?.let { return it }
    val receipt=JSONObject().put("fixture_hash",hash).put("decision_id",id).put("option_id",option)
      .put("option_label",label).put("input_method",input).put("recorded_time_ms",recordedMs)
      .put("device_utc_ms",utcMs).put("mode","recorded")
    val next=JSONArray(receipts.toString()).put(receipt)
    persist(next);return receipt
  }
  @Synchronized fun clear() { persist(JSONArray()) }
  private fun persist(next: JSONArray) {
    file.parentFile?.mkdirs()
    val temp=File(file.path+".tmp")
    try {
      FileOutputStream(temp).use { it.write(next.toString().toByteArray(Charsets.UTF_8));it.fd.sync() }
      Files.move(temp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
      receipts=next
    } finally { temp.delete() }
  }
}
