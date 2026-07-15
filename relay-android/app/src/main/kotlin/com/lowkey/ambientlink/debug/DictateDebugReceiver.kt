package com.lowkey.ambientlink.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.lowkey.ambientlink.BuildConfig
import com.lowkey.ambientlink.hud.AgentYank
import com.lowkey.ambientlink.hud.Awaiting
import com.lowkey.ambientlink.relay.RelayService
import com.lowkey.ambientlink.soda.SodaFixtureRunner

/**
 * Debug adb hooks — fixture STT tests and glasses HUD yank without touching the phone.
 */
class DictateDebugReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    when (intent.action) {
      ACTION_FIXTURE -> {
        val path = intent.getStringExtra(EXTRA_PATH)?.takeIf { it.isNotBlank() } ?: return
        val expect = intent.getStringExtra(EXTRA_EXPECT)?.takeIf { it.isNotBlank() }
        SodaFixtureRunner(context.applicationContext).run(path, expect) { _, _ -> }
      }
      ACTION_YANK -> {
        val prompt = intent.getStringExtra(EXTRA_PROMPT)?.takeIf { it.isNotBlank() }
          ?: run {
            Log.w(TAG, "DEBUG_HUD_YANK missing prompt extra")
            return
          }
        val thread = intent.getStringExtra(EXTRA_THREAD) ?: "cursor"
        Log.i(TAG, "debug yank thread=$thread")
        RelayService.debugYank(
          AgentYank(
            thread = thread,
            label = thread,
            agent = "cursor",
            lastAssistant = prompt,
            awaiting = Awaiting.QUESTION,
          ),
        )
      }
      ACTION_INPUT -> {
        val thread = intent.getStringExtra(EXTRA_THREAD) ?: return
        val text = intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: return
        val id = RelayService.debugSendInput(thread, text)
        Log.i(TAG, "debug input thread=$thread id=$id")
      }
    }
  }

  companion object {
    const val ACTION_FIXTURE = "com.lowkey.ambientlink.DEBUG_SODA_FIXTURE"
    const val ACTION_YANK = "com.lowkey.ambientlink.DEBUG_HUD_YANK"
    const val ACTION_INPUT = "com.lowkey.ambientlink.DEBUG_SEND_INPUT"
    const val EXTRA_PATH = "path"
    const val EXTRA_EXPECT = "expect"
    const val EXTRA_PROMPT = "prompt"
    const val EXTRA_THREAD = "thread"
    const val EXTRA_TEXT = "text"
    private const val TAG = "DictateDebug"

    fun registerIfDebug(context: Context) {
      if (!BuildConfig.DEBUG) return
      val filter = IntentFilter().apply {
        addAction(ACTION_FIXTURE)
        addAction(ACTION_YANK)
        addAction(ACTION_INPUT)
      }
      context.registerReceiver(DictateDebugReceiver(), filter, Context.RECEIVER_EXPORTED)
      Log.i(TAG, "registered debug broadcasts (debug builds only)")
    }
  }
}
