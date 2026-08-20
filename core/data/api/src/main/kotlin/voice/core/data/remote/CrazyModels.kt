package voice.core.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class CrazyServerCapabilitiesDto(
  val streaming: Boolean = true,
  @SerialName("byte_ranges") val byteRanges: Boolean = true,
  @SerialName("wav_chapter_streaming") val wavChapterStreaming: Boolean = true,
  @SerialName("incremental_delivery") val incrementalDelivery: Boolean = true,
  @SerialName("progress_sync") val progressSync: Boolean = true,
)

@Serializable
public data class CrazyServerInfoDto(
  @SerialName("server_name") val serverName: String = "Crazy Audiobook Creator",
  val version: String = "2.0.0",
  val capabilities: CrazyServerCapabilitiesDto = CrazyServerCapabilitiesDto(),
  @SerialName("active_project_id") val activeProjectId: String? = null,
  @SerialName("is_busy") val isBusy: Boolean = false,
)

@Serializable
public data class CrazyBookSummaryDto(
  @SerialName("project_id") val projectId: String,
  val title: String,
  val author: String = "Unknown Author",
  val genre: String = "",
  val year: String = "",
  val description: String = "",
  val isbn: String = "",
  val status: String = "queued", // "ready_full", "ready_partial", "in_progress", "queued"
  @SerialName("total_chapters") val totalChapters: Int = 0,
  @SerialName("generated_chapters_count") val generatedChaptersCount: Int = 0,
  @SerialName("mastered_chapters_count") val masteredChaptersCount: Int = 0,
  @SerialName("total_duration_seconds") val totalDurationSeconds: Double = 0.0,
  @SerialName("is_live_generating") val isLiveGenerating: Boolean = false,
  @SerialName("cover_url") val coverUrl: String? = null,
  @SerialName("stream_url") val streamUrl: String = "",
  @SerialName("download_url") val downloadUrl: String = "",
  @SerialName("file_size_bytes") val fileSizeBytes: Long? = null,
  @SerialName("published_deliveries_count") val publishedDeliveriesCount: Int = 0,
  @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
public data class CrazyCatalogResponse(
  val books: List<CrazyBookSummaryDto> = emptyList(),
)

@Serializable
public data class CrazyChapterDto(
  val number: Int,
  val title: String,
  val status: String = "mastered", // "mastered", "generating", "pending"
  @SerialName("duration_seconds") val durationSeconds: Double? = null,
  @SerialName("start_ms") val startMs: Long? = null,
  @SerialName("end_ms") val endMs: Long? = null,
  @SerialName("stream_url") val streamUrl: String? = null,
  @SerialName("download_url") val downloadUrl: String? = null,
)

@Serializable
public data class CrazyDeliveryBatchDto(
  @SerialName("delivery_id") val deliveryId: String,
  val title: String,
  val chapters: List<Int> = emptyList(),
  val status: String = "published",
  @SerialName("download_url") val downloadUrl: String = "",
)

@Serializable
public data class CrazyBookDetailDto(
  @SerialName("project_id") val projectId: String,
  val title: String,
  val author: String = "Unknown Author",
  val genre: String = "",
  val year: String = "",
  val description: String = "",
  val isbn: String = "",
  val narrator: String? = null,
  val series: String? = null,
  val part: String? = null,
  @SerialName("total_chapters") val totalChapters: Int = 0,
  @SerialName("mastered_chapters_count") val masteredChaptersCount: Int = 0,
  @SerialName("is_live_generating") val isLiveGenerating: Boolean = false,
  @SerialName("cover_url") val coverUrl: String? = null,
  @SerialName("stream_url") val streamUrl: String = "",
  @SerialName("download_url") val downloadUrl: String = "",
  val deliveries: List<CrazyDeliveryBatchDto> = emptyList(),
  val chapters: List<CrazyChapterDto> = emptyList(),
)

@Serializable
public data class CrazyProgressRequest(
  @SerialName("client_id") val clientId: String = "voice_android",
  @SerialName("chapter_number") val chapterNumber: Int = 1,
  @SerialName("position_ms") val positionMs: Long = 0,
  @SerialName("playback_speed") val playbackSpeed: Float = 1.0f,
  @SerialName("is_completed") val isCompleted: Boolean = false,
)

@Serializable
public data class CrazyProgressResponse(
  val success: Boolean = true,
  @SerialName("saved_position") val savedPosition: CrazyProgressRequest? = null,
)
