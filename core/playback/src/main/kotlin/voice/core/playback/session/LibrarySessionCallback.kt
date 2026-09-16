package voice.core.playback.session

import android.os.Bundle
import androidx.datastore.core.DataStore
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.MediaSession.ControllerInfo
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import voice.core.data.Book
import voice.core.data.BookId
import voice.core.data.chapterNumber
import voice.core.data.durationMs
import voice.core.data.markForPosition
import voice.core.data.repo.BookRepository
import voice.core.data.store.CurrentBookStore
import voice.core.data.store.ShowRemainingTimeStore
import voice.core.logging.api.Logger
import voice.core.playback.player.VoicePlayer
import voice.core.playback.session.search.BookSearchHandler
import voice.core.playback.session.search.BookSearchParser

@Inject
class LibrarySessionCallback(
  private val mediaItemProvider: MediaItemProvider,
  private val scope: CoroutineScope,
  private val player: VoicePlayer,
  private val bookSearchParser: BookSearchParser,
  private val bookSearchHandler: BookSearchHandler,
  @CurrentBookStore
  private val currentBookStoreId: DataStore<BookId?>,
  private val bookRepository: BookRepository,
  @ShowRemainingTimeStore
  private val showRemainingTimeStore: DataStore<Boolean>,
  private val crazySyncManager: voice.core.data.remote.CrazySyncManager,
  private val context: android.content.Context,
) : MediaLibrarySession.Callback {

  override fun onAddMediaItems(
    mediaSession: MediaSession,
    controller: ControllerInfo,
    mediaItems: MutableList<MediaItem>,
  ): ListenableFuture<List<MediaItem>> {
    Logger.d("onAddMediaItems")
    return scope.future {
      mediaItems.map { item ->
        mediaItemProvider.item(item.mediaId) ?: item
      }
    }
  }

  override fun onSetMediaItems(
    mediaSession: MediaSession,
    controller: ControllerInfo,
    mediaItems: MutableList<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ): ListenableFuture<MediaItemsWithStartPosition> {
    Logger.d("onSetMediaItems(mediaItems.size=${mediaItems.size}, startIndex=$startIndex, startPosition=$startPositionMs)")
    val item = mediaItems.singleOrNull()
    return if (startIndex == C.INDEX_UNSET && startPositionMs == C.TIME_UNSET && item != null) {
      scope.future {
        onSetMediaItemsForSingleItem(item)
          ?: super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs).await()
      }
    } else {
      super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
    }
  }

  private suspend fun onSetMediaItemsForSingleItem(item: MediaItem): MediaItemsWithStartPosition? {
    val searchQuery = item.requestMetadata.searchQuery
    return if (searchQuery != null) {
      val search = bookSearchParser.parse(searchQuery, item.requestMetadata.extras)
      val searchResult = bookSearchHandler.handle(search) ?: return null
      currentBookStoreId.updateData { searchResult.id }
      mediaItemProvider.mediaItemsWithStartPosition(searchResult)
    } else {
      val mediaId = item.mediaId.toMediaIdOrNull()
      val targetBookId = when (mediaId) {
        is MediaId.Book -> mediaId.id
        is MediaId.Chapter -> mediaId.bookId
        is MediaId.ChapterMark -> mediaId.bookId
        else -> null
      }
      if (targetBookId != null) {
        currentBookStoreId.updateData { targetBookId }
      }
      mediaItemProvider.mediaItemsWithStartPosition(item.mediaId)
    }
  }

  override fun onGetLibraryRoot(
    session: MediaLibrarySession,
    browser: ControllerInfo,
    params: LibraryParams?,
  ): ListenableFuture<LibraryResult<MediaItem>> {
    val rootItem = mediaItemProvider.root()
    Logger.d("onGetLibraryRoot(isRecent=${params?.isRecent == true}, controller=${browser.packageName}). Returning ${rootItem.mediaId}")
    return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
  }

  override fun onGetItem(
    session: MediaLibrarySession,
    browser: ControllerInfo,
    mediaId: String,
  ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
    Logger.d("onGetItem(mediaId=$mediaId)")
    val item = mediaItemProvider.item(mediaId)
    if (item != null) {
      LibraryResult.ofItem(item, null)
    } else {
      LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
    }
  }

  override fun onGetChildren(
    session: MediaLibrarySession,
    browser: ControllerInfo,
    parentId: String,
    page: Int,
    pageSize: Int,
    params: LibraryParams?,
  ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
    Logger.d("onGetChildren for $parentId")
    val children = mediaItemProvider.children(parentId)
    if (children != null) {
      LibraryResult.ofItemList(children, params)
    } else {
      LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
    }
  }

  override fun onPlaybackResumption(
    mediaSession: MediaSession,
    controller: ControllerInfo,
    isForPlayback: Boolean,
  ): ListenableFuture<MediaItemsWithStartPosition> {
    Logger.d("onPlaybackResumption")
    return scope.future {
      val currentBook = currentBook()
      if (currentBook != null) {
        mediaItemProvider.mediaItemsWithStartPosition(currentBook)
      } else {
        throw UnsupportedOperationException()
      }
    }
  }

  private suspend fun currentBook(): Book? {
    val bookId = currentBookStoreId.data.first() ?: return null
    return bookRepository.get(bookId)
  }

  @Suppress("DEPRECATION")
  override fun onConnect(
    session: MediaSession,
    controller: ControllerInfo,
  ): ConnectionResult {
    Logger.d("onConnect to ${controller.packageName}")

    val isCarController = controller.packageName in listOf(
      "com.google.android.projection.gearhead",
      "com.google.android.apps.automotive.templates.host",
      "com.google.android.carassistant",
      "com.google.android.gms",
    )
    if (isCarController) {
      Logger.d("onConnect to car controller ${controller.packageName}")
      scope.launch {
        // Preload catalog so books are immediately available in memory
        try {
          val books = bookRepository.all()
          Logger.d("Preloaded ${books.size} books on car connect")
        } catch (_: Exception) {}
      }
      if (player.playbackState == Player.STATE_IDLE) {
        Logger.d("Preparing current book so it shows up as recently played")
        scope.launch {
          prepareCurrentBook()
        }
      }
    }

    val connectionResult = super.onConnect(session, controller)
    val sessionCommands = connectionResult.availableSessionCommands
      .buildUpon()
      .add(SessionCommand(CustomCommand.CUSTOM_COMMAND_ACTION, Bundle.EMPTY))
      .add(SessionCommand(CustomCommand.CUSTOM_ACTION_FLAG_ISSUE, Bundle.EMPTY))
      .build()

    val flagButton = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
      .setIconResId(voice.core.playback.R.drawable.ic_flag)
      .setDisplayName(context.getString(voice.core.strings.R.string.playback_action_flag_issue))
      .setSessionCommand(SessionCommand(CustomCommand.CUSTOM_ACTION_FLAG_ISSUE, Bundle.EMPTY))
      .setSlots(CommandButton.SLOT_FORWARD, CommandButton.SLOT_FORWARD_SECONDARY)
      .build()

    val fastForwardButton = CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD)
      .setDisplayName(context.getString(voice.core.strings.R.string.playback_action_fast_forward))
      .setPlayerCommand(androidx.media3.common.Player.COMMAND_SEEK_FORWARD)
      .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
      .build()

    val rewindButton = CommandButton.Builder(CommandButton.ICON_SKIP_BACK)
      .setDisplayName(context.getString(voice.core.strings.R.string.playback_action_rewind))
      .setPlayerCommand(androidx.media3.common.Player.COMMAND_SEEK_BACK)
      .setSlots(CommandButton.SLOT_BACK)
      .build()

    val mediaButtons = listOf(
      rewindButton,
      flagButton,
      fastForwardButton,
    )

    return ConnectionResult.AcceptedResultBuilder(session)
      .setAvailableSessionCommands(sessionCommands)
      .setAvailablePlayerCommands(connectionResult.availablePlayerCommands)
      .setCustomLayout(listOf(flagButton))
      .setMediaButtonPreferences(mediaButtons)
      .build()
  }

  private suspend fun prepareCurrentBook() {
    val bookId = currentBookStoreId.data.first() ?: return
    val book = bookRepository.get(bookId) ?: return
    val item = mediaItemProvider.mediaItem(book)
    player.setMediaItem(item)
    player.prepare()
  }

  override fun onCustomCommand(
    session: MediaSession,
    controller: ControllerInfo,
    customCommand: SessionCommand,
    args: Bundle,
  ): ListenableFuture<SessionResult> {
    if (customCommand.customAction == CustomCommand.CUSTOM_ACTION_FLAG_ISSUE) {
      val isCar = controller.packageName in listOf(
        "com.google.android.projection.gearhead",
        "com.google.android.apps.automotive.templates.host",
        "com.google.android.carassistant",
        "com.google.android.gms",
      )
      scope.launch {
        handleFlagIssue(source = if (isCar) "android_auto" else "media_session")
      }
      return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }
    val command = CustomCommand.parse(customCommand, args)
      ?: return super.onCustomCommand(session, controller, customCommand, args)
    when (command) {
      CustomCommand.ForceSeekToNext -> {
        player.forceSeekToNext()
      }
      CustomCommand.ForceSeekToPrevious -> {
        player.forceSeekToPrevious()
      }
      is CustomCommand.SetSkipSilence -> {
        player.setSkipSilenceEnabled(command.skipSilence)
      }
      is CustomCommand.SetGain -> {
        player.setGain(command.gain)
      }
      CustomCommand.ToggleRemainingTime -> {
        scope.launch {
          showRemainingTimeStore.updateData { !it }
        }
      }
      is CustomCommand.FlagPlaybackIssue -> {
        scope.launch {
          handleFlagIssue(source = "command")
        }
      }
    }

    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
  }

  private suspend fun handleFlagIssue(source: String) {
    val currentBook = currentBook() ?: return
    val currentMediaItem = player.currentMediaItem
    val mediaId = currentMediaItem?.mediaId?.toMediaIdOrNull()
    val rawPosInChapter = mediaId?.positionInChapter(player.currentPosition)
      ?: currentBook.content.positionInChapter
    val currentMark = currentBook.currentChapter.markForPosition(rawPosInChapter)
    val chapterNumber = currentMark.chapterNumber ?: (currentBook.content.currentChapterIndex + 1)
    val positionInChapter = if (currentMark.durationMs > 0) {
      rawPosInChapter - currentMark.startMs
    } else {
      rawPosInChapter
    }

    val flagResult = runCatching {
      crazySyncManager.flagPlaybackIssue(
        bookId = currentBook.id,
        chapterNumber = chapterNumber,
        positionMs = positionInChapter,
        issueType = "wrong_speaker",
        userNote = "",
        source = source,
      )
    }
    Logger.d("Flagged playback issue from $source: $flagResult")

    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
      val totalSec = positionInChapter / 1000
      val mm = totalSec / 60
      val ss = totalSec % 60
      val timeStr = String.format("%02d:%02d", mm, ss)
      val msg = if (flagResult.isSuccess) {
        "Playback issue flagged at Ch $chapterNumber, $timeStr"
      } else {
        "Flagging issue failed: ${flagResult.exceptionOrNull()?.message ?: "Check server"}"
      }
      android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
  }
}
