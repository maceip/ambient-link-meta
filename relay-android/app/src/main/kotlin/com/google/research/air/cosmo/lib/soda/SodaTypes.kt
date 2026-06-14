package com.google.research.air.cosmo.lib.soda

import java.io.File
import java.time.Instant

fun interface SodaTranscriptCallback {
  fun onTranscript(text: String, isFinal: Boolean)
  fun onDirectednessTrigger(at: Instant) = Unit
  fun onSessionEnded() = Unit
}

sealed class SodaPrepareResult {
  data class Available(val packDir: File) : SodaPrepareResult()
  data class Unavailable(val reason: String) : SodaPrepareResult()
}

sealed class SodaStartResult {
  data object Started : SodaStartResult()
  data class Failed(val reason: String) : SodaStartResult()
}
