package voice.features.bookOverview.deleteBook

import android.app.Application
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.documentfile.provider.DocumentFile
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import voice.core.data.BookId
import voice.core.data.remote.CrazySyncManager
import voice.core.data.repo.BookContentRepo
import voice.core.logging.api.Logger
import voice.core.scanner.MediaScanTrigger
import voice.features.bookOverview.bottomSheet.BottomSheetItem
import voice.features.bookOverview.bottomSheet.BottomSheetItemViewModel
import voice.features.bookOverview.di.BookOverviewScope
import java.io.File

@SingleIn(BookOverviewScope::class)
@ContributesIntoSet(BookOverviewScope::class)
class DeleteBookViewModel(
  private val application: Application,
  private val mediaScanTrigger: MediaScanTrigger,
  private val bookContentRepo: BookContentRepo,
  private val crazySyncManager: CrazySyncManager,
) : BottomSheetItemViewModel {

  private val scope = MainScope()

  private val _state = mutableStateOf<DeleteBookViewState?>(null)
  internal val state: State<DeleteBookViewState?> get() = _state

  override suspend fun items(bookId: BookId): List<BottomSheetItem> {
    return listOf(BottomSheetItem.DeleteBook)
  }

  override suspend fun onItemClick(
    bookId: BookId,
    item: BottomSheetItem,
  ) {
    if (item != BottomSheetItem.DeleteBook) return

    val isCrazy = bookId.value.startsWith("crazy://")
    val content = bookContentRepo.get(bookId)
    val displayName = if (isCrazy) {
      content?.name ?: bookId.value.removePrefix("crazy://")
    } else {
      bookId.toUri().pathSegments
        .let { segments ->
          val result = segments.lastOrNull()?.removePrefix("primary:")
          if (result.isNullOrEmpty()) {
            Logger.w("Could not determine path for $segments")
            segments.joinToString(separator = "\"")
          } else {
            result
          }
        }
    }

    _state.value = DeleteBookViewState(
      id = bookId,
      deleteCheckBoxChecked = false,
      fileToDelete = displayName,
      isRemoteBook = isCrazy,
    )
  }

  internal fun onDismiss() {
    _state.value = null
  }

  internal fun onDeleteCheckBoxCheck(checked: Boolean) {
    _state.value = _state.value?.copy(deleteCheckBoxChecked = checked)
  }

  internal fun onConfirmDeletion() {
    val state = _state.value
    if (state != null) {
      check(state.confirmButtonEnabled)
      scope.launch {
        val bookId = state.id
        if (bookId.value.startsWith("crazy://")) {
          val content = bookContentRepo.get(bookId)
          val projectId = content?.remoteProjectId ?: bookId.value.removePrefix("crazy://")
          crazySyncManager.ignoreBook(projectId)
          if (content != null) {
            bookContentRepo.put(content.copy(isActive = false))
          }
          File(application.filesDir, "crazy_downloads/$projectId").deleteRecursively()
          File(application.filesDir, "crazy_covers/$projectId.jpg").delete()
        } else {
          val uri = bookId.toUri()
          val documentFile = DocumentFile.fromSingleUri(application, uri)
          documentFile?.delete()
          mediaScanTrigger.scan(restartIfScanning = true)
        }
      }
    }
    _state.value = null
  }
}

data class DeleteBookViewState(
  val id: BookId,
  val deleteCheckBoxChecked: Boolean,
  val fileToDelete: String,
  val isRemoteBook: Boolean = false,
) {

  val confirmButtonEnabled = if (isRemoteBook) true else deleteCheckBoxChecked
}
