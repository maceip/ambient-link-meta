package com.lowkey.facechat.soda

import android.content.Context
import android.util.Log
import com.google.research.air.cosmo.lib.soda.SodaPrepareResult
import com.google.research.air.cosmo.lib.soda.SodaSession
import com.google.research.air.cosmo.lib.soda.SodaStartResult
import com.google.research.air.cosmo.lib.soda.SodaTranscriptCallback
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Feeds a canonical 16 kHz mono PCM WAV through SODA the same way neural's
 * GatekeeperDebugReceiver does for adb-driven device tests.
 */
class SodaFixtureRunner(private val context: Context) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  fun run(
    wavPath: String,
    expectSubstring: String?,
    onDone: (passed: Boolean, transcript: String) -> Unit,
  ) {
    scope.launch {
      val result = runBlocking(wavPath, expectSubstring)
      onDone(result.passed, result.transcript)
    }
  }

  private suspend fun runBlocking(
    wavPath: String,
    expectSubstring: String?,
  ): FixtureResult = withContext(Dispatchers.IO) {
    val file = File(wavPath)
    if (!file.isFile || !file.canRead()) {
      Log.w(TAG, "fixture unreadable path=$wavPath")
      return@withContext FixtureResult(false, "")
    }
    val pcm = WavPcmReader.readPcm16Mono16k(file)
    if (pcm == null) {
      Log.w(TAG, "fixture not 16k mono pcm16 wav path=$wavPath size=${file.length()}")
      return@withContext FixtureResult(false, "")
    }
    if (!SodaRuntime.isAvailable(context)) {
      Log.w(TAG, "fixture soda unavailable")
      return@withContext FixtureResult(false, "")
    }
    val pack = SodaRuntime.preparePack(context)
    val packDir = when (pack) {
      is SodaPrepareResult.Available -> pack.packDir
      is SodaPrepareResult.Unavailable -> {
        Log.w(TAG, "fixture pack unavailable: ${pack.reason}")
        return@withContext FixtureResult(false, "")
      }
    }
    val chunks = pcm.toList().chunked(FRAME_BYTES).map { it.toByteArray() }
    val finals = mutableListOf<String>()
    val session = SodaSession(context, packDir, Clock.systemUTC())
    val started = session.start(
      SodaTranscriptCallback { text, isFinal ->
        if (!isFinal || text.isBlank()) return@SodaTranscriptCallback
        val trimmed = text.trim()
        finals.add(trimmed)
        Log.i(TAG, "audio_fixture_final: name=${file.name} text=$trimmed")
      },
    )
    if (started !is SodaStartResult.Started) {
      Log.w(TAG, "fixture session start failed: $started")
      session.stop()
      return@withContext FixtureResult(false, "")
    }
    val trailingSilence = List(TRAILING_SILENCE_CHUNKS) { ByteArray(FRAME_BYTES) }
    Log.i(
      TAG,
      "audio_fixture_start: name=${file.name} bytes=${pcm.size} chunks=${chunks.size} expect=$expectSubstring",
    )
    (chunks + trailingSilence).forEach { chunk ->
      val direct = ByteBuffer.allocateDirect(chunk.size).order(ByteOrder.nativeOrder())
      direct.put(chunk)
      direct.position(0)
      session.addAudio(direct, chunk.size)
      delay(CHUNK_DELAY_MS)
    }
    delay(SESSION_STOP_DELAY_MS)
    session.stop()
    val transcript = finals.lastOrNull().orEmpty()
    val passed = when {
      transcript.isBlank() -> false
      expectSubstring.isNullOrBlank() -> true
      else -> transcript.contains(expectSubstring, ignoreCase = true)
    }
    if (passed) {
      Log.i(TAG, "audio_fixture_pass: name=${file.name} transcript=$transcript")
    } else {
      Log.w(
        TAG,
        "audio_fixture_fail: name=${file.name} expected~$expectSubstring transcript=$transcript",
      )
    }
    FixtureResult(passed, transcript)
  }

  private data class FixtureResult(val passed: Boolean, val transcript: String)

  companion object {
    const val TAG = "DictateDebug"
    const val FRAME_BYTES = 1280 // 40 ms @ 16 kHz mono PCM16
    private const val CHUNK_DELAY_MS = 40L
    private const val TRAILING_SILENCE_CHUNKS = 50
    private const val SESSION_STOP_DELAY_MS = 10_000L
  }
}
