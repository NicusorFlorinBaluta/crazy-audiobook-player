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
            val chTitle = ch.title.ifBlank { "Chapter ${ch.number}" }

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
              marks.add(MarkData(name = ch.title.ifBlank { "Chapter ${ch.number}" }, startMs = startMs))
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

      val detail = api.getBookDetail(projectId).body()
        ?: throw IllegalStateException("Cannot fetch book details for $projectId")

      val validChapters = detail.chapters.filter { it.status == "mastered" || it.streamUrl != null }
      val downloadDir = File(context.filesDir, "crazy_downloads/$projectId").apply { mkdirs() }

      val localChapterIds = mutableListOf<ChapterId>()
      val totalToDownload = if (validChapters.isNotEmpty()) validChapters.size else 1
      var downloadedCount = 0

      if (validChapters.isNotEmpty()) {
        for ((idx, ch) in validChapters.withIndex()) {
          onProgress(idx.toFloat() / totalToDownload.toFloat(), "Downloading ${ch.title} (${idx + 1}/$totalToDownload)...")
          val downloadUrl = "$serverUrl/api/projects/$projectId/download/chapter/${ch.number}"
          val targetFile = File(downloadDir, "chapter_${ch.number}.wav")

          val req = Request.Builder().url(downloadUrl).build()
          val resp = okHttpClient.newCall(req).execute()
          if (resp.isSuccessful) {
            resp.body.byteStream().use { input ->
              FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
              }
            }
            val localId = ChapterId(Uri.fromFile(targetFile).toString())
            localChapterIds.add(localId)
            val durationMs = ((ch.durationSeconds ?: 0.0) * 1000.0).toLong()
            val finalDuration = if (durationMs > 0) durationMs else 60_000L

            chapterRepo.put(
              Chapter(
                id = localId,
                name = ch.title,
                duration = finalDuration,
                fileLastModified = Instant.now(),
                fileSize = targetFile.length(),
                markData = listOf(MarkData(name = ch.title, startMs = 0L)),
              )
            )
            downloadedCount++
          }
        }
      } else {
        onProgress(0.5f, "Downloading ${content.name}...")
        val downloadUrl = "$serverUrl/api/projects/$projectId/download"
        val targetFile = File(downloadDir, "$projectId.m4b")
        val req = Request.Builder().url(downloadUrl).build()
        val resp = okHttpClient.newCall(req).execute()
        if (resp.isSuccessful) {
          resp.body.byteStream().use { input ->
            FileOutputStream(targetFile).use { output ->
              input.copyTo(output)
            }
          }
          val localId = ChapterId(Uri.fromFile(targetFile).toString())
          localChapterIds.add(localId)

          val marks = mutableListOf<MarkData>()
          var cumMs = 0L
          for (ch in detail.chapters) {
            val dur = ((ch.durationSeconds ?: 0.0) * 1000.0).toLong()
            marks.add(MarkData(name = ch.title.ifBlank { "Chapter ${ch.number}" }, startMs = cumMs))
            cumMs += if (dur > 0) dur else 60_000L
          }

          chapterRepo.put(
            Chapter(
              id = localId,
              name = content.name,
              duration = (detail.totalChapters * 60_000L).coerceAtLeast(cumMs),
              fileLastModified = Instant.now(),
              fileSize = targetFile.length(),
              markData = marks,
            )
          )
          downloadedCount++
        }
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
