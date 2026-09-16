package voice.features.playbackScreen

import voice.core.data.remote.CrazyChapterLyricsDto
import voice.core.data.remote.CrazyChapterReaderDto
import voice.core.data.remote.CrazyReaderParagraphDto
import voice.core.data.remote.CrazyScriptLineDto
import voice.core.data.remote.CrazySyncManager
import voice.features.playbackScreen.PlayerDisplayMode
import voice.features.playbackScreen.ReaderTheme

import app.cash.molecule.RecompositionMode
import app.cash.molecule.launchMolecule
import app.cash.turbine.test
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import voice.core.common.DispatcherProvider
import voice.core.data.Book
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.Bookmark
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.data.KioskModeDemoData
import voice.core.data.MarkData
import voice.core.data.markForPosition
import voice.core.data.sleeptimer.SleepTimerPreference
import voice.core.featureflag.MemoryFeatureFlag
import voice.core.playback.CurrentBookResolver
import voice.core.playback.LivePlaybackState
import voice.core.playback.PlayerController
import voice.core.playback.overlay
import voice.core.playback.playstate.PlayStateManager
import voice.core.sleeptimer.SleepTimer
import voice.core.sleeptimer.SleepTimerMode
import voice.core.sleeptimer.SleepTimerMode.TimedWithDuration
import voice.core.sleeptimer.SleepTimerState
import voice.features.sleepTimer.SleepTimerViewState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class BookPlayViewModelTest {

  private val scope = TestScope()
  private val sleepTimerDataStore = MemoryDataStore(SleepTimerPreference.Default.copy(duration = 5.minutes))
  private val book = book()
  private val sleepTimer = mockk<SleepTimer> {
    val stateFlow = MutableStateFlow<SleepTimerState>(SleepTimerState.Disabled)
    every {
      state
    } returns stateFlow
    every {
      enable(any())
    } answers {
      stateFlow.value = when (val mode = firstArg<SleepTimerMode>()) {
        is TimedWithDuration -> SleepTimerState.Enabled.WithDuration(mode.duration)
        SleepTimerMode.TimedWithDefault -> SleepTimerState.Enabled.WithDuration(runBlocking { sleepTimerDataStore.data.first() }.duration)
        SleepTimerMode.EndOfChapter -> SleepTimerState.Enabled.WithEndOfChapter
      }
    }
    every {
      disable()
    } answers {
      stateFlow.value = SleepTimerState.Disabled
    }
  }

  private val player = mockk<PlayerController>()
  private val playStateManager = mockk<PlayStateManager> {
    every { playStateFlow } returns MutableStateFlow(PlayStateManager.PlayState.Paused)
  }
  private val currentBookStoreId = MemoryDataStore<BookId?>(null)
  private val currentBookResolver = mockk<CurrentBookResolver> {
    coEvery { book(book.id) } returns book
  }
  private val viewModel = BookPlayViewModel(
    bookRepository = mockk {
      coEvery { get(book.id) } returns book
      every { flow(book.id) } returns MutableStateFlow(book)
    },
    currentBookResolver = mockk { coEvery { book(book.id) } returns book },
    player = player.apply {
      every { pauseIfCurrentBookDifferentFrom(book.id) } just Runs
    },
    sleepTimer = sleepTimer,
    playStateManager = playStateManager,
    currentBookStoreId = currentBookStoreId,
    navigator = mockk(),
    bookmarkRepository = mockk {
      coEvery { addBookmarkAtBookPosition(book, any(), any()) } returns Bookmark(
        bookId = book.id,
        chapterId = book.currentChapter.id,
        addedAt = Instant.now(),
        setBySleepTimer = true,
        id = Bookmark.Id(Uuid.random()),
        time = 0L,
        title = null,
      )
    },
    volumeGainFormatter = mockk(),
    batteryOptimization = mockk(),
    sleepTimerPreferenceStore = sleepTimerDataStore,
    bookId = book.id,
    dispatcherProvider = DispatcherProvider(scope.coroutineContext, scope.coroutineContext, scope.coroutineContext),
    experimentalPlaybackPersistenceFeatureFlag = MemoryFeatureFlag(false),
    kioskModeFeatureFlag = MemoryFeatureFlag(false),
    crazySyncManager = mockk(relaxed = true),
    showRemainingTimeStore = MemoryDataStore(true),
  )

  @Test
  fun sleepTimerValueChanging() = scope.runTest {
    fun assertDialogSleepTime(expected: Int) {
      assertEquals(expected = BookPlayDialogViewState.SleepTimer(SleepTimerViewState(expected)), actual = viewModel.dialogState.value)
    }

    viewModel.toggleSleepTimer()
    yield()
    assertDialogSleepTime(5)

    suspend fun incrementAndAssert(time: Int) {
      viewModel.incrementSleepTime()
      yield()
      assertDialogSleepTime(time)
    }

    suspend fun decrementAndAssert(time: Int) {
      viewModel.decrementSleepTime()
      yield()
      assertDialogSleepTime(time)
    }

    decrementAndAssert(4)
    decrementAndAssert(3)
    decrementAndAssert(2)
    decrementAndAssert(1)

    decrementAndAssert(1)

    incrementAndAssert(2)
    incrementAndAssert(3)
  }

  @Test
  fun sleepTimerSettingFixedValue() = scope.runTest {
    viewModel.toggleSleepTimer()
    viewModel.onAcceptSleepTime(10)
    assertEquals(expected = 5.minutes, actual = sleepTimerDataStore.data.first().duration)
    yield()
    verify(exactly = 1) {
      sleepTimer.enable(TimedWithDuration(10.minutes))
    }
  }

  @Test
  fun deactivateSleepTimer() = scope.runTest {
    viewModel.toggleSleepTimer()
    viewModel.onAcceptSleepTime(10)
    viewModel.toggleSleepTimer()
    yield()
    verifyOrder {
      sleepTimer.enable(TimedWithDuration(10.minutes))
      sleepTimer.disable()
    }
    assertIs<SleepTimerState.Disabled>(sleepTimer.state.value)
  }

  @Test
  fun onCurrentChapterClickShowsDialogWithCorrectState() = scope.runTest {
    viewModel.onCurrentChapterClick()
    yield()

    val dialogState = assertIs<BookPlayDialogViewState.SelectChapterDialog>(viewModel.dialogState.value)

    assertEquals(
      expected = listOf(
        BookPlayDialogViewState.SelectChapterDialog.ItemViewState(
          number = 1,
          name = "Chapter Start",
          active = false,
          time = "0:00",
        ),
        BookPlayDialogViewState.SelectChapterDialog.ItemViewState(
          number = 2,
          name = "Middle Section",
          active = false,
          time = "2:00",
        ),
        BookPlayDialogViewState.SelectChapterDialog.ItemViewState(
          number = 3,
          name = "Final Section",
          active = false,
          time = "4:00",
        ),
        BookPlayDialogViewState.SelectChapterDialog.ItemViewState(
          number = 4,
          name = "Chapter Start",
          active = false,
          time = "5:00",
        ),
        BookPlayDialogViewState.SelectChapterDialog.ItemViewState(
          number = 5,
          name = "Middle Section",
          active = true,
          time = "7:00",
        ),
        BookPlayDialogViewState.SelectChapterDialog.ItemViewState(
          number = 6,
          name = "Final Section",
          active = false,
          time = "9:00",
        ),
      ),
      actual = dialogState.items,
    )
  }

  @Test
  fun onChapterClickSetsPositionAndDismissesDialog() = scope.runTest {
    every { player.setPosition(any(), any()) } just Runs

    viewModel.onCurrentChapterClick()
    yield()

    assertIs<BookPlayDialogViewState.SelectChapterDialog>(viewModel.dialogState.value)

    viewModel.onChapterClick(number = 2)
    yield()

    // Verify player.setPosition was called with correct parameters
    // The second mark starts at 2 minutes position in the first chapter
    verify(exactly = 1) {
      player.setPosition(time = 2.minutes.inWholeMilliseconds, id = book.chapters.first().id)
    }

    assertEquals(expected = null, actual = viewModel.dialogState.value)
  }

  @Test
  fun `SelectChapterDialog renders clean manuscript names and true chapter numbers`() = scope.runTest {
    val customChapter = Chapter(
      id = ChapterId("http://test-part7"),
      name = "Part 07",
      duration = 3_500_000,
      fileLastModified = Instant.EPOCH,
      markData = listOf(
        MarkData(startMs = 0, name = "29::Chapter Twenty-Eight"),
        MarkData(startMs = 600_000, name = "30::Chapter Twenty-Nine"),
      ),
      fileSize = 0,
    )
    val customBook = Book(
      content = book.content.copy(
        chapters = listOf(customChapter.id),
        currentChapter = customChapter.id,
        positionInChapter = 10_000,
      ),
      chapters = listOf(customChapter),
    )
    val customVm = viewModel(book = customBook)

    customVm.onCurrentChapterClick()
    yield()

    val dialog = customVm.dialogState.value
    assertIs<BookPlayDialogViewState.SelectChapterDialog>(dialog)
    assertEquals(expected = 2, actual = dialog.items.size)
    assertEquals(expected = 29, actual = dialog.items[0].number)
    assertEquals(expected = "Chapter Twenty-Eight", actual = dialog.items[0].name)
    assertEquals(expected = 30, actual = dialog.items[1].number)
    assertEquals(expected = "Chapter Twenty-Nine", actual = dialog.items[1].name)
  }

  @Test
  fun `display mode switches between Cover, Lyrics, and Reader`() = scope.runTest {
    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      var item = awaitItem()
      while (item == null) {
        item = awaitItem()
      }
      assertEquals(expected = PlayerDisplayMode.Cover, actual = item.displayMode)

      viewModel.setDisplayMode(PlayerDisplayMode.Lyrics)
      assertEquals(expected = PlayerDisplayMode.Lyrics, actual = awaitItem()?.displayMode)

      viewModel.setDisplayMode(PlayerDisplayMode.Reader)
      assertEquals(expected = PlayerDisplayMode.Reader, actual = awaitItem()?.displayMode)

      viewModel.setDisplayMode(PlayerDisplayMode.Cover)
      assertEquals(expected = PlayerDisplayMode.Cover, actual = awaitItem()?.displayMode)
    }
  }

  @Test
  fun `reader theme and font size adjustments operate cleanly within bounds`() = scope.runTest {
    val crazyBook = book().let { b ->
      b.copy(content = b.content.copy(remoteProjectId = "crazy-project"))
    }
    val crazyVm = viewModel(book = crazyBook)

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      crazyVm.viewState()
    }.test {
      var item = awaitItem()
      while (item == null) {
        item = awaitItem()
      }

      crazyVm.setReaderTheme(ReaderTheme.Sepia)
      assertEquals(expected = ReaderTheme.Sepia, actual = awaitItem()?.readerState?.theme)

      crazyVm.setReaderTheme(ReaderTheme.Oled)
      assertEquals(expected = ReaderTheme.Oled, actual = awaitItem()?.readerState?.theme)

      crazyVm.setReaderFontSize(20)
      assertEquals(expected = 20, actual = awaitItem()?.readerState?.fontSizeSp)

      crazyVm.setReaderFontSize(50)
      assertEquals(expected = 32, actual = awaitItem()?.readerState?.fontSizeSp)

      crazyVm.setReaderFontSize(8)
      assertEquals(expected = 12, actual = awaitItem()?.readerState?.fontSizeSp)

      crazyVm.toggleAutoFollow(false)
      assertEquals(expected = false, actual = awaitItem()?.readerState?.autoFollow)

      crazyVm.toggleAutoFollow(true)
      assertEquals(expected = true, actual = awaitItem()?.readerState?.autoFollow)
    }
  }

  @Test
  fun `overlay prefers live controller position`() {
    val persistedBook = book()
    val overlaidBook = persistedBook.overlay(
      LivePlaybackState(
        bookId = persistedBook.id,
        chapterId = persistedBook.chapters.first().id,
        positionMs = 1.minutes.inWholeMilliseconds,
        isPlaying = true,
        playbackSpeed = 1F,
      ),
    )

    assertEquals(expected = persistedBook.chapters.first().id, actual = overlaidBook.currentChapter.id)
    assertEquals(expected = 1.minutes.inWholeMilliseconds, actual = overlaidBook.content.positionInChapter)
  }

  @Test
  fun `viewState prefers live playback state when feature flag is enabled`() = scope.runTest {
    val persistedBook = book()
    val livePlaybackFlow = MutableStateFlow<LivePlaybackState?>(null)
    val viewModel = viewModel(
      book = persistedBook,
      experimentalPlaybackPersistence = true,
      livePlaybackFlow = livePlaybackFlow,
    )

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      assertEquals(expected = null, actual = awaitItem())
      assertEquals(expected = 30.seconds, actual = awaitItem()!!.playedTime)

      livePlaybackFlow.value = LivePlaybackState(
        bookId = persistedBook.id,
        chapterId = persistedBook.chapters.first().id,
        positionMs = 1.minutes.inWholeMilliseconds,
        isPlaying = true,
        playbackSpeed = 1F,
      )

      val state = awaitItem()!!
      assertEquals(expected = true, actual = state.playing)
      assertEquals(expected = "Chapter Start", actual = state.chapterName)
      assertEquals(expected = 1.minutes, actual = state.playedTime)
    }
  }

  @Test
  fun `viewState falls back to manager play state when live playback is unavailable`() = scope.runTest {
    val viewModel = viewModel(
      experimentalPlaybackPersistence = true,
      livePlaybackFlow = MutableStateFlow(null),
      playStateFlow = MutableStateFlow(PlayStateManager.PlayState.Playing),
    )

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      assertEquals(expected = null, actual = awaitItem())
      val state = awaitItem()!!
      assertEquals(expected = true, actual = state.playing)
      assertEquals(expected = 30.seconds, actual = state.playedTime)
    }
  }

  @Test
  fun `viewState uses currently playing demo book in kiosk mode`() = scope.runTest {
    val viewModel = viewModel(kioskMode = true)

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      val state = awaitItem()!!
      assertEquals(expected = KioskModeDemoData.currentlyPlaying.title, actual = state.title)
      assertEquals(expected = KioskModeDemoData.currentlyPlaying.chapter, actual = state.chapterName)
      assertEquals(expected = KioskModeDemoData.currentlyPlaying.coverUrl, actual = state.cover)
    }
  }

  @Test
  fun `viewState contains showRemainingTime and toggleShowRemainingTime updates it`() = scope.runTest {
    val vm = viewModel()
    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      vm.viewState()
    }.test {
      skipItems(1) // initial null
      val initial = awaitItem()!!
      assertEquals(expected = true, actual = initial.showRemainingTime)

      vm.toggleShowRemainingTime()
      val updated = awaitItem()!!
      assertEquals(expected = false, actual = updated.showRemainingTime)
    }
  }

  @Test
  fun `viewState switches display modes between Cover, Lyrics, and Reader`() = scope.runTest {
    val crazyBook = book.copy(
      content = book.content.copy(remoteProjectId = "test_project")
    )
    val viewModel = viewModel(book = crazyBook)

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      skipItems(1) // initial null
      val initial = awaitItem()!!
      assertEquals(expected = PlayerDisplayMode.Cover, actual = initial.displayMode)
      assertEquals(expected = true, actual = initial.isCrazyBook)

      viewModel.setDisplayMode(PlayerDisplayMode.Lyrics)
      assertEquals(expected = PlayerDisplayMode.Lyrics, actual = awaitItem()!!.displayMode)

      viewModel.setDisplayMode(PlayerDisplayMode.Reader)
      assertEquals(expected = PlayerDisplayMode.Reader, actual = awaitItem()!!.displayMode)

      viewModel.setDisplayMode(PlayerDisplayMode.Cover)
      assertEquals(expected = PlayerDisplayMode.Cover, actual = awaitItem()!!.displayMode)
    }
  }

  @Test
  fun `viewState synchronizes active lyrics line and seeks to line timing`() = scope.runTest {
    val crazyBook = book.copy(
      content = book.content.copy(
        remoteProjectId = "emberdark",
        positionInChapter = 55000L,
      )
    )

    val mockSync = mockk<CrazySyncManager> {
      coEvery { getChapterLyrics(any(), any()) } returns Result.success(
        CrazyChapterLyricsDto(
          projectId = "emberdark",
          chapterNumber = 1,
          chapterTitle = "Prologue",
          lines = listOf(
            CrazyScriptLineDto(lineId = "l1", speaker = "Narrator", text = "Line 1", startMs = 53450, endMs = 64170),
            CrazyScriptLineDto(lineId = "l2", speaker = "Starling", text = "Line 2", startMs = 65070, endMs = 68590),
          )
        )
      )
      coEvery { getChapterReader(any(), any()) } returns Result.success(
        CrazyChapterReaderDto(projectId = "emberdark", chapterNumber = 1)
      )
    }

    val playerMock = mockk<PlayerController>(relaxed = true) {
      every { pauseIfCurrentBookDifferentFrom(any()) } just Runs
      every { livePlaybackStateFlow(any()) } returns MutableStateFlow(null)
    }

    val viewModel = viewModel(
      book = crazyBook,
      playerMock = playerMock,
      crazySyncManager = mockSync,
    )

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      skipItems(1)
      var item = awaitItem()!!
      // Wait until lyrics are populated
      while (item.lyricsState?.lines.isNullOrEmpty()) {
        item = awaitItem()!!
      }

      val lyrics = item.lyricsState
      assertEquals(expected = 2, actual = lyrics.lines.size)
      assertEquals(expected = 0, actual = lyrics.activeLineIndex) // 55000ms is in Line 1 (53450..64170)

      // Test seekToPositionMs
      viewModel.seekToPositionMs(65070L)
      yield()
      val markStart = crazyBook.currentChapter.markForPosition(crazyBook.content.positionInChapter).startMs
      verify { playerMock.setPosition(markStart + 65070L, any()) }
    }
  }

  @Test
  fun `viewState synchronizes active reader paragraph and adjusts typography settings`() = scope.runTest {
    val crazyBook = book.copy(
      content = book.content.copy(
        remoteProjectId = "emberdark",
        positionInChapter = 66000L,
      )
    )

    val mockSync = mockk<CrazySyncManager> {
      coEvery { getChapterLyrics(any(), any()) } returns Result.success(
        CrazyChapterLyricsDto(projectId = "emberdark", chapterNumber = 1)
      )
      coEvery { getChapterReader(any(), any()) } returns Result.success(
        CrazyChapterReaderDto(
          projectId = "emberdark",
          chapterNumber = 1,
          title = "Prologue",
          paragraphs = listOf(
            CrazyReaderParagraphDto(index = 0, text = "Paragraph 1", startMs = 53450, endMs = 64170),
            CrazyReaderParagraphDto(index = 1, text = "Paragraph 2", startMs = 65070, endMs = 68590),
          )
        )
      )
    }

    val playerMock = mockk<PlayerController>(relaxed = true) {
      every { pauseIfCurrentBookDifferentFrom(any()) } just Runs
      every { livePlaybackStateFlow(any()) } returns MutableStateFlow(null)
    }

    val viewModel = viewModel(
      book = crazyBook,
      playerMock = playerMock,
      crazySyncManager = mockSync,
    )

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      skipItems(1)
      var item = awaitItem()!!
      while (item.readerState?.paragraphs.isNullOrEmpty()) {
        item = awaitItem()!!
      }

      val reader = item.readerState
      assertEquals(expected = 2, actual = reader.paragraphs.size)
      assertEquals(expected = 1, actual = reader.activeParagraphIndex) // 66000ms is in Paragraph 2 (65070..68590)

      // Test font size change
      viewModel.setReaderFontSize(24)
      assertEquals(expected = 24, actual = awaitItem()!!.readerState?.fontSizeSp)

      // Test theme change
      viewModel.setReaderTheme(ReaderTheme.Dark)
      assertEquals(expected = ReaderTheme.Dark, actual = awaitItem()!!.readerState?.theme)

      // Test auto-follow toggle
      viewModel.toggleAutoFollow(false)
      assertEquals(expected = false, actual = awaitItem()!!.readerState?.autoFollow)

      // Test paragraph tap seek
      viewModel.seekToPositionMs(53450L)
      yield()
      val markStart = crazyBook.currentChapter.markForPosition(crazyBook.content.positionInChapter).startMs
      verify { playerMock.setPosition(markStart + 53450L, any()) }
    }
  }

  private fun viewModel(
    book: Book = this.book,
    experimentalPlaybackPersistence: Boolean = false,
    kioskMode: Boolean = false,
    livePlaybackFlow: MutableStateFlow<LivePlaybackState?> = MutableStateFlow(null),
    playStateFlow: MutableStateFlow<PlayStateManager.PlayState> = MutableStateFlow(PlayStateManager.PlayState.Paused),
    playerMock: PlayerController? = null,
    crazySyncManager: CrazySyncManager = mockk {
      coEvery { getChapterLyrics(any(), any()) } returns Result.success(CrazyChapterLyricsDto(projectId = "test", chapterNumber = 1))
      coEvery { getChapterReader(any(), any()) } returns Result.success(CrazyChapterReaderDto(projectId = "test", chapterNumber = 1))
    },
  ): BookPlayViewModel {
    val effectivePlayer = playerMock ?: mockk {
      every { pauseIfCurrentBookDifferentFrom(book.id) } just Runs
      every { livePlaybackStateFlow(book.id) } returns livePlaybackFlow
    }
    return BookPlayViewModel(
      bookRepository = mockk {
        coEvery { get(book.id) } returns book
        every { flow(book.id) } returns MutableStateFlow(book)
      },
      currentBookResolver = mockk { coEvery { book(book.id) } returns book },
      player = effectivePlayer,
      sleepTimer = sleepTimer,
      playStateManager = mockk {
        every { this@mockk.playStateFlow } returns playStateFlow
        every { playState } returns playStateFlow.value
      },
      currentBookStoreId = MemoryDataStore(null),
      navigator = mockk(),
      bookmarkRepository = mockk(),
      volumeGainFormatter = mockk(),
      batteryOptimization = mockk(),
      sleepTimerPreferenceStore = sleepTimerDataStore,
      bookId = book.id,
      dispatcherProvider = DispatcherProvider(scope.coroutineContext, scope.coroutineContext, scope.coroutineContext),
      experimentalPlaybackPersistenceFeatureFlag = MemoryFeatureFlag(experimentalPlaybackPersistence),
      kioskModeFeatureFlag = MemoryFeatureFlag(kioskMode),
      crazySyncManager = crazySyncManager,
      showRemainingTimeStore = MemoryDataStore(true),
    )
  }
}

