package com.lowkey.ambientlink.soda

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.research.air.cosmo.lib.soda.SodaPrepareResult
import com.google.research.air.cosmo.lib.soda.SodaSession
import com.google.research.air.cosmo.lib.soda.SodaStartResult
import com.google.research.air.cosmo.lib.soda.SodaTranscriptCallback
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Clock
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SodaSmokeTest {
  @Test
  fun sodaNativeAvailableAndAcceptsPcm() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    assertTrue("libsoda_dev_jni must load on device", SodaRuntime.isAvailable(context))

    val pack = SodaRuntime.preparePack(context)
    assertTrue("lp_cpu pack must extract", pack is SodaPrepareResult.Available)
    val packDir = (pack as SodaPrepareResult.Available).packDir

    val session = SodaSession(context, packDir, Clock.systemUTC())
    assertTrue(session.start(SodaTranscriptCallback { _, _ -> }) is SodaStartResult.Started)

    val pcmBytes = 1280
    val pcm = ByteBuffer.allocateDirect(pcmBytes).order(ByteOrder.nativeOrder())
    repeat(pcmBytes / 2) { pcm.putShort(0) }
    pcm.position(0)
    session.addAudio(pcm, pcmBytes)
    session.stop()
  }
}
