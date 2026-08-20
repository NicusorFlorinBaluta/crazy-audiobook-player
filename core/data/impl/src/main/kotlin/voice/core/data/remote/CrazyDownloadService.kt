package voice.core.data.remote

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.data.repo.BookContentRepo
import voice.core.data.repo.ChapterRepo
import voice.core.data.store.CrazyServerUrlStore
import java.io.File
import java.io.FileOutputStream
import java.time.Instant

@Inject
@SingleIn(AppScope::class)
public class CrazyDownloadService(
  private val context: Context,
  @CrazyServerUrlStore private val serverUrlStore: DataStore<String>,
  private val bookContentRepo: BookContentRepo,
  private val chapterRepo: ChapterRepo,
) {

  private val okHttpClient = OkHttpClient.Builder().build()

  public suspend fun downloadBook(bookId: BookId, onProgress: (Float) -> Unit = {}): Result<File> = withContext(Dispatchers.IO) {
    runCatching {
      val content = bookContentRepo.get(bookId)
        ?: throw IllegalArgumentException("Book not found: $bookId")
      val projectId = content.remoteProjectId
        ?: throw IllegalArgumentException("Not a remote project: $bookId")

      val serverUrl = serverUrlStore.data.first().trim().removeSuffix("/")
      val downloadUrl = "$serverUrl/api/projects/$projectId/download"

      val targetDir = File(context.getExternalFilesDir(Environment.DIRECTORY_PODCASTS), "crazy_audiobooks")
      targetDir.mkdirs()
      val targetFile = File(targetDir, "$projectId.m4b")

      val request = Request.Builder().url(downloadUrl).build()
      val response = okHttpClient.newCall(request).execute()

      if (!response.isSuccessful) {
        throw IllegalStateException("Download failed with HTTP ${response.code}")
      }

      val body = response.body
      val contentLength = body.contentLength()
      var bytesRead = 0L

      body.byteStream().use { input ->
        FileOutputStream(targetFile).use { output ->
          val buffer = ByteArray(8192)
          var read: Int
          while (input.read(buffer).also { read = it } != -1) {
            output.write(buffer, 0, read)
            bytesRead += read
            if (contentLength > 0) {
              onProgress(bytesRead.toFloat() / contentLength.toFloat())
            }
          }
        }
      }

      // Update Room entities
      val localUri = Uri.fromFile(targetFile).toString()
      val localChapterId = ChapterId(localUri)

      val localChapter = Chapter(
        id = localChapterId,
        name = content.name,
        duration = 0L,
        fileLastModified = Instant.now(),
        fileSize = targetFile.length(),
        markData = emptyList(),
      )
      chapterRepo.put(localChapter)

      val updatedContent = content.copy(
        isDownloaded = true,
        isRemoteStream = false,
        chapters = listOf(localChapterId),
        currentChapter = localChapterId,
      )
      bookContentRepo.put(updatedContent)

      targetFile
    }
  }
}