private fun book(
  name: String = "TestBook",
  lastPlayedAtMillis: Long = 0L,
  addedAtMillis: Long = 0L,
): Book {
  val chapters = listOf(
    chapter(),
    chapter(),
  )
  return Book(
    content = BookContent(
      author = Uuid.random().toString(),
      name = name,
      positionInChapter = 2.5.minutes.inWholeMilliseconds,
      playbackSpeed = 1F,
      addedAt = Instant.ofEpochMilli(addedAtMillis),
      chapters = chapters.map { it.id },
      cover = null,
      currentChapter = chapters[1].id,
      isActive = true,
      lastPlayedAt = Instant.ofEpochMilli(lastPlayedAtMillis),
      skipSilence = false,
      id = BookId(Uuid.random().toString()),
      gain = 0F,
      genre = null,
      narrator = null,
      series = null,
      part = null,
    ),
    chapters = chapters,
  )
}

private fun chapter(): Chapter {
  return Chapter(
    id = ChapterId("http://${Uuid.random()}"),
    duration = 5.minutes.inWholeMilliseconds,
    fileLastModified = Instant.EPOCH,
    markData = listOf(
      MarkData(startMs = 0L, name = "Chapter Start"),
      MarkData(startMs = 2.minutes.inWholeMilliseconds, name = "Middle Section"),
      MarkData(startMs = 4.minutes.inWholeMilliseconds, name = "Final Section"),
    ),
    name = "name",
    fileSize = 0,
  )
}
