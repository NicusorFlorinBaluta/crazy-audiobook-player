package voice.features.playbackScreen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import voice.core.common.DispatcherProvider
import voice.core.common.MainScope
import voice.core.data.remote.CrazySyncManager
import voice.core.data.Book
import voice.core.data.BookId
import voice.core.data.KioskModeDemoData
import voice.core.data.chapterNumber
import voice.core.data.displayTitle
import voice.core.data.durationMs
import voice.core.data.markForPosition
import voice.core.data.repo.BookRepository
import voice.core.data.repo.BookmarkRepo
import voice.core.data.sleeptimer.SleepTimerPreference
import voice.core.data.store.CurrentBookStore
import voice.core.data.store.ShowRemainingTimeStore
import voice.core.data.store.SleepTimerPreferenceStore
import voice.core.featureflag.ExperimentalPlaybackPersistenceQualifier
import voice.core.featureflag.FeatureFlag
import voice.core.featureflag.KioskModeFeatureFlagQualifier
import voice.core.logging.api.Logger
import voice.core.playback.CurrentBookResolver
import voice.core.playback.PlayerController
import voice.core.playback.misc.Decibel
import voice.core.playback.misc.VolumeGain
import voice.core.playback.overlay
import voice.core.playback.playstate.PlayStateManager
import voice.core.sleeptimer.SleepTimer
import voice.core.sleeptimer.SleepTimerMode
import voice.core.sleeptimer.SleepTimerMode.TimedWithDuration
import voice.core.sleeptimer.SleepTimerState
import voice.core.ui.formatTime
import voice.features.playbackScreen.batteryOptimization.BatteryOptimization
import voice.features.sleepTimer.SleepTimerViewState
import voice.navigation.Destination
import voice.navigation.Navigator
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

