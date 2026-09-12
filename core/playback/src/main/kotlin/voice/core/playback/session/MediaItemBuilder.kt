package voice.core.playback.session

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.ClippingConfiguration
import androidx.media3.common.MediaMetadata
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal enum class MediaType {
  AudioBook,
  AudioBookChapter,
  AudioBookRoot,
}

internal fun MediaItem(
  title: String,
  mediaId: MediaId,
  isPlayable: Boolean,
  browsable: Boolean,
  album: String? = null,
  artist: String? = null,
  subtitle: String? = null,
  genre: String? = null,
  sourceUri: Uri? = null,
  imageUri: Uri? = null,
  artworkData: ByteArray? = null,
  durationMs: Long? = null,
  clippingConfiguration: ClippingConfiguration = ClippingConfiguration.UNSET,
  mediaType: MediaType,
  mimeType: String? = null,
): MediaItem {
  val metadataBuilder =
    MediaMetadata.Builder()
      .setAlbumTitle(album)
      .setTitle(title)
      .setDisplayTitle(title)
      .setSubtitle(subtitle ?: album ?: artist)
      .setArtist(artist)
      .setAlbumArtist(artist)
      .setGenre(genre)
      .setIsBrowsable(browsable)
      .setIsPlayable(isPlayable)
      .setArtworkUri(imageUri)
      .setDurationMs(durationMs)
      .setMediaType(
        when (mediaType) {
          MediaType.AudioBook -> MediaMetadata.MEDIA_TYPE_AUDIO_BOOK
          MediaType.AudioBookChapter -> MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER
          MediaType.AudioBookRoot -> MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS
        },
      )

  if (artworkData != null) {
    metadataBuilder.setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
  }

  val metadata = metadataBuilder.build()

  val builder = MediaItem.Builder()
    .setMediaId(Json.encodeToString(MediaId.serializer(), mediaId))
    .setMediaMetadata(metadata)
    .setUri(sourceUri)
    .setClippingConfiguration(clippingConfiguration)
  if (mimeType != null) {
    builder.setMimeType(mimeType)
  }
  return builder.build()
}

fun String.toMediaIdOrNull(): MediaId? = try {
  when (this) {
    "root", "/", "" -> MediaId.Root
    "allBooks", "books", "library" -> MediaId.AllBooks
    "recent", "continue" -> MediaId.Recent
    else -> Json.decodeFromString(MediaId.serializer(), this)
  }
} catch (_: SerializationException) {
  null
}
