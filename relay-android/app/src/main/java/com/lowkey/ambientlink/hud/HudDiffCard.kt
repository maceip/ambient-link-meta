package com.lowkey.ambientlink.hud

import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.views.Alignment
import com.meta.wearable.dat.display.views.ButtonStyle
import com.meta.wearable.dat.display.views.Direction
import com.meta.wearable.dat.display.views.FlexBoxBackground
import com.meta.wearable.dat.display.views.IconName
import com.meta.wearable.dat.display.views.IconStyle
import com.meta.wearable.dat.display.views.TextColor
import com.meta.wearable.dat.display.views.TextStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * "Changed code files" card, rendered with ONLY the DAT token vocabulary
 * (mwdat-display 0.7.0). This is the honest ceiling of the native widget:
 *   - text: 2 colours (PRIMARY/SECONDARY) x 3 styles (HEADING/BODY/META)
 *   - per-file magnitude bar built from proportional flexBox(flexGrow) children
 *   - CODE/diff icon (monochrome), CARD-backed rows, PRIMARY + OUTLINE buttons
 *
 * There is no red/green and no per-line tint in the token system, so "added vs
 * removed" is shown as numbers; the bar encodes *total churn* relative to the
 * busiest file. For the full-colour green/red treatment, push a bitmap via
 * image(uri, FILL) instead (see hud-design-lab/).
 */
object HudDiffCard {

  /** Files we treat as "code" — excludes docs (.md/.txt), config (.json/.toml/
   *  .yaml/.gradle/.properties/.xml/.plist), markup (.html) and styles (.css). */
  private val CODE_EXT = setOf(
    "kt", "kts", "swift", "js", "mjs", "ts", "tsx", "jsx", "java", "py",
    "c", "cc", "cpp", "h", "hpp", "go", "rs", "rb", "m", "mm",
  )

  data class FileChurn(val path: String, val added: Int, val removed: Int) {
    val total: Int get() = added + removed
    val name: String get() = path.substringAfterLast('/')
    val ext: String get() = name.substringAfterLast('.', "")
  }

  /** Parse `git diff --numstat` output ("<add>\t<del>\t<path>") to FileChurn. */
  fun parseNumstat(numstat: String): List<FileChurn> =
    numstat.lineSequence()
      .mapNotNull { line ->
        val p = line.trim().split('\t')
        if (p.size < 3) return@mapNotNull null
        val add = p[0].toIntOrNull() ?: return@mapNotNull null // "-" for binary
        val del = p[1].toIntOrNull() ?: return@mapNotNull null
        FileChurn(p[2], add, del)
      }
      .toList()

  fun codeFilesByChurn(files: List<FileChurn>): List<FileChurn> =
    files.filter { it.ext.lowercase() in CODE_EXT }
      .sortedByDescending { it.total }

  fun send(
    scope: CoroutineScope,
    display: Display,
    files: List<FileChurn>,
    top: Int = 5,
    onOpen: () -> Unit,
  ) {
    val ranked = codeFilesByChurn(files).take(top)
    if (ranked.isEmpty()) return
    val max = (ranked.maxOf { it.total }).coerceAtLeast(1)

    scope.launch {
      display.sendContent {
        // default Direction is COLUMN (matches existing HudWidgets cards)
        flexBox(gap = 8, padding = 16) {
          text("changed files · code", style = TextStyle.META, color = TextColor.SECONDARY)

          ranked.forEach { f ->
            flexBox(gap = 4, padding = 10, background = FlexBoxBackground.CARD) {
              // name row: icon + filename (grows) + churn counts
              flexBox(
                direction = Direction.ROW,
                gap = 6,
                crossAlignment = Alignment.CENTER,
                background = FlexBoxBackground.NONE,
              ) {
                icon(name = IconName.CODE, style = IconStyle.OUTLINE)
                text(
                  f.name,
                  style = TextStyle.BODY,
                  color = TextColor.PRIMARY,
                  flexGrow = 1f,
                )
                text(
                  "+${f.added} -${f.removed}",
                  style = TextStyle.META,
                  color = TextColor.SECONDARY,
                )
              }
              // magnitude bar: filled CARD segment (∝ total) + empty NONE spacer.
              // The single-space text gives the segments height; verify the bar
              // is visible on-device — empty flexBoxes may collapse otherwise.
              flexBox(
                direction = Direction.ROW,
                gap = 0,
                background = FlexBoxBackground.NONE,
              ) {
                flexBox(
                  background = FlexBoxBackground.CARD,
                  flexGrow = f.total.toFloat(),
                ) { text(" ", style = TextStyle.META, color = TextColor.SECONDARY) }
                flexBox(
                  background = FlexBoxBackground.NONE,
                  flexGrow = (max - f.total).toFloat().coerceAtLeast(0.001f),
                ) { text(" ", style = TextStyle.META, color = TextColor.SECONDARY) }
              }
            }
          }

          flexBox(
            direction = Direction.ROW,
            gap = 6,
            background = FlexBoxBackground.NONE,
          ) {
            button("open", style = ButtonStyle.PRIMARY, onClick = onOpen)
          }
        }
      }
    }
  }
}
