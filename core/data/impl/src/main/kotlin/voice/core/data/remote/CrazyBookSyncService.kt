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
import voice.core.data.store.CrazyIgnoredBooksStore
import voice.core.data.store.CrazyServerUrlStore
import voice.core.logging.api.Logger
import java.io.File
import java.io.FileOutputStream
import java.time.Instant

private val crazyJson: kotlinx.serialization.json.Json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

@Inject
@SingleIn(AppScope::class)
public class CrazyBookSyncService(
  private val clientFactory: CrazyClientFactory,
  @CrazyServerUrlStore private val serverUrlStore: DataStore<String>,
  @CrazyDownloadWifiOnlyStore private val wifiOnlyStore: DataStore<Boolean>,
  @CrazyIgnoredBooksStore private val ignoredBooksStore: DataStore<Set<String>>,
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
        rawUrl = if (rawUrl.startsWith("192.168.") || rawUrl.startsWith("10.") || rawUrl.startsWith("localhost")) "http://$rawUrl" else "https://$rawUrl"
      }
      val (cleanServerUrl, _) = clientFactory.parseUrlAndAuth(rawUrl)
      val serverUrl = cleanServerUrl.removeSuffix("/")
      val api = clientFactory.create(rawUrl)
      val okHttpClient = clientFactory.createOkHttpClient(rawUrl)

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

      val ignored = ignoredBooksStore.data.first()
      for (book in catalog) {
        if (book.projectId.startsWith("_") || book.projectId in ignored) {
          continue
        }
        if (book.status == "queued" && book.totalChapters == 0 && book.totalDurationSeconds <= 0.0) {
          continue
        }

        val bookId = BookId("crazy://${book.projectId}")
        val existingContent = bookContentRepo.get(bookId)

        // Download cover artwork locally if available
        var coverFile: File? = existingContent?.cover
        val targetCover = File(coversDir, "${book.projectId}.jpg")
        if ((coverFile == null || !coverFile.exists() || coverFile.length() <= 0) && targetCover.exists() && targetCover.length() > 0) {
          coverFile = targetCover
        }
        val rawCoverUrl = book.coverUrl
        if (!rawCoverUrl.isNullOrBlank() && (coverFile == null || !coverFile.exists() || coverFile.length() <= 0)) {
          try {
            val fullCoverUrl = if (rawCoverUrl.startsWith("http")) rawCoverUrl else "${serverUrl.trimEnd('/')}/${rawCoverUrl.trimStart('/')}"
            val req = Request.Builder().url(fullCoverUrl).build()
            val coverResp = okHttpClient.newCall(req).execute()
            if (coverResp.isSuccessful) {
              FileOutputStream(targetCover).use { out ->
                coverResp.body.byteStream().copyTo(out)
              }
              if (targetCover.exists() && targetCover.length() > 0) {
                coverFile = targetCover
              }
            }
          } catch (e: Exception) {
            Logger.w("Failed to download cover: ${e.message}")
          }
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

        val publishedDeliveries = detail?.deliveries?.filter { it.status == "published" && it.downloadUrl.isNotBlank() } ?: emptyList()
        val validChapters = detail?.chapters?.filter { it.status == "mastered" || it.streamUrl != null } ?: emptyList()

        if (publishedDeliveries.isNotEmpty()) {
          for (delivery in publishedDeliveries) {
            val rawStream = delivery.downloadUrl.split("#").first()
            val streamUrl = if (rawStream.startsWith("http")) rawStream else "$serverUrl/$rawStream"
            val chId = ChapterId(streamUrl)
            chapterIds.add(chId)
            val chDetails = if (delivery.chapterDetails.isNotEmpty()) {
              delivery.chapterDetails
            } else {
              val firstCh = detail?.chapters?.find { it.number in delivery.chapters }
              val baseOffset = firstCh?.startMs ?: 0L
              detail?.chapters?.filter { it.number in delivery.chapters }?.map {
                it.copy(startMs = (it.startMs ?: 0L) - baseOffset)
              } ?: emptyList()
            }

            val chDetailsDuration = chDetails.sumOf { ((it.durationSeconds ?: 0.0) * 1000.0).toLong() }
            val durationMs = ((delivery.durationSeconds ?: 0.0) * 1000.0).toLong()
            val finalDuration = if (durationMs > 0) durationMs else if (chDetailsDuration > 0) chDetailsDuration else 300_000L

            val marks = mutableListOf<MarkData>()

            for (ch in chDetails) {
              val rawTitle = ch.title.replace(Regex("""^Chapter\s+\d+\s*[:\-–]\s*""", RegexOption.IGNORE_CASE), "").trim()
              val cleanTitle = rawTitle.ifBlank { "Chapter ${ch.number}" }
              val markTitle = "${ch.number}::$cleanTitle"
              marks.add(
                MarkData(
                  name = markTitle,
                  startMs = ch.startMs ?: 0L,
                )
              )
            }

            if (marks.isEmpty()) {
              marks.add(MarkData(name = delivery.title, startMs = 0L))
            }

            chaptersList.add(
              Chapter(
                id = chId,
                name = delivery.title,
                duration = finalDuration,
                fileLastModified = Instant.now(),
                fileSize = 0L,
                markData = marks,
              )
            )
          }
        } else if (validChapters.isNotEmpty()) {
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

            val rawTitle = ch.title.replace(Regex("""^Chapter\s+\d+\s*[:\-–]\s*""", RegexOption.IGNORE_CASE), "").trim()
            val chTitle = rawTitle.ifBlank { "Chapter ${ch.number}" }

            val marks = listOf(
              MarkData(
                name = chTitle,
                startMs = ch.startMs ?: 0L,
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
          val rawFull = book.streamUrl.ifBlank { detail?.streamUrl ?: "" }
          val streamUrl = if (rawFull.isNotBlank()) {
            if (rawFull.startsWith("http")) rawFull else "$serverUrl/${rawFull.trimStart('/')}"
          } else {
            "$serverUrl/api/projects/${book.projectId}/stream"
          }
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
              val rawTitle = ch.title.replace(Regex("""^Chapter\s+\d+\s*[:\-–]\s*""", RegexOption.IGNORE_CASE), "").trim()
              val markTitle = rawTitle.ifBlank { "Chapter ${ch.number}" }
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
        val downloadsDir = File(context.filesDir, "crazy_downloads/${book.projectId}")
        val hasDownloadedFiles = downloadsDir.exists() && (downloadsDir.listFiles()?.any { it.isFile && it.length() > 0 } == true)
        val isDownloaded = (existingContent?.isDownloaded == true) && hasDownloadedFiles

        // Check remote progress from server for two-way sync
        var selectedCurrentChapter = firstChapter
        var selectedPosition = 0L

        var remoteChapterNumber = 1
        var remotePosInChapter = 0L
        try {
          val progResp = api.getProgress(book.projectId)
          if (progResp.isSuccessful && progResp.body()?.savedPosition != null) {
            val saved = progResp.body()!!.savedPosition!!
            remoteChapterNumber = saved.chapterNumber.coerceAtLeast(1)
            remotePosInChapter = saved.positionMs.coerceAtLeast(0L)
          }
        } catch (_: Exception) {}

        if (publishedDeliveries.isNotEmpty()) {
          val deliveryIdx = publishedDeliveries.indexOfFirst { remoteChapterNumber in it.chapters }
          if (deliveryIdx in chapterIds.indices) {
            selectedCurrentChapter = chapterIds[deliveryIdx]
            val deliv = publishedDeliveries[deliveryIdx]
            val chDetail = deliv.chapterDetails.find { it.number == remoteChapterNumber }
            val baseStartMs = chDetail?.startMs ?: 0L
            selectedPosition = baseStartMs + remotePosInChapter
          }
        } else if (validChapters.isNotEmpty()) {
          val chIdx = (remoteChapterNumber - 1).coerceIn(chapterIds.indices)
          selectedCurrentChapter = chapterIds[chIdx]
          selectedPosition = remotePosInChapter
        } else {
          selectedCurrentChapter = firstChapter
          selectedPosition = remotePosInChapter
        }

        // If user already had local progress on this device and that chapter is valid, keep local
        if (existingContent != null && existingContent.currentChapter in chapterIds && existingContent.positionInChapter > 0L) {
          selectedCurrentChapter = existingContent.currentChapter
          selectedPosition = existingContent.positionInChapter
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
          remoteStatus = "${book.status}@${System.currentTimeMillis()}",
        )

        bookContentRepo.put(newContent)
        syncCount++
      }

      // Mark any remote books as inactive if they no longer exist in the server catalog or are ignored
      val validRemoteBookIds = catalog
        .filter { it.projectId !in ignored }
        .map { BookId("crazy://${it.projectId}") }
        .toSet()
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

  override suspend fun ignoreBook(projectId: String) {
    ignoredBooksStore.updateData { it + projectId }
    val bookId = BookId("crazy://$projectId")
    val existing = bookContentRepo.get(bookId)
    if (existing != null && existing.isActive) {
      bookContentRepo.put(existing.copy(isActive = false))
    }
  }

  override suspend fun restoreIgnoredBooks(): Result<Int> {
    ignoredBooksStore.updateData { emptySet() }
    return syncCatalog()
  }

  override suspend fun syncProgressToServer(bookContent: BookContent) {
    val projectId = bookContent.remoteProjectId ?: return
    val rawUrl = serverUrlStore.data.first().trim()
    val serverUrl = (if (rawUrl.startsWith("http")) rawUrl else "http://$rawUrl").removeSuffix("/")
    val api = clientFactory.create(serverUrl)

    val currentChapterObj = chapterRepo.get(bookContent.currentChapter)
    var trueChapterNumber = bookContent.currentChapterIndex + 1
    var posInTrueChapter = bookContent.positionInChapter

    if (currentChapterObj != null && currentChapterObj.chapterMarks.isNotEmpty()) {
      val mark = currentChapterObj.chapterMarks.find { bookContent.positionInChapter in it.startMs..it.endMs }
        ?: currentChapterObj.chapterMarks.first()
      val mNum = Regex("""Chapter\s+(\d+)""", RegexOption.IGNORE_CASE).find(mark.name ?: "")?.groupValues?.get(1)?.toIntOrNull()
      if (mNum != null) {
        trueChapterNumber = mNum
        posInTrueChapter = (bookContent.positionInChapter - mark.startMs).coerceAtLeast(0L)
      } else {
        posInTrueChapter = (bookContent.positionInChapter - mark.startMs).coerceAtLeast(0L)
      }
    }

    val resp = api.saveProgress(
      projectId = projectId,
      request = CrazyProgressRequest(
        clientId = "voice_android",
        chapterNumber = trueChapterNumber.coerceAtLeast(1),
        positionMs = posInTrueChapter,
        playbackSpeed = bookContent.playbackSpeed,
        isCompleted = false,
      ),
    )
    if (!resp.isSuccessful) {
      return
    }
  }

  override suspend fun downloadBookOffline(
    bookId: BookId,
    onProgress: (Float, String) -> Unit,
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
        rawUrl = if (rawUrl.startsWith("192.168.") || rawUrl.startsWith("10.") || rawUrl.startsWith("localhost")) "http://$rawUrl" else "https://$rawUrl"
      }
      val serverUrl = rawUrl.removeSuffix("/")
      val api = clientFactory.create(serverUrl)
      val okHttpClient = clientFactory.createOkHttpClient(serverUrl)

      val detailResp = api.getBookDetail(projectId)
      if (!detailResp.isSuccessful || detailResp.body() == null) {
        throw IllegalStateException("Failed to load project details for download: HTTP ${detailResp.code()}")
      }
      val detail = detailResp.body()!!
      val validChapters = detail.chapters.filter { it.status == "mastered" || it.streamUrl != null || it.downloadUrl != null }
      if (validChapters.isEmpty()) {
        throw IllegalStateException("No mastered chapters available to download.")
      }

      val downloadsDir = File(context.filesDir, "crazy_downloads/$projectId").apply { mkdirs() }
      var downloadedCount = 0

      // Map each chapter to its downloaded local part file
      val localChapters = mutableListOf<Chapter>()
      val localChapterIds = mutableListOf<ChapterId>()
      val downloadedPartFiles = mutableMapOf<String, File>()

      val publishedDeliveries = detail.deliveries.filter { it.status == "published" && it.downloadUrl.isNotBlank() }
      val totalParts = if (publishedDeliveries.isNotEmpty()) publishedDeliveries.size else validChapters.size
      var partIdx = 0

      if (publishedDeliveries.isNotEmpty()) {
        for (delivery in publishedDeliveries) {
          partIdx++
          onProgress((partIdx.toFloat() / totalParts), "Downloading ${delivery.title} ($partIdx of $totalParts)...")
          val rawDlUrl = delivery.downloadUrl.split("#").first()
          val fullDlUrl = if (rawDlUrl.startsWith("http")) rawDlUrl else "$serverUrl/$rawDlUrl"
          val fileName = File(rawDlUrl).name
          val partFile = File(downloadsDir, fileName)

          if (!partFile.exists() || partFile.length() == 0L) {
            val req = Request.Builder().url(fullDlUrl).build()
            val resp = okHttpClient.newCall(req).execute()
            if (!resp.isSuccessful) {
              throw IllegalStateException("Failed to download ${delivery.title}: HTTP ${resp.code}")
            }
            val body = resp.body
            FileOutputStream(partFile).use { out ->
              body.byteStream().copyTo(out)
            }
          }

          val localChId = ChapterId(Uri.fromFile(partFile).toString())
          localChapterIds.add(localChId)
          val chDetails = if (delivery.chapterDetails.isNotEmpty()) {
            delivery.chapterDetails
          } else {
            val firstCh = detail.chapters.find { it.number in delivery.chapters }
            val baseOffset = firstCh?.startMs ?: 0L
            detail.chapters.filter { it.number in delivery.chapters }.map {
              it.copy(startMs = (it.startMs ?: 0L) - baseOffset)
            }
          }

          val chDetailsDur = chDetails.sumOf { ((it.durationSeconds ?: 0.0) * 1000.0).toLong() }
          val durMs = ((delivery.durationSeconds ?: 0.0) * 1000.0).toLong()
          val finalDur = if (durMs > 0) durMs else if (chDetailsDur > 0) chDetailsDur else 300_000L

          val marks = mutableListOf<MarkData>()

          for (ch in chDetails) {
            val rawTitle = ch.title.replace(Regex("""^Chapter\s+\d+\s*[:\-–]\s*""", RegexOption.IGNORE_CASE), "").trim()
            val cleanTitle = rawTitle.ifBlank { "Chapter ${ch.number}" }
            val markTitle = "${ch.number}::$cleanTitle"
            marks.add(
              MarkData(
                name = markTitle,
                startMs = ch.startMs ?: 0L,
              )
            )
          }

          if (marks.isEmpty()) {
            marks.add(MarkData(name = delivery.title, startMs = 0L))
          }

          val chapter = Chapter(
            id = localChId,
            name = delivery.title,
            duration = finalDur,
            fileLastModified = Instant.now(),
            fileSize = partFile.length(),
            markData = marks,
          )
          chapterRepo.put(chapter)
          localChapters.add(chapter)
          downloadedCount++
        }
      } else {
        for (ch in validChapters) {
          val rawDlUrl = (ch.downloadUrl ?: ch.streamUrl ?: "").split("#").first()
          val fullDlUrl = if (rawDlUrl.startsWith("http")) rawDlUrl else "$serverUrl/$rawDlUrl"
          val chFile = File(downloadsDir, "chapter_${String.format("%03d", ch.number)}.m4b")
          if (!chFile.exists() || chFile.length() == 0L) {
            val req = Request.Builder().url(fullDlUrl).build()
            val resp = okHttpClient.newCall(req).execute()
            if (resp.isSuccessful) {
              FileOutputStream(chFile).use { out -> resp.body.byteStream().copyTo(out) }
            }
          }

          val localChId = ChapterId(Uri.fromFile(chFile).toString())
          localChapterIds.add(localChId)
          val durMs = ((ch.durationSeconds ?: 0.0) * 1000.0).toLong()
          val finalDur = if (durMs > 0) durMs else 60_000L
          val rawTitle = ch.title.replace(Regex("""^Chapter\s+\d+\s*[:\-–]\s*""", RegexOption.IGNORE_CASE), "").trim()
          val chTitle = rawTitle.ifBlank { "Chapter ${ch.number}" }

          val chapter = Chapter(
            id = localChId,
            name = chTitle,
            duration = finalDur,
            fileLastModified = Instant.now(),
            fileSize = chFile.length(),
            markData = listOf(MarkData(name = chTitle, startMs = ch.startMs ?: 0L)),
          )
          chapterRepo.put(chapter)
          localChapters.add(chapter)
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

      // Pre-cache lyrics & reader for all chapters for complete offline reading experience
      onProgress(0.95f, "Caching synchronized lyrics and reader for offline reading...")
      for (ch in validChapters) {
        try {
          val lRes = getChapterLyrics(projectId, ch.number, forceRefresh = true)
          if (lRes.isSuccess) Unit
          val rRes = getChapterReader(projectId, ch.number, forceRefresh = true)
          if (rRes.isSuccess) Unit
        } catch (_: Exception) {}
      }
      onProgress(1.0f, "Download complete!")
      downloadedCount
    }
  }

  override suspend fun deleteDownloadedAudio(bookId: BookId): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
      val content = bookContentRepo.get(bookId) ?: return@runCatching
      val projectId = content.remoteProjectId ?: return@runCatching
      val downloadsDir = File(context.filesDir, "crazy_downloads/$projectId")
      if (downloadsDir.exists()) {
        downloadsDir.deleteRecursively()
      }
      val resetContent = content.copy(
        isDownloaded = false,
        isRemoteStream = true,
      )
      bookContentRepo.put(resetContent)
      val syncedCount = syncCatalog().getOrThrow()
      if (syncedCount < 0) Unit
      Unit
    }
  }

  override suspend fun getChapterLyrics(
    projectId: String,
    chapterNumber: Int,
    forceRefresh: Boolean,
  ): Result<CrazyChapterLyricsDto> = withContext(Dispatchers.IO) {
    runCatching {
      val scriptsDir = File(context.filesDir, "crazy_scripts").apply { mkdirs() }
      val cacheFile = File(scriptsDir, "${projectId}_ch${chapterNumber}.json")
      if (!forceRefresh && cacheFile.exists() && cacheFile.length() > 0) {
        try {
          return@runCatching crazyJson
            .decodeFromString<CrazyChapterLyricsDto>(cacheFile.readText())
        } catch (e: Exception) {
          // fall through to network
        }
      }

      var rawUrl = serverUrlStore.data.first().trim()
      if (rawUrl.isBlank()) {
        if (cacheFile.exists() && cacheFile.length() > 0) {
          return@runCatching crazyJson.decodeFromString<CrazyChapterLyricsDto>(cacheFile.readText())
        }
        throw IllegalArgumentException("Server URL is not set")
      }

      try {
        val api = clientFactory.create(rawUrl)
        val resp = api.getChapterLyrics(projectId, chapterNumber)
        if (!resp.isSuccessful || resp.body() == null) {
          throw IllegalStateException("Failed to fetch lyrics: HTTP ${resp.code()}")
        }
        val body = resp.body()!!
        try {
          cacheFile.writeText(crazyJson.encodeToString(CrazyChapterLyricsDto.serializer(), body))
        } catch (_: Exception) {}
        body
      } catch (e: Exception) {
        if (cacheFile.exists() && cacheFile.length() > 0) {
          return@runCatching crazyJson.decodeFromString<CrazyChapterLyricsDto>(cacheFile.readText())
        }
        throw e
      }
    }
  }

  override suspend fun getChapterReader(
    projectId: String,
    chapterNumber: Int,
    forceRefresh: Boolean,
  ): Result<CrazyChapterReaderDto> = withContext(Dispatchers.IO) {
    runCatching {
      val readerDir = File(context.filesDir, "crazy_reader").apply { mkdirs() }
      val cacheFile = File(readerDir, "${projectId}_ch${chapterNumber}.json")
      if (!forceRefresh && cacheFile.exists() && cacheFile.length() > 0) {
        try {
          return@runCatching crazyJson
            .decodeFromString<CrazyChapterReaderDto>(cacheFile.readText())
        } catch (e: Exception) {
          // fall through to network
        }
      }

      var rawUrl = serverUrlStore.data.first().trim()
      if (rawUrl.isBlank()) {
        if (cacheFile.exists() && cacheFile.length() > 0) {
          return@runCatching crazyJson.decodeFromString<CrazyChapterReaderDto>(cacheFile.readText())
        }
        throw IllegalArgumentException("Server URL is not set")
      }

      try {
        val api = clientFactory.create(rawUrl)
        val resp = api.getChapterReader(projectId, chapterNumber)
        if (!resp.isSuccessful || resp.body() == null) {
          throw IllegalStateException("Failed to fetch reader: HTTP ${resp.code()}")
        }
        val body = resp.body()!!
        try {
          cacheFile.writeText(crazyJson.encodeToString(CrazyChapterReaderDto.serializer(), body))
        } catch (_: Exception) {}
        body
      } catch (e: Exception) {
        if (cacheFile.exists() && cacheFile.length() > 0) {
          return@runCatching crazyJson.decodeFromString<CrazyChapterReaderDto>(cacheFile.readText())
        }
        throw e
      }
    }
  }
}
