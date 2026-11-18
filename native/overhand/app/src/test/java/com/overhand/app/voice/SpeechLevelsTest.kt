package com.overhand.app.voice

import android.media.AudioFormat
import android.os.Looper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SpeechLevelsTest {
  @Test fun realPcmLevelsWaitForPlaybackAndStopCancelsThem() {
    val meter = SpeechLevels(); val levels = mutableListOf<Float>(); meter.listener = { levels += it }
    val pcm = ByteBuffer.allocate(1280).order(ByteOrder.LITTLE_ENDIAN)
    repeat(320) { pcm.putShort(0) }; repeat(320) { pcm.putShort(8192) }
    meter.begin("t1-1",16000,AudioFormat.ENCODING_PCM_16BIT,1)
    meter.pcm("t1-1",pcm.array())
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
    assertTrue("synthesis alone does not animate",levels.isEmpty())
    meter.start("t1-1"); shadowOf(Looper.getMainLooper()).idle()
    assertEquals(listOf(0f),levels)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(21))
    assertEquals(listOf(0f,1f),levels)
    meter.stop(); shadowOf(Looper.getMainLooper()).idle()
    assertEquals(0f,levels.last())
    val n = levels.size
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
    assertEquals(n,levels.size)
  }
  @Test fun audioArrivingAfterPlaybackStartsAlsoUsesRealSamples() {
    val meter = SpeechLevels(); val levels = mutableListOf<Float>(); meter.listener = { levels += it }
    meter.start("t2-1"); meter.begin("t2-1",16000,AudioFormat.ENCODING_PCM_8BIT,1)
    meter.pcm("t2-1",ByteArray(320) { 128.toByte() }); shadowOf(Looper.getMainLooper()).idle()
    assertEquals(listOf(0f),levels)
    meter.pcm("t2-1",ByteArray(320) { 192.toByte() })
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(21)); assertEquals(1f,levels.last())
    meter.done("t2-1"); shadowOf(Looper.getMainLooper()).idle(); assertEquals(0f,levels.last())
    meter.stop()
  }
}
