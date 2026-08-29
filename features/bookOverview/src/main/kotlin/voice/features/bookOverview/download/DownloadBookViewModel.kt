package voice.features.bookOverview.download

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import voice.core.data.BookId
import voice.core.data.remote.CrazySyncManager
import voice.core.data.repo.BookRepository
import voice.features.bookOverview.bottomSheet.BottomSheetItem
import voice.features.bookOverview.bottomSheet.BottomSheetItemViewModel
import voice.features.bookOverview.di.BookOverviewScope

@Inject
@ContributesIntoSet(BookOverviewScope::class)
class DownloadBookViewModel(
  private val context: Context,
  private val repo: BookRepository,
  private val crazySyncManager: CrazySyncManager,
) : BottomSheetItemViewModel {

  private val mainHandler = Handler(Looper.getMainLooper())

  private fun showToast(msg: String, length: Int = Toast.LENGTH_SHORT) {
    mainHandler.post {
      Toast.makeText(context, msg, length).show()
    }
  }

  override suspend fun items(bookId: BookId): List<BottomSheetItem> {
    val book = repo.get(bookId) ?: return emptyList()
    if (book.content.remoteProjectId == null) return emptyList()
    return if (book.content.isDownloaded) {
      listOf(BottomSheetItem.DeleteDownloaded)
    } else {
      listOf(BottomSheetItem.DownloadOffline)
    }
  }

  override suspend fun onItemClick(bookId: BookId, item: BottomSheetItem) {
    if (item == BottomSheetItem.DownloadOffline) {
      showToast("Starting offline download...")
      withContext(Dispatchers.IO) {
        val result = crazySyncManager.downloadBookOffline(bookId) { progress, message ->
          if (progress == 1.0f) {
            showToast(message, Toast.LENGTH_LONG)
          }
        }
        if (result.isSuccess) {
          showToast("Audiobook downloaded for 100% offline playback!", Toast.LENGTH_LONG)
        } else {
          showToast("Download failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG)
        }
      }
    } else if (item == BottomSheetItem.DeleteDownloaded) {
      withContext(Dispatchers.IO) {
        val result = crazySyncManager.deleteDownloadedAudio(bookId)
        if (result.isSuccess) {
          showToast("Offline files deleted. Switched back to streaming.")
        } else {
          showToast("Failed to delete offline files: ${result.exceptionOrNull()?.message}")
        }
      }
    }
  }
}
