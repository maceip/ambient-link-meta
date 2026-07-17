package com.lowkey.ambientlink.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.zIndex
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lowkey.ambientlink.settings.AiCoreProbe
import com.lowkey.ambientlink.settings.CompanionSuggest
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/** One filled button style for the whole phone app (settings, relay, debug). */
@Composable
fun AmbientPrimaryButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier.fillMaxWidth(),
  enabled: Boolean = true,
  loading: Boolean = false,
) {
  FilledTonalButton(
    onClick = onClick,
    enabled = enabled && !loading,
    modifier = modifier.defaultMinSize(minHeight = 44.dp),
    contentPadding = PaddingValues(vertical = 10.dp, horizontal = 16.dp),
    shape = AmbientTheme.fieldShape,
    colors = AmbientTheme.tonalButtonColors(),
  ) {
    if (loading) {
      CircularProgressIndicator(
        Modifier
          .padding(end = 8.dp)
          .size(18.dp),
        strokeWidth = 2.dp,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
      )
    }
    Text(text, fontWeight = FontWeight.Medium)
  }
}

data class ActionLine(val message: String, val ok: Boolean = true)

@Composable
fun InlineActionStatus(line: ActionLine?, modifier: Modifier = Modifier) {
  if (line == null) return
  Text(
    line.message,
    modifier = modifier.fillMaxWidth(),
    style = MaterialTheme.typography.bodySmall,
    color = if (line.ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
  )
}

/** Selectable pill — same shape/color on agent, snooze, and quick-reply rows. */
@Composable
fun AmbientPillGrid(
  pills: List<String>,
  selected: Set<String>,
  onPillClick: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  if (pills.isEmpty()) return
  val scrollState = rememberScrollState()
  Row(
    modifier = modifier
      .fillMaxWidth()
      .horizontalScroll(scrollState),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    pills.forEach { label ->
      val isOn = label in selected
      val interaction = remember(label) { MutableInteractionSource() }
      val pressed by interaction.collectIsPressedAsState()
      val scale by animateFloatAsState(if (pressed) 0.96f else 1f, label = "pillScale")
      Text(
        label,
        modifier = Modifier
          .scale(scale)
          .clip(AmbientTheme.pillShape)
          .background(
            if (isOn) AmbientTheme.accentSelectedBackground()
            else AmbientTheme.accentUnselectedBackground(),
          )
          .border(
            1.dp,
            if (isOn) AmbientTheme.accentSelectedBorder()
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
            AmbientTheme.pillShape,
          )
          .clickable(interactionSource = interaction, indication = null) { onPillClick(label) }
          .defaultMinSize(minHeight = 36.dp)
          .padding(horizontal = 14.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Medium,
        color = if (isOn) AmbientTheme.accentSelectedForeground()
        else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
      )
    }
  }
}

/** @deprecated Use [AmbientPillGrid] — kept as alias for call sites being migrated. */
@Composable
fun FuzzyPillGrid(
  hazeState: HazeState,
  pills: List<String>,
  selected: Set<String> = emptySet(),
  onPillClick: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  AmbientPillGrid(pills, selected, onPillClick, modifier)
}

@Composable
fun SettingsBlockLabel(
  title: String,
  description: String? = null,
  modifier: Modifier = Modifier,
) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(
      title,
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurface,
    )
    if (!description.isNullOrBlank()) {
      Text(
        description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        lineHeight = 18.sp,
      )
    }
  }
}

@Composable
fun InlineSaveField(
  label: String,
  value: String,
  onValueChange: (String) -> Unit,
  placeholder: String,
  actionLabel: String,
  actionLoading: Boolean,
  onAction: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    OutlinedTextField(
      value = value,
      onValueChange = onValueChange,
      label = { Text(label) },
      placeholder = { Text(placeholder) },
      singleLine = true,
      enabled = enabled,
      modifier = Modifier.weight(1f),
      shape = AmbientTheme.fieldShape,
    )
    FilledTonalButton(
      onClick = onAction,
      enabled = enabled && !actionLoading,
      modifier = Modifier
        .height(56.dp)
        .defaultMinSize(minHeight = 44.dp),
      contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
      shape = AmbientTheme.fieldShape,
      colors = AmbientTheme.tonalButtonColors(),
    ) {
      if (actionLoading) {
        CircularProgressIndicator(
          Modifier.size(18.dp),
          strokeWidth = 2.dp,
          color = MaterialTheme.colorScheme.onSurface,
        )
      } else {
        Text(actionLabel, fontWeight = FontWeight.Medium)
      }
    }
  }
}

