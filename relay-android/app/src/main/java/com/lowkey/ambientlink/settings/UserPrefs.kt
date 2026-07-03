package com.lowkey.ambientlink.settings

import android.content.Context
import org.json.JSONArray

/** Phone-side companion settings (quick replies + snooze). */
object UserPrefs {
  const val PREFS = "ambient-link-meta"
  private const val KEY_QUICK_REPLIES = "quick_replies_json"
  private const val KEY_SNOOZE_UNTIL_MS = "snooze_until_ms"
  private const val KEY_ONBOARDING_DONE = "companion_onboarding_done"
  private const val KEY_COMPANION_TIP_SEEN = "companion_tip_seen"
  private const val KEY_SNOOZE_DURATION_MS = "snooze_duration_ms"

  val DEFAULT_QUICK_REPLIES = listOf("continue", "looks good", "explain more")
  val SUGGESTED_QUICK_REPLIES = listOf("continue", "looks good", "explain more")
  val SUGGESTED_SNOOZE_MINUTES = listOf(15, 60, 240)

  private fun prefs(ctx: Context) =
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun getQuickReplies(ctx: Context): List<String> {
    val raw = prefs(ctx).getString(KEY_QUICK_REPLIES, null) ?: return DEFAULT_QUICK_REPLIES
    return try {
      val arr = JSONArray(raw)
      buildList {
        for (i in 0 until arr.length()) {
          val s = arr.optString(i, "").trim()
          if (s.isNotEmpty()) add(s)
        }
      }.ifEmpty { DEFAULT_QUICK_REPLIES }
    } catch (_: Exception) {
      DEFAULT_QUICK_REPLIES
    }
  }

  fun setQuickReplies(ctx: Context, replies: List<String>) {
    val clean = replies.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(12)
    val arr = JSONArray()
    clean.forEach { arr.put(it) }
    prefs(ctx).edit().putString(KEY_QUICK_REPLIES, arr.toString()).apply()
  }

  fun getSnoozeUntilMs(ctx: Context): Long =
    prefs(ctx).getLong(KEY_SNOOZE_UNTIL_MS, 0L)

  fun setSnoozeUntilMs(ctx: Context, untilMs: Long) {
    prefs(ctx).edit().putLong(KEY_SNOOZE_UNTIL_MS, untilMs.coerceAtLeast(0L)).apply()
  }

  fun snoozeDurationMs(ctx: Context): Long =
    prefs(ctx).getLong(KEY_SNOOZE_DURATION_MS, 15L * 60_000)

  fun setSnoozeDurationMs(ctx: Context, ms: Long) {
    prefs(ctx).edit().putLong(KEY_SNOOZE_DURATION_MS, ms.coerceAtLeast(60_000)).apply()
  }

  fun isSnoozing(ctx: Context): Boolean =
    System.currentTimeMillis() < getSnoozeUntilMs(ctx)

  fun activateSnooze(ctx: Context, durationMs: Long) {
    setSnoozeDurationMs(ctx, durationMs)
    setSnoozeUntilMs(ctx, System.currentTimeMillis() + durationMs)
  }

  fun clearSnooze(ctx: Context) {
    setSnoozeUntilMs(ctx, 0L)
  }

  fun isOnboardingDone(ctx: Context): Boolean =
    prefs(ctx).getBoolean(KEY_ONBOARDING_DONE, false)

  fun setOnboardingDone(ctx: Context) {
    prefs(ctx).edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
  }

  fun hasSeenCompanionTip(ctx: Context): Boolean =
    prefs(ctx).getBoolean(KEY_COMPANION_TIP_SEEN, false)

  fun setCompanionTipSeen(ctx: Context) {
    prefs(ctx).edit().putBoolean(KEY_COMPANION_TIP_SEEN, true).apply()
  }

  private const val KEY_SHOW_CONTINUE = "chip_show_continue"
  private const val KEY_SHOW_DICTATE = "chip_show_dictate"
  private const val KEY_AUTO_CONTINUE = "chip_auto_continue"
  private const val KEY_DEFAULT_AGENT = "default_agent"

  val DEFAULT_AGENTS = listOf("cursor", "claude", "codex")

  fun getDefaultAgent(ctx: Context): String {
    val raw = prefs(ctx).getString(KEY_DEFAULT_AGENT, "cursor")?.lowercase() ?: "cursor"
    return if (raw in DEFAULT_AGENTS) raw else "cursor"
  }

  fun setDefaultAgent(ctx: Context, agent: String) {
    val clean = agent.lowercase().trim()
    val value = if (clean in DEFAULT_AGENTS) clean else "cursor"
    prefs(ctx).edit().putString(KEY_DEFAULT_AGENT, value).apply()
  }

  fun showContinueChip(ctx: Context): Boolean =
    prefs(ctx).getBoolean(KEY_SHOW_CONTINUE, true)

  fun setShowContinueChip(ctx: Context, on: Boolean) {
    prefs(ctx).edit().putBoolean(KEY_SHOW_CONTINUE, on).apply()
  }

  fun showDictateChip(ctx: Context): Boolean =
    prefs(ctx).getBoolean(KEY_SHOW_DICTATE, true)

  fun setShowDictateChip(ctx: Context, on: Boolean) {
    prefs(ctx).edit().putBoolean(KEY_SHOW_DICTATE, on).apply()
  }

  /** When on, done-cards auto-tap the primary chip after a short countdown. */
  fun autoContinueEnabled(ctx: Context): Boolean =
    prefs(ctx).getBoolean(KEY_AUTO_CONTINUE, true)

  fun setAutoContinueEnabled(ctx: Context, on: Boolean) {
    prefs(ctx).edit().putBoolean(KEY_AUTO_CONTINUE, on).apply()
  }

  private const val KEY_UI_THEME = "ui_theme"

  val UI_THEMES = listOf("meta", "dracula", "tokyo-night", "catppuccin", "nord")

  fun getUiTheme(ctx: Context): String {
    val raw = prefs(ctx).getString(KEY_UI_THEME, "meta")?.lowercase() ?: "meta"
    return if (raw in UI_THEMES) raw else "meta"
  }

  fun setUiTheme(ctx: Context, theme: String) {
    val clean = theme.lowercase().trim()
    val value = if (clean in UI_THEMES) clean else "meta"
    prefs(ctx).edit().putString(KEY_UI_THEME, value).apply()
  }
}
