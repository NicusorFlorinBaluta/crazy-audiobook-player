package voice.features.playbackScreen.view

import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import voice.core.data.BookId
import voice.features.playbackScreen.BookPlayViewState
import voice.features.playbackScreen.PlayerDisplayMode
import voice.features.playbackScreen.ReaderTheme
import kotlin.time.Duration

@Composable
internal fun BookPlayView(
  viewState: BookPlayViewState,
  bookId: BookId,
  useLandscapeLayout: Boolean,
  onPlayClick: () -> Unit,
  onRewindClick: () -> Unit,
  onFastForwardClick: () -> Unit,
  onSeek: (Duration) -> Unit,
  onSleepTimerClick: () -> Unit,
  onBookmarkClick: () -> Unit,
  onBookmarkLongClick: () -> Unit,
  onSpeedChangeClick: () -> Unit,
  onSkipSilenceClick: () -> Unit,
  onVolumeBoostClick: () -> Unit,
  onSkipToNext: () -> Unit,
  onSkipToPrevious: () -> Unit,
  onCloseClick: () -> Unit,
  onCurrentChapterClick: () -> Unit,
  onDisplayModeChange: (PlayerDisplayMode) -> Unit = {},
  onSeekToLine: (Long) -> Unit = {},
  onSeekToParagraph: (Long) -> Unit = {},
  onUpdateReaderTheme: (ReaderTheme) -> Unit = {},
  onUpdateReaderFontSize: (Int) -> Unit = {},
  onToggleAutoFollow: (Boolean) -> Unit = {},
  onRetrySync: () -> Unit = {},
  snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
  Scaffold(
    snackbarHost = {
      SnackbarHost(hostState = snackbarHostState)
    },
    topBar = {
      BookPlayAppBar(
        viewState = viewState,
        onSleepTimerClick = onSleepTimerClick,
        onBookmarkClick = onBookmarkClick,
        onBookmarkLongClick = onBookmarkLongClick,
        onSpeedChangeClick = onSpeedChangeClick,
        onSkipSilenceClick = onSkipSilenceClick,
        onVolumeBoostClick = onVolumeBoostClick,
        onCloseClick = onCloseClick,
        useLandscapeLayout = useLandscapeLayout,
      )
    },
    content = {
      BookPlayContent(
        contentPadding = it,
        viewState = viewState,
        bookId = bookId,
        onPlayClick = onPlayClick,
        onRewindClick = onRewindClick,
        onFastForwardClick = onFastForwardClick,
        onSeek = onSeek,
        onSkipToNext = onSkipToNext,
        onSkipToPrevious = onSkipToPrevious,
        onCurrentChapterClick = onCurrentChapterClick,
        onDisplayModeChange = onDisplayModeChange,
        onSeekToLine = onSeekToLine,
        onSeekToParagraph = onSeekToParagraph,
        onUpdateReaderTheme = onUpdateReaderTheme,
        onUpdateReaderFontSize = onUpdateReaderFontSize,
        onToggleAutoFollow = onToggleAutoFollow,
        onRetrySync = onRetrySync,
        useLandscapeLayout = useLandscapeLayout,
      )
    },
  )
}
