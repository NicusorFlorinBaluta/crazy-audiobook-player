package voice.features.playbackScreen.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import voice.core.data.BookId
import voice.features.playbackScreen.BookPlayViewState
import voice.features.playbackScreen.PlayerDisplayMode
import voice.features.playbackScreen.ReaderTheme
import voice.features.playbackScreen.view.lyrics.LyricsView
import voice.features.playbackScreen.view.reader.EbookReaderView
import kotlin.time.Duration

@Composable
internal fun BookPlayContent(
  contentPadding: PaddingValues,
  viewState: BookPlayViewState,
  bookId: BookId,
  onPlayClick: () -> Unit,
  onRewindClick: () -> Unit,
  onFastForwardClick: () -> Unit,
  onSeek: (Duration) -> Unit,
  onSkipToNext: () -> Unit,
  onSkipToPrevious: () -> Unit,
  onCurrentChapterClick: () -> Unit,
  onDisplayModeChange: (PlayerDisplayMode) -> Unit,
  onSeekToLine: (Long) -> Unit,
  onSeekToParagraph: (Long) -> Unit,
  onUpdateReaderTheme: (ReaderTheme) -> Unit,
  onUpdateReaderFontSize: (Int) -> Unit,
  onToggleAutoFollow: (Boolean) -> Unit,
  onRetrySync: () -> Unit,
  useLandscapeLayout: Boolean,
) {
  if (useLandscapeLayout) {
    Row(Modifier.padding(contentPadding)) {
      Box(
        modifier = Modifier
          .fillMaxHeight()
          .weight(1F)
          .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
      ) {
        when (viewState.displayMode) {
          PlayerDisplayMode.Cover -> CoverRow(
            bookId = bookId,
            cover = viewState.cover,
            onPlayClick = onPlayClick,
            sleepTimerState = viewState.sleepTimerState,
            modifier = Modifier.fillMaxSize(),
          )
          PlayerDisplayMode.Lyrics -> LyricsView(
            lyricsState = viewState.lyricsState,
            onLineClick = onSeekToLine,
            onRetry = onRetrySync,
            modifier = Modifier.fillMaxSize(),
          )
          PlayerDisplayMode.Reader -> EbookReaderView(
            readerState = viewState.readerState,
            onParagraphClick = onSeekToParagraph,
            onUpdateTheme = onUpdateReaderTheme,
            onUpdateFontSize = onUpdateReaderFontSize,
            onToggleAutoFollow = onToggleAutoFollow,
            onRetry = onRetrySync,
            modifier = Modifier.fillMaxSize(),
          )
        }
      }

      Column(
        modifier = Modifier
          .fillMaxHeight()
          .weight(1F),
        verticalArrangement = Arrangement.Center,
      ) {
        if (viewState.isCrazyBook) {
          PlayerViewModeSwitcher(
            currentMode = viewState.displayMode,
            onModeSelect = onDisplayModeChange,
            modifier = Modifier.fillMaxWidth(),
          )
          Spacer(modifier = Modifier.size(8.dp))
        }

        viewState.chapterName?.let { chapterName ->
          ChapterRow(
            chapterName = chapterName,
            nextPreviousVisible = viewState.showPreviousNextButtons,
            onSkipToNext = onSkipToNext,
            onSkipToPrevious = onSkipToPrevious,
            onCurrentChapterClick = onCurrentChapterClick,
          )
        }
        Spacer(modifier = Modifier.size(16.dp))
        SliderRow(
          duration = viewState.duration,
          playedTime = viewState.playedTime,
          onSeek = onSeek,
        )
        Spacer(modifier = Modifier.size(16.dp))
        PlaybackRow(
          playing = viewState.playing,
          onPlayClick = onPlayClick,
          onRewindClick = onRewindClick,
          onFastForwardClick = onFastForwardClick,
        )
      }
    }
  } else {
    Column(Modifier.padding(contentPadding)) {
      if (viewState.isCrazyBook) {
        PlayerViewModeSwitcher(
          currentMode = viewState.displayMode,
          onModeSelect = onDisplayModeChange,
          modifier = Modifier.fillMaxWidth(),
        )
      }

      Box(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1F),
      ) {
        when (viewState.displayMode) {
          PlayerDisplayMode.Cover -> CoverRow(
            bookId = bookId,
            onPlayClick = onPlayClick,
            cover = viewState.cover,
            sleepTimerState = viewState.sleepTimerState,
            modifier = Modifier
              .fillMaxWidth()
              .padding(start = 16.dp, end = 16.dp, top = 8.dp),
          )
          PlayerDisplayMode.Lyrics -> LyricsView(
            lyricsState = viewState.lyricsState,
            onLineClick = onSeekToLine,
            onRetry = onRetrySync,
            modifier = Modifier.fillMaxSize(),
          )
          PlayerDisplayMode.Reader -> EbookReaderView(
            readerState = viewState.readerState,
            onParagraphClick = onSeekToParagraph,
            onUpdateTheme = onUpdateReaderTheme,
            onUpdateFontSize = onUpdateReaderFontSize,
            onToggleAutoFollow = onToggleAutoFollow,
            onRetry = onRetrySync,
            modifier = Modifier.fillMaxSize(),
          )
        }
      }

      viewState.chapterName?.let { chapterName ->
        Spacer(modifier = Modifier.size(12.dp))
        ChapterRow(
          chapterName = chapterName,
          nextPreviousVisible = viewState.showPreviousNextButtons,
          onSkipToNext = onSkipToNext,
          onSkipToPrevious = onSkipToPrevious,
          onCurrentChapterClick = onCurrentChapterClick,
        )
      }
      Spacer(modifier = Modifier.size(16.dp))
      SliderRow(
        duration = viewState.duration,
        playedTime = viewState.playedTime,
        onSeek = onSeek,
      )
      Spacer(modifier = Modifier.size(12.dp))
      PlaybackRow(
        playing = viewState.playing,
        onPlayClick = onPlayClick,
        onRewindClick = onRewindClick,
        onFastForwardClick = onFastForwardClick,
      )
      Spacer(modifier = Modifier.size(20.dp))
    }
  }
}
