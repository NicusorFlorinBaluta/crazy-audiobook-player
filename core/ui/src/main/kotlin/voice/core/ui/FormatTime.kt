package voice.core.ui

fun formatTime(
  timeMs: Long,
  durationMs: Long = 0,
): String = voice.core.common.formatTime(timeMs, durationMs)
