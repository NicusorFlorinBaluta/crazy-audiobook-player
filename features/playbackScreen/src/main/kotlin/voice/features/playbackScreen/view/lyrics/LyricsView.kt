package voice.features.playbackScreen.view.lyrics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import voice.core.data.remote.CrazyScriptLineDto
import voice.core.ui.icons.VoiceIcons
import voice.features.playbackScreen.LyricsViewState
import kotlin.math.abs
import kotlin.math.max

@Composable
internal fun LyricsView(
  lyricsState: LyricsViewState?,
  onLineClick: (Long) -> Unit,
  onRetry: () -> Unit,
  modifier: Modifier = Modifier,
) {
  if (lyricsState == null || lyricsState.isLoading) {
    Box(
      modifier = modifier.fillMaxSize(),
      contentAlignment = Alignment.Center,
    ) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(modifier = Modifier.size(36.dp))
        Spacer(modifier = Modifier.height(12.dp))
        Text(
          text = "Loading synchronized script...",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    return
  }

  if (lyricsState.errorMessage != null && lyricsState.lines.isEmpty()) {
    Box(
      modifier = modifier.fillMaxSize().padding(24.dp),
      contentAlignment = Alignment.Center,
    ) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
          text = "Script not available offline",
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text = lyricsState.errorMessage,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        FilledTonalButton(onClick = onRetry) {
          Text("Retry Sync")
        }
      }
    }
    return
  }

  if (lyricsState.lines.isEmpty()) {
    Box(
      modifier = modifier.fillMaxSize().padding(24.dp),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = "No script lines available for this chapter.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    return
  }

  val listState = rememberLazyListState()
  val isDragged by listState.interactionSource.collectIsDraggedAsState()
  var userScrolledManually by remember { mutableStateOf(false) }

  if (isDragged) {
    userScrolledManually = true
  }

  // Auto-resume auto-scroll after 6 seconds of no touch
  LaunchedEffect(userScrolledManually) {
    if (userScrolledManually) {
      delay(6000)
      userScrolledManually = false
    }
  }

  // Auto-scroll when active line changes and user is not manually scrolling
  LaunchedEffect(lyricsState.activeLineIndex, userScrolledManually) {
    if (!userScrolledManually && lyricsState.activeLineIndex >= 0) {
      val targetIndex = max(0, lyricsState.activeLineIndex)
      listState.animateScrollToItem(targetIndex)
    }
  }

  Box(modifier = modifier.fillMaxSize()) {
    LazyColumn(
      state = listState,
      contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp, start = 16.dp, end = 16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
      modifier = Modifier.fillMaxSize(),
    ) {
      itemsIndexed(
        items = lyricsState.lines,
        key = { index, line -> line.lineId.ifEmpty { "line_$index" } },
      ) { index, line ->
        val isActive = index == lyricsState.activeLineIndex
        val isPast = index < lyricsState.activeLineIndex

        LyricsLineItem(
          line = line,
          isActive = isActive,
          isPast = isPast,
          onClick = { onLineClick(line.startMs) },
        )
      }
    }

    // Floating Sync / Snap-to-Current Button
    AnimatedVisibility(
      visible = userScrolledManually && lyricsState.activeLineIndex >= 0,
      enter = fadeIn(),
      exit = fadeOut(),
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(bottom = 16.dp),
    ) {
      FloatingActionButton(
        onClick = {
          userScrolledManually = false
        },
        shape = CircleShape,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "Sync to Audio",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
          )
        }
      }
    }
  }
}

@Composable
private fun LyricsLineItem(
  line: CrazyScriptLineDto,
  isActive: Boolean,
  isPast: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val contentAlpha = when {
    isActive -> 1.0f
    isPast -> 0.60f
    else -> 0.50f
  }

  val backgroundColor = if (isActive) {
    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
  } else {
    Color.Transparent
  }

  val speakerColor = remember(line.speaker) {
    characterSpeakerColor(line.speaker)
  }

  Surface(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .clickable(onClick = onClick),
    shape = RoundedCornerShape(12.dp),
    color = backgroundColor,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 4.dp),
      ) {
        // Speaker badge
        Box(
          modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(speakerColor.copy(alpha = if (isActive) 0.25f else 0.15f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
          Text(
            text = line.speaker,
            color = speakerColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
          )
        }

        // Emotion tag if present
        if (!line.emotion.isNullOrBlank()) {
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "(${line.emotion})",
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Normal,
          )
        }
      }

      // Spoken Text
      Text(
        text = line.text,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
        fontSize = if (isActive) 18.sp else 16.sp,
        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
        lineHeight = if (isActive) 24.sp else 22.sp,
      )
    }
  }
}

private fun characterSpeakerColor(speaker: String): Color {
  val clean = speaker.lowercase().trim()
  if (clean == "narrator") {
    return Color(0xFF00897B) // Teal/Emerald for narrator
  }
  val colors = listOf(
    Color(0xFFE65100), // Deep Orange
    Color(0xFF6A1B9A), // Purple
    Color(0xFF1565C0), // Blue
    Color(0xFF2E7D32), // Green
    Color(0xFFC2185B), // Pink
    Color(0xFFD84315), // Rust
    Color(0xFF00838F), // Cyan
    Color(0xFF4527A0), // Deep Purple
  )
  val hash = abs(clean.hashCode())
  return colors[hash % colors.size]
}
