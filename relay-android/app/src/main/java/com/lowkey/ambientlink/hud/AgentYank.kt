package com.lowkey.ambientlink.hud

enum class Awaiting { PERMISSION, QUESTION, DONE }

private val chipActionSuffix = Regex(
  """\s+[—–-]\s+(continue|dictate|dismiss)(\s*\|\s*(continue|dictate|dismiss))+\.?\s*$""",
  RegexOption.IGNORE_CASE,
)

private const val ASK_MAX_CHARS = 120
private const val ASK_MAX_LINES = 3
private const val READY_USER_CHARS = 40

private fun cleanAssistant(text: String): String =
  text.replace(chipActionSuffix, "").trim()

private fun isDiffLike(text: String): Boolean {
  if (text.contains(Regex("""^diff --git """, RegexOption.MULTILINE))) return true
  if (text.contains(Regex("""^(\+\+\+|---|@@\s)""", RegexOption.MULTILINE))) return true
  val lines = text.lines()
  var diffish = 0
  for (i in 0 until minOf(lines.size, 40)) {
    if (lines[i].isNotEmpty() && lines[i][0] in "+-@\\") diffish++
  }
  return diffish >= 12
}

private fun isCodeDump(text: String): Boolean {
  val fences = Regex("```").findAll(text).count()
  return fences >= 4 && text.length > 600
}

private fun isTableLike(text: String): Boolean {
  var pipes = 0
  for (line in text.lines().asSequence().filter { it.isNotBlank() }.take(24)) {
    if (line.count { it == '|' } >= 2) pipes++
  }
  return pipes >= 3
}

private fun isDump(text: String): Boolean =
  isDiffLike(text) || isCodeDump(text) || isTableLike(text)

private fun clampAsk(text: String): String {
  val lines = text.trim().lines().take(ASK_MAX_LINES).joinToString("\n").trim()
  if (lines.isEmpty()) return ""
  if (lines.length <= ASK_MAX_CHARS) return lines
  return lines.take(ASK_MAX_CHARS - 1).trimEnd() + "…"
}

/** Prefer last "?…" sentence; else last short paragraph; else clamped head. */
private fun extractAsk(text: String): String {
  val cleaned = cleanAssistant(text)
  if (cleaned.isEmpty() || isDump(cleaned)) return ""

  val chunks = cleaned.split(Regex("""(?<=[.!?])(?:\s+|\n+)"""))
  for (i in chunks.indices.reversed()) {
    val s = chunks[i].trim()
    if (s.contains('?')) return clampAsk(s)
  }
  val lines = cleaned.lines().map { it.trim() }.filter { it.isNotEmpty() }
  for (i in lines.indices.reversed()) {
    if (lines[i].contains('?')) return clampAsk(lines[i])
  }
  val paras = cleaned.split(Regex("""\n\s*\n""")).map { it.trim() }.filter { it.isNotEmpty() }
  val last = paras.lastOrNull() ?: cleaned
  if (last.length <= ASK_MAX_CHARS && last.lines().size <= ASK_MAX_LINES) return last
  return clampAsk(cleaned)
}

private fun readyLine(lastUserInput: String): String {
  val user = lastUserInput.trim().lines().firstOrNull().orEmpty()
  if (user.isEmpty()) return "ready"
  val short = if (user.length > READY_USER_CHARS) {
    user.take(READY_USER_CHARS - 1).trimEnd() + "…"
  } else user
  return "ready · last: $short"
}

data class AgentYank(
  val thread: String,
  val label: String,
  val agent: String = "generic",
  val lastAssistant: String = "",
  val lastUserInput: String = "",
  val awaiting: Awaiting = Awaiting.DONE,
  val permissionPrompt: String? = null,
) {
  /** Glasses body: ask only for permission/question; ready (± last user) when done. */
  val bodyText: String
    get() = when (awaiting) {
      Awaiting.PERMISSION -> {
        val raw = permissionPrompt?.takeIf { it.isNotBlank() }
          ?: cleanAssistant(lastAssistant).takeIf { it.isNotBlank() }
          ?: ""
        clampAsk(raw).ifBlank { "needs approval" }
      }
      Awaiting.QUESTION -> extractAsk(lastAssistant).ifBlank { "question" }
      Awaiting.DONE -> readyLine(lastUserInput)
    }

  val metaLine: String
    get() = when (awaiting) {
      Awaiting.PERMISSION -> "$label · needs approval"
      Awaiting.QUESTION -> "$label · question"
      Awaiting.DONE -> "$label · done"
    }
}
