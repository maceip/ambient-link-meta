package com.lowkey.ambientlink.soda

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** 16 kHz mono PCM16 — prefers glasses/headset HFP when routed via Bluetooth SCO. */
class MicCapture(
  private val context: Context,
  private val useBluetoothSco: Boolean = false,
  private val onPcmFrame: (ByteArray, Int) -> Unit,
) {
  companion object {
    const val SAMPLE_RATE_HZ = 16_000
    const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
    const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    const val FRAME_BYTES = 1280
  }

  fun hasPermission(): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
      PackageManager.PERMISSION_GRANTED

  private val running = AtomicBoolean(false)
  private var audioManager: AudioManager? = null

  @SuppressLint("MissingPermission")
  fun start(): Boolean {
    if (!hasPermission()) return false
    if (!running.compareAndSet(false, true)) return true

    val am = context.getSystemService(AudioManager::class.java)
    audioManager = am
    if (useBluetoothSco && am.isBluetoothScoAvailableOffCall) {
      am.mode = AudioManager.MODE_IN_COMMUNICATION
      runCatching {
        if (am.isBluetoothScoOn) {
          am.stopBluetoothSco()
          am.isBluetoothScoOn = false
          Thread.sleep(300)
        }
        am.startBluetoothSco()
        am.isBluetoothScoOn = true
      }
      var waited = 0
      while (!am.isBluetoothScoOn && waited < 60) {
        Thread.sleep(50)
        waited++
      }
    }
    val source = when {
      useBluetoothSco && am.isBluetoothScoOn -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
      else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL, ENCODING)
      .coerceAtLeast(FRAME_BYTES * 4)

    val record = AudioRecord(
      source,
      SAMPLE_RATE_HZ,
      CHANNEL,
      ENCODING,
      minBuf,
    )

    if (record.state != AudioRecord.STATE_INITIALIZED) {
      running.set(false)
      record.release()
      return false
    }

    record.startRecording()
    thread(name = "SodaMicCapture", isDaemon = true) {
      val buf = ByteArray(FRAME_BYTES)
      try {
        while (running.get()) {
          val n = record.read(buf, 0, FRAME_BYTES)
          if (n > 0) onPcmFrame(buf, n)
        }
      } finally {
        record.stop()
        record.release()
      }
    }
    return true
  }

  fun stop() {
    running.set(false)
    audioManager?.let { am ->
      runCatching {
        if (useBluetoothSco) {
          am.stopBluetoothSco()
          am.isBluetoothScoOn = false
        }
        am.mode = AudioManager.MODE_NORMAL
      }
    }
    audioManager = null
  }
}
