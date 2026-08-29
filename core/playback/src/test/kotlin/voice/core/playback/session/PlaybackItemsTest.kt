package voice.core.playback.session

import voice.core.data.chapterNumber
import voice.core.data.displayTitle
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.data.MarkData
import voice.core.playback.session.search.book
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class PlaybackItemsTest {

  @Test
  fun `maps file chapter position to clipped playback item`() {
    val chapter = chapter(
      duration = 20_000,
      MarkData(startMs = 0, name = "Intro"),
      MarkData(startMs = 12_000, name = "Chapter 1"),
    )
    val book = book(listOf(chapter))

    val playbackItem = book.playbackItemForPosition(
      chapterId = chapter.id,
      positionInChapterMs = 15_000,
    )

    assertEquals(expected = 1, actual = playbackItem?.index)
    assertEquals(expected = 3_000, actual = playbackItem?.positionInMediaItem(15_000))
    assertEquals(expected = 15_000, actual = playbackItem?.mediaId?.positionInChapter(3_000))
  }

  @Test
  fun `maps chapter duration position to last playback item`() {
    val chapter = chapter(
      duration = 20_000,
      MarkData(startMs = 0, name = "Intro"),
      MarkData(startMs = 12_000, name = "Chapter 1"),
    )
    val book = book(listOf(chapter))

    val playbackItem = book.playbackItemForPosition(
      chapterId = chapter.id,
      positionInChapterMs = chapter.duration,
    )

    assertEquals(expected = 1, actual = playbackItem?.index)
    assertEquals(expected = 7_999, actual = playbackItem?.positionInMediaItem(chapter.duration))
  }

  @Test
  fun `indexes marks across file chapters`() {
    val firstChapter = chapter(
      duration = 20_000,
      MarkData(startMs = 0, name = "One"),
      MarkData(startMs = 5_000, name = "Two"),
    )
    val secondChapter = chapter(
      duration = 20_000,
      MarkData(startMs = 0, name = "Three"),
      MarkData(startMs = 7_000, name = "Four"),
    )
    val book = book(listOf(firstChapter, secondChapter))

    val playbackItem = book.playbackItemForPosition(
      chapterId = secondChapter.id,
      positionInChapterMs = 8_000,
    )

    assertEquals(expected = 3, actual = playbackItem?.index)
    assertEquals(expected = secondChapter.id, actual = playbackItem?.mediaId?.realChapterId)
    assertEquals(expected = 1_000, actual = playbackItem?.positionInMediaItem(8_000))
  }

  @Test
  fun `multi-part delivery packages chapters with clean display titles and accurate mark lookups`() {
    val part1 = chapter(
      duration = 2_986_500,
      MarkData(startMs = 0, name = "1::Prologue"),
      MarkData(startMs = 537_450, name = "2::Chapter One"),
      MarkData(startMs = 1_032_920, name = "3::Chapter Two"),
      MarkData(startMs = 1_621_240, name = "4::Chapter Three"),
      MarkData(startMs = 2_231_790, name = "5::Chapter Four"),
    )
    val part4 = chapter(
      duration = 3_000_000,
      MarkData(startMs = 0, name = "14::Chapter Thirteen"),
      MarkData(startMs = 600_000, name = "15::Chapter Fourteen"),
    )
    val part7 = chapter(
      duration = 3_500_000,
      MarkData(startMs = 0, name = "29::Chapter Twenty-Eight"),
      MarkData(startMs = 55_260, name = "30::Chapter Twenty-Nine"),
    )

    val book = book(listOf(part1, part4, part7))
    val items = book.playbackItems()

    assertEquals(expected = 9, actual = items.size)
    assertEquals(expected = "Prologue", actual = items[0].mark.displayTitle)
    assertEquals(expected = 1, actual = items[0].mark.chapterNumber)
    assertEquals(expected = "Chapter One", actual = items[1].mark.displayTitle)
    assertEquals(expected = 2, actual = items[1].mark.chapterNumber)
    assertEquals(expected = "Chapter Twenty-Eight", actual = items[7].mark.displayTitle)
    assertEquals(expected = 29, actual = items[7].mark.chapterNumber)

    // Lookup within Part 01
    val ch2Lookup = book.playbackItemForPosition(
      chapterId = part1.id,
      positionInChapterMs = 600_000,
    )
    assertEquals(expected = 1, actual = ch2Lookup?.index)
    assertEquals(expected = "Chapter One", actual = ch2Lookup?.mark?.displayTitle)
    assertEquals(expected = 62_550, actual = ch2Lookup?.positionInMediaItem(600_000))

    // Lookup within Part 07 (Chapter 29)
    val ch29Lookup = book.playbackItemForPosition(
      chapterId = part7.id,
      positionInChapterMs = 10_000,
    )
    assertEquals(expected = 7, actual = ch29Lookup?.index)
    assertEquals(expected = "Chapter Twenty-Eight", actual = ch29Lookup?.mark?.displayTitle)
    assertEquals(expected = 29, actual = ch29Lookup?.mark?.chapterNumber)
    assertEquals(expected = 10_000, actual = ch29Lookup?.positionInMediaItem(10_000))
  }

  private fun chapter(
    @Suppress("SameParameterValue") duration: Long,
    vararg marks: MarkData,
  ): Chapter {
    return Chapter(
      id = ChapterId(Uuid.random().toString()),
      name = "chapter",
      duration = duration,
      fileLastModified = Instant.EPOCH,
      markData = marks.toList(),
      fileSize = 0,
    )
  }
}
