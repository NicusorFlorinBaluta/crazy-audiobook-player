package voice.features.playbackScreen

internal sealed interface BookPlayViewEffect {
  data object BookmarkAdded : BookPlayViewEffect
  data object RequestIgnoreBatteryOptimization : BookPlayViewEffect
  data class PlaybackIssueFlagged(val message: String) : BookPlayViewEffect
}
