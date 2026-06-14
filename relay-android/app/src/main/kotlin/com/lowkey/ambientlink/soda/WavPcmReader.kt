package com.lowkey.ambientlink.soda

import java.io.File

/** Minimal reader for canonical dictate fixtures: mono 16 kHz 16-bit PCM little-endian WAV. */
object WavPcmReader {
  fun readPcm16Mono16k(file: File): ByteArray? {
    val bytes = file.readBytes()
    if (bytes.size < 44 ||
      String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" ||
      String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE"
    ) {
      return null
    }
    val header = parseWavHeader(bytes) ?: return null
    if (header.channels != 1 || header.sampleRate != 16_000 || header.bitsPerSample != 16) {
      return null
    }
    return bytes.copyOfRange(header.dataStart, header.dataEnd)
  }

  private fun parseWavHeader(bytes: ByteArray): WavHeader? {
    var offset = 12
    var fmtFound = false
    var channels = 0
    var sampleRate = 0
    var bitsPerSample = 0
    while (offset + 8 <= bytes.size) {
      val chunkId = String(bytes, offset, 4, Charsets.US_ASCII)
      val chunkSize = readLeInt(bytes, offset + 4)
      val payloadStart = offset + 8
      if (chunkId == "fmt " && chunkSize >= 16) {
        channels = readLeShort(bytes, payloadStart + 2).toInt() and 0xffff
        sampleRate = readLeInt(bytes, payloadStart + 4)
        bitsPerSample = readLeShort(bytes, payloadStart + 14).toInt() and 0xffff
        fmtFound = true
      } else if (chunkId == "data" && fmtFound) {
        val dataEnd = (payloadStart + chunkSize).coerceAtMost(bytes.size)
        return WavHeader(channels, sampleRate, bitsPerSample, payloadStart, dataEnd)
      }
      offset = payloadStart + chunkSize + (chunkSize and 1)
    }
    return null
  }

  private data class WavHeader(
    val channels: Int,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val dataStart: Int,
    val dataEnd: Int,
  )

  private fun readLeInt(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xff) or
      ((bytes[offset + 1].toInt() and 0xff) shl 8) or
      ((bytes[offset + 2].toInt() and 0xff) shl 16) or
      ((bytes[offset + 3].toInt() and 0xff) shl 24)

  private fun readLeShort(bytes: ByteArray, offset: Int): Short =
    ((bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)).toShort()
}
