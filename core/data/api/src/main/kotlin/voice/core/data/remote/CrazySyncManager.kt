package voice.core.data.remote

import voice.core.data.BookContent
import voice.core.data.BookId

public interface CrazySyncManager {
  public suspend fun syncCatalog(): Result<Int>
  public suspend fun syncProgressToServer(bookContent: BookContent)
  public suspend fun ignoreBook(projectId: String)
  public suspend fun restoreIgnoredBooks(): Result<Int>
  public suspend fun downloadBookOffline(bookId: BookId, onProgress: (Float, String) -> Unit = { _, _ -> }): Result<Int>
  public suspend fun deleteDownloadedAudio(bookId: BookId): Result<Unit>
  public suspend fun getChapterLyrics(projectId: String, chapterNumber: Int, forceRefresh: Boolean = false): Result<CrazyChapterLyricsDto>
  public suspend fun getChapterReader(projectId: String, chapterNumber: Int, forceRefresh: Boolean = false): Result<CrazyChapterReaderDto>
  public suspend fun flagPlaybackIssue(
    bookId: BookId,
    chapterNumber: Int,
    positionMs: Long,
    issueType: String = "wrong_speaker",
    userNote: String = "",
    source: String = "phone",
    lineId: String? = null,
  ): Result<CrazyPlaybackFlagDto>
}
