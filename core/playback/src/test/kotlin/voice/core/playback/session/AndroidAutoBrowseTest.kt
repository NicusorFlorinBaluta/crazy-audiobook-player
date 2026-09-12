package voice.core.playback.session

import android.app.Application
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import voice.core.data.Book
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.data.MarkData
import voice.core.data.repo.BookContentRepo
import voice.core.data.repo.BookRepository
import voice.core.data.repo.ChapterRepo
import voice.core.playback.MemoryDataStore
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AndroidAutoBrowseTest {

  private val context = ApplicationProvider.getApplicationContext<Application>()
  private val currentBookId = BookId("crazy://book1")
  private val currentBookStore = MemoryDataStore<BookId?>(currentBookId)
  private val showRemainingTimeStore = MemoryDataStore(true)

  private val testChapter1 = Chapter(
    id = ChapterId("stream://ch1"),
    name = "Chapter One",
    duration = 300_000L,
    fileLastModified = Instant.now(),
    fileSize = 0L,
    markData = listOf(
      MarkData(name = "Chapter 1", startMs = 0L),
      MarkData(name = "Chapter 2", startMs = 150_000L),
    ),
  )

  private val testBook = Book(
    content = BookContent(
      id = currentBookId,
      playbackSpeed = 1.0f,
      skipSilence = false,
      isActive = true,
      lastPlayedAt = Instant.now(),
      author = "Author Name",
      name = "Dungeon Crawler Carl",
      addedAt = Instant.now(),
      chapters = listOf(testChapter1.id),
      currentChapter = testChapter1.id,
      positionInChapter = 50_000L,
      cover = null,
      gain = 0.0f,
      genre = "Fantasy",
      narrator = "Jeff Hays",
      series = null,
      part = null,
      remoteProjectId = "dcc",
      isDownloaded = false,
    ),
    chapters = listOf(testChapter1),
  )

  private val bookRepo = mockk<BookRepository> {
    coEvery { all() } returns listOf(testBook)
    coEvery { get(currentBookId) } returns testBook
  }

  private val mediaItemProvider = MediaItemProvider(
    bookRepository = bookRepo,
    application = context,
    chapterRepo = mockk {
      coEvery { get(testChapter1.id) } returns testChapter1
    },
    contentRepo = mockk {
      coEvery { get(currentBookId) } returns testBook.content
    },
    imageFileProvider = mockk(relaxed = true),
    currentBookStoreId = currentBookStore,
    showRemainingTimeStore = showRemainingTimeStore,
  )

  @Test
  fun `toMediaIdOrNull parses tolerant strings and standard JSON`() {
    assertEquals(expected = MediaId.Root, actual = "root".toMediaIdOrNull())
    assertEquals(expected = MediaId.Root, actual = "/".toMediaIdOrNull())
    assertEquals(expected = MediaId.Root, actual = "".toMediaIdOrNull())
    assertEquals(expected = MediaId.AllBooks, actual = "allBooks".toMediaIdOrNull())
    assertEquals(expected = MediaId.AllBooks, actual = "books".toMediaIdOrNull())
    assertEquals(expected = MediaId.Recent, actual = "recent".toMediaIdOrNull())
    assertEquals(expected = MediaId.Root, actual = "{\"type\":\"root\"}".toMediaIdOrNull())
    assertEquals(expected = MediaId.AllBooks, actual = "{\"type\":\"allBooks\"}".toMediaIdOrNull())
    assertEquals(expected = MediaId.Recent, actual = "{\"type\":\"recent\"}".toMediaIdOrNull())
  }

  @Test
  fun `root returns browsable tabs required by Android Auto`() = runTest {
    val rootChildren = mediaItemProvider.children("root")
    assertNotNull(rootChildren)
    assertEquals(expected = 2, actual = rootChildren.size)

    val allBooksTab = rootChildren[0]
    val recentTab = rootChildren[1]

    // Tabs must be browsable for Android Auto to display them as tabs
    assertTrue(allBooksTab.mediaMetadata.isBrowsable == true, "allBooksTab must be browsable")
    assertFalse(allBooksTab.mediaMetadata.isPlayable == true, "allBooksTab should not be playable")
    assertEquals(expected = MediaId.AllBooks, actual = allBooksTab.mediaId.toMediaIdOrNull())

    assertTrue(recentTab.mediaMetadata.isBrowsable == true, "recentTab must be browsable")
    assertFalse(recentTab.mediaMetadata.isPlayable == true, "recentTab should not be playable")
    assertEquals(expected = MediaId.Recent, actual = recentTab.mediaId.toMediaIdOrNull())
  }

  @Test
  fun `allBooks tab returns books with playable and browsable flags`() = runTest {
    val books = mediaItemProvider.children("allBooks")
    assertNotNull(books)
    assertEquals(expected = 1, actual = books.size)

    val bookItem = books.first()
    assertEquals(expected = "Dungeon Crawler Carl", actual = bookItem.mediaMetadata.title.toString())
    assertEquals(expected = "Author Name", actual = bookItem.mediaMetadata.artist.toString())
    assertTrue(bookItem.mediaMetadata.isPlayable == true, "Book must be playable")
    assertTrue(bookItem.mediaMetadata.isBrowsable == true, "Book with chapters must be browsable")
  }

  @Test
  fun `onGetLibraryRoot always returns Root even when isRecent is requested`() = runTest {
    val callback = LibrarySessionCallback(
      mediaItemProvider = mediaItemProvider,
      scope = CoroutineScope(Dispatchers.Unconfined),
      player = mockk(relaxed = true),
      bookSearchParser = mockk(relaxed = true),
      bookSearchHandler = mockk(relaxed = true),
      currentBookStoreId = currentBookStore,
      bookRepository = bookRepo,
      showRemainingTimeStore = showRemainingTimeStore,
    )

    val session = mockk<MediaLibraryService.MediaLibrarySession>()
    val controller = mockk<MediaSession.ControllerInfo> {
      every { packageName } returns "com.google.android.projection.gearhead"
    }

    val paramsWithRecent = MediaLibraryService.LibraryParams.Builder().setRecent(true).build()
    val resultFuture = callback.onGetLibraryRoot(session, controller, paramsWithRecent)
    val result = resultFuture.get()

    assertNotNull(result.value)
    assertEquals(expected = MediaId.Root, actual = result.value!!.mediaId.toMediaIdOrNull())
  }
}
