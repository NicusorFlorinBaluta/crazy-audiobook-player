package voice.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import java.io.File
import java.time.Instant

@Entity(tableName = "content2")
public data class BookContent(
  @PrimaryKey
  val id: BookId,
  val playbackSpeed: Float,
  val skipSilence: Boolean,
  val isActive: Boolean,
  val lastPlayedAt: Instant,
  val author: String?,
  val name: String,
  val addedAt: Instant,
  val chapters: List<ChapterId>,
  val currentChapter: ChapterId,
  val positionInChapter: Long,
  val cover: File?,
  @ColumnInfo(defaultValue = "0")
  val gain: Float,
  val genre: String?,
  val narrator: String?,
  val series: String?,
  val part: String?,
  @ColumnInfo(defaultValue = "NULL")
  val remoteProjectId: String? = null,
  @ColumnInfo(defaultValue = "0")
  val isRemoteStream: Boolean = false,
  @ColumnInfo(defaultValue = "0")
  val isDownloaded: Boolean = false,
  @ColumnInfo(defaultValue = "NULL")
  val remoteStreamUrl: String? = null,
  @ColumnInfo(defaultValue = "NULL")
  val remoteStatus: String? = null,
) {

  @Ignore
  val currentChapterIndex: Int = chapters.indexOf(currentChapter)

  val coverUrl: String? get() = cover?.toURI()?.toString()

  init {
    require(currentChapter in chapters && positionInChapter >= 0) {
      "invalid data in $this"
    }
  }
}
