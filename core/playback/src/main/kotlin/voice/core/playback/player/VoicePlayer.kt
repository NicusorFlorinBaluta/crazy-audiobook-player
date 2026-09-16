package voice.core.playback.player

import android.app.Application
import android.widget.Toast
import kotlinx.coroutines.Dispatchers

import androidx.datastore.core.DataStore
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import voice.core.analytics.api.Analytics
import androidx.media3.common.MediaMetadata
import voice.core.common.formatTime
import voice.core.data.Book
import voice.core.data.BookContent
import voice.core.data.store.ShowRemainingTimeStore
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import voice.core.data.BookId
import voice.core.data.repo.BookRepository
import voice.core.data.store.AutoRewindAmountStore
import voice.core.data.store.CurrentBookStore
import voice.core.data.store.SeekTimeStore
import voice.core.logging.api.Logger
import voice.core.playback.misc.Decibel
import voice.core.playback.misc.VolumeGain
import voice.core.playback.session.MediaId
import voice.core.playback.session.MediaItemProvider
import voice.core.playback.session.playbackItemForPosition
import voice.core.playback.session.playbackItems
import voice.core.playback.session.positionInMediaItem
import voice.core.playback.session.toMediaIdOrNull
import voice.core.sleeptimer.SleepTimer
import voice.core.sleeptimer.SleepTimerState
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Inject
class VoicePlayer(
  private val application: Application,
  private val player: Player,
  private val repo: BookRepository,
  @CurrentBookStore
  private val currentBookStoreId: DataStore<BookId?>,
  @SeekTimeStore
  private val seekTimeStore: DataStore<Int>,
  @AutoRewindAmountStore
  private val autoRewindAmountStore: DataStore<Int>,
  @ShowRemainingTimeStore
  private val showRemainingTimeStore: DataStore<Boolean>,
  private val mediaItemProvider: MediaItemProvider,
  private val scope: CoroutineScope,
  private val volumeGain: VolumeGain,
  private val sleepTimer: SleepTimer,
  private val analytics: Analytics,
) : ForwardingPlayer(player) {

  private val listeners = CopyOnWriteArraySet<Player.Listener>()
  private var dynamicMediaMetadata: MediaMetadata? = null
  private var timeTickerJob: Job? = null
  private var cachedBook: Book? = null
  private var cachedShowRemainingTime: Boolean = true

  override fun addListener(listener: Player.Listener) {
    super.addListener(listener)
    listeners.add(listener)
    dynamicMediaMetadata?.let {
      try {
        listener.onMediaMetadataChanged(it)
      } catch (e: Exception) {
        Logger.w(e, "Error dispatching onMediaMetadataChanged")
      }
    }
  }

  override fun removeListener(listener: Player.Listener) {
    super.removeListener(listener)
    listeners.remove(listener)
  }

  override fun getMediaMetadata(): MediaMetadata {
    return dynamicMediaMetadata ?: super.getMediaMetadata()
  }

  override fun getCurrentMediaItem(): MediaItem? {
    val item = super.getCurrentMediaItem() ?: return null
    val meta = dynamicMediaMetadata ?: return item
    return item.buildUpon().setMediaMetadata(meta).build()
  }

  internal val playerListener = object : Player.Listener {
    override fun onPositionDiscontinuity(
      oldPosition: Player.PositionInfo,
      newPosition: Player.PositionInfo,
      reason: Int,
    ) {
      if (reason == DISCONTINUITY_REASON_AUTO_TRANSITION) {
        pauseAndDisableSleepTimerIfEndOfChapter()
      }
      updateDynamicMetadata()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
      if (playbackState == STATE_ENDED) {
        pauseAndDisableSleepTimerIfEndOfChapter()
      }
      syncTimeTicker()
      updateDynamicMetadata()
    }

    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
      Logger.d("onPlaybackSuppressionReasonChanged=$playbackSuppressionReason")
      if (playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS) {
        triggerAutoRewind()
      }
    }

    override fun onPlayWhenReadyChanged(
      playWhenReady: Boolean,
      reason: Int,
    ) {
      Logger.d("onPlayWhenReadyChanged playWhenReady=$playWhenReady reason=$reason")
      if (!playWhenReady && (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS || reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)) {
        triggerAutoRewind()
      }
      syncTimeTicker()
      updateDynamicMetadata()
    }

    override fun onMediaItemTransition(
      mediaItem: MediaItem?,
      reason: Int,
    ) {
      scope.launch {
        val bookId = currentBookStoreId.data.first()
        cachedBook = bookId?.let { repo.get(it) }
        updateDynamicMetadata()
      }
    }

    override fun onPlayerError(error: PlaybackException) {
      val msg = "Playback error: ${error.errorCodeName} (${error.errorCode}): ${error.message}"
      Logger.e(msg)
      scope.launch(Dispatchers.Main) {
        try {
          Toast.makeText(application, msg, Toast.LENGTH_LONG).show()
        } catch (_: Exception) {}
      }
    }

    private fun pauseAndDisableSleepTimerIfEndOfChapter() {
      if (sleepTimer.state.value !is SleepTimerState.Enabled.WithEndOfChapter) return
      Logger.v("Pausing due to EndOfChapter")
      sleepTimer.disable()
      player.pause()
    }
  }

  init {
    player.addListener(playerListener)
  }

  private fun syncTimeTicker() {
    val isPlaying = player.playWhenReady && player.playbackState == Player.STATE_READY
    if (isPlaying) {
      if (timeTickerJob == null || timeTickerJob?.isActive != true) {
        timeTickerJob = scope.launch {
          while (true) {
            delay(1000)
            updateDynamicMetadata()
          }
        }
      }
    } else {
      timeTickerJob?.cancel()
      timeTickerJob = null
    }
  }

  internal fun updateDynamicMetadata() {
    val currentItem = player.currentMediaItem ?: return
    val baseMeta = currentItem.mediaMetadata
    val book = cachedBook ?: runBlocking {
      currentBookStoreId.data.first()?.let { repo.get(it) }
    }?.also { cachedBook = it } ?: return
    val bookName = book.content.name

    val currentPosMs = player.currentPosition.takeIf { it >= 0L } ?: 0L
    val durationMs = player.duration.takeIf { it > 0L } ?: baseMeta.durationMs ?: 0L
    val remainingMs = (durationMs - currentPosMs).coerceAtLeast(0L)

    val showRemaining = runBlocking {
      try { showRemainingTimeStore.data.first() } catch (_: Exception) { true }
    }

    val timeFormatted = if (durationMs > 0L) {
      if (showRemaining) {
        "${formatTime(currentPosMs, durationMs)} (-${formatTime(remainingMs, durationMs)})"
      } else {
        "${formatTime(currentPosMs, durationMs)} / ${formatTime(durationMs, durationMs)}"
      }
    } else {
      formatTime(currentPosMs)
    }

    val newSubtitle = if (bookName.isNotBlank()) "$timeFormatted • $bookName" else timeFormatted

    if (dynamicMediaMetadata?.subtitle?.toString() == newSubtitle) {
      return
    }

    val updatedMetadata = baseMeta.buildUpon()
      .setSubtitle(newSubtitle)
      .build()

    dynamicMediaMetadata = updatedMetadata
    listeners.forEach { listener ->
      try {
        listener.onMediaMetadataChanged(updatedMetadata)
      } catch (e: Exception) {
        Logger.w(e, "Error dispatching onMediaMetadataChanged")
      }
    }
  }

  fun forceSeekToNext() {
    scope.launch {
      val nextMediaItemIndex = player.nextMediaItemIndex.takeUnless { it == C.INDEX_UNSET }
        ?: return@launch
      player.seekTo(nextMediaItemIndex, 0)
    }
  }

  fun forceSeekToPrevious() {
    scope.launch {
      val currentPosition = player.currentPosition
      if (currentPosition > THRESHOLD_FOR_BACK_SEEK_MS) {
        player.seekTo(0)
      } else {
        val previousMediaItemIndex = player.previousMediaItemIndex.takeUnless { it == C.INDEX_UNSET }
        if (previousMediaItemIndex != null) {
          player.seekTo(previousMediaItemIndex, 0)
        } else {
          player.seekTo(0)
        }
      }
    }
  }

  override fun getAvailableCommands(): Player.Commands {
    return super.getAvailableCommands()
      .buildUpon()
      .addAll(
        COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        COMMAND_SEEK_TO_PREVIOUS,
        COMMAND_SEEK_TO_NEXT,
        COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
      )
      .build()
  }

  override fun seekToPreviousMediaItem() {
    seekBack()
  }

  override fun seekToNextMediaItem() {
    seekForward()
  }

  override fun seekToPrevious() {
    seekBack()
  }

  override fun seekToNext() {
    seekForward()
  }

  override fun seekBack() {
    scope.launch {
      seekBackBy(seekTimeStore.data.first().seconds)
    }
  }

  private suspend fun seekBackBy(skipAmount: Duration) {
    seekBackBy(
      skipAmount = skipAmount,
      crossMediaItems = true,
    )
  }

  private suspend fun seekBackBy(
    skipAmount: Duration,
    crossMediaItems: Boolean,
  ) {
    var currentPosition = player.currentPosition.takeUnless { it == C.TIME_UNSET }
      ?.milliseconds
      ?.coerceAtLeast(ZERO)
      ?: return
    var remaining = skipAmount
    var mediaItemIndex = player.currentMediaItemIndex.takeUnless { it == C.INDEX_UNSET } ?: return

    while (remaining > currentPosition) {
      if (!crossMediaItems) {
        player.seekTo(mediaItemIndex, 0)
        return
      }
      remaining -= currentPosition
      val previousMediaItemIndex = mediaItemIndex - 1
      if (previousMediaItemIndex < 0) {
        player.seekTo(0)
        return
      }
      val previousMediaItem = player.getMediaItemAt(previousMediaItemIndex)
      currentPosition = previousMediaItem.mediaMetadata.durationMs?.milliseconds ?: return
      mediaItemIndex = previousMediaItemIndex
    }

    player.seekTo(mediaItemIndex, (currentPosition - remaining).inWholeMilliseconds)
  }

  override fun seekForward() {
    scope.launch {
      val skipAmount = seekTimeStore.data.first().seconds

      val currentPosition = player.currentPosition.takeUnless { it == C.TIME_UNSET }
        ?.milliseconds
        ?.coerceAtLeast(ZERO)
        ?: return@launch
      val newPosition = currentPosition + skipAmount

      val duration = player.duration.takeUnless { it == C.TIME_UNSET }
        ?.milliseconds
        ?: return@launch

      if (newPosition > duration) {
        val nextMediaItemIndex = nextMediaItemIndex.takeUnless { it == C.INDEX_UNSET }
          ?: return@launch
        player.seekTo(nextMediaItemIndex, (duration - newPosition).absoluteValue.inWholeMilliseconds)
      } else {
        player.seekTo(newPosition.inWholeMilliseconds)
      }
    }
  }

  override fun play() {
    playWhenReady = true
  }

  private fun triggerAutoRewind() {
    val currentPosition = player.currentPosition.takeUnless { it == C.TIME_UNSET }?.milliseconds ?: ZERO
    if (currentPosition > ZERO) {
      scope.launch {
        val amount = autoRewindAmountStore.data.first().seconds
        if (amount > ZERO) {
          seekBackBy(
            skipAmount = amount,
            crossMediaItems = false,
          )
        }
      }
    }
  }

  override fun setPlayWhenReady(playWhenReady: Boolean) {
    Logger.d("setPlayWhenReady=$playWhenReady")
    analytics.event(if (playWhenReady) "play" else "pause")

    if (playWhenReady) {
      updateLastPlayedAt()
    } else {
      triggerAutoRewind()
    }
    super.setPlayWhenReady(playWhenReady)
  }

  override fun pause() {
    playWhenReady = false
  }

  private fun updateLastPlayedAt() {
    scope.launch {
      currentBookStoreId.data.first()?.let { bookId ->
        repo.updateBook(bookId) {
          val lastPlayedAt = Instant.now()
          Logger.v("Update ${it.name}: lastPlayedAt to $lastPlayedAt")
          it.copy(lastPlayedAt = lastPlayedAt)
        }
      }
    }
  }

  override fun getPlaybackState(): Int = when (val state = super.getPlaybackState()) {
    STATE_BUFFERING -> STATE_READY
    else -> state
  }

  override fun setMediaItem(
    mediaItem: MediaItem,
    startPositionMs: Long,
  ) {
    setBook(mediaItem, explicitStartPositionMs = startPositionMs.takeUnless { it == C.TIME_UNSET })
  }

  override fun setMediaItem(
    mediaItem: MediaItem,
    resetPosition: Boolean,
  ) {
    setBook(mediaItem, resetPosition = resetPosition)
  }

  override fun setMediaItems(mediaItems: List<MediaItem>) {
    val first = mediaItems.firstOrNull() ?: return
    setBook(first, explicitMediaItems = mediaItems)
  }

  override fun setMediaItems(
    mediaItems: List<MediaItem>,
    resetPosition: Boolean,
  ) {
    val first = mediaItems.firstOrNull() ?: return
    setBook(first, explicitMediaItems = mediaItems, resetPosition = resetPosition)
  }

  override fun setMediaItem(mediaItem: MediaItem) {
    setBook(mediaItem)
  }

  override fun setMediaItems(
    mediaItems: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ) {
    if (mediaItems.isEmpty()) return
    val targetIndex = if (startIndex != C.INDEX_UNSET && startIndex in mediaItems.indices) startIndex else 0
    val targetItem = mediaItems.getOrNull(targetIndex) ?: mediaItems.first()
    setBook(
      targetItem,
      explicitMediaItems = mediaItems,
      explicitStartIndex = startIndex.takeUnless { it == C.INDEX_UNSET },
      explicitStartPositionMs = startPositionMs.takeUnless { it == C.TIME_UNSET },
    )
  }

  private fun setBook(
    mediaItem: MediaItem,
    explicitMediaItems: List<MediaItem>? = null,
    explicitStartIndex: Int? = null,
    explicitStartPositionMs: Long? = null,
    resetPosition: Boolean = false,
  ) {
    Logger.v("setBook(${mediaItem.mediaId}, startIndex=$explicitStartIndex, startPos=$explicitStartPositionMs)")
    val mediaId = mediaItem.mediaId.toMediaIdOrNull()
    if (mediaId != null) {
      val targetBookId = when (mediaId) {
        is MediaId.Book -> mediaId.id
        is MediaId.Chapter -> mediaId.bookId
        is MediaId.ChapterMark -> mediaId.bookId
        else -> null
      }
      if (targetBookId != null) {
        val book = runBlocking {
          repo.get(targetBookId)
        }
        if (book != null) {
          cachedBook = book
          player.setPlaybackSpeed(book.content.playbackSpeed)
          setSkipSilenceEnabled(book.content.skipSilence)
          volumeGain.gain = Decibel(book.content.gain)
          val playbackItems = book.playbackItems()
          if (playbackItems.isEmpty()) return

          val (targetPlaybackItem, initialPosMs) = when {
            explicitStartIndex != null -> {
              val item = playbackItems.getOrNull(explicitStartIndex) ?: playbackItems.first()
              Pair(item, explicitStartPositionMs ?: 0L)
            }
            resetPosition -> {
              Pair(playbackItems.first(), 0L)
            }
            mediaId is MediaId.Chapter -> {
              val item = playbackItems.find { it.chapter.id == mediaId.chapterId } ?: playbackItems.first()
              Pair(item, explicitStartPositionMs ?: 0L)
            }
            mediaId is MediaId.ChapterMark -> {
              val item = playbackItems.find { it.chapter.id == mediaId.chapterId && it.markIndex == mediaId.markIndex }
                ?: playbackItems.first()
              Pair(item, explicitStartPositionMs ?: 0L)
            }
            else -> {
              val cur = book.playbackItemForPosition(
                chapterId = book.content.currentChapter,
                positionInChapterMs = book.content.positionInChapter,
              ) ?: playbackItems.first()
              Pair(cur, cur.positionInMediaItem(book.content.positionInChapter))
            }
          }

          val mediaItems = explicitMediaItems ?: mediaItemProvider.playbackItems(book)
          if (mediaItems.isNotEmpty()) {
            player.setMediaItems(
              mediaItems,
              targetPlaybackItem.index.coerceIn(0, mediaItems.size - 1),
              initialPosMs,
            )
            player.prepare()
          }
        }
      } else {
        Logger.w("Unexpected mediaId=$mediaId")
      }
    }
  }

  override fun setPlaybackSpeed(speed: Float) {
    super.setPlaybackSpeed(speed)
    scope.launch {
      updateBook { it.copy(playbackSpeed = speed) }
    }
  }

  fun setSkipSilenceEnabled(enabled: Boolean) {
    scope.launch {
      updateBook { it.copy(skipSilence = enabled) }
    }
    if (player is ExoPlayer) {
      player.skipSilenceEnabled = enabled
    }
  }

  fun setGain(gain: Decibel) {
    volumeGain.gain = gain
    scope.launch {
      updateBook { it.copy(gain = gain.value) }
    }
  }

  override fun release() {
    timeTickerJob?.cancel()
    timeTickerJob = null
    player.removeListener(playerListener)
    super.release()
  }

  private suspend fun updateBook(update: (BookContent) -> BookContent) {
    val bookId = currentBookStoreId.data.first() ?: return
    repo.updateBook(bookId, update)
  }
}

private const val THRESHOLD_FOR_BACK_SEEK_MS = 2000
