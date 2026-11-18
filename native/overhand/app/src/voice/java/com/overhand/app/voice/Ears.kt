package com.overhand.app.voice

import android.content.Context
import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File

/**
 * On-device recogniser through sherpa-onnx (Apache-2.0), MIT-licensed English model. The model ships inside the
 * APK (assets/moonshine, t805) and is read straight from there; a copy pushed to the app's files folder by
 * tools/voice_judges/voicec_device.sh wins, for development.
 */
class SherpaTranscriber(private val assets: AssetManager?, private val dir: String) : Transcriber {
  val fromApk get() = assets != null
  private val recognizer by lazy {
    OfflineRecognizer(assets, OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
      moonshine = OfflineMoonshineModelConfig(encoder = "$dir/encoder_model.ort", mergedDecoder = "$dir/decoder_model_merged.ort"),
      tokens = "$dir/tokens.txt", numThreads = 2)))
  }
  /** Loads the model and runs one throwaway decode, so the first real turn does not pay the cold start. */
  fun warm() {
    recognizer
    val noise = java.util.Random(805); transcribe(FloatArray(16000) { (noise.nextFloat() - 0.5f) * 0.002f }, 16000)
  }
  override fun transcribe(samples: FloatArray, sampleRate: Int): String {
    val stream = recognizer.createStream()
    try { stream.acceptWaveform(samples, sampleRate); recognizer.decode(stream); return recognizer.getResult(stream).text }
    finally { stream.release() }
  }
}

/** Entry point the main source set reaches by reflection, so builds without the recogniser library still compile. */
object Ears {
  /** Loads and warms the model (about 1 s): call it off the UI thread. Null when there is no model. */
  @JvmStatic fun create(context: Context): Transcriber? {
    val t0 = android.os.SystemClock.elapsedRealtime()
    val pushed = File(context.getExternalFilesDir(null), "voice/moonshine")
    val ears = if (File(pushed, "encoder_model.ort").isFile) SherpaTranscriber(null, pushed.absolutePath)
      else if (runCatching { context.assets.list("moonshine")?.contains("encoder_model.ort") == true }.getOrDefault(false))
        SherpaTranscriber(context.assets, "moonshine")
      else return null
    ears.warm()
    android.util.Log.i(Voice.TAG, "ears ready source=${if (ears.fromApk) "apk" else "pushed"} warm_ms=${android.os.SystemClock.elapsedRealtime() - t0}")
    return ears
  }
}
