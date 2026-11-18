package com.overhand.app.voice

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The push-to-talk audio path from a WAV file to the recogniser's input, with a fake recogniser. */
class VoiceAudioTest {
  private fun wav(samples: ShortArray, rate: Int = 16000): ByteArray {
    val out = ByteArrayOutputStream()
    fun i32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
    fun i16(v: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
    out.write("RIFF".toByteArray()); i32(36 + 2 * samples.size); out.write("WAVE".toByteArray())
    out.write("fmt ".toByteArray()); i32(16); i16(1); i16(1); i32(rate); i32(rate * 2); i16(2); i16(16)
    out.write("LIST".toByteArray()); i32(3); out.write(byteArrayOf(1, 2, 3, 0)) // odd chunk, padded
    out.write("data".toByteArray()); i32(2 * samples.size); samples.forEach { i16(it.toInt()) }
    return out.toByteArray()
  }

  private class Fake : Transcriber {
    var got: FloatArray? = null; var rate = 0
    override fun transcribe(samples: FloatArray, sampleRate: Int): String { got = samples; rate = sampleRate; return "  keep it private \n" }
  }

  /** Feed a WAV through the session in 100 ms frames, the way the recorder thread does. */
  private fun hold(session: PushToTalkSession, pcm: ShortArray) {
    session.press()
    pcm.toList().chunked(1600).forEach { session.frames(it.toShortArray(), it.size) }
  }

  @Test fun wavToTranscriptKeepsEverySampleInOrder() {
    val tone = ShortArray(16000) { (12000 * sin(2 * PI * 440 * it / 16000)).toInt().toShort() }
    val pcm = Wav.read(wav(tone))
    assertEquals(16000, pcm.sampleRate); assertArrayEquals(tone, pcm.samples)
    val fake = Fake(); var t = 0L
    val session = PushToTalkSession(fake) { t.also { t += 250_000_000 } }
    hold(session, pcm.samples)
    val r = session.release()
    assertEquals("keep it private", r.text); assertEquals(250, r.decodeMs); assertEquals(1.0f, r.audioSeconds, 1e-6f)
    assertEquals(16000, fake.rate)
    assertArrayEquals(FloatArray(16000) { tone[it] / 32768f }, fake.got, 0f)
    assertFalse(session.holding)
  }

  @Test fun framesOutsideAHoldAreIgnoredAndShortHoldsAreNotDecoded() {
    val fake = Fake(); val session = PushToTalkSession(fake)
    session.frames(ShortArray(1600), 1600)
    assertEquals(0, session.buffer.size)
    hold(session, ShortArray(3200)) // 0.2 s
    assertTrue(session.release().tooShort); assertEquals(null, fake.got)
  }

  @Test fun aStuckHoldIsCappedAtThirtySeconds() {
    val b = PcmBuffer(maxSeconds = 30)
    repeat(31 * 10) { b.append(ShortArray(1600) { 1 }, 1600) }
    assertEquals(30 * 16000, b.size); assertTrue(b.truncated)
    b.clear(); assertEquals(0, b.size); assertFalse(b.truncated)
  }

  @Test fun rejectsWavsTheCapturePathNeverProduces() {
    val stereo = wav(ShortArray(4)).also { it[22] = 2 }
    assertTrue(runCatching { Wav.read(stereo) }.exceptionOrNull()!!.message!!.contains("mono"))
    assertTrue(runCatching { Wav.read(ByteArray(8)) }.isFailure)
  }

  /** A real corpus sentence (synthetic voice, data/voice/corpus) when this machine has it. */
  @Test fun realCorpusWavReachesTheRecogniserIntact() {
    val f = File("../../../../data/voice/corpus/project/sapi_zira/14.wav").takeIf { it.isFile }
      ?: File("/opt/flowstate/Documents/example-project/data/voice/corpus/project/sapi_zira/14.wav")
    assumeTrue("corpus not present", f.isFile)
    val pcm = Wav.read(f.readBytes())
    assertEquals(16000, pcm.sampleRate)
    val fake = Fake(); val session = PushToTalkSession(fake)
    hold(session, pcm.samples); val r = session.release()
    assertFalse(r.tooShort); assertEquals(pcm.samples.size, fake.got!!.size)
    assertEquals(pcm.samples.size / 16000f, r.audioSeconds, 1e-4f)
    assertTrue("speech should not be silent", fake.got!!.any { kotlin.math.abs(it) > 0.05f })
  }
}
