package voice.core.playback.di

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaLibraryService
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import voice.core.data.store.CrazyServerUrlStore
import voice.core.logging.api.Logger
import voice.core.featureflag.FeatureFlag
import voice.core.featureflag.Media3AudioOffloadFeatureFlagQualifier
import voice.core.playback.misc.VolumeGain
import voice.core.playback.notification.MainActivityIntentProvider
import voice.core.playback.player.DurationInconsistenciesUpdater
import voice.core.playback.player.OnlyAudioRenderersFactory
import voice.core.playback.player.VoicePlayer
import voice.core.playback.player.onAudioSessionIdChanged
import voice.core.playback.playstate.PlayStateDelegatingListener
import voice.core.playback.playstate.PositionUpdater
import voice.core.playback.session.LibrarySessionCallback
import voice.core.playback.session.PlaybackService
import voice.core.strings.R as StringsR

@ContributesTo(PlaybackScope::class)
interface PlaybackModule {

  @Provides
  @SingleIn(PlaybackScope::class)
  fun mediaSourceFactory(
    context: Context,
    @CrazyServerUrlStore serverUrlStore: DataStore<String>,
  ): MediaSource.Factory {
    val baseHttpFactory = DefaultHttpDataSource.Factory()
      .setAllowCrossProtocolRedirects(true)
      .setConnectTimeoutMs(20000)
      .setReadTimeoutMs(60000)
      .setUserAgent("VoiceAudiobookPlayer/CrazyVoice")

    val dynamicHttpFactory = DataSource.Factory {
      val delegate = baseHttpFactory.createDataSource()
      object : HttpDataSource by delegate {
        override fun open(dataSpec: DataSpec): Long {
          var uri = dataSpec.uri
          var authHeader: String? = null

          val userInfo = uri.userInfo
          if (!userInfo.isNullOrBlank()) {
            val encoded = Base64.encodeToString(userInfo.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            authHeader = "Basic $encoded"
            val hostPart = uri.host ?: ""
            val portPart = if (uri.port != -1) ":${uri.port}" else ""
            uri = uri.buildUpon().encodedAuthority("$hostPart$portPart").build()
          }

          if (authHeader == null) {
            val rawUrl = try {
              runBlocking { serverUrlStore.data.first().trim() }
            } catch (_: Exception) {
              ""
            }
            var formattedUrl = rawUrl
            if (formattedUrl.isNotBlank() && !formattedUrl.startsWith("http://") && !formattedUrl.startsWith("https://")) {
              formattedUrl = if (formattedUrl.startsWith("192.168.") || formattedUrl.startsWith("10.") || formattedUrl.startsWith("localhost")) "http://$formattedUrl" else "https://$formattedUrl"
            }
            val httpUrl = formattedUrl.toHttpUrlOrNull()
            if (httpUrl != null && (httpUrl.username.isNotEmpty() || httpUrl.password.isNotEmpty())) {
              val creds = "${httpUrl.username}:${httpUrl.password}"
              val encoded = Base64.encodeToString(creds.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
              authHeader = "Basic $encoded"
            }
          }

          if (authHeader != null) {
            delegate.setRequestProperty("Authorization", authHeader)
          }

          val cleanDataSpec = if (uri != dataSpec.uri) {
            dataSpec.buildUpon().setUri(uri).build()
          } else {
            dataSpec
          }

          Logger.d("Streaming DataSource open: uri=${cleanDataSpec.uri}, hasAuth=${authHeader != null}")
          return delegate.open(cleanDataSpec)
        }
      }
    }

    val dataSourceFactory = DefaultDataSource.Factory(context, dynamicHttpFactory)
    val extractorsFactory = DefaultExtractorsFactory()
      .setConstantBitrateSeekingEnabled(true)
    return DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
  }

  @Provides
  @SingleIn(PlaybackScope::class)
  fun player(
    context: Context,
    onlyAudioRenderersFactory: OnlyAudioRenderersFactory,
    mediaSourceFactory: MediaSource.Factory,
    playStateDelegatingListener: PlayStateDelegatingListener,
    positionUpdater: PositionUpdater,
    volumeGain: VolumeGain,
    durationInconsistenciesUpdater: DurationInconsistenciesUpdater,
    @Media3AudioOffloadFeatureFlagQualifier media3AudioOffloadFeatureFlag: FeatureFlag<Boolean>,
  ): Player {
    val audioAttributes = AudioAttributes.Builder()
      .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
      .setUsage(C.USAGE_MEDIA)
      .build()

    return ExoPlayer.Builder(context, onlyAudioRenderersFactory, mediaSourceFactory)
      .setAudioAttributes(audioAttributes, true)
      .setHandleAudioBecomingNoisy(true)
      .setWakeMode(C.WAKE_MODE_LOCAL)
      .build()
      .also { player ->
        if (media3AudioOffloadFeatureFlag.get()) {
          player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setAudioOffloadPreferences(
              TrackSelectionParameters.AudioOffloadPreferences.Builder()
                .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
                .setIsGaplessSupportRequired(true)
                .setIsSpeedChangeSupportRequired(true)
                .build(),
            )
            .build()
        }
        playStateDelegatingListener.attachTo(player)
        positionUpdater.attachTo(player)
        durationInconsistenciesUpdater.attachTo(player)
        player.onAudioSessionIdChanged {
          volumeGain.audioSessionId = it
        }
      }
  }

  @Provides
  @SingleIn(PlaybackScope::class)
  fun scope(): CoroutineScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

  @Suppress("DEPRECATION")
  @Provides
  @SingleIn(PlaybackScope::class)
  fun session(
    service: PlaybackService,
    player: VoicePlayer,
    callback: LibrarySessionCallback,
    mainActivityIntentProvider: MainActivityIntentProvider,
    context: Context,
  ): MediaLibraryService.MediaLibrarySession {
    @Suppress("DEPRECATION")
    val bitmapLoader = CacheBitmapLoader(DataSourceBitmapLoader(context))
    return MediaLibraryService.MediaLibrarySession.Builder(service, player, callback)
      .setSessionActivity(mainActivityIntentProvider.toCurrentBook())
      .setBitmapLoader(bitmapLoader)
      .setMediaButtonPreferences(
        listOf(
          CommandButton.Builder(CommandButton.ICON_SKIP_BACK)
            .setDisplayName(context.getString(StringsR.string.playback_action_rewind))
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .setSlots(CommandButton.SLOT_BACK)
            .build(),
          CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setIconResId(voice.core.playback.R.drawable.ic_flag)
            .setDisplayName(context.getString(StringsR.string.playback_action_flag_issue))
            .setSessionCommand(androidx.media3.session.SessionCommand(voice.core.playback.session.CustomCommand.CUSTOM_ACTION_FLAG_ISSUE, android.os.Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_FORWARD, CommandButton.SLOT_FORWARD_SECONDARY)
            .build(),
          CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD)
            .setDisplayName(context.getString(StringsR.string.playback_action_fast_forward))
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .build(),
        ),
      )
      .build()
  }
}