@Composable
fun QuickRepliesEditor(
  replies: List<String>,
  hazeState: HazeState,
  onChange: (List<String>) -> Unit,
  modifier: Modifier = Modifier,
  addStatus: ActionLine? = null,
) {
  var draft by remember { mutableStateOf("") }
  Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    SettingsBlockLabel(
      "Quick replies",
      "Short taps on glasses peek cards — tap a pill to remove it.",
    )
    if (replies.isNotEmpty()) {
      AmbientPillGrid(
        pills = replies,
        selected = replies.toSet(),
        onPillClick = { pill -> onChange(replies.filter { it != pill }) },
      )
    }
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text("Add reply") },
        modifier = Modifier.weight(1f),
        placeholder = { Text("e.g. looks good") },
        singleLine = true,
        shape = AmbientTheme.fieldShape,
      )
      FilledTonalButton(
        onClick = {
          val t = draft.trim()
          if (t.isNotEmpty() && !replies.contains(t)) {
            onChange(replies + t)
            draft = ""
          }
        },
        enabled = draft.trim().isNotEmpty(),
        modifier = Modifier
          .height(56.dp)
          .defaultMinSize(minHeight = 44.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        shape = AmbientTheme.fieldShape,
        colors = AmbientTheme.tonalButtonColors(),
      ) {
        Text("Add", fontWeight = FontWeight.Medium)
      }
    }
    InlineActionStatus(addStatus)
  }
}

fun formatSnoozeLabel(minutes: Int): String = when {
  minutes < 60 -> "${minutes}m"
  minutes % 60 == 0 -> "${minutes / 60}h"
  else -> "${minutes / 60}h ${minutes % 60}m"
}

/** Empty-state callout — shown until we have real usage to suggest from. */
@Composable
fun SuggestionEmptyCallout(
  modifier: Modifier = Modifier,
  aiReady: Boolean = false,
) {
  val pulse = rememberInfiniteTransition(label = "calloutPulse")
  val glow by pulse.animateFloat(
    initialValue = 0.35f,
    targetValue = 0.75f,
    animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
    label = "glow",
  )
  BoxWithConstraints(modifier.fillMaxWidth()) {
    val expanded = maxWidth >= 520.dp
    val bubbleMax = if (expanded) 420.dp else maxWidth
    Column(
      modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
      horizontalAlignment = if (expanded) Alignment.CenterHorizontally else Alignment.Start,
    ) {
      Box(
        modifier = Modifier
          .widthIn(max = bubbleMax)
          .clip(RoundedCornerShape(20.dp))
          .background(
            Brush.linearGradient(
              listOf(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f),
              ),
            ),
          )
          .border(
            1.5.dp,
            Brush.linearGradient(
              listOf(
                MaterialTheme.colorScheme.primary.copy(alpha = glow),
                MaterialTheme.colorScheme.secondary.copy(alpha = glow * 0.6f),
              ),
            ),
            RoundedCornerShape(20.dp),
          )
          .padding(horizontal = 16.dp, vertical = 14.dp),
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("💬", fontSize = if (expanded) 30.sp else 26.sp)
          Text(
            "Suggestions fill in as you use the app",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
          )
          Text(
            if (aiReady) {
              "On-device AI is ready — keep using sessions and pills will appear here."
            } else {
              "Chat with your agents first. We learn from your sessions; AI Core makes it smarter on supported phones."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 18.sp,
          )
        }
      }
      Box(
        Modifier
          .offset(x = if (expanded) 52.dp else 28.dp, y = (-3).dp)
          .clip(RoundedCornerShape(4.dp))
          .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
          .padding(horizontal = 10.dp, vertical = 4.dp),
      ) {
        Text("more you use it ↑", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
      }
    }
  }
}

