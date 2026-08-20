package voice.features.bookOverview.overview

import androidx.annotation.StringRes
import voice.core.data.Book
import voice.core.data.BookComparator
import java.util.concurrent.TimeUnit.SECONDS
import voice.core.strings.R as StringsR

enum class BookOverviewCategory(
  @StringRes val nameRes: Int? = null,
  val customTitle: String? = null,
  val comparator: Comparator<Book>,
) {
  CRAZY_STREAM(
    nameRes = null,
    customTitle = "☁️ Crazy Audiobooks (Server)",
    comparator = BookComparator.ByName,
  ),
  CRAZY_DOWNLOADED(
    nameRes = null,
    customTitle = "📥 Crazy Audiobooks (Offline / Downloaded)",
    comparator = BookComparator.ByName,
  ),
  CURRENT(
    nameRes = StringsR.string.library_category_current_title,
    customTitle = "📁 Local Audiobooks (In Progress)",
    comparator = BookComparator.ByLastPlayed,
  ),
  NOT_STARTED(
    nameRes = StringsR.string.library_category_not_started_title,
    customTitle = "📁 Local Audiobooks (Not Started)",
    comparator = BookComparator.ByName,
  ),
  FINISHED(
    nameRes = StringsR.string.library_category_completed_title,
    customTitle = "📁 Local Audiobooks (Completed)",
    comparator = BookComparator.ByLastPlayed,
  ),
}

val Book.category: BookOverviewCategory
  get() {
    if (content.isDownloaded) {
      return BookOverviewCategory.CRAZY_DOWNLOADED
    }
    if (content.isRemoteStream || content.remoteProjectId != null) {
      return BookOverviewCategory.CRAZY_STREAM
    }
    return if (position == 0L) {
      BookOverviewCategory.NOT_STARTED
    } else {
      if (position >= duration - SECONDS.toMillis(5)) {
        BookOverviewCategory.FINISHED
      } else {
        BookOverviewCategory.CURRENT
      }
    }
  }
