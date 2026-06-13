package com.lowkey.facechat.dictation

/** Client-side STT callback. Host orchestration: ambient-link-core dictate WS frames. */
interface DictationCallback {
  fun onPartial(text: String) {}
  fun onFinal(text: String)
  fun onCancelled() {}
  fun onError(message: String) {}
}
