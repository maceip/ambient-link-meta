package com.lowkey.ambientlink.settings

/**
 * Phone UI surface flags. Flip to true to resurrect theme picker, quick-reply
 * editor, AI Core / CompanionSuggest chrome — code paths stay in the tree.
 */
object CompanionUiFlags {
  /** Theme, custom quick replies, AI suggestions, AI Core settings, fat tip. */
  const val SHOW_ADVANCED_COMPANION_UI = false
}