@AssistedInject
class BookPlayViewModel(
  private val bookRepository: BookRepository,
  private val currentBookResolver: CurrentBookResolver,
  private val player: PlayerController,
  private val sleepTimer: SleepTimer,
  private val playStateManager: PlayStateManager,
  @CurrentBookStore
  private val currentBookStoreId: DataStore<BookId?>,
  private val navigator: Navigator,
  private val bookmarkRepository: BookmarkRepo,
  private val volumeGainFormatter: VolumeGainFormatter,
  private val batteryOptimization: BatteryOptimization,
  dispatcherProvider: DispatcherProvider,
  @SleepTimerPreferenceStore
  private val sleepTimerPreferenceStore: DataStore<SleepTimerPreference>,
  @ExperimentalPlaybackPersistenceQualifier
  private val experimentalPlaybackPersistenceFeatureFlag: FeatureFlag<Boolean>,
  @KioskModeFeatureFlagQualifier
  private val kioskModeFeatureFlag: FeatureFlag<Boolean>,
  private val crazySyncManager: CrazySyncManager,
  @ShowRemainingTimeStore
  private val showRemainingTimeStore: DataStore<Boolean>,
  @Assisted
  private val bookId: BookId,
) {

  private val scope = MainScope(dispatcherProvider)

  internal val viewEffects: Flow<BookPlayViewEffect>
    field = MutableSharedFlow<BookPlayViewEffect>(extraBufferCapacity = 1)

  internal val dialogState: State<BookPlayDialogViewState?>
    field = mutableStateOf<BookPlayDialogViewState?>(null)

  private val displayMode = mutableStateOf(PlayerDisplayMode.Cover)
  private val readerTheme = mutableStateOf(ReaderTheme.Sepia)
  private val readerFontSize = mutableStateOf(18)
  private val readerAutoFollow = mutableStateOf(true)

  private val lyricsState = mutableStateOf<LyricsViewState?>(null)
  private val readerState = mutableStateOf<ReaderViewState?>(null)
  private var lastLoadedKey: String? = null

  init {
    scope.launch {
      player.pauseIfCurrentBookDifferentFrom(bookId)
      currentBookStoreId.updateData { bookId }
    }
  }

  fun flagPlaybackIssue(
    issueType: String = "wrong_speaker",
    userNote: String = "",
    lineId: String? = null,
  ) {
    scope.launch {
      val book = currentBook() ?: return@launch
      val currentMark = book.currentChapter.markForPosition(book.content.positionInChapter)
      val chapterNumber = currentMark.chapterNumber ?: (book.content.currentChapterIndex + 1)
      val positionInChapter = if (currentMark.durationMs > 0) {
        book.content.positionInChapter - currentMark.startMs
      } else {
        book.content.positionInChapter
      }

      val flagResult = runCatching {
        crazySyncManager.flagPlaybackIssue(
          bookId = book.id,
          chapterNumber = chapterNumber,
          positionMs = positionInChapter,
          issueType = issueType,
          userNote = userNote,
          source = if (lineId != null) "lyrics_view" else "phone",
          lineId = lineId,
        )
      }
      Logger.d("Flagged playback issue from phone/lyrics: $flagResult")

      val totalSec = positionInChapter / 1000
      val mm = totalSec / 60
      val ss = totalSec % 60
      val timeStr = String.format("%02d:%02d", mm, ss)
      val msg = if (flagResult.isSuccess) {
        "Issue flagged at Ch $chapterNumber, $timeStr"
      } else {
        "Flagging failed: ${flagResult.exceptionOrNull()?.message ?: "Check server"}"
      }
      viewEffects.emit(BookPlayViewEffect.PlaybackIssueFlagged(msg))
    }
  }

  fun setDisplayMode(mode: PlayerDisplayMode) {
    displayMode.value = mode
    if ((mode == PlayerDisplayMode.Lyrics && lyricsState.value?.errorMessage != null) ||
        (mode == PlayerDisplayMode.Reader && readerState.value?.errorMessage != null)) {
      retryLyricsAndReader()
    }
  }

  fun retryLyricsAndReader() {
    scope.launch {
      val book = currentBook() ?: return@launch
      val remoteProjectId = book.content.remoteProjectId ?: return@launch
      val currentMark = book.currentChapter.markForPosition(book.content.positionInChapter)
      val chapterNumber = currentMark.chapterNumber ?: (book.content.currentChapterIndex + 1)
      lastLoadedKey = null
      loadLyricsAndReader(remoteProjectId, chapterNumber, forceRefresh = true)
    }
  }

  fun setReaderTheme(theme: ReaderTheme) {
    readerTheme.value = theme
  }

  fun setReaderFontSize(sizeSp: Int) {
    readerFontSize.value = sizeSp.coerceIn(12, 32)
  }

  fun toggleAutoFollow(enabled: Boolean) {
    readerAutoFollow.value = enabled
  }

  fun seekToPositionMs(positionMs: Long) {
    scope.launch {
      val book = currentBook() ?: return@launch
      val currentChapter = book.currentChapter
      val currentMark = currentChapter.markForPosition(book.content.positionInChapter)
      player.setPosition(currentMark.startMs + positionMs, currentChapter.id)
    }
  }

  private fun loadLyricsAndReader(projectId: String, chapterNumber: Int, forceRefresh: Boolean = false) {
    scope.launch {
      lyricsState.value = lyricsState.value?.copy(isLoading = true, errorMessage = null) ?: LyricsViewState(isLoading = true)
      readerState.value = readerState.value?.copy(isLoading = true, errorMessage = null) ?: ReaderViewState(isLoading = true)

      val lyricsRes = crazySyncManager.getChapterLyrics(projectId, chapterNumber, forceRefresh)
      lyricsRes.onSuccess { dto ->
        lyricsState.value = LyricsViewState(
          lines = dto.lines,
          isLoading = false,
        )
      }.onFailure { err ->
        lyricsState.value = LyricsViewState(
          isLoading = false,
          errorMessage = err.localizedMessage ?: "Failed to sync script with server",
        )
      }

      val readerRes = crazySyncManager.getChapterReader(projectId, chapterNumber, forceRefresh)
      readerRes.onSuccess { dto ->
        readerState.value = ReaderViewState(
          title = dto.title.ifEmpty { dto.sourceHeading },
          paragraphs = dto.paragraphs,
          fontSizeSp = readerFontSize.value,
          theme = readerTheme.value,
          autoFollow = readerAutoFollow.value,
          isLoading = false,
        )
      }.onFailure { err ->
        readerState.value = ReaderViewState(
          isLoading = false,
          errorMessage = err.localizedMessage ?: "Failed to sync ebook with server",
        )
      }
    }
  }

  @Composable
  fun viewState(): BookPlayViewState? {
    val kioskMode = remember { kioskModeFeatureFlag.get() }
    if (kioskMode) return kioskModeViewState()

    val persistedBook = remember(bookId) {
      bookRepository.flow(bookId).filterNotNull()
    }.collectAsState(initial = null).value ?: return null

    val experimentalPlaybackPersistence = experimentalPlaybackPersistenceFeatureFlag.get()
    val livePlaybackState = if (experimentalPlaybackPersistence) {
      remember(bookId) { player.livePlaybackStateFlow(bookId) }
        .collectAsState(null).value
    } else {
      null
    }
    val managerPlayState by remember {
      playStateManager.playStateFlow
    }.collectAsState()

    val book = if (livePlaybackState != null) {
      persistedBook.overlay(livePlaybackState)
    } else {
      persistedBook
    }
    val isPlaying = livePlaybackState?.isPlaying ?: (managerPlayState == PlayStateManager.PlayState.Playing)

    val currentMark = book.currentChapter.markForPosition(book.content.positionInChapter)
    val positionInCurrentMark = if (isPlaying && currentMark.durationMs > 0) {
      val relativePosition = book.content.positionInChapter - currentMark.startMs
      relativePosition.coerceIn(0L, currentMark.durationMs)
    } else {
      book.content.positionInChapter - currentMark.startMs
    }

    val isCrazyBook = !book.content.remoteProjectId.isNullOrBlank()
    val remoteProjectId = book.content.remoteProjectId
    val chapterNumber = currentMark.chapterNumber ?: (book.content.currentChapterIndex + 1)

    if (isCrazyBook && remoteProjectId != null) {
      val currentKey = "${remoteProjectId}_ch${chapterNumber}"
      if (lastLoadedKey != currentKey) {
        lastLoadedKey = currentKey
        loadLyricsAndReader(remoteProjectId, chapterNumber)
      }
    }

    val currentPosMs = positionInCurrentMark

    val activeLineIdx = lyricsState.value?.lines?.let { lines ->
      val found = lines.indexOfLast { currentPosMs >= it.startMs && currentPosMs < it.endMs }
      if (found >= 0) found else lines.indexOfLast { currentPosMs >= it.startMs }
    } ?: -1

    val activeParagraphIdx = readerState.value?.paragraphs?.let { paras ->
      val found = paras.indexOfLast { currentPosMs >= it.startMs && currentPosMs < it.endMs }
      if (found >= 0) found else paras.indexOfLast { currentPosMs >= it.startMs }
    } ?: -1

    val currentLyrics = lyricsState.value?.copy(activeLineIndex = activeLineIdx)
    val currentReader = readerState.value?.copy(
      activeParagraphIndex = activeParagraphIdx,
      fontSizeSp = readerFontSize.value,
      theme = readerTheme.value,
      autoFollow = readerAutoFollow.value,
    )

    val sleepTime = remember { sleepTimer.state }.collectAsState().value
    val hasMoreThanOneChapter = book.chapters.sumOf { it.chapterMarks.count() } > 1
    val showRemainingTime by remember { showRemainingTimeStore.data }.collectAsState(initial = true)
    return BookPlayViewState(
      sleepTimerState = sleepTime.toViewState(),
      playing = isPlaying,
      title = book.content.name,
      author = book.content.author,
      narrator = book.content.narrator,
      series = book.content.series,
      showPreviousNextButtons = hasMoreThanOneChapter,
      chapterName = currentMark.displayTitle.takeIf { hasMoreThanOneChapter && it.isNotBlank() },
      duration = currentMark.durationMs.milliseconds,
      playedTime = positionInCurrentMark.milliseconds,
      cover = book.content.coverUrl,
      skipSilence = book.content.skipSilence,
      displayMode = displayMode.value,
      lyricsState = currentLyrics,
      readerState = currentReader,
      isCrazyBook = isCrazyBook,
      showRemainingTime = showRemainingTime,
    )
  }

  private fun kioskModeViewState(): BookPlayViewState {
    val currentlyPlaying = KioskModeDemoData.currentlyPlaying
    val book = KioskModeDemoData.currentlyPlayingBook
    return BookPlayViewState(
      sleepTimerState = BookPlayViewState.SleepTimerViewState.Disabled,
      playing = true,
      title = currentlyPlaying.title,
      author = "Demo Author",
      narrator = "AI Ensemble",
      series = null,
      showPreviousNextButtons = true,
      chapterName = currentlyPlaying.chapter,
      duration = 14.hours + 27.minutes,
      playedTime = 10.hours + 24.minutes,
      cover = book.coverUrl,
      skipSilence = false,
      displayMode = PlayerDisplayMode.Cover,
      lyricsState = null,
      readerState = null,
      isCrazyBook = false,
      showRemainingTime = true,
    )
  }

  fun toggleShowRemainingTime() {
    scope.launch {
      showRemainingTimeStore.updateData { !it }
    }
  }

  fun dismissDialog() {
    Logger.d("dismissDialog")
    dialogState.value = null
  }

  fun incrementSleepTime() {
    updateSleepTimeViewState {
      val customTime = it.customSleepTime
      val newTime = customTime + 1
      sleepTimerPreferenceStore.updateData { preference -> preference.copy(duration = newTime.minutes) }
      SleepTimerViewState(newTime)
    }
  }

  fun decrementSleepTime() {
    updateSleepTimeViewState {
      val customTime = it.customSleepTime
      val newTime = (customTime - 1).coerceAtLeast(1)
      sleepTimerPreferenceStore.updateData { preference ->
        preference.copy(duration = newTime.minutes)
      }
      SleepTimerViewState(newTime)
    }
  }

  fun onAcceptSleepTime(time: Int) {
    updateSleepTimeViewState {
      val book = currentBook() ?: return@updateSleepTimeViewState null
      scope.launch {
        bookmarkRepository.addBookmarkAtBookPosition(
          book = book,
          setBySleepTimer = true,
          title = null,
        )
      }
      sleepTimer.enable(TimedWithDuration(time.minutes))
      null
    }
  }

  fun onAcceptSleepAtEndOfChapter() {
    updateSleepTimeViewState {
      sleepTimer.enable(SleepTimerMode.EndOfChapter)
      null
    }
  }

  private fun updateSleepTimeViewState(update: suspend (SleepTimerViewState) -> SleepTimerViewState?) {
    scope.launch {
      val current = dialogState.value
      val updated: SleepTimerViewState? = if (current is BookPlayDialogViewState.SleepTimer) {
        update(current.viewState)
      } else {
        update(SleepTimerViewState(sleepTimerPreferenceStore.data.first().duration.inWholeMinutes.toInt()))
      }
      dialogState.value = updated?.let(BookPlayDialogViewState::SleepTimer)
    }
  }

  fun onPlaybackSpeedChanged(speed: Float) {
    dialogState.value = BookPlayDialogViewState.SpeedDialog(speed)
    player.setSpeed(speed)
  }

  fun onVolumeGainChanged(gain: Decibel) {
    dialogState.value = volumeGainDialogViewState(gain)
    player.setGain(gain)
  }

  fun next() {
    player.next()
  }

  fun previous() {
    player.previous()
  }

  fun playPause() {
    if (playStateManager.playState != PlayStateManager.PlayState.Playing) {
      scope.launch {
        if (batteryOptimization.shouldRequest()) {
          viewEffects.tryEmit(BookPlayViewEffect.RequestIgnoreBatteryOptimization)
          batteryOptimization.onBatteryOptimizationsRequested()
        }
      }
    }
    player.playPause()
  }

  fun rewind() {
    player.rewind()
  }

  fun fastForward() {
    player.fastForward()
  }

  fun onCloseClick() {
    navigator.goBack()
  }

  fun onCurrentChapterClick() {
    scope.launch {
      val book = currentBook() ?: return@launch
      var globalIndex = 0
      dialogState.value = BookPlayDialogViewState.SelectChapterDialog(
        items = book.chapters.flatMapIndexed { chapterIndex, chapter ->
          val previousChapters = book.chapters.take(chapterIndex)
          val baseDuration = previousChapters.sumOf { it.duration }
          chapter.chapterMarks.map { chapterMark ->
            val currentIndex = globalIndex++
            val chNumber = chapterMark.chapterNumber ?: (currentIndex + 1)
            val markName = chapterMark.displayTitle.ifBlank { chapter.name ?: "" }
            BookPlayDialogViewState.SelectChapterDialog.ItemViewState(
              number = chNumber,
              name = markName,
              active = chapterMark == book.currentMark && chapter == book.currentChapter,
              time = formatTime(baseDuration + chapterMark.startMs),
            )
          }
        },
      )
    }
  }

  fun onChapterClick(index: Int = -1, number: Int = -1) {
    val targetIndex = when {
      index >= 0 -> index
      number > 0 -> number - 1
      else -> 0
    }
    scope.launch {
      val book = currentBook() ?: return@launch
      val allMarks = book.chapters.flatMap { ch -> ch.chapterMarks.map { mark -> ch to mark } }
      val target = allMarks.getOrNull(targetIndex) ?: allMarks.firstOrNull { (ch, mark) ->
        val markName = mark.name ?: ch.name ?: ""
        val match = Regex("""(?:ch\.|chapter)\s*(\d+)""", RegexOption.IGNORE_CASE).find(markName)
        match?.groupValues?.get(1)?.toIntOrNull() == (targetIndex + 1)
      }

      if (target != null) {
        val (chapter, mark) = target
        player.setPosition(mark.startMs, chapter.id)
        dialogState.value = null
      }
    }
  }

  fun onPlaybackSpeedIconClick() {
    scope.launch {
      val playbackSpeed = currentBook()?.content?.playbackSpeed ?: return@launch
      dialogState.value = BookPlayDialogViewState.SpeedDialog(playbackSpeed)
    }
  }

  fun onVolumeGainIconClick() {
    scope.launch {
      val content = currentBook()?.content ?: return@launch
      dialogState.value = volumeGainDialogViewState(Decibel(content.gain))
    }
  }

  private fun volumeGainDialogViewState(gain: Decibel): BookPlayDialogViewState.VolumeGainDialog {
    return BookPlayDialogViewState.VolumeGainDialog(
      gain = gain,
      maxGain = VolumeGain.MAX_GAIN,
      valueFormatted = volumeGainFormatter.format(gain),
    )
  }

  fun onBookmarkClick() {
    navigator.goTo(Destination.Bookmarks(bookId))
  }

  fun onBookmarkLongClick() {
    scope.launch {
      val book = currentBook() ?: return@launch
      bookmarkRepository.addBookmarkAtBookPosition(
        book = book,
        title = null,
        setBySleepTimer = false,
      )
      viewEffects.tryEmit(BookPlayViewEffect.BookmarkAdded)
    }
  }

  fun seekTo(position: Duration) {
    scope.launch {
      val book = currentBook() ?: return@launch
      val currentChapter = book.currentChapter
      val currentMark = currentChapter.markForPosition(book.content.positionInChapter)
      player.setPosition(currentMark.startMs + position.inWholeMilliseconds, currentChapter.id)
    }
  }

  fun toggleSleepTimer() {
    scope.launch {
      Logger.d("toggleSleepTimer while active=${sleepTimer.state.value}")
      if (sleepTimer.state.value.enabled) {
        sleepTimer.disable()
        dialogState.value = null
      } else {
        dialogState.value = BookPlayDialogViewState.SleepTimer(
          viewState = SleepTimerViewState(
            customSleepTime = sleepTimerPreferenceStore.data.first().duration.inWholeMinutes.toInt(),
          ),
        )
      }
    }
  }

  fun onBatteryOptimizationRequested() {
    navigator.goTo(Destination.BatteryOptimization)
  }

  fun toggleSkipSilence() {
    scope.launch {
      val skipSilence = currentBook()?.content?.skipSilence ?: return@launch
      player.skipSilence(!skipSilence)
    }
  }

  private suspend fun currentBook(): Book? {
    return currentBookResolver.book(bookId)
  }

  @AssistedFactory
  interface Factory {
    fun create(bookId: BookId): BookPlayViewModel
  }
}

private fun SleepTimerState.toViewState(): BookPlayViewState.SleepTimerViewState = when (this) {
  SleepTimerState.Disabled -> BookPlayViewState.SleepTimerViewState.Disabled
  is SleepTimerState.Enabled.WithDuration -> BookPlayViewState.SleepTimerViewState.Enabled.WithDuration(this.leftDuration)
  SleepTimerState.Enabled.WithEndOfChapter -> BookPlayViewState.SleepTimerViewState.Enabled.WithEndOfChapter
}