/** AI Core readiness chip — three user-visible states. */
@Composable
fun AiCoreStatusChip(
  status: AiCoreProbe.Status,
  probing: Boolean = false,
  modifier: Modifier = Modifier,
) {
  if (probing) {
    StatusChip(
      modifier = modifier,
      label = "Checking AI Core…",
      background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
      border = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
      textColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    return
  }
  when (status.tier) {
    AiCoreProbe.Tier.UNSUPPORTED -> StatusChip(
      modifier = modifier,
      label = "Your phone does not support AI Core — smart suggestions disabled",
      background = Color(0x33F0566E),
      border = Color(0x99F0566E),
      textColor = Color(0xFFFF8FA0),
    )
    AiCoreProbe.Tier.NEEDS_MODEL -> StatusChip(
      modifier = modifier,
      label = if (status.isDownloading) {
        "AI Core detected — model download in progress"
      } else {
        "AI Core detected — download Gemini Nano to enable smart suggestions"
      },
      background = Color(0x33F0A93C),
      border = Color(0x99F0A93C),
      textColor = Color(0xFFFFD18A),
    )
    AiCoreProbe.Tier.READY -> StatusChip(
      modifier = modifier,
      label = "You're good to go · ${status.modelName ?: "Gemini Nano"}",
      background = Color(0x333DC97A),
      border = Color(0x993DC97A),
      textColor = Color(0xFF8EEBB5),
    )
  }
}

@Composable
private fun StatusChip(
  label: String,
  background: Color,
  border: Color,
  textColor: Color,
  modifier: Modifier = Modifier,
) {
  Text(
    label,
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(background)
      .border(1.dp, border, RoundedCornerShape(12.dp))
      .padding(horizontal = 12.dp, vertical = 10.dp),
    style = MaterialTheme.typography.labelMedium,
    color = textColor,
    lineHeight = 18.sp,
  )
}

/** One-time modal — suggestions tip on first launch only. */
@Composable
fun FirstRunTipOverlay(
  aiCore: AiCoreProbe.Status,
  probing: Boolean = false,
  downloadBusy: Boolean = false,
  onDismiss: () -> Unit,
  onDownloadModel: () -> Unit,
  onOpenAiCore: () -> Unit,
) {
  val scale by animateFloatAsState(
    targetValue = 1f,
    animationSpec = tween(durationMillis = 280),
    label = "modalScale",
  )
  Box(
    Modifier
      .fillMaxSize()
      .zIndex(100f),
  ) {
    // 50% scrim — tap outside the card to dismiss.
    Box(
      Modifier
        .fillMaxSize()
        .background(Color.Black.copy(alpha = 0.5f))
        .pointerInput(Unit) {
          detectTapGestures(onTap = { onDismiss() })
        },
    )
    Column(
      Modifier
        .align(Alignment.Center)
        .padding(horizontal = 28.dp)
        .widthIn(max = 340.dp)
        .scale(scale)
        .shadow(24.dp, RoundedCornerShape(22.dp), ambientColor = Color.Black, spotColor = Color.Black)
        .clip(RoundedCornerShape(22.dp))
        .background(Color(0xFF141820))
        .border(1.5.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(22.dp))
        .padding(horizontal = 22.dp, vertical = 24.dp)
        .pointerInput(Unit) {
          detectTapGestures { /* consume taps on the card */ }
        },
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        "Tips",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
      )
      Text(
        "Suggestions fill in as you use the app",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
      )
      AiCoreStatusChip(status = aiCore, probing = probing)
      Text(
        when (aiCore.tier) {
          AiCoreProbe.Tier.READY ->
            "On-device AI can suggest quick replies and snooze times from your session patterns."
          AiCoreProbe.Tier.NEEDS_MODEL ->
            "Pattern-based suggestions work now. Download Gemini Nano for smarter, on-device suggestions."
          AiCoreProbe.Tier.UNSUPPORTED ->
            "Chat with your agents first. We still learn your patterns for quick-reply suggestions — without on-device AI."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        lineHeight = 20.sp,
      )
      if (!probing && aiCore.tier == AiCoreProbe.Tier.NEEDS_MODEL && !aiCore.isDownloading) {
        FilledTonalButton(
          onClick = onDownloadModel,
          enabled = !downloadBusy,
          modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp),
          contentPadding = PaddingValues(vertical = 10.dp),
          shape = RoundedCornerShape(12.dp),
        ) {
          if (downloadBusy) {
            CircularProgressIndicator(
              Modifier.padding(end = 8.dp),
              strokeWidth = 2.dp,
              color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text("Downloading model…", fontWeight = FontWeight.Medium)
          } else {
            Text("Download Gemini Nano", fontWeight = FontWeight.Medium)
          }
        }
        TextButton(onClick = onOpenAiCore, modifier = Modifier.fillMaxWidth()) {
          Text("Open AI Core settings")
        }
      }
      AmbientPrimaryButton(text = "Got it", onClick = onDismiss)
      TextButton(onClick = onDismiss) {
        Text("Tap outside to dismiss", color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

/** Slim first-run tip when advanced companion UI is off — BT / Meta AI only. */
@Composable
fun SimpleCompanionTipOverlay(onDismiss: () -> Unit) {
  val scale by animateFloatAsState(
    targetValue = 1f,
    animationSpec = tween(durationMillis = 280),
    label = "simpleTipScale",
  )
  Box(
    Modifier
      .fillMaxSize()
      .zIndex(100f),
  ) {
    Box(
      Modifier
        .fillMaxSize()
        .background(Color.Black.copy(alpha = 0.5f))
        .pointerInput(Unit) {
          detectTapGestures(onTap = { onDismiss() })
        },
    )
    Column(
      Modifier
        .align(Alignment.Center)
        .padding(horizontal = 28.dp)
        .widthIn(max = 340.dp)
        .scale(scale)
        .shadow(24.dp, RoundedCornerShape(22.dp), ambientColor = Color.Black, spotColor = Color.Black)
        .clip(RoundedCornerShape(22.dp))
        .background(Color(0xFF141820))
        .border(1.5.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(22.dp))
        .padding(horizontal = 22.dp, vertical = 24.dp)
        .pointerInput(Unit) {
          detectTapGestures { /* consume taps on the card */ }
        },
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        "Tips",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
      )
      Text(
        "Connect glasses, then forget this screen",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        "Grant Bluetooth when asked and register Meta AI so peek chips can wake the glasses. " +
          "Status, default agent, Continue/Dictate, and snooze are all you need here.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        lineHeight = 20.sp,
      )
      AmbientPrimaryButton(text = "Got it", onClick = onDismiss)
      TextButton(onClick = onDismiss) {
        Text("Tap outside to dismiss", color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun AiQuickReplySuggestions(
  hazeState: HazeState,
  suggestions: List<String>,
  loading: Boolean,
  fromAi: Boolean,
  selected: Set<String>,
  onAdd: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val pending = remember(suggestions, selected) {
    suggestions.filter { it !in selected }
  }
  if (!loading && pending.isEmpty()) return

  Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    SettingsBlockLabel(
      if (loading) "Suggested replies…" else "Suggested replies",
      if (fromAi) "From on-device AI based on your sessions." else "From your recent session patterns.",
    )
    if (loading) {
      CircularProgressIndicator(
        Modifier.size(20.dp),
        strokeWidth = 2.dp,
        color = MaterialTheme.colorScheme.primary,
      )
    } else {
      AmbientPillGrid(pills = pending, selected = emptySet(), onPillClick = onAdd)
    }
  }
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun AiSnoozeSuggestions(
  hazeState: HazeState,
  suggestions: List<CompanionSuggest.SnoozeOption>,
  loading: Boolean,
  fromAi: Boolean,
  selected: Set<String> = emptySet(),
  onPick: (CompanionSuggest.SnoozeOption) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    SettingsBlockLabel(
      if (loading) "Suggested snooze…" else "Suggested snooze",
      if (fromAi) "Smart snooze times from your habits." else "Based on when you usually snooze.",
    )
    if (loading) {
      CircularProgressIndicator(
        Modifier.size(20.dp),
        strokeWidth = 2.dp,
        color = MaterialTheme.colorScheme.primary,
      )
    } else if (suggestions.isNotEmpty()) {
      AmbientPillGrid(
        pills = suggestions.map { it.label },
        selected = selected,
        onPillClick = { label -> suggestions.firstOrNull { it.label == label }?.let(onPick) },
      )
    }
  }
}

@Composable
fun AiCoreSettingsSection(
  status: AiCoreProbe.Status,
  probing: Boolean,
  modifier: Modifier = Modifier,
) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    AiCoreStatusChip(status = status, probing = probing)
    Text(
      when (status.tier) {
        AiCoreProbe.Tier.READY ->
          "On-device model: ${status.modelName ?: "Gemini Nano"}"
        AiCoreProbe.Tier.NEEDS_MODEL ->
          "Eligible — download Gemini Nano in AI Core settings for smarter suggestions."
        AiCoreProbe.Tier.UNSUPPORTED ->
          "Not eligible on this device — pattern-based suggestions still work."
      },
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      lineHeight = 18.sp,
    )
  }
}

@Composable
fun SodaDebugPanel(context: android.content.Context) {
  val rt = Runtime.getRuntime()
  val memMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
  val nativeOk = com.lowkey.ambientlink.soda.SodaRuntime.isAvailable(context)
  val pack = com.lowkey.ambientlink.soda.SodaRuntime.preparePack(context)
  val packLabel = when (pack) {
    is com.google.research.air.cosmo.lib.soda.SodaPrepareResult.Available -> "lp_cpu ready"
    is com.google.research.air.cosmo.lib.soda.SodaPrepareResult.Unavailable -> pack.reason
    else -> "unknown"
  }
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(
      "Speech (SODA)",
      style = MaterialTheme.typography.labelMedium,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text("Native: ${if (nativeOk) "loaded" else "missing"}", style = MaterialTheme.typography.bodySmall)
    Text("Pack: $packLabel", style = MaterialTheme.typography.bodySmall)
    Text(
      "Capture: ${if (com.lowkey.ambientlink.dictation.DictationManager.isActive()) "active" else "idle"}",
      style = MaterialTheme.typography.bodySmall,
    )
    Text("Heap ≈ ${memMb}MB", style = MaterialTheme.typography.bodySmall)
    Text("WER: not tracked", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}
