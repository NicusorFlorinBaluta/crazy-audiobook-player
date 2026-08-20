package voice.core.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Request
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.data.MarkData
import voice.core.data.repo.BookContentRepo
import voice.core.data.repo.ChapterRepo
import voice.core.data.store.CrazyDownloadWifiOnlyStore
import voice.core.data.store.CrazyServerUrlStore
import java.io.File
import java.io.FileOutputStream
import java.time.Instant

@Inject
@SingleIn(AppScope::class)
public class CrazyBookSyncService(
  private val clientFactory: CrazyClientFactory,
  @CrazyServerUrlStore private val serverUrlStore: DataStore<String>,
  @CrazyDownloadWifiOnlyStore private val wifiOnlyStore: DataStore<Boolean>,
  private val bookContentRepo: BookContentRepo,
  private val chapterRepo: ChapterRepo,
  private val context: Context,
) : CrazySyncManager {

  private fun isWifiOrEthernetConnected(): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val activeNet = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(activeNet) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
  }

  override suspend fun syncCatalog(): Result<Int> = withContext(Dispatchers.IO) {
    runCatching {
      var rawUrl = serverUrlStore.data.first().trim()
      if (rawUrl.isBlank()) {
        throw IllegalArgumentException("Server URL is empty. Please set your Crazy Audiobook Server address in Settings.")
      }
      if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
        rawUrl = "http://$rawUrl"
      }
      val serverUrl = rawUrl.removeSuffix("/")
      val api = clientFactory.create(serverUrl)
      val okHttpClient = clientFactory.createOkHttpClient(serverUrl)

      val response = try {
        api.getCatalog()
      } catch (e: Exception) {
        throw IllegalStateException("Cannot connect to $serverUrl: ${e.localizedMessage ?: e.message}", e)
      }

      if (!response.isSuccessful || response.body() == null) {
        throw IllegalStateException("Server returned HTTP ${response.code()} from $serverUrl")
      }

      val catalog = response.body()!!.books
      var syncCount = 0

      val coversDir = File(context.filesDir, "crazy_covers").apply { mkdirs() }

      for (book in catalog) {
        if (book.projectId.startsWith("_")) {
          continue
        }
        if (book.status == "queued" && book.totalChapters == 0 && book.totalDurationSeconds <= 0.0) {
          continue
        }

        val bookId = BookId("crazy://${book.projectId}")
        val existingContent = bookContentRepo.get(bookId)

        // Download cover artwork locally if available
        var coverFile: File? = existingContent?.cover
        val rawCoverUrl = book.coverUrl
        if (rawCoverUrl != null && (coverFile == null || !coverFile.exists())) {
          try {
            val fullCoverUrl = if (rawCoverUrl.startsWith("http")) rawCoverUrl else "$serverUrl/$rawCoverUrl"
            val targetCover = File(coversDir, "${book.projectId}.jpg")
            val req = Request.Builder().url(fullCoverUrl).build()
            val coverResp = okHttpClient.newCall(req).execute()
            if (coverResp.isSuccessful) {
              val body = coverResp.body
              FileOutputStream(targetCover).use { out ->
                body.byteStream().copyTo(out)
              }
              coverFile = targetCover
            }
          } catch (_: Exception) {}
        }

        // Fetch detail with rich chapter manifest and metadata
        val detail = try {
          val detailResp = api.getBookDetail(book.projectId)
          detailResp.body()
        } catch (_: Exception) {
          null
        }

        val chaptersList = mutableListOf<Chapter>()
        val chapterIds = mutableListOf<ChapterId>()

        val validChapters = detail?.chapters?.filter { it.status == "mastered" || it.streamUrl != null } ?: emptyList()

        if (validChapters.isNotEmpty()) {
          for (ch in validChapters) {
            val rawStream = ch.streamUrl
            val streamUrl = if (rawStream != null) {
              if (rawStream.startsWith("http")) rawStream else "$serverUrl/$rawStream"
            } else {
              "$serverUrl/api/projects/${book.projectId}/stream/chapter/${ch.number}?format=aac"
            }
            val chId = ChapterId(streamUrl)
            chapterIds.add(chId)
            val durationMs = ((ch.durationSeconds ?: 0.0) * 1000.0).toLong()
            val finalDuration = if (durationMs > 0) durationMs else 60_000L

            val rawTitle = ch.title.trim()
            val chTitle = when {
              rawTitle.isBlank() -> "Chapter ${ch.number}"
              rawTitle.equals("Chapter ${ch.number}", ignoreCase = true) -> rawTitle
              rawTitle.startsWith("Chapter ${ch.number}:", ignoreCase = true) || rawTitle.startsWith("Chapter ${ch.number} -", ignoreCase = true) -> rawTitle
              else -> "Chapter ${ch.number}: $rawTitle"
            }

            val marks = listOf(
              MarkData(
                name = chTitle,
                startMs = 0L,
              )
            )

            chaptersList.add(
              Chapter(
                id = chId,
                name = chTitle,
                duration = finalDuration,
                fileLastModified = Instant.now(),
                fileSize = 0L,
                markData = marks,
              )
            )
          }
        } else {
          // Fallback for full book stream with embedded chapter marks
          val streamUrl = "$serverUrl/api/projects/${book.projectId}/stream"
          val chId = ChapterId(streamUrl)
          chapterIds.add(chId)
          val totalDurationMs = (book.totalDurationSeconds * 1000.0).toLong()
          val finalDuration = if (totalDurationMs > 0) totalDurationMs else 300_000L

          val marks = mutableListOf<MarkData>()
          var cumMs = 0L
          val allChapters = detail?.chapters ?: emptyList()
          if (allChapters.isNotEmpty()) {
            for (ch in allChapters) {
              val dur = ((ch.durationSeconds ?: 0.0) * 1000.0).toLong()
              val startMs = ch.startMs ?: cumMs
              val rawTitle = ch.title.trim()
              val markTitle = when {
                rawTitle.isBlank() -> "Chapter ${ch.number}"
                rawTitle.equals("Chapter ${ch.number}", ignoreCase = true) -> rawTitle
                rawTitle.startsWith("Chapter ${ch.number}:", ignoreCase = true) || rawTitle.startsWith("Chapter ${ch.number} -", ignoreCase = true) -> rawTitle
                else -> "Chapter ${ch.number}: $rawTitle"
              }
              marks.add(MarkData(name = markTitle, startMs = startMs))
              cumMs = (ch.endMs ?: (startMs + if (dur > 0) dur else 60_000L))
            }
          } else {
            marks.add(MarkData(name = book.title, startMs = 0L))
          }

          chaptersList.add(
            Chapter(
              id = chId,
              name = book.title,
              duration = finalDuration,
              fileLastModified = Instant.now(),
              fileSize = book.fileSizeBytes ?: 0L,
              markData = marks,
            )
          )
        }

        for (chapter in chaptersList) {
          chapterRepo.put(chapter)
        }

        val firstChapter = chapterIds.first()
        val isDownloaded = existingContent?.isDownloaded ?: false

        // Check remote progress from server for two-way sync
        var remoteChapterIndex = 0
        var remotePositionMs = 0L
        try {
          val progResp = api.getProgress(book.projectId)
          if (progResp.isSuccessful && progResp.body()?.savedPosition != null) {
            val saved = progResp.body()!!.savedPosition!!
            remoteChapterIndex = (saved.chapterNumber - 1).coerceAtLeast(0)
            remotePositionMs = saved.positionMs
          }
        } catch (_: Exception) {}

        val selectedCurrentChapter = if (existingContent != null && existingContent.currentChapter in chapterIds) {
          existingContent.currentChapter
        } else if (remoteChapterIndex in chapterIds.indices) {
          chapterIds[remoteChapterIndex]
        } else {
          firstChapter
        }

        val selectedPosition = if (existingContent != null && existingContent.positionInChapter > 0L) {
          existingContent.positionInChapter
        } else {
          remotePositionMs
        }

        val bookTitle = (detail?.title ?: book.title).ifBlank { "Unknown Title" }
        val bookAuthor = (detail?.author ?: book.author).ifBlank { "Unknown Author" }
        val bookGenre = (detail?.genre ?: book.genre).ifBlank { null }
        val bookNarrator = detail?.narrator?.ifBlank { null } ?: "AI Ensemble"
        val bookSeries = detail?.series?.ifBlank { null }
        val bookPart = detail?.part?.ifBlank { null } ?: (detail?.year ?: book.year).ifBlank { null }

        val newContent = BookContent(
          id = bookId,
          playbackSpeed = existingContent?.playbackSpeed ?: 1.0f,
          skipSilence = existingContent?.skipSilence ?: false,
          isActive = true,
          lastPlayedAt = existingContent?.lastPlayedAt ?: Instant.now(),
          author = bookAuthor,
          name = bookTitle,
          addedAt = existingContent?.addedAt ?: Instant.now(),
          chapters = chapterIds,
          currentChapter = selectedCurrentChapter,
          positionInChapter = selectedPosition,
          cover = coverFile,
          gain = 0.0f,
          genre = bookGenre,
          narrator = bookNarrator,
          series = bookSeries,
          part = bookPart,
          remoteProjectId = book.projectId,
          isRemoteStream = !isDownloaded,
          isDownloaded = isDownloaded,
          remoteStreamUrl = "$serverUrl/api/projects/${book.projectId}/stream",
          remoteStatus = book.status,
        )

        bookContentRepo.put(newContent)
        syncCount++
      }

      // Mark any remote books as inactive if they no longer exist in the server catalog
      val validRemoteBookIds = catalog.map { BookId("crazy://${it.projectId}") }.toSet()
      val allBooks = bookContentRepo.all()
      for (b in allBooks) {
        if (b.id.value.startsWith("crazy://") && b.id !in validRemoteBookIds) {
          if (b.isActive) {
            bookContentRepo.put(b.copy(isActive = false))
          }
          try {
            b.cover?.delete()
          } catch (_: Exception) {}
        }
      }

      syncCount
    }
  }

  override suspend fun syncProgressToServer(bookContent: BookContent) {
    val projectId = bookContent.remoteProjectId ?: return
    val rawUrl = serverUrlStore.data.first().trim()
    val serverUrl = (if (rawUrl.startsWith("http")) rawUrl else "http://$rawUrl").removeSuffix("/")
    val api = clientFactory.create(serverUrl)

    val currentChIndex = bookContent.currentChapterIndex + 1
    val resp = api.saveProgress(
      projectId = projectId,
      request = CrazyProgressRequest(
        clientId = "voice_android",
        chapterNumber = currentChIndex.coerceAtLeast(1),
        positionMs = bookContent.positionInChapter,
        playbackSpeed = bookContent.playbackSpeed,
        isCompleted = false,
      ),
    )
    if (!resp.isSuccessful) {
      return
    }
  }

  public suspend fun downloadBookOffline(
    bookId: BookId,
    onProgress: (Float, String) -> Unit = { _, _ -> },
  ): Result<Int> = withContext(Dispatchers.IO) {
    runCatching {
      val isWifiOnly = wifiOnlyStore.data.first()
      if (isWifiOnly && !isWifiOrEthernetConnected()) {
        throw IllegalStateException("Download paused: Wi-Fi only mode is enabled. (Can be disabled in Settings)")
      }

      val content = bookContentRepo.get(bookId)
        ?: throw IllegalArgumentException("Book not found in library: $bookId")
      val projectId = content.remoteProjectId
        ?: throw IllegalArgumentException("Not a Crazy Audiobook project: $bookId")

      var rawUrl = serverUrlStore.data.first().trim()
      if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
        rawUrl = "http://$rawUrl"
      }
      val serverUrl = rawUrl.removeSuffix("/")
      val api = clientFactory.create(serverUrl)
      val okHttpClient = clientFactory.createOkHttpClient(serverUrl)

      val detailResp = api.getBookDetail(projectId)
      if (!detailResp.isSuccessful || detailResp.body() == null) {
        throw IllegalStateException("Failed to load project details for download: HTTP ${detailResp.code()}")
      }
      val detail = detailResp.body()!!
      val validChapters = detail.chapters.filter { it.status == "mastered" || it.downloadUrl != null }
      if (validChapters.isEmpty()) {
        throw IllegalStateException("No mastered chapters available to download.")
      }

      val downloadsDir = File(context.filesDir, "crazy_downloads/$projectId").apply { mkdirs() }
      var downloadedCount = 0
      val total = validChapters.size

      val localChapters = mutableListOf<Chapter>()
      val localChapterIds = mutableListOf<ChapterId>()

      for ((idx, ch) in validChapters.withIndex()) {
        onProgress((idx.toFloat() / total), "Downloading Chapter ${ch.number} of $total...")
        val rawDlUrl = ch.downloadUrl ?: "api/projects/$projectId/download/chapter/${ch.number}"
        val fullDlUrl = if (rawDlUrl.startsWith("http")) rawDlUrl else "$serverUrl/$rawDlUrl"
        val chFile = File(downloadsDir, "chapter_${String.format("%03d", ch.number)}.wav")

        if (!chFile.exists() || chFile.length() == 0L) {
          val req = Request.Builder().url(fullDlUrl).build()
          val resp = okHttpClient.newCall(req).execute()
          if (!resp.isSuccessful) {
            throw IllegalStateException("Failed to download chapter ${ch.number}: HTTP ${resp.code}")
          }
          val body = resp.body
          FileOutputStream(chFile).use { out ->
            body.byteStream().copyTo(out)
          }
        }

        val localChId = ChapterId(Uri.fromFile(chFile).toString())
        localChapterIds.add(localChId)
        val durMs = ((ch.durationSeconds ?: 0.0) * 1000.0).toLong()
        val finalDur = if (durMs > 0) durMs else 60_000L
        val rawTitle = ch.title.trim()
        val chTitle = when {
          rawTitle.isBlank() -> "Chapter ${ch.number}"
          rawTitle.equals("Chapter ${ch.number}", ignoreCase = true) -> rawTitle
          rawTitle.startsWith("Chapter ${ch.number}:", ignoreCase = true) || rawTitle.startsWith("Chapter ${ch.number} -", ignoreCase = true) -> rawTitle
          else -> "Chapter ${ch.number}: $rawTitle"
        }

        val chapter = Chapter(
          id = localChId,
          name = chTitle,
          duration = finalDur,
          fileLastModified = Instant.now(),
          fileSize = chFile.length(),
          markData = listOf(MarkData(name = chTitle, startMs = 0L)),
        )
        chapterRepo.put(chapter)
        localChapters.add(chapter)
        downloadedCount++
      }

      val updatedContent = content.copy(
        isDownloaded = true,
        isRemoteStream = false,
        chapters = localChapterIds,
        currentChapter = localChapterIds.firstOrNull() ?: content.currentChapter,
      )
      bookContentRepo.put(updatedContent)
      onProgress(1.0f, "Download complete!")
      downloadedCount
    }
  }
}
