package com.lowkey.ambientlink.link

import kotlinx.coroutines.flow.StateFlow

/**
 * Copy of the canonical contract in ambient-link-core/contracts/GlassLink.kt.
 * Kept in-repo until the shared :core-android library lands (TODO(shared)).
 *
 * Meta implements this against the DAT capture path; the display side lives in
 * `hud/` (DatDisplaySession etc.). Shape extracted from the recovered Cosmo
 * CosmoGlassManager (see ambient-link-google/glasses_link.md), per the routing
 * plan in ambient-link-core/ROUTING.md.
 *
 * Impls MUST: expose state as StateFlow, make bind() idempotent, push media via
 * callbacks, throttle frames (1/10s) through an EphemeralBuffer, run capture in a
 * typed foreground service, and honor a settings gate.
 */
interface GlassLink {
    val connected: StateFlow<Boolean>
    val bound: StateFlow<Boolean>

    suspend fun bind()
    fun unbind()

    fun setupImageCapture(onFrame: (Frame) -> Unit)
    fun startImageCapture()
    fun stopImageCapture()

    fun startAudioCapture(onBytes: (ByteArray, Int) -> Unit)
    fun stopAudioCapture()

    fun clear()

    data class Frame(val width: Int, val height: Int, val pixels: ByteArray, val tsMillis: Long)

    companion object {
        const val DEFAULT_FRAME_INTERVAL_MS: Long = 10_000
    }
}
