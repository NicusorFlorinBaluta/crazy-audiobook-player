package voice.core.data

import kotlinx.serialization.Serializable
import kotlin.time.Duration

@Serializable
public data class MarkData(
  val startMs: Long,
  val name: String,
) : Comparable<MarkData> {
  override fun compareTo(other: MarkData): Int {
    return startMs.compareTo(other.startMs)
  }
}

@Serializable
public data class ChapterMark(
  val name: String?,
  val startMs: Long,
  val endMs: Long,
) {

  init {
    require(startMs < endMs) {
      "Start must be less than end in $this"
    }
  }

  public operator fun contains(position: Duration): Boolean = position.inWholeMilliseconds in startMs..endMs
  public operator fun contains(positionMs: Long): Boolean = positionMs in startMs..endMs
}

public val ChapterMark.durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)

public fun Chapter.markForPosition(positionInChapterMs: Long): ChapterMark {
  val clampedPositionInChapterMs = positionInChapterMs.coerceIn(
    0L,
    (duration - 1).coerceAtLeast(0L),
  )
  return chapterMarks.find { clampedPositionInChapterMs in it.startMs..it.endMs }
    ?: chapterMarks.first()
}

public val ChapterMark.displayTitle: String
  get() {
    val raw = name ?: ""
    return when {
      raw.contains("::") -> raw.substringAfter("::").trim()
      raw.startsWith("Ch.", ignoreCase = true) && raw.contains(":") -> raw.substringAfter(":").trim()
      raw.startsWith("Ch.", ignoreCase = true) && raw.contains("-") -> raw.substringAfter("-").trim()
      else -> raw
    }
  }

public val ChapterMark.chapterNumber: Int?
  get() {
    val raw = name ?: return null
    if (raw.contains("::")) {
      return raw.substringBefore("::").trim().toIntOrNull()
    }
    val chRegex = Regex("""(?:ch\.|chapter)\s*(\d+)""", RegexOption.IGNORE_CASE)
      .find(raw)?.groupValues?.get(1)?.toIntOrNull()
    if (chRegex != null) return chRegex

    return Regex("""^\s*(\d+)\s*[:\-–]""")
      .find(raw)?.groupValues?.get(1)?.toIntOrNull()
  }
