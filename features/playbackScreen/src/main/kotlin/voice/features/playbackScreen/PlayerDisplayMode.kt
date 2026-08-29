package voice.features.playbackScreen

import androidx.compose.runtime.Immutable
import voice.core.data.remote.CrazyReaderParagraphDto
import voice.core.data.remote.CrazyScriptLineDto

public enum class PlayerDisplayMode {
  Cover,
  Lyrics,
  Reader,
}

public enum class ReaderTheme {
  Light,
  Sepia,
  Dark,
  Oled,
}

@Immutable
public data class LyricsViewState(
  val lines: List<CrazyScriptLineDto> = emptyList(),
  val activeLineIndex: Int = -1,
  val isLoading: Boolean = false,
  val errorMessage: String? = null,
)

@Immutable
public data class ReaderViewState(
  val title: String = "",
  val paragraphs: List<CrazyReaderParagraphDto> = emptyList(),
  val activeParagraphIndex: Int = -1,
  val isLoading: Boolean = false,
  val errorMessage: String? = null,
  val fontSizeSp: Int = 18,
  val theme: ReaderTheme = ReaderTheme.Sepia,
  val autoFollow: Boolean = true,
)
