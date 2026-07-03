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

/** Frosted pill strip — single horizontal row (scrolls when needed). */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun FuzzyPillGrid(
  hazeState: HazeState,
  pills: List<String>,
  selected: Set<String> = emptySet(),
  onPillClick: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  if (pills.isEmpty()) return
  val scrollState = rememberScrollState()
  val hazeStyle = HazeMaterials.regular(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))

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
      val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "pillScale")
      val borderColor by animateColorAsState(
        if (isOn) MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
        else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
        label = "pillBorder",
      )
      Text(
        label,
        modifier = Modifier
          .scale(scale)
          .clip(RoundedCornerShape(999.dp))
          .hazeEffect(state = hazeState, style = hazeStyle)
          .background(
            if (isOn) MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
            else Color.White.copy(alpha = 0.07f),
          )
          .border(1.dp, borderColor, RoundedCornerShape(999.dp))
          .clickable(interactionSource = interaction, indication = null) { onPillClick(label) }
          .defaultMinSize(minHeight = 32.dp)
          .padding(horizontal = 12.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
      )
    }
  }
}

@Composable
fun QuickRepliesEditor(
  replies: List<String>,
  hazeState: HazeState,
  onChange: (List<String>) -> Unit,
  modifier: Modifier = Modifier,
) {
  var draft by remember { mutableStateOf("") }
  Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (replies.isNotEmpty()) {
      FuzzyPillGrid(
        hazeState = hazeState,
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
        modifier = Modifier.weight(1f),
        placeholder = { Text("add a quick reply…") },
        singleLine = true,
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
        modifier = Modifier.height(56.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
      ) {
        Text("Add")
      }
    }
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
        Button(
          onClick = onDownloadModel,
          enabled = !downloadBusy,
          modifier = Modifier.fillMaxWidth(),
        ) {
          if (downloadBusy) {
            CircularProgressIndicator(
              Modifier.padding(end = 8.dp),
              strokeWidth = 2.dp,
              color = MaterialTheme.colorScheme.onPrimary,
            )
            Text("Downloading model…")
          } else {
            Text("Download Gemini Nano")
          }
        }
        TextButton(onClick = onOpenAiCore, modifier = Modifier.fillMaxWidth()) {
          Text("Open AI Core settings")
        }
      }
      Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
        Text("Got it")
      }
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

  Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    SuggestionHeader("Suggestions", loading)
    if (loading) {
      CircularProgressIndicator(
        Modifier.size(20.dp),
        strokeWidth = 2.dp,
      )
    } else {
      FuzzyPillGrid(hazeState, pending, emptySet(), onAdd)
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
  onPick: (CompanionSuggest.SnoozeOption) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    SuggestionHeader("Snooze", loading)
    if (loading) {
      CircularProgressIndicator(
        Modifier.size(20.dp),
        strokeWidth = 2.dp,
      )
    } else if (suggestions.isNotEmpty()) {
      FuzzyPillGrid(
        hazeState = hazeState,
        pills = suggestions.map { it.label },
        onPillClick = { label -> suggestions.firstOrNull { it.label == label }?.let(onPick) },
      )
    }
  }
}

@Composable
private fun SuggestionHeader(title: String, loading: Boolean) {
  Text(
    if (loading) "$title…" else title,
    style = MaterialTheme.typography.labelMedium,
    fontWeight = FontWeight.SemiBold,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}
