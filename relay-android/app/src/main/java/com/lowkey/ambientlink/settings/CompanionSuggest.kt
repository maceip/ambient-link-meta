package com.lowkey.ambientlink.settings

import android.content.Context
import android.util.Log
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.lowkey.ambientlink.hud.AgentYank
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Companion suggestions — one prefs log, one optional on-device AI call, simple fallbacks.
 * Record usage via [noteYank]; refresh UI via [load] when the app opens.
 */
object CompanionSuggest {
  private const val TAG = "CompanionSuggest"
  private const val KEY_LOG = "companion_log"
  private const val MAX_LOG = 24

  data class SnoozeOption(val label: String, val minutes: Int)

  data class Result(
    val quickReplies: List<String> = emptyList(),
    val snooze: List<SnoozeOption> = emptyList(),
    val fromAi: Boolean = false,
    val aiReady: Boolean = false,
    val aiCore: AiCoreProbe.Status = AiCoreProbe.Status(AiCoreProbe.Tier.UNSUPPORTED),
  )

  private fun prefs(ctx: Context) =
    ctx.getSharedPreferences(UserPrefs.PREFS, Context.MODE_PRIVATE)

  /** Append one line when the relay pushes session state we can learn from. */
  fun noteYank(ctx: Context, yank: AgentYank) {
    val user = yank.lastUserInput.trim().take(120)
    val agent = yank.lastAssistant.trim().take(120)
    if (user.isBlank() && agent.isBlank()) return
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val line = "$hour|$user|$agent|${yank.awaiting.name.lowercase()}"
    val arr = try {
      JSONArray(prefs(ctx).getString(KEY_LOG, "[]"))
    } catch (_: Exception) {
      JSONArray()
    }
    val next = JSONArray()
    next.put(line)
    for (i in 0 until arr.length()) {
      if (next.length() >= MAX_LOG) break
      if (arr.optString(i) != line) next.put(arr.optString(i))
    }
    prefs(ctx).edit().putString(KEY_LOG, next.toString()).apply()
  }

  fun hasData(ctx: Context): Boolean = logLines(ctx).isNotEmpty()

  suspend fun load(ctx: Context): Result = withContext(Dispatchers.Default) {
    val aiCore = AiCoreProbe.probe()
    val lines = logLines(ctx)
    if (lines.isEmpty()) return@withContext Result(aiCore = aiCore, aiReady = aiCore.isReady)
    if (aiCore.isReady) {
      try {
        nanoSuggest(lines)?.let {
          return@withContext it.copy(aiReady = true, aiCore = aiCore)
        }
      } catch (e: Exception) {
        Log.w(TAG, "nano: ${e.message}")
      }
    }
    fallback(lines).copy(aiReady = aiCore.isReady, aiCore = aiCore)
  }

  private fun logLines(ctx: Context): List<String> {
    return try {
      val arr = JSONArray(prefs(ctx).getString(KEY_LOG, "[]"))
      buildList {
        for (i in 0 until arr.length()) {
          val s = arr.optString(i, "").trim()
          if (s.isNotEmpty()) add(s)
        }
      }
    } catch (_: Exception) {
      emptyList()
    }
  }

  private suspend fun nanoSuggest(lines: List<String>): Result? {
    val blob = lines.take(16).joinToString("\n") { "- $it" }
    val prompt = """
      App activity log (hour|user|agent|state). Suggest 3 quick replies (under 28 chars)
      and 3 snooze options for peak chat hours. JSON only:
      {"replies":["a","b","c"],"snooze":[{"label":"short","minutes":60}]}

      $blob
    """.trimIndent()
    val raw = Generation.getClient()
      .generateContent(generateContentRequest(TextPart(prompt)) { temperature = 0.2f; maxOutputTokens = 180 })
      .candidates.firstOrNull()?.text?.trim().orEmpty()
    val json = extractJson(raw) ?: return null
    val replies = json.optJSONArray("replies")?.let { arr ->
      buildList { for (i in 0 until arr.length()) arr.optString(i).trim().takeIf { it.isNotEmpty() }?.let { add(it.take(48)) } }
    }.orEmpty().take(3)
    val snooze = json.optJSONArray("snooze")?.let { arr ->
      buildList {
        for (i in 0 until arr.length()) {
          val o = arr.optJSONObject(i) ?: continue
          val label = o.optString("label").trim()
          val mins = o.optInt("minutes", 0)
          if (label.isNotEmpty() && mins in 5..480) add(SnoozeOption(label.take(32), mins))
        }
      }
    }.orEmpty().take(3)
    if (replies.isEmpty() && snooze.isEmpty()) return null
    return Result(replies, snooze, fromAi = true)
  }

  private fun fallback(lines: List<String>): Result {
    val replies = buildList {
      for (line in lines) {
        val state = line.substringAfterLast('|')
        when {
          state == "permission" -> add("approve")
          state == "question" -> add("yes")
          line.contains('?') -> add("explain more")
        }
        if (size >= 3) break
      }
      for (d in listOf("continue", "looks good", "explain more")) {
        if (size >= 3) break
        if (d !in this) add(d)
      }
    }.distinct().take(3)

    val hours = lines.mapNotNull { it.substringBefore('|').toIntOrNull() }
    val snooze = if (hours.isEmpty()) {
      UserPrefs.SUGGESTED_SNOOZE_MINUTES.map { SnoozeOption(formatMin(it), it) }
    } else {
      hours.groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }
        .take(3)
        .map { (h, _) -> SnoozeOption(peakLabel(h), peakMinutes(h)) }
    }
    return Result(replies, snooze)
  }

  private fun peakLabel(h: Int): String {
    val end = (h + 1).coerceAtMost(23)
    fun clock(x: Int) = when {
      x == 0 || x == 12 -> "12"
      x > 12 -> "${x - 12}"
      else -> "$x"
    }
    val ap = if (h < 12) "am" else "pm"
    return "busy ${clock(h)}–${clock(end)}$ap"
  }

  private fun peakMinutes(h: Int): Int {
    val now = Calendar.getInstance()
    val nowH = now.get(Calendar.HOUR_OF_DAY)
    val nowM = now.get(Calendar.MINUTE)
    return if (h >= nowH) ((h - nowH) * 60 + (60 - nowM)).coerceIn(15, 480) else 60
  }

  private fun formatMin(m: Int) = when {
    m < 60 -> "${m}m"
    m % 60 == 0 -> "${m / 60}h"
    else -> "${m / 60}h ${m % 60}m"
  }

  private fun extractJson(raw: String): JSONObject? {
    val s = raw.indexOf('{')
    val e = raw.lastIndexOf('}')
    if (s < 0 || e <= s) return null
    return try { JSONObject(raw.substring(s, e + 1)) } catch (_: Exception) { null }
  }
}
