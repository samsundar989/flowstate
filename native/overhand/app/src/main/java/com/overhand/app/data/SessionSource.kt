package com.overhand.app.data

import org.json.JSONArray
import org.json.JSONObject

fun interface HttpClient {
  fun request(method: String, path: String, body: String?): String
}

interface SessionSource {
  val recorded: Boolean
  fun decisions(): JSONArray
  fun answer(decision: JSONObject, label: String, input: String): String
}

/** Every async operation captures a token, including transitions back to the same URL. */
class ModeGeneration {
  @Volatile var token=0L
    private set
  @Synchronized fun advance(): Long { token++;return token }
  fun accepts(captured: Long)=captured==token
}
