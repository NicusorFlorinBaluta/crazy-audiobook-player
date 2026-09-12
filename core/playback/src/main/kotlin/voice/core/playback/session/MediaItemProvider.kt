package voice.core.playback.session

import android.app.Application
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.media3.common.C
import voice.core.common.formatTime
import voice.core.data.displayTitle
import voice.core.data.store.ShowRemainingTimeStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.ClippingConfiguration
import androidx.media3.common.MimeTypes
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import voice.core.data.Book
import voice.core.data.BookComparator
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.Chapter
import voice.core.data.durationMs
import voice.core.data.repo.BookContentRepo
import voice.core.data.repo.BookRepository
import voice.core.data.repo.ChapterRepo
import voice.core.data.store.CurrentBookStore
import voice.core.data.toUri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.io.ByteArrayOutputStream
import java.io.File
import voice.core.strings.R as StringsR

@Inject
class MediaItemProvider(
  private val bookRepository: BookRepository,
  private val application: Application,
  private val chapterRepo: ChapterRepo,
  private val contentRepo: BookContentRepo,
  private val imageFileProvider: ImageFileProvider,
  @CurrentBookStore
  private val currentBookStoreId: DataStore<BookId?>,
  @ShowRemainingTimeStore
  private val showRemainingTimeStore: DataStore<Boolean>,
) {

  fun root(): MediaItem = MediaItem(
    title = application.getString(StringsR.string.media_session_library_root),
    browsable = true,
    isPlayable = false,
    mediaId = MediaId.Root,
    mediaType = MediaType.AudioBookRoot,
  )

  fun allBooksTab(): MediaItem = MediaItem(
    title = application.getString(StringsR.string.media_session_library_root),
    browsable = true,
    isPlayable = false,
    mediaId = MediaId.AllBooks,
    mediaType = MediaType.AudioBookRoot,
  )

  fun recent(): MediaItem = MediaItem(
    title = application.getString(StringsR.string.media_session_library_recent),
    browsable = true,
    isPlayable = false,
    mediaId = MediaId.Recent,
    mediaType = MediaType.AudioBookRoot,
  )

  suspend fun item(id: String): MediaItem? {
    val mediaId = id.toMediaIdOrNull() ?: return null
    return when (mediaId) {
      MediaId.Root -> root()
      MediaId.AllBooks -> allBooksTab()
      is MediaId.Book -> {
        bookRepository.get(mediaId.id)?.let { mediaItem(it, isBrowseItem = false) }
      }
      is MediaId.Chapter -> {
        val content = contentRepo.get(mediaId.bookId) ?: return null
        chapterRepo.get(mediaId.chapterId)?.let {
          mediaItem(it, content)
        }
      }
      is MediaId.ChapterMark -> {
        val content = contentRepo.get(mediaId.bookId) ?: return null
        val chapter = chapterRepo.get(mediaId.chapterId) ?: return null
        val mark = chapter.chapterMarks.getOrNull(mediaId.markIndex) ?: return null
        mediaItem(
          playbackItem = PlaybackItem(
            index = 0,
            bookId = mediaId.bookId,
            chapter = chapter,
            markIndex = mediaId.markIndex,
            mark = mark,
          ),
          content = content,
        )
      }
      MediaId.Recent -> recent()
    }
  }

  fun mediaItemsWithStartPosition(book: Book): MediaItemsWithStartPosition {
    return MediaItemsWithStartPosition(
      listOf(mediaItem(book)),
      C.INDEX_UNSET,
      C.TIME_UNSET,
    )
  }

  suspend fun mediaItemsWithStartPosition(id: String): MediaItemsWithStartPosition? {
    return when (val mediaId = id.toMediaIdOrNull()) {
      is MediaId.Book -> {
        val book = bookRepository.get(mediaId.id) ?: return null
        mediaItemsWithStartPosition(book)
      }
      is MediaId.Chapter, is MediaId.ChapterMark, MediaId.Root, MediaId.Recent, MediaId.AllBooks, null -> null
    }
  }

  suspend fun chapters(bookId: BookId): List<MediaItem>? {
    val book = bookRepository.get(bookId) ?: return null
    val showRemaining = try { showRemainingTimeStore.data.first() } catch (_: Exception) { true }
    return playbackItems(book, showRemaining)
  }

  internal fun playbackItems(book: Book, showRemaining: Boolean? = null): List<MediaItem> {
    return book.playbackItems().map { playbackItem ->
      mediaItem(playbackItem, book.content, showRemaining)
    }
  }

  suspend fun children(id: String): List<MediaItem>? {
    val mediaId = id.toMediaIdOrNull() ?: return null
    return when (mediaId) {
      MediaId.Root -> {
        // Android Auto expects top-level browsable items as navigation tabs
        listOf(
          allBooksTab(),
          recent(),
        )
      }
      MediaId.AllBooks -> {
        bookRepository.all()
          .sortedWith(BookComparator.ByLastPlayed)
          .map { book ->
            mediaItem(book, isBrowseItem = true)
          }
      }
      is MediaId.Book -> chapters(mediaId.id)
      is MediaId.Chapter, is MediaId.ChapterMark -> null
      MediaId.Recent -> {
        val recentBooks = mutableListOf<MediaItem>()
        val currentBookId = currentBookStoreId.data.first()
        val currentBook = currentBookId?.let { bookRepository.get(it) }
        if (currentBook != null) {
          recentBooks.add(mediaItem(currentBook, isBrowseItem = true))
        }
        val otherRecent = bookRepository.all()
          .sortedWith(BookComparator.ByLastPlayed)
          .filter { it.id != currentBookId }
          .take(5)
          .map { mediaItem(it, isBrowseItem = true) }
        recentBooks.addAll(otherRecent)
        recentBooks
      }
    }
  }

  private val artworkCache = mutableMapOf<String, ByteArray>()

  private fun resolveCover(content: BookContent): File? {
    val cover = content.cover?.takeIf { it.exists() && it.length() > 0 }
    if (cover != null) return cover
    // Fallback: check crazy_covers folder directly if remoteProjectId is present
    return content.remoteProjectId?.let { pid ->
      File(application.filesDir, "crazy_covers/$pid.jpg").takeIf { it.exists() && it.length() > 0 }
    }
  }

  fun mediaItem(book: Book, isBrowseItem: Boolean = false): MediaItem {
    val cover = resolveCover(book.content)
    val hasMultipleChapters = book.playbackItems().size > 1
    return MediaItem(
      title = book.content.name,
      album = book.content.name,
      artist = book.content.author,
      genre = book.content.genre,
      mediaId = MediaId.Book(book.id),
      browsable = if (isBrowseItem) hasMultipleChapters else false,
      isPlayable = true,
      imageUri = cover?.toProvidedUri(),
      artworkData = if (isBrowseItem) null else cover?.toArtworkData(),
      mediaType = MediaType.AudioBook,
    )
  }

  private fun mediaItem(
    chapter: Chapter,
    content: BookContent,
  ): MediaItem {
    val cover = resolveCover(content)
    return MediaItem(
      title = chapter.name ?: chapter.id.value,
      album = content.name,
      artist = content.author,
      genre = content.genre,
      mediaId = MediaId.Chapter(bookId = content.id, chapterId = chapter.id),
      browsable = false,
      isPlayable = true,
      sourceUri = chapter.id.toUri(),
      imageUri = cover?.toProvidedUri(),
      artworkData = cover?.toArtworkData(),
      mediaType = MediaType.AudioBookChapter,
      mimeType = if (chapter.id.value.contains(".m4b", ignoreCase = true) || chapter.id.value.contains(".m4a", ignoreCase = true)) MimeTypes.AUDIO_MP4 else null,
    )
  }

  private fun mediaItem(
    playbackItem: PlaybackItem,
    content: BookContent,
    showRemaining: Boolean? = null,
  ): MediaItem {
    val needsClipping = playbackItem.mark.startMs > 0L ||
      (playbackItem.chapter.chapterMarks.size > 1 && playbackItem.mark.endMs < playbackItem.chapter.duration)
    val clippingConfig = if (needsClipping) {
      ClippingConfiguration.Builder()
        .setStartPositionMs(playbackItem.mark.startMs)
        .setEndPositionMs(playbackItem.mark.endMs)
        .build()
    } else {
      ClippingConfiguration.UNSET
    }
    val cover = resolveCover(content)
    val cleanTitle = playbackItem.mark.displayTitle.ifBlank {
      playbackItem.chapter.name ?: playbackItem.chapter.id.value
    }
    val isCurrent = playbackItem.chapter.id == content.currentChapter &&
      content.positionInChapter in playbackItem.mark.startMs..playbackItem.mark.endMs
    val durationText = formatTime(playbackItem.mark.durationMs, playbackItem.mark.durationMs)
    val shouldShowRemaining = showRemaining ?: runBlocking {
      try { showRemainingTimeStore.data.first() } catch (_: Exception) { true }
    }
    val subtitleText = if (isCurrent) {
      val playedInMark = (content.positionInChapter - playbackItem.mark.startMs).coerceAtLeast(0L)
      val remainingInMark = (playbackItem.mark.durationMs - playedInMark).coerceAtLeast(0L)
      val timePart = if (shouldShowRemaining) {
        "${formatTime(playedInMark, playbackItem.mark.durationMs)} (-${formatTime(remainingInMark, playbackItem.mark.durationMs)})"
      } else {
        "${formatTime(playedInMark, playbackItem.mark.durationMs)} / $durationText"
      }
      if (content.name.isNotBlank()) "$timePart • ${content.name}" else timePart
    } else {
      if (content.name.isNotBlank()) "$durationText • ${content.name}" else durationText
    }
    return MediaItem(
      title = cleanTitle,
      album = content.name,
      artist = content.author,
      subtitle = subtitleText,
      genre = content.genre,
      mediaId = playbackItem.mediaId,
      browsable = false,
      isPlayable = true,
      sourceUri = playbackItem.chapter.id.toUri(),
      imageUri = cover?.toProvidedUri(),
      artworkData = cover?.toArtworkData(),
      durationMs = playbackItem.mark.durationMs,
      clippingConfiguration = clippingConfig,
      mediaType = MediaType.AudioBookChapter,
      mimeType = if (playbackItem.chapter.id.value.contains(".m4b", ignoreCase = true) || playbackItem.chapter.id.value.contains(".m4a", ignoreCase = true)) MimeTypes.AUDIO_MP4 else null,
    )
  }

  private fun File.toProvidedUri(): Uri = imageFileProvider.uri(this)

  private fun File.toArtworkData(): ByteArray? {
    if (!exists() || length() <= 0) return null
    val path = absolutePath
    synchronized(artworkCache) {
      artworkCache[path]?.let { return it }
    }
    return try {
      val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
      }
      BitmapFactory.decodeFile(path, options)
      val w = options.outWidth
      val h = options.outHeight
      if (w <= 0 || h <= 0) return null

      val cardW = 320
      val cardH = 375

      val maxDim = maxOf(w, h)
      var sampleSize = 1
      while (maxDim / (sampleSize * 2) >= 375) {
        sampleSize *= 2
      }
      val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
      }
      val srcBitmap = BitmapFactory.decodeFile(path, decodeOptions) ?: return null

      val compositeBitmap = Bitmap.createBitmap(cardW, cardH, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(compositeBitmap)

      // Ambient blur
      val tiny = Bitmap.createScaledBitmap(srcBitmap, 16, 20, true)
      val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
      canvas.drawBitmap(tiny, Rect(0, 0, 16, 20), RectF(0f, 0f, cardW.toFloat(), cardH.toFloat()), bgPaint)
      tiny.recycle()

      canvas.drawColor(Color.argb(160, 12, 12, 12))

      val maxShowcaseH = 212f
      val maxShowcaseW = 288f
      val scale = minOf(maxShowcaseW / srcBitmap.width, maxShowcaseH / srcBitmap.height)
      val scaledW = (srcBitmap.width * scale).toInt()
      val scaledH = (srcBitmap.height * scale).toInt()
      val left = (cardW - scaledW) / 2f
      val top = 14f

      val borderPaint = Paint().apply {
        color = Color.argb(70, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
      }
      canvas.drawRect(left - 1, top - 1, left + scaledW + 1, top + scaledH + 1, borderPaint)

      val srcRect = Rect(0, 0, srcBitmap.width, srcBitmap.height)
      val dstRect = RectF(left, top, left + scaledW, top + scaledH)
      val coverPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
      canvas.drawBitmap(srcBitmap, srcRect, dstRect, coverPaint)
      srcBitmap.recycle()

      val bos = ByteArrayOutputStream()
      compositeBitmap.compress(Bitmap.CompressFormat.JPEG, 80, bos)
      compositeBitmap.recycle()
      val bytes = bos.toByteArray()
      synchronized(artworkCache) {
        if (artworkCache.size > 20) artworkCache.clear()
        artworkCache[path] = bytes
      }
      bytes
    } catch (_: Exception) {
      null
    }
  }
}
