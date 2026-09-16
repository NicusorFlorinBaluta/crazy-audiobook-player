package voice.core.data

import kotlin.test.Test
import kotlin.test.assertEquals

class ChapterMarkDisplayTitleTest {

  @Test
  fun `displayTitle parses encoded delimiter correctly`() {
    val mark = ChapterMark(name = "29::Chapter Twenty-Eight", startMs = 0, endMs = 50_000)
    assertEquals(expected = "Chapter Twenty-Eight", actual = mark.displayTitle)
    assertEquals(expected = 29, actual = mark.chapterNumber)
  }

  @Test
  fun `displayTitle handles prologue with encoded delimiter`() {
    val mark = ChapterMark(name = "1::Prologue", startMs = 0, endMs = 30_000)
    assertEquals(expected = "Prologue", actual = mark.displayTitle)
    assertEquals(expected = 1, actual = mark.chapterNumber)
  }

  @Test
  fun `displayTitle strips legacy colon prefix`() {
    val mark = ChapterMark(name = "Ch. 29: Chapter Twenty-Eight", startMs = 0, endMs = 50_000)
    assertEquals(expected = "Chapter Twenty-Eight", actual = mark.displayTitle)
    assertEquals(expected = 29, actual = mark.chapterNumber)
  }

  @Test
  fun `displayTitle strips legacy hyphen notation`() {
    val mark = ChapterMark(name = "Ch.29 - Chapter Twenty-Eight", startMs = 0, endMs = 50_000)
    assertEquals(expected = "Chapter Twenty-Eight", actual = mark.displayTitle)
    assertEquals(expected = 29, actual = mark.chapterNumber)
  }

  @Test
  fun `displayTitle preserves clean standalone titles`() {
    val mark = ChapterMark(name = "Epilogue", startMs = 0, endMs = 20_000)
    assertEquals(expected = "Epilogue", actual = mark.displayTitle)
    assertEquals(expected = null, actual = mark.chapterNumber)
  }

  @Test
  fun `chapterNumber parses leading digit with colon notation`() {
    val mark = ChapterMark(name = "1: Uncle Wulfgar", startMs = 0, endMs = 40_000)
    assertEquals(expected = 1, actual = mark.chapterNumber)
  }

  @Test
  fun `chapterNumber parses leading digit with hyphen notation`() {
    val mark = ChapterMark(name = "3 - Unexpected Advantages", startMs = 0, endMs = 40_000)
    assertEquals(expected = 3, actual = mark.chapterNumber)
  }

  @Test
  fun `chapterNumber parses standard chapter digits`() {
    val mark = ChapterMark(name = "Chapter 14", startMs = 0, endMs = 40_000)
    assertEquals(expected = "Chapter 14", actual = mark.displayTitle)
    assertEquals(expected = 14, actual = mark.chapterNumber)
  }
}
